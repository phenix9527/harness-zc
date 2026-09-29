package com.zc.api.demo.common.limiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 基于 Redis 的固定窗口限流器（多场景复用：优惠券领取限流、短信频控）。
 *
 * <p>为什么用固定窗口而不是滑动窗口/Sentinel：
 * 小型业务用 {@code INCR + EXPIRE} 就够，引入 Sentinel/Redisson 是给自己加运维成本。
 * 代价是窗口边界有突刺（59 秒打满 + 00 秒再打满 = 2 倍流量），对本类场景可接受。
 * 如果确实需要平滑，可换 <b>滑动窗口 ZSet</b> 或 <b>令牌桶 Lua</b>，接口不变。
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li><b>INCR 和 EXPIRE 必须原子</b>：分开写如果中间进程挂掉，key 会永不失效 →
 *       该用户/该 IP 被永久限流。所以这里用 Lua 包住。</li>
 *   <li>限流是「挡流量」手段，不是正确性依据；真正的正确性靠唯一索引/CAS 兜底。</li>
 *   <li>Redis 不可用时怎么办？本实现选择 <b>fail-open（放行）</b>并打 error 日志，
 *       因为限流器挂了不应该把业务一起拖垮；若你的接口是「发短信」这类花钱的，
 *       建议改成 <b>fail-close（拒绝）</b>，把选择权留在调用方。</li>
 *   <li>窗口内第一次请求才 set 过期时间，所以「窗口」是相对用户行为滑动的，不是整点对齐的。</li>
 * </ol>
 * @author admin
 *
 * TODO zhoucong common/limiter/RedisRateLimiter.java:22-31  fail-open 还是 fail-close，是个业务决策不是一个技术决策
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /**
     * KEYS[1] = 计数 key
     * ARGV[1] = 阈值 limit
     * ARGV[2] = 窗口秒数 windowSeconds
     * 返回：0 = 放行；>0 = 被限流，值为剩余秒数（retryAfter）
     */
    private static final String WINDOW_LUA =
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end "
                    + "if c > tonumber(ARGV[1]) then "
                    + "  local ttl = redis.call('TTL', KEYS[1]) "
                    + "  if ttl == nil or ttl < 0 then ttl = tonumber(ARGV[2]) end "
                    + "  return ttl "
                    + "end "
                    + "return 0";

    @SuppressWarnings("unchecked")
    private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>(WINDOW_LUA, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取一次配额。
     *
     * @param key           限流维度 key，例如 {@code rate:sms:60s:13800000000}
     * @param limit         窗口内最大次数
     * @param windowSeconds 窗口长度（秒）
     * @return 0 表示放行；大于 0 表示被限流，值为建议等待秒数（retryAfter）
     */
    public long tryAcquire(String key, int limit, int windowSeconds) {
        List<String> keys = Collections.singletonList(key);
        try {
            Long ret = redisTemplate.execute(SCRIPT, keys, String.valueOf(limit), String.valueOf(windowSeconds));
            return ret == null ? 0L : ret;
        } catch (Exception e) {
            // fail-open：限流组件故障不阻断主流程，但必须留下告警线索
            log.error("rate limiter error, fail-open. key={}", key, e);
            return 0L;
        }
    }

    /** 手动重置（例如用户申诉解封、运营后台解限） */
    public void reset(String key) {
        redisTemplate.delete(key);
    }

    /** 当前窗口已用次数，仅用于排查问题（生产慎用 KEYS 类似的统计） */
    public long currentCount(String key) {
        String v = redisTemplate.opsForValue().get(key);
        return v == null ? 0L : Long.parseLong(v);
    }

    /** 兼容写法示例：有些老代码用 setIfAbsent 做「N 秒内只允许一次」 */
    public boolean tryAcquireOnce(String key, Duration window) {
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, "1", window);
        return Boolean.TRUE.equals(ok);
    }
}
