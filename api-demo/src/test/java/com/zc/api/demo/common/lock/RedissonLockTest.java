package com.zc.api.demo.common.lock;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedissonLock 的行为契约测试。
 *
 * <p>这里要守住的核心是<b>「锁的获取与释放必须严格配对」</b>：
 * 拿了不解锁 = 任务被卡住；没拿却解锁 = IllegalMonitorStateException 掩盖真实异常。
 * 另一条是<b>必须走两参数的 tryLock</b>（保留看门狗），
 * 这条靠 Mockito 的 never() 断言钉死 —— 一旦有人改成三参数（显式租期），测试立刻红。
 *
 * <p><b>JUnit 4 差异点（写错就会编译不过或断言失效）：</b>
 * <ul>
 *   <li><b>失败消息在前、条件在后</b>：{@code assertTrue("msg", condition)}。
 *       JUnit 5 恰好相反（{@code assertTrue(condition, "msg")}），
 *       照搬过来会被当成两个不同的重载直接编译失败 —— 这是迁移时最常踩的一脚。</li>
 *   <li>需要抛受检异常的用例：JUnit 4 直接在方法签名上 {@code throws Exception} 即可，
 *       不需要任何额外注解。</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.class)
public class RedissonLockTest {

    private static final String LOCK_KEY = "lock:job:order-timeout-compensate";

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    /** 拿到锁：执行业务并在 finally 中解锁；且必须使用两参数 tryLock 保留看门狗 */
    @Test
    public void shouldRunTaskAndUnlock() throws Exception {
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        AtomicBoolean executed = new AtomicBoolean(false);
        boolean ran = new RedissonLock(redissonClient)
                .tryLockAndRun(LOCK_KEY, Duration.ZERO, () -> executed.set(true));

        Assert.assertTrue(ran);
        Assert.assertTrue("业务必须被执行", executed.get());
        verify(lock).unlock();

        // ★ 关键断言：绝不能调用带 leaseTime 的三参数重载 —— 那会关掉看门狗，
        // 让锁退回「固定租期」，也就是被替换掉的手写版的老问题
        verify(lock, never()).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
    }

    /** 抢不到锁：直接跳过业务，且绝不能去 unlock 别人的锁 */
    @Test
    public void shouldSkipWhenLockNotAcquired() throws Exception {
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        AtomicBoolean executed = new AtomicBoolean(false);
        boolean ran = new RedissonLock(redissonClient)
                .tryLockAndRun(LOCK_KEY, Duration.ZERO, () -> executed.set(true));

        Assert.assertFalse(ran);
        Assert.assertFalse("没拿到锁就不该执行任务", executed.get());
        verify(lock, never()).unlock();
        // 没拿到锁就不该问「是不是我持有的」，这条调用本身是多余的
        verify(lock, never()).isHeldByCurrentThread();
    }

    /** 业务抛异常：异常被吞掉（不能打断下次调度），但锁必须释放 */
    @Test
    public void shouldUnlockEvenWhenTaskThrows() throws Exception {
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        boolean ran = new RedissonLock(redissonClient)
                .tryLockAndRun(LOCK_KEY, Duration.ZERO, () -> {
                    throw new IllegalStateException("boom");
                });

        Assert.assertFalse("任务异常时返回 false 表示本次没跑成功", ran);
        verify(lock).unlock();
    }

    /** 等待锁时被中断：返回 false 并恢复中断标记（否则上层收不到中断信号） */
    @Test
    public void shouldRestoreInterruptFlag() throws Exception {
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());

        boolean ran = new RedissonLock(redissonClient)
                .tryLockAndRun(LOCK_KEY, Duration.ofSeconds(1), () -> {
                });

        Assert.assertFalse(ran);
        Assert.assertTrue("中断标记必须被恢复", Thread.currentThread().isInterrupted());
        verify(lock, never()).unlock();
        // 清理标记，避免污染同线程执行的其他用例
        Thread.interrupted();
    }

    /** 锁已不属于当前线程（看门狗续期失败/超时被抢）：不调用 unlock，避免 IllegalMonitorStateException */
    @Test
    public void shouldNotUnlockWhenNotHeldByCurrentThread() throws Exception {
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        new RedissonLock(redissonClient).tryLockAndRun(LOCK_KEY, Duration.ZERO, () -> {
        });

        verify(lock, never()).unlock();
        verify(lock).isHeldByCurrentThread();
    }

    /** 传入的等待时间原样透传（0 表示不排队，是定时任务的正确语义） */
    @Test
    public void shouldPassWaitTimeThrough() throws Exception {
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        new RedissonLock(redissonClient).tryLockAndRun(LOCK_KEY, Duration.ZERO, () -> {
        });

        verify(lock).tryLock(eq(0L), eq(TimeUnit.MILLISECONDS));
    }
}
