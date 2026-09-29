package com.zc.api.demo.order.job;

import com.zc.api.demo.common.lock.RedissonLock;
import com.zc.api.demo.order.entity.Order;
import com.zc.api.demo.order.mapper.OrderMapper;
import com.zc.api.demo.order.service.OrderTimeoutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;
import java.util.List;

/**
 * 场景4 的第二道防线：兜底扫表关单。
 *
 * <p><b>不用它行不行？不行。</b> 延迟消息会丢：
 * <ul>
 *   <li>MQ 重启 / 队列被误删 / 交换机没绑定好；</li>
 *   <li>消息在 delay 队列里被 purge；</li>
 *   <li>发布时队列 TTL 参数配错，消息提前过期。</li>
 * </ul>
 * 消息一丢，订单就永远挂在「待支付」，库存和券一直被占着，
 * <b>而且没有任何告警</b>——直到运营来问「为什么库存对不上」。
 * 定时扫表是必需的，不是「优化」。
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li><b>多副本会重复执行</b>：所以用 {@link RedissonLock} 做互斥。锁只用于
 *       「同一时刻一个实例跑」，不用它保证业务正确性（正确性靠 CAS）。更规范的做法是交给
 *       XXL-JOB / 调度平台的单机路由，别在应用里自己抢锁。
 *       <b>这里用 Redisson 而不是手写 SETNX 锁，就是为了看门狗续期</b> ——
 *       扫表任务耗时随数据量增长，固定 TTL 的锁总会被跑超（详见 {@link RedissonLock} 的类注释）。</li>
 *   <li><b>必须分批 + LIMIT</b>：一次捞全量会把内存和 DB 打爆。</li>
 *   <li><b>扫描窗口要有容差</b>：这里扫「创建时间早于 30 分钟前」的，和延迟消息的时间对齐，
 *       避免和正常流程抢同一批订单（虽然抢了也安全，但会产生大量无意义的 CAS）。</li>
 *   <li><b>和在线流量错峰</b>：扫表会占用连接，建议低峰期、慢一点跑，别用高频 cron 硬刷。</li>
 *   <li><b>单条失败要隔离</b>：一批里某条数据异常不能让整批停下，
 *       否则一条脏数据会把所有超时订单都卡住（经典「死信堵住队列」问题的翻版）。</li>
 * </ol>
 */
@Component
public class OrderTimeoutCompensateJob {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutCompensateJob.class);

    private static final String LOCK_KEY = "lock:job:order-timeout-compensate";
    private static final int BATCH_SIZE = 200;
    /** 单次最多处理多少批，防止一次调度跑太久影响下一轮 */
    private static final int MAX_BATCH = 20;

    private final OrderMapper orderMapper;
    private final OrderTimeoutService orderTimeoutService;
    private final RedissonLock redissonLock;

    /** 订单超时时间（分钟），与延迟消息的 TTL 保持一致；扫描时留 1 分钟容差 */
    @Value("${demo.order.timeout-minutes:30}")
    private int timeoutMinutes;

    public OrderTimeoutCompensateJob(OrderMapper orderMapper,
                                     OrderTimeoutService orderTimeoutService,
                                     RedissonLock redissonLock) {
        this.orderMapper = orderMapper;
        this.orderTimeoutService = orderTimeoutService;
        this.redissonLock = redissonLock;
    }

    /**
     * 每 2 分钟扫一次。
     *
     * <p>{@code fixedDelay} 而不是 {@code fixedRate}：上一轮没跑完不要叠加下一轮，
     * 否则任务会越堆越多。{@code initialDelay} 给应用留出启动时间。
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 120_000)
    public void scanTimeoutOrders() {
        // 注意点：等待时间给 0 —— 抢不到锁说明别的实例正在扫，本次直接跳过才是正确行为。
        // 排队等锁只会让调度线程堆积，而且等到的往往是「别人已经处理完的那批数据」。
        // 锁的续期交给 Redisson 看门狗，这里不再需要手工估算 TTL。
        redissonLock.tryLockAndRun(LOCK_KEY, Duration.ZERO, this::doScan);
    }

    private void doScan() {
        // 留 1 分钟容差：给延迟消息一点发挥空间，减少无效 CAS
        long deadlineMillis = System.currentTimeMillis() - (timeoutMinutes + 1) * 60_000L;
        Date deadline = new Date(deadlineMillis);

        int totalClosed = 0;
        for (int batch = 0; batch < MAX_BATCH; batch++) {
            List<Order> orders = orderMapper.selectWaitPayBefore(deadline, BATCH_SIZE);
            if (orders.isEmpty()) {
                break;
            }
            for (Order order : orders) {
                try {
                    // handleTimeout 里的 CAS 保证「已经被延迟消息关掉的订单」在这里不会重复处理
                    orderTimeoutService.handleTimeout(order.getId());
                    totalClosed++;
                } catch (Exception e) {
                    // 单条失败跳过，不中断整批
                    log.error("compensate close order fail, orderId={}", order.getId(), e);
                }
            }
            if (orders.size() < BATCH_SIZE) {
                break;
            }
        }
        if (totalClosed > 0) {
            log.info("order timeout compensate job done, closed={}", totalClosed);
        }
    }
}
