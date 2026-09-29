package com.zc.api.demo.sms.service;

import com.zc.api.demo.common.api.BizException;
import com.zc.api.demo.common.api.ErrorCode;
import com.zc.api.demo.common.api.RetryAfter;
import com.zc.api.demo.common.api.SystemException;
import com.zc.api.demo.common.limiter.RedisRateLimiter;
import com.zc.api.demo.sms.client.SmsClient;
import com.zc.api.demo.sms.dto.SendSmsResp;
import com.zc.api.demo.sms.dto.SmsScene;
import org.apache.commons.lang3.RandomStringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;

/**
 * 场景3：短信验证码 —— 限流 + 频控样板。
 *
 * <p><b>这个接口代码最少，但限流维度最全，而且错误语义直接影响前端体验。</b>
 *
 * <p><b>频控设计（三档 + 两个维度）：</b>
 * <pre>
 * 维度          窗口      限额     key 里带 scene？
 * 同手机号      60 秒     1 次     带（登录/注册各自独立，用户体验好）
 * 同手机号      1 小时    5 次     不带（跨场景全局，防换场景绕过）
 * 同手机号      24 小时   20 次     不带（跨场景全局，短信是按条计费的）
 * 同 IP         1 分钟    10 次     防「一个脚本刷一万个手机号」
 * </pre>
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li><b>错误信息必须带 retryAfter</b>，前端才能做倒计时。只返回「操作过于频繁」，
 *       用户会一直点 —— 这是「错误语义」这一器官最容易被忽略的地方。</li>
 *   <li><b>频控用固定窗口（INCR + EXPIRE）就够</b>，别上 Sentinel/Redisson 给自己加运维成本。
 *       代价是窗口边界有突刺（59s 打满 + 00s 再打满 = 2 倍流量），对本场景可接受。</li>
 *   <li><b>发送失败要把频控计数回滚掉</b>，否则用户因为厂商接口抖动被自己的失败卡 60 秒。</li>
 *   <li><b>不要暴露手机号是否存在</b>：登录场景无论新老用户都返回「验证码已发送」，
 *       否则接口就成了「手机号是否注册」的查询器。</li>
 *   <li>防刷兜底是「IP 限流 + 图形验证码门槛 + 黑名单」三层，短信被刷是真花钱，
 *       不要只靠手机号维度频控。</li>
 * </ol>
 */
@Service
public class SmsCodeService {

    private static final Logger log = LoggerFactory.getLogger(SmsCodeService.class);

    private static final String CODE_KEY_PREFIX = "sms:code:";
    private static final String FAIL_KEY_PREFIX = "sms:fail:";
    private static final String LIMIT_60S_PREFIX = "rate:sms:60s:";
    private static final String LIMIT_1H_PREFIX = "rate:sms:1h:";
    private static final String LIMIT_24H_PREFIX = "rate:sms:24h:";
    private static final String LIMIT_IP_PREFIX = "rate:sms:ip:";

    /** 验证码校验的最大错误次数，超过就作废验证码 */
    private static final int MAX_VERIFY_FAIL = 5;
    /** 错误计数的统计窗口（秒），与验证码有效期解耦，避免「错两次等过期再来」的绕过 */
    private static final int FAIL_WINDOW_SECONDS = 3600;

    private final StringRedisTemplate redisTemplate;
    private final RedisRateLimiter rateLimiter;
    private final SmsClient smsClient;
    private final DefaultRedisScript<Long> smsVerifyScript;

    @Value("${demo.sms.code-ttl-seconds:300}")
    private int codeTtlSeconds;

    @Value("${demo.sms.window-60s-limit:1}")
    private int window60sLimit;

    @Value("${demo.sms.window-1h-limit:5}")
    private int window1hLimit;

    @Value("${demo.sms.window-24h-limit:20}")
    private int window24hLimit;

    @Value("${demo.sms.ip-limit-per-minute:10}")
    private int ipLimitPerMinute;

    public SmsCodeService(StringRedisTemplate redisTemplate,
                          RedisRateLimiter rateLimiter,
                          SmsClient smsClient,
                          DefaultRedisScript<Long> smsVerifyScript) {
        this.redisTemplate = redisTemplate;
        this.rateLimiter = rateLimiter;
        this.smsClient = smsClient;
        this.smsVerifyScript = smsVerifyScript;
    }

    /**
     * 发送验证码。
     *
     * @param mobile   手机号
     * @param scene    场景（决定模板与有效期）
     * @param clientIp 客户端 IP（可为空，内网调用场景）
     */
    public SendSmsResp sendCode(String mobile, SmsScene scene, String clientIp) {
        // ────────────────────────────────────────────────────────────────
        // 1. 多维度频控：任一命中即拒绝，返回剩余秒数（而不是抛 500）
        // ────────────────────────────────────────────────────────────────
        long retryAfter = checkLimit(mobile, scene, clientIp);
        if (retryAfter > 0) {
            // 业务失败 + retryAfter：前端拿到这段文字可以直接做倒计时
            throw new BizException(ErrorCode.SEND_TOO_FREQUENT,
                    "发送过于频繁，请 " + retryAfter + " 秒后重试", RetryAfter.of(retryAfter));
        }

        // ────────────────────────────────────────────────────────────────
        // 2. 生成验证码并落 Redis
        //    注意：这里用的是 commons-lang3 的 RandomStringUtils（底层 ThreadLocalRandom）。
        //    对「登录验证码」这类安全敏感场景，建议换成 SecureRandom，
        //    否则理论上可被预测（虽然还要配合频控和错误次数限制）。
        // ────────────────────────────────────────────────────────────────
        String code = RandomStringUtils.randomNumeric(6);
        int ttl = scene.getCodeTtlSeconds() > 0 ? scene.getCodeTtlSeconds() : codeTtlSeconds;
        String codeKey = codeKey(mobile, scene);
        redisTemplate.opsForValue().set(codeKey, code, Duration.ofSeconds(ttl));

        // ────────────────────────────────────────────────────────────────
        // 3. 调用网关发送
        //    失败处理是重点：必须把频控计数回滚，否则用户被自己的失败卡住 60 秒
        // ────────────────────────────────────────────────────────────────
        try {
            smsClient.send(mobile, scene.getTemplate(), code);
        } catch (Exception e) {
            rollbackLimit(mobile, scene);
            // 验证码已经写进 Redis 了，发送失败要删掉，避免「用户没收到但验证码有效」
            redisTemplate.delete(codeKey);
            log.error("send sms fail, mobile={}, scene={}", mask(mobile), scene, e);
            // 系统类异常 -> 5xx，调用方可以重试
            throw new SystemException(ErrorCode.DEPENDENCY_ERROR, "短信发送失败，请稍后重试", e);
        }

        log.info("sms code sent, mobile={}, scene={}, ttl={}s", mask(mobile), scene, ttl);
        return new SendSmsResp(ttl);
    }

    /**
     * 校验验证码（登录/注册流程调用）。
     *
     * <p>校验成功<b>立即失效</b>，防重放；并且这一步与错误计数一起放在 Lua 里保证原子。
     */
    public void verify(String mobile, SmsScene scene, String input) {
        String codeKey = codeKey(mobile, scene);
        String failKey = FAIL_KEY_PREFIX + scene.name() + ":" + mobile;
        Long ret = redisTemplate.execute(smsVerifyScript,
                Arrays.asList(codeKey, failKey),
                input, String.valueOf(FAIL_WINDOW_SECONDS), String.valueOf(MAX_VERIFY_FAIL));
        long result = ret == null ? 0L : ret;
        switch ((int) result) {
            case 1:
                log.info("sms code verified, mobile={}, scene={}", mask(mobile), scene);
                return;
            case -2:
                // 错误次数超限，验证码已作废：这里必须让用户重新发送，而不是继续试
                log.warn("sms code verify fail too many times, code invalidated. mobile={}, scene={}",
                        mask(mobile), scene);
                throw new BizException(ErrorCode.SMS_CODE_INVALID, "验证码错误次数过多，请重新获取");
            case -1:
                log.info("sms code mismatch, mobile={}, scene={}", mask(mobile), scene);
                throw new BizException(ErrorCode.SMS_CODE_INVALID);
            default:
                // 0：不存在或已过期。这个提示要和「错误」区分开，用户才知道该重新获取
                throw new BizException(ErrorCode.SMS_CODE_INVALID, "验证码已过期，请重新获取");
        }
    }

    /* ==================== 内部方法 ==================== */

    /** 三档手机号频控 + 一档 IP 频控；返回 0 = 放行，>0 = 需等待秒数 */
    private long checkLimit(String mobile, SmsScene scene, String clientIp) {
        // 60 秒档带 scene：登录和注册互不影响，避免用户操作 A 场景时被 B 场景挡住
        long ttl = rateLimiter.tryAcquire(
                LIMIT_60S_PREFIX + scene.name() + ":" + mobile, window60sLimit, 60);
        if (ttl > 0) {
            return ttl;
        }
        // 1 小时 / 24 小时档不带 scene：跨场景全局限制，防止「换个场景继续刷」
        ttl = rateLimiter.tryAcquire(LIMIT_1H_PREFIX + mobile, window1hLimit, 3600);
        if (ttl > 0) {
            return ttl;
        }
        ttl = rateLimiter.tryAcquire(LIMIT_24H_PREFIX + mobile, window24hLimit, 86400);
        if (ttl > 0) {
            return ttl;
        }
        if (clientIp != null && !clientIp.isEmpty()) {
            // IP 维度：防「一个脚本刷一万个手机号」
            ttl = rateLimiter.tryAcquire(LIMIT_IP_PREFIX + clientIp, ipLimitPerMinute, 60);
            if (ttl > 0) {
                return ttl;
            }
        }
        return 0L;
    }

    /**
     * 回滚频控计数（发送失败时调用）。
     *
     * <p>注意点：只需要回滚 60 秒档。1 小时/24 小时档是用来防刷的总量限制，
     * 即使发送失败也建议保留计数（真实项目里按团队风控策略定，这里选择保守不回滚 24h 档）。
     */
    private void rollbackLimit(String mobile, SmsScene scene) {
        rateLimiter.reset(LIMIT_60S_PREFIX + scene.name() + ":" + mobile);
    }

    private String codeKey(String mobile, SmsScene scene) {
        return CODE_KEY_PREFIX + scene.name() + ":" + mobile;
    }

    /** 手机号脱敏：日志和监控里绝不允许出现完整手机号 */
    private String mask(String mobile) {
        if (mobile == null || mobile.length() < 7) {
            return "***";
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }
}
