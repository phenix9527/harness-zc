package com.zc.api.demo.common.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redisson 分布式锁 —— <b>用来替换 {@link RedisLock} 的手写简易版</b>，解决它的核心缺陷：锁续期。
 *
 * <p><b>为什么值得换（旧版的真实故障场景）：</b>
 * 手写版 {@code RedisLock} 只能指定固定 TTL，比如 5 分钟。任务一旦跑超 5 分钟：
 * <pre>
 *   T+0     实例A 拿到锁，TTL 5min
 *   T+5:01  锁自动过期（A 还在跑！）
 *   T+5:02  实例B 拿到同一把锁，两个实例同时扫表关单
 * </pre>
 * 结果不是数据出错（CAS 兜得住），而是<b>资源白费 + 日志混乱 + 偶发重复告警</b>——
 * 属于「不致命但一直恶心你」的那类问题。把 TTL 调大只是把问题往后推，不能消除。
 *
 * <p><b>看门狗（watchdog）机制：</b>
 * 调用 {@code tryLock(waitTime, TimeUnit)} <b>且不传 leaseTime</b> 时，
 * Redisson 会启动一个后台续期线程，按 {@code lockWatchdogTimeout / 3} 的间隔不断把锁 TTL 重置回
 * {@code lockWatchdogTimeout}（默认 30s）。只要持有锁的 JVM 还活着，锁就一直有效；
 * JVM 挂掉后停止续期，最长 30s 自动释放，不会死锁。
 *
 * <p><b>★ 最容易踩的坑：</b>一旦你写成 {@code tryLock(waitTime, leaseTime, unit)} 显式指定租期，
 * <b>看门狗就自动关闭</b>，行为退化成手写版那个老问题。很多人「用了 Redisson 还是出问题」都是这个原因。
 *
 * <p><b>边界（和 {@link RedisLock} 一致，不因为换了库而改变）：</b>
 * <pre>
 * ✅ 适合：定时任务多副本防重复执行、缓存重建、运营脚本互斥
 * ❌ 不适合：当幂等的正确性保证。看门狗只保证「进程活着时锁不丢」，
 *            它挡不住主从切换双持有、挡不住业务线程长 GC 停顿导致续期失败。
 *            正确性永远由 DB 唯一索引 / CAS 兜底 —— 本项目场景 1/2/4 的主链路依然<b>不加锁</b>。
 * </pre>
 */
@Component
public class RedissonLock {

    private static final Logger log = LoggerFactory.getLogger(RedissonLock.class);

    private final RedissonClient redissonClient;

    public RedissonLock(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 拿锁 → 执行 → 释放。拿不到锁直接跳过（不等待业务执行）。
     *
     * <p>注意点：这里刻意<b>不暴露 leaseTime 参数</b>。留出这个口子，
     * 早晚会有人传一个「足够大」的固定租期，把看门狗的价值又抹掉了。
     *
     * @param key      锁 key，调用方自带命名空间，例如 {@code lock:job:order-timeout-compensate}
     * @param waitTime 抢锁最多等多久；定时任务场景建议给 0 或很小值 ——
     *                 抢不到说明别的实例正在跑，本次调度直接放弃才是正确行为，排队反而会堆积
     * @return true = 执行过任务；false = 没拿到锁（跳过）或执行中抛异常
     */
    public boolean tryLockAndRun(String key, Duration waitTime, Runnable task) {
        RLock lock = redissonClient.getLock(key);
        boolean locked = false;
        try {
            // ★ 两个参数的 tryLock 才启用看门狗（三个参数带 leaseTime 会关掉它）
            locked = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
            if (!locked) {
                log.debug("lock busy, skip this round. key={}", key);
                return false;
            }
            task.run();
            return true;
        } catch (InterruptedException e) {
            // 注意点：中断标记必须恢复。吞掉中断会让优雅停机、任务取消这类上层机制永远收不到信号
            Thread.currentThread().interrupt();
            log.warn("lock wait interrupted, key={}", key);
            return false;
        } catch (Exception e) {
            // 任务异常不能影响下一次调度：这里吞掉（自己记日志），绝不往外抛
            log.error("task error under lock, key={}", key, e);
            return false;
        } finally {
            // 注意点：unlock 前必须判断 isHeldByCurrentThread。
            // 不是自己持有的锁去 unlock 会抛 IllegalMonitorStateException，
            // 而且会把异常盖在真正的业务异常上面，把排查方向带偏。
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
