package com.zc.api.demo.pay.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

/**
 * 支付回调验签器。
 *
 * <p><b>★ 必须先验签，再做任何事。</b> 回调地址是公网可达的，任何人都能往上打请求。
 * 不验签 = 谁都能把自己的订单改成「已支付」。这是资金安全的第一道门。
 *
 * <p><b>⚠️ 关于本实现的重要说明：</b>
 * 真实微信支付 v3 的验签流程是：用<b>平台证书公钥</b>对「时间戳 + 随机串 + 报文」做 RSA-SHA256 验签，
 * 并且需要定期下载/轮换平台证书、校验应答签名。本类为了聚焦「接口设计的骨架」，
 * 用 <b>HMAC-SHA256 + 商户密钥</b> 做了简化实现，演示的是<b>验签这一步的位置与顺序</b>，
 * <b>不能</b>直接用于生产。生产请使用官方 SDK（wechatpay-java）或标准的 RSA 验签实现。
 *
 * <p><b>验签之外还有三件事必须做（这三个是最常被漏掉的）：</b>
 * <ol>
 *   <li><b>时间戳容差校验（防重放）</b>：只允许 5 分钟内的回调。攻击者抓到一次合法报文，
 *       可以无限重放。虽然 CAS 幂等能挡住「重复改状态」，但挡不住它消耗你的资源。</li>
 *   <li><b>notifyId 去重</b>：把处理过的 notifyId 写进 Redis，TTL 覆盖网关的最长重试窗口
 *       （微信约 24 小时）。这一层是「减少无效处理」，不是正确性依据。</li>
 *   <li><b>幂等最终仍靠 DB</b>：不管验签/去重做得多好，最终写入必须靠
 *       {@code UPDATE ... WHERE status = 待支付} 的 CAS 兜底。</li>
 * </ol>
 * @author admin
 * TODO zhoucong  pay/service/WxPayVerifier.java:97-114   为什么 notifyId 要当「处理中」而不是「已完成」
 */
@Service
public class WxPayVerifier {

    private static final Logger log = LoggerFactory.getLogger(WxPayVerifier.class);

    /** 网关回调时间戳容差（秒）。超出即视为重放 */
    private static final long MAX_TIMESTAMP_GAP_SECONDS = 300L;

    private static final String NOTIFY_ID_KEY_PREFIX = "pay:notify:id:";

    /** 「处理中」标记的 TTL：够一次处理用就行，处理完会延长 */
    private static final Duration PROCESSING_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;

    /** 简化实现用的商户密钥；生产环境放配置中心/密钥管理，禁止硬编码进代码或 Git */
    @Value("${demo.pay.wx.api-v3-key:}")
    private String apiV3Key;

    public WxPayVerifier(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 验签。
     *
     * @param signature 请求头 Wechatpay-Signature
     * @param timestamp 请求头 Wechatpay-Timestamp
     * @param nonce     请求头 Wechatpay-Nonce
     * @param body      <b>原始报文</b>（HttpServletRequest 里读出来的字符串，不能是反序列化再序列化的结果）
     * @return true = 验签通过
     */
    public boolean verify(String signature, String timestamp, String nonce, String body) {
        if (signature == null || timestamp == null || body == null) {
            return false;
        }
        // 1) 时间戳容差：防重放
        if (!withinTolerance(timestamp)) {
            log.warn("pay notify replay suspected, timestamp={}", timestamp);
            return false;
        }
        // 2) 签名比对（简化版：HMAC-SHA256）
        try {
            String expected = hmacSha256(timestamp + "\n" + nonce + "\n" + body + "\n", apiV3Key);
            // ★ 必须用常量时间比较，防止时序攻击（自己写 equals 循环会泄漏信息）
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8))) {
                log.warn("pay notify sign mismatch");
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("pay notify verify error", e);
            return false;
        }
    }

    /**
     * 标记「正在处理」这个通知。
     *
     * <p><b>★ 这里有个非常容易写错的地方，值得单独说：</b>
     * 很多人写成「验签通过就立刻把 notifyId 标记为已处理（TTL 24h）」，
     * 结果第一次处理因为系统异常失败了，网关按规则重试时被自己判成「重复通知」直接吞掉 ——
     * <b>把重试机制给屏蔽了，用户付了钱订单永远是待支付。</b>
     *
     * <p>正确做法是把 Redis 标记当<b>「处理中」的短锁</b>，而不是「已完成」的记录：
     * <ol>
     *   <li>进来先尝试打一个短 TTL 的「处理中」标记，防止同一时刻重复处理；</li>
     *   <li>处理失败 → {@link #releaseMark(String)} 把标记删掉，让重试能进来；</li>
     *   <li>处理成功 → {@link #extendMark(String, Duration)} 把 TTL 延长到覆盖网关重试窗口。</li>
     * </ol>
     * <b>而真正的幂等永远由 DB 的 CAS 保证</b>，Redis 这层只是省资源。
     *
     * @return true = 抢到处理权；false = 已有实例在处理
     */
    public boolean tryMarkProcessing(String notifyId) {
        if (notifyId == null || notifyId.isEmpty()) {
            // 没有 notifyId 时不能靠它去重，完全交给 DB CAS 兜底
            return true;
        }
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(NOTIFY_ID_KEY_PREFIX + notifyId, "PROCESSING", PROCESSING_TTL);
        return Boolean.TRUE.equals(first);
    }

    /** 处理成功后把标记延长到覆盖网关的最长重试窗口，避免短时间内重复回调反复打 DB */
    public void extendMark(String notifyId, Duration ttl) {
        if (notifyId == null || notifyId.isEmpty()) {
            return;
        }
        try {
            redisTemplate.expire(NOTIFY_ID_KEY_PREFIX + notifyId, ttl);
        } catch (Exception e) {
            log.warn("extend notify mark fail, notifyId={}", notifyId, e);
        }
    }

    /** 处理失败后释放标记，保证网关重试能正常进入 */
    public void releaseMark(String notifyId) {
        if (notifyId == null || notifyId.isEmpty()) {
            return;
        }
        try {
            redisTemplate.delete(NOTIFY_ID_KEY_PREFIX + notifyId);
        } catch (Exception e) {
            log.warn("release notify mark fail, notifyId={}", notifyId, e);
        }
    }

    private boolean withinTolerance(String timestamp) {
        try {
            long ts = Long.parseLong(timestamp.trim());
            long now = System.currentTimeMillis() / 1000;
            return Math.abs(now - ts) <= MAX_TIMESTAMP_GAP_SECONDS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String hmacSha256(String content, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }
}
