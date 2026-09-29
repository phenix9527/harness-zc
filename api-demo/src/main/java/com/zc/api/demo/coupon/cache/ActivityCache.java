package com.zc.api.demo.coupon.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zc.api.demo.coupon.entity.Activity;
import com.zc.api.demo.coupon.mapper.ActivityMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 活动缓存：本地缓存（抗热点） + Redis（跨实例一致）。
 *
 * <p><b>为什么做两级：</b> 秒杀场景下活动信息会被每秒钟几万次读取，
 * 全打 Redis 也是一笔不小的开销；本地缓存几秒能挡掉绝大部分。
 *
 * <p><b>本地缓存用 Caffeine，不再手写 ConcurrentHashMap + TTL。手写版有三个硬伤：</b>
 * <ol>
 *   <li><b>只增不减</b>：没有容量上限，活动越多内存越涨。表现为「Full GC 越来越频繁」，
 *       排查时很难联想到是缓存 —— 这是最常见的自研缓存事故。</li>
 *   <li><b>没有真正的过期清理</b>：惰性判断 {@code expired()}，过期条目在被访问到之前一直占内存。</li>
 *   <li><b>缓存击穿</b>：热点 key 过期的一瞬间，N 个并发请求同时发现「本地没有 → Redis 没有 → 查 DB」，
 *       一起打到数据库。Caffeine 的 {@code get(key, mappingFunction)} 对同一 key 做了
 *       <b>单飞（single-flight）</b>，并发加载会被合并成一次，这是手写版完全没有的能力。</li>
 * </ol>
 *
 * <p><b>注意点（这类缓存最容易出事故的地方）：</b>
 * <ol>
 *   <li><b>本地缓存一定会不一致。</b> 多副本 + 本地 TTL 意味着「运营停活动」后最多几秒才全量生效。
 *       所以：活动上下线这种强时效操作，必须提供 {@link #evict(String)} 并让运营后台调用
 *       （或通过 MQ 广播失效），不能只依赖 TTL 到期。</li>
 *   <li><b>缓存穿透</b>：活动不存在时不要把 null 缓存成「空对象」后当正常返回 ——
 *       {@code get(k, fn)} 的 fn 返回 null 时 Caffeine 不会写入缓存，正好符合这里的要求；
 *       若要防穿透，用「空值 + 短 TTL」或布隆过滤器。</li>
 *   <li><b>缓存与 DB 的一致性</b>：这里读多写少、且活动是运营操作，采用「Cache-Aside + 短 TTL」足够。
 *       不要在这里用「先更新 DB 再更新缓存」那套，收益为负。</li>
 *   <li><b>反序列化失败要放行到 DB</b>：Redis 里的旧格式 JSON 会让反序列化抛异常，
 *       必须 catch 后回源并覆盖缓存，否则活动永远读不到。</li>
 *   <li><b>{@code expireAfterWrite} 而不是 {@code expireAfterAccess}</b>：我们要表达的是
 *       「这条数据最多用几秒就必须回源一次」，这是写入时间决定的，与访问频率无关；
 *       用 access 语义会让热门活动永远不过期，反而失去最终一致性的兜底。</li>
 *   <li><b>不用 Spring Cache 注解（{@code @Cacheable}）</b>：① 同类内自调用走不到代理，注解静默失效；
 *       ② 两级缓存的失效顺序、穿透策略需要显式控制，注解表达不了；
 *       ③ 看代码时容易把「注解缓存」误当成强一致读。显式调用虽然啰嗦，但行为一目了然。</li>
 *   <li><b>★ 加载函数里绝对不能回调本地缓存。</b>
 *       {@code get(key, mappingFunction)} 是在<b>持有该 key 的锁</b>的情况下执行 mappingFunction 的，
 *       此时再调用 {@code localCache.invalidate(key)} 会去等一把自己正持有的锁 ——
 *       结果是请求线程<b>永久挂起</b>：不打日志、不死循环、CPU 也不高，
 *       属于最难排查的一类故障（本类在改造时就踩过一次，整轮单测卡死）。
 *       所以下面「反序列化失败」的分支只清 Redis，绝不碰本地缓存（见 {@link #deleteRedisKey}）。</li>
 *   <li><b>单飞的代价要知道</b>：同一 key 的并发加载会被合并，但代价是这些线程都要等这一次加载完成。
 *       上游（Redis/DB）如果变慢，会把等待放大成「接口 RT 尖刺」。
 *       所以加载路径上不要放慢操作 —— 这也是为什么这里没有加分布式锁/重试。</li>
 * </ol>
 * @author admin
 * TODO zhoucong coupon/cache/ActivityCache.java 类注释   Caffeine 加载函数里调 invalidate 会死锁（已实证复现）
 */
@Slf4j
@Component
public class ActivityCache {

    private static final String REDIS_KEY_PREFIX = "activity:info:";

    /** 本地缓存：容量 + 写入后过期，都交给 Caffeine 管 */
    private final Cache<String, Activity> localCache;

    /** Redis TTL（秒）：跨实例共享，比本地长一些 */
    private final long redisTtlSeconds;

    private final ActivityMapper activityMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ActivityCache(ActivityMapper activityMapper,
                         StringRedisTemplate redisTemplate,
                         ObjectMapper objectMapper,
                         // 本地 TTL：越短越安全，越长越省 Redis。默认 5s 是「运营操作容忍度」和「热点压力」的折中
                         @Value("${demo.cache.activity.local-ttl-millis:5000}") long localTtlMillis,
                         // 容量上限：必须给。按「活动数 × 单对象大小」估，宁可小也不能不设
                         @Value("${demo.cache.activity.local-max-size:10000}") long localMaxSize,
                         @Value("${demo.cache.activity.redis-ttl-seconds:60}") long redisTtlSeconds) {
        this.activityMapper = activityMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.redisTtlSeconds = redisTtlSeconds;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(localMaxSize)
                .expireAfterWrite(Duration.ofMillis(localTtlMillis))
                // 打开统计：命中率是判断「本地缓存有没有用」的唯一依据，
                // 拍脑袋设的缓存参数最后都是靠这个指标调出来的
                .recordStats()
                .build();
    }

    public Activity getByCode(String activityCode) {
        // get(key, mappingFunction)：同一 key 的并发加载会被合并，天然防止热点 key 过期瞬间打穿 DB；
        // mappingFunction 返回 null 时不会写入缓存（不缓存空值）
        return localCache.get(activityCode, this::loadFromUpstream);
    }

    /** Redis → DB 的回源链路；返回 null 表示活动确实不存在 */
    private Activity loadFromUpstream(String activityCode) {
        Activity activity = loadFromRedis(activityCode);
        if (activity != null) {
            return activity;
        }
        activity = activityMapper.selectByCode(activityCode);
        if (activity != null) {
            writeToRedis(activity);
        }
        // 注意：这里刻意不缓存 null，避免运营刚建的活动被判成不存在
        return activity;
    }

    /**
     * 主动失效。运营后台改活动/停活动后必须调用；多副本部署时应该用 MQ 广播到所有实例，
     * 或者干脆把这段逻辑收口到「活动配置服务」里由它广播。
     *
     * <p><b>只在缓存外部调用</b>（Controller / 运营接口 / MQ 消费者）。
     * 严禁在 {@link #loadFromUpstream} 里调用 —— 会死锁，原因见类注释。
     */
    public void evict(String activityCode) {
        localCache.invalidate(activityCode);
        deleteRedisKey(activityCode);
        log.info("activity cache evicted, code={}", activityCode);
    }

    /** 本地缓存命中率（0~1）。接到监控上，比任何参数猜测都可靠。 */
    public double localHitRate() {
        return localCache.stats().hitRate();
    }

    /**
     * 只删 Redis，不碰本地缓存。
     *
     * <p>专供回源失败分支使用：那时我们正处在 {@code get(key, fn)} 的加载过程中，
     * 动本地缓存就会死锁。
     */
    private void deleteRedisKey(String activityCode) {
        try {
            redisTemplate.delete(REDIS_KEY_PREFIX + activityCode);
        } catch (Exception e) {
            // Redis 删失败不影响主流程：脏数据有 TTL 兜底
            log.warn("activity cache delete redis fail, code={}", activityCode, e);
        }
    }

    private Activity loadFromRedis(String activityCode) {
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + activityCode);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, Activity.class);
        } catch (Exception e) {
            // 反序列化失败不能让主流程挂掉：清掉 Redis 里的脏数据，走 DB 回源。
            // ★ 注意这里只能调 deleteRedisKey，不能调 evict —— 我们此刻正处于
            //   get(key, fn) 的加载过程中，动本地缓存会死锁（见类注释）
            log.warn("activity cache deserialize fail, fallback to db. code={}", activityCode, e);
            deleteRedisKey(activityCode);
            return null;
        }
    }

    private void writeToRedis(Activity activity) {
        try {
            String json = objectMapper.writeValueAsString(activity);
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + activity.getActivityCode(), json,
                    Duration.ofSeconds(redisTtlSeconds));
        } catch (Exception e) {
            // 写缓存失败不影响主流程，只记日志
            log.warn("activity cache write fail, code={}", activity.getActivityCode(), e);
        }
    }
}
