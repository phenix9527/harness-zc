package com.zc.api.demo.common.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * 极简 Redis 分布式锁 —— <b>只用于「同一时刻只有一个实例执行」的调度类任务</b>。
 *
 * <p><b>★ 状态：已被 {@link RedissonLock} 取代，保留下来作为「手写 vs 框架」的对照。</b>
 * 定时任务已经切到 Redisson（看门狗续期）。这里刻意留着不删，是因为它把「一把锁要注意哪几件事」
 * 逐条写出来了 —— 看 Redisson 的 API 是学不到这些的。新代码请直接用 {@link RedissonLock}。
 *
 * <p><b>它的硬伤（也是当初换掉它的唯一原因）：只能给固定 TTL，无法续期。</b>
 * 任务跑超 TTL 就会被第二个实例抢到锁，两个实例同时执行。
 * 调大 TTL 只是把问题往后推：调得越大，实例崩溃后锁越久不释放。
 * 这是「固定租期」模型的固有矛盾 —— 要么实现看门狗，要么换 Redisson。</p>
 *
 * <p><b>先说清楚它的适用边界（很重要，用错比不用更糟）：</b>
 * <pre>
 * ✅ 适合：定时任务多副本防重复执行、缓存重建、运营脚本互斥
 * ❌ 不适合：当作「幂等」的正确性保证。锁会过期、会丢、Redis 主从切换会双持有，
 *            正确性永远要由 DB 唯一索引 / CAS 兜底。
 * </pre>
 * 本文档场景 2/4 的关单、场景 1 的发券都<b>不用锁</b>，因为那一行 CAS 已经足够。
 * 加锁只会在给你多一个失败点。
 *
 * <p><b>实现要点（缺一个都会出错）：</b>
 * <ol>
 *   <li>value 必须唯一（UUID）：否则可能删掉别人持有的锁。</li>
 *   <li>必须有过期时间：否则持锁进程崩溃 = 死锁。</li>
 *   <li><b>解锁必须「比较 value 再删」并且原子</b>（所以用 Lua）：先 GET 再 DEL 中间会插入竞态。</li>
 *   <li>业务执行时间可能超过锁 TTL → 要么把 TTL 设得足够长，要么实现看门狗续期
 *       （这时请直接用 Redisson，别自己写）。</li>
 *   <li>强一致场景要 RedLock / etcd / ZooKeeper，或者干脆改用「DB 条件更新 + 单实例调度」。</li>
 *   <li><b>另一处容易忽略的缺陷</b>：{@code ThreadLocal} 存 token，同一线程嵌套加两把不同的锁时，
 *       {@code holder} 会被覆盖 —— 第二次 unlock 用错 token，第一把锁就永远删不掉（只能等 TTL）。
 *       真实项目里请用 {@code Map<String, String>} 或直接把 token 由调用方持有。
 *       本类只服务于「一个任务一把锁」的定时任务，所以没修。</li>
 * </ol>
 */
@Component
public class RedisLock {

    private static final Logger log = LoggerFactory.getLogger(RedisLock.class);

    /** 解锁脚本：只有 value 匹配才删，保证不会误删他人的锁 */
    private static final RedisScript<Long> UNLOCK_LUA = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ThreadLocal<String> holder = new ThreadLocal<>();

    public RedisLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试加锁（不等待，立即返回）。
     *
     * @param key   锁的维度，例如 {@code lock:job:order-timeout-scan}
     * @param ttl   锁的存活时间；必须 > 任务最长执行时间
     * @return true = 拿到锁，可以执行；false = 别人在跑，本实例直接跳过
     */
    public boolean tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        if (Boolean.TRUE.equals(ok)) {
            holder.set(token);
            return true;
        }
        return false;
    }

    /** 释放锁。必须在 finally 里调用。 */
    public void unlock(String key) {
        String token = holder.get();
        if (token == null) {
            return;
        }
        try {
            redisTemplate.execute(UNLOCK_LUA, Collections.singletonList(key), token);
        } catch (Exception e) {
            // 释放失败只影响其他实例晚一点拿到锁（等 TTL），不要因此抛异常打断主流程
            log.warn("unlock fail, key={}", key, e);
        } finally {
            holder.remove();
        }
    }

    /** 模板方法：拿到锁才执行，异常不影响后续调度 */
    public void runWithLock(String key, Duration ttl, Runnable task) {
        if (!tryLock(key, ttl)) {
            log.debug("lock not acquired, skip. key={}", key);
            return;
        }
        try {
            task.run();
        } catch (Exception e) {
            log.error("task error under lock, key={}", key, e);
        } finally {
            unlock(key);
        }
    }
}
