package com.zc.api.demo.coupon.service;

import com.zc.api.demo.common.api.BizException;
import com.zc.api.demo.common.api.ErrorCode;
import com.zc.api.demo.common.api.RetryAfter;
import com.zc.api.demo.common.limiter.RedisRateLimiter;
import com.zc.api.demo.coupon.cache.ActivityCache;
import com.zc.api.demo.coupon.dto.ClaimCouponReq;
import com.zc.api.demo.coupon.dto.ClaimCouponResp;
import com.zc.api.demo.coupon.entity.Activity;
import com.zc.api.demo.coupon.entity.Coupon;
import com.zc.api.demo.coupon.mapper.CouponMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.Date;

/**
 * 场景1：优惠券领取 —— 高并发写入样板。
 *
 * <p><b>这个接口把「五脏」占全了：</b>
 * <pre>
 * 契约    ClaimCouponReq / ClaimCouponResp，字段少而明确
 * 校验    参数校验（@Valid）+ 业务前置条件（活动进行中、库存够）
 * 幂等    已领过 => 返回上次结果（不是报错）；DB 唯一索引兜底
 * 并发    Redis Lua 原子预扣（库存 + 用户标记）；DB 唯一索引最终兜底
 * 错误语义 已抢光/活动未开始 = 业务失败（HTTP 200 + 2xxxx）；Redis/DB 异常 = 5xx 可重试
 * 状态机  活动状态守卫（status + 时间窗口）
 * 可观测  关键路径日志带 activityCode/userId/left；traceId 由过滤器统一打
 * </pre>
 *
 * <p><b>分层原则：</b>
 * <pre>
 * Redis  = 挡在前面减少无效请求（快，但不可信：会挂 / 会过期 / 会被误清）
 * DB     = 正确性的事实来源（唯一索引 uk_user_activity）
 * 唯一索引不是可选项 —— 没有它，Redis 挂掉或 Lua 写错就是超发。
 * </pre>
 */
@Service
public class CouponClaimService {

    private static final Logger log = LoggerFactory.getLogger(CouponClaimService.class);

    private static final String STOCK_KEY_PREFIX = "coupon:stock:";
    private static final String USER_MARK_KEY_PREFIX = "coupon:user:";
    private static final String LIMIT_USER_KEY_PREFIX = "rate:coupon:user:";
    private static final String LIMIT_IP_KEY_PREFIX = "rate:coupon:ip:";

    /** Lua 返回值：用户已领过 */
    private static final long LUA_ALREADY_CLAIMED = -1L;
    /** Lua 返回值：库存不足 */
    private static final long LUA_SOLD_OUT = -2L;

    /** 幂等命中但 DB 还查不到时的重试次数/间隔：等并发的那笔事务提交 */
    private static final int IDEMPOTENT_RETRY_TIMES = 3;
    private static final long IDEMPOTENT_RETRY_INTERVAL_MILLIS = 50L;

    private final ActivityCache activityCache;
    private final CouponMapper couponMapper;
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> couponClaimScript;
    private final RedisRateLimiter rateLimiter;

    /** 用户标记 TTL：必须 >= 活动时长，否则活动还没结束标记就过期了，会退回「靠唯一索引挡」 */
    @Value("${demo.coupon.user-mark-ttl-seconds:604800}")
    private long userMarkTtlSeconds;

    @Value("${demo.coupon.user-limit-per-second:1}")
    private int userLimitPerSecond;

    @Value("${demo.coupon.ip-limit-per-minute:10}")
    private int ipLimitPerMinute;

    public CouponClaimService(ActivityCache activityCache,
                              CouponMapper couponMapper,
                              StringRedisTemplate redisTemplate,
                              DefaultRedisScript<Long> couponClaimScript,
                              RedisRateLimiter rateLimiter) {
        this.activityCache = activityCache;
        this.couponMapper = couponMapper;
        this.redisTemplate = redisTemplate;
        this.couponClaimScript = couponClaimScript;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 领取优惠券。
     *
     * @param activityCode 活动编码（对外标识，不用自增 id，防遍历）
     * @param req          请求体
     * @param clientIp     客户端 IP，用于 IP 维度限流（可能为空，内部调用场景）
     * @return 券信息；幂等命中时返回的是<b>上次那张券</b>
     */
    public ClaimCouponResp claim(String activityCode, ClaimCouponReq req, String clientIp) {
        Long userId = req.getUserId();

        // ────────────────────────────────────────────────────────────────
        // 0. 限流放最前面：用户 1 次/秒 + IP 10 次/分钟
        //    目的：别让刷接口的流量打到 Redis/DB。限流是「省资源」手段，不是正确性手段。
        // ────────────────────────────────────────────────────────────────
        checkRateLimit(userId, clientIp);

        // ────────────────────────────────────────────────────────────────
        // 1. 业务前置校验：活动必须存在且「进行中」（状态机守卫）
        // ────────────────────────────────────────────────────────────────
        Activity act = activityCache.getByCode(activityCode);
        if (act == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        // 服务端时间做判断，绝不用客户端传的时间
        if (!act.isRunning(new Date())) {
            throw new BizException(ErrorCode.COUPON_NOT_RUNNING);
        }

        // ────────────────────────────────────────────────────────────────
        // 2. 幂等：已领过 ——> 「直接返回上次结果」，而不是报错
        //    理由：用户点两次，第二次看到「已领取 + 券号」是正常体验；
        //         返回 500/错误码会让前端以为失败，引导用户重试，流量反而放大。
        //    这是快路径，只能挡掉「串行重复」，挡不住并发（两个请求可能同时查到 null）。
        // ────────────────────────────────────────────────────────────────
        Coupon existing = couponMapper.selectByUserAndActivity(userId, act.getId());
        if (existing != null) {
            log.info("coupon claim hit idempotent, activityCode={}, userId={}, couponNo={}",
                    activityCode, userId, existing.getCouponNo());
            return ClaimCouponResp.of(existing, false);
        }

        // ────────────────────────────────────────────────────────────────
        // 3. 并发防护：Redis Lua 原子预扣
        //    「判断用户是否已领 + 判断库存 + 扣库存 + 打用户标记」必须是一次原子操作，
        //    分开写必然出现中间态（扣了库存但标记没打上）→ 超发。
        // ────────────────────────────────────────────────────────────────
        String stockKey = STOCK_KEY_PREFIX + act.getId();
        String userKey = USER_MARK_KEY_PREFIX + act.getId() + ":" + userId;
        Long left = redisTemplate.execute(couponClaimScript,
                Arrays.asList(stockKey, userKey), String.valueOf(userMarkTtlSeconds));

        if (left == null) {
            // Lua 返回 null 说明 Redis 侧执行异常（脚本没返回值/连接问题）
            // 系统类错误 -> 抛出去变 5xx，让调用方重试；千万不要在这里「当作抢光」静默吞掉
            throw new BizException(ErrorCode.DEPENDENCY_ERROR, "库存服务异常，请稍后重试");
        }

        if (left == LUA_ALREADY_CLAIMED) {
            // Redis 标记已存在 → 用户确实领过（或正在领）。回查 DB 拿上次的券。
            // 注意这里要重试：并发下「标记先落、DB 事务还没提交」，立刻查会是 null。
            Coupon last = queryExistingWithRetry(userId, act.getId());
            if (last != null) {
                return ClaimCouponResp.of(last, false);
            }
            // 标记在、DB 无记录、等了几轮还是查不到 → 我们不能确定结果，
            // 按「系统类」返回让调用方稍后重试/查询，别谎报成功也别谎报失败。
            log.error("coupon mark exists but db record missing, activityId={}, userId={}", act.getId(), userId);
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
        }

        if (left == LUA_SOLD_OUT) {
            log.info("coupon sold out, activityCode={}, userId={}", activityCode, userId);
            // 注意：这一步也可能是「库存 key 没初始化」造成的，排查时先看这个日志
            log.warn("if stock key not initialized, check warm-up job. stockKey={}", stockKey);
            throw new BizException(ErrorCode.COUPON_SOLD_OUT);
        }

        // ────────────────────────────────────────────────────────────────
        // 4. 最终防线：DB 唯一索引 uk_user_activity(user_id, activity_id)
        //    并发穿透到这里的请求，会因唯一索引冲突抛 DuplicateKeyException。
        //    捕获后必须「回补 Redis 库存」，否则每次穿透都白扣一份库存 → 库存泄漏、少发。
        // ────────────────────────────────────────────────────────────────
        try {
            Coupon coupon = buildCoupon(userId, act);
            couponMapper.insert(coupon);
            log.info("coupon claim success, activityCode={}, userId={}, couponNo={}, leftStock={}",
                    activityCode, userId, coupon.getCouponNo(), left);
            return ClaimCouponResp.of(coupon, true);
        } catch (DuplicateKeyException e) {
            // 并发穿透：同一个用户的另一个请求已经插进去了（撞 uk_user_activity 的
            // 只可能是同 user+activity，其他用户根本撞不上这个索引）。
            // 注意：正常「双击」走不到这 —— Lua 的 SETNX 是原子的，第二个请求必然拿到 0
            // 而走 -1 分支回查。能落到这里，说明 Redis 标记防线失效了
            // （主从切换丢标记 / 被误删，见学习文档阶段4实验1），catch 是那之后的兜底。
            // 收到这个异常时，前面那个请求的事务必然已提交（InnoDB：未提交的 INSERT
            // 会让后面的等锁，提交后才报 DuplicateKey，回滚则本方直接成功）。
            // 回补库存，然后按幂等返回「已领取」
            redisTemplate.opsForValue().increment(stockKey);
            log.info("coupon claim duplicate, rollback stock. activityCode={}, userId={}, leftStock={}",
                    activityCode, userId, left);
            Coupon last = queryExistingWithRetry(userId, act.getId());
            if (last == null) {
                throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
            }
            return ClaimCouponResp.of(last, false);
        }
    }

    /**
     * 活动预热：把活动库存写进 Redis。
     *
     * <p><b>用 SETNX 而不是 SET 的理由：</b>预热任务可能被重复触发（发布重跑、手动补跑），
     * 用 SET 会把已经扣减过的库存重置回总量 → 直接超发。
     *
     * <p><b>注意点：</b>
     * <ol>
     *   <li>必须在活动开始<b>之前</b>执行完。没初始化时 Lua 会按库存 0 处理（宁可不发也不超发），
     *       表现为「秒抢光」并打出 warn 日志。</li>
     *   <li>Redis 里的库存是「缓存出来的数字」，DB 才是最终事实。线上要能容忍两者不一致
     *       （比如 Redis 被清空后重建），重建方案是「总量 - DB 已发数量」，不是直接写总量。</li>
     *   <li>库存 key 的过期时间要覆盖活动结束 + 余量，否则活动进行中 key 自己没了 = 全场抢光。</li>
     * </ol>
     */
    public void warmUpStock(Long activityId, int totalStock, Duration ttl) {
        String stockKey = STOCK_KEY_PREFIX + activityId;
        Boolean first = redisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(totalStock), ttl);
        log.info("warm up coupon stock, activityId={}, totalStock={}, firstTime={}",
                activityId, totalStock, first);
    }

    /**
     * 券号生成。
     *
     * <p>注意点：<b>不要用 UUID</b>（32 位太长、无业务含义、客服无法口述）；
     * <b>不要暴露自增 id</b>（可被枚举/推测发券量）。
     * 这里用「活动前缀 + 日期 + 8 位随机」，冲突概率极低，且 uk_coupon_no 会兜底
     * （真撞了会抛 DuplicateKeyException，表现为用户看到「领取处理中」，需重试）。
     */
    private Coupon buildCoupon(Long userId, Activity act) {
        Coupon coupon = new Coupon();
        coupon.setCouponNo(act.getActivityCode() + System.currentTimeMillis() % 100000000L
                + String.format("%04d", (int) (Math.random() * 10000)));
        coupon.setUserId(userId);
        coupon.setActivityId(act.getId());
        coupon.setStatus(Coupon.STATUS_UNUSED);
        return coupon;
    }

    /** 幂等回查：短重试几次，等待并发的那个事务提交 */
    private Coupon queryExistingWithRetry(Long userId, Long activityId) {
        for (int i = 0; i < IDEMPOTENT_RETRY_TIMES; i++) {
            Coupon coupon = couponMapper.selectByUserAndActivity(userId, activityId);
            if (coupon != null) {
                return coupon;
            }
            sleepQuietly();
        }
        return null;
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(IDEMPOTENT_RETRY_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 限流：用户维度 1 次/秒、IP 维度 10 次/分钟。
     * 命中的返回是「业务失败 + retryAfter」，不是 500 —— 前端据此做倒计时。
     */
    private void checkRateLimit(Long userId, String clientIp) {
        long retryAfter = rateLimiter.tryAcquire(LIMIT_USER_KEY_PREFIX + userId, userLimitPerSecond, 1);
        if (retryAfter > 0) {
            throw new BizException(ErrorCode.REQUEST_TOO_FREQUENT,
                    "操作过于频繁，请 " + retryAfter + " 秒后重试", RetryAfter.of(retryAfter));
        }
        if (clientIp != null && !clientIp.isEmpty()) {
            retryAfter = rateLimiter.tryAcquire(LIMIT_IP_KEY_PREFIX + clientIp, ipLimitPerMinute, 60);
            if (retryAfter > 0) {
                throw new BizException(ErrorCode.REQUEST_TOO_FREQUENT,
                        "操作过于频繁，请 " + retryAfter + " 秒后重试", RetryAfter.of(retryAfter));
            }
        }
    }
}
