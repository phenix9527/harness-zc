package com.zc.api.demo.order.service;

import com.zc.api.demo.config.RabbitConfig;
import com.zc.api.demo.common.tx.AfterCommitExecutor;
import com.zc.api.demo.order.constant.OrderStatus;
import com.zc.api.demo.order.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * 场景4：订单超时关闭。
 *
 * <p><b>整体链路：</b>
 * <pre>
 * 下单成功 ──发延迟消息──▶ order.timeout.delay.queue（TTL 30min）
 *                                   │ 到期变死信
 *                                   ▼
 *                          order.timeout.queue
 *                                   │ 消费
 *                                   ▼
 *                    CAS 关单（仅当仍是待支付）
 *                                   │ rows==1 才继续
 *                                   ▼
 *                      释放库存 / 退还优惠券（各自幂等）
 *
 * 兜底：OrderTimeoutCompensateJob 定时扫表，处理「消息丢了」的订单
 * </pre>
 *
 * <p><b>为什么「消息丢了」是必须处理的场景：</b> MQ 重启、队列被误删、消息 TTL 设置错误、
 * 发布时交换机还没建好，任何一种都会让消息消失。而消息一丢，订单就永远挂在「待支付」占着库存，
 * 且没有任何告警 —— 只能靠定时扫表兜底。这是必需的第二道防线，不是可选优化。
 */
@Service
public class OrderTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutService.class);

    private final OrderMapper orderMapper;
    private final OrderStockService orderStockService;
    private final RabbitTemplate rabbitTemplate;
    private final AfterCommitExecutor afterCommitExecutor;

    public OrderTimeoutService(OrderMapper orderMapper,
                               OrderStockService orderStockService,
                               RabbitTemplate rabbitTemplate,
                               AfterCommitExecutor afterCommitExecutor) {
        this.orderMapper = orderMapper;
        this.orderStockService = orderStockService;
        this.rabbitTemplate = rabbitTemplate;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    /**
     * 注册超时关单（下单成功后调用）。
     *
     * <p><b>★ 注意点：一定不能在事务里直接发 MQ。</b>
     * 事务还没提交就发出消息，消费者可能在提交前就查到数据（查到 null 或旧状态），
     * 出现「消息处理完了但事务回滚了」的空转。所以用 {@link AfterCommitExecutor}
     * 把发送推迟到事务提交之后（见该类的说明，以及更彻底的「本地消息表」方案）。
     */
    public void registerTimeout(Long orderId) {
        afterCommitExecutor.run(() -> sendDelayMessage(orderId));
    }

    /**
     * 发送延迟消息。
     *
     * <p>注意：这里发到「延迟队列」，靠队列 TTL + 死信转发实现延迟，
     * 所以生产端不需要知道延迟多久（由队列配置决定）。
     * 缺点是一个延迟等级一个队列；要任意延迟请用 RocketMQ 延迟等级或 Redis ZSet。
     */
    public void sendDelayMessage(Long orderId) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.ORDER_TIMEOUT_DELAY_EXCHANGE,
                    RabbitConfig.ORDER_TIMEOUT_DELAY_ROUTING_KEY, orderId);
            log.info("order timeout delay message sent, orderId={}", orderId);
        } catch (Exception e) {
            // ★ 这里绝不能让下单失败：消息发不出去还有兜底扫表。
            //   但必须打 error 并接告警 —— 「延迟消息发送失败率」要进监控大盘。
            log.error("send delay message fail, rely on compensate job. orderId={}", orderId, e);
        }
    }

    /**
     * 超时处理主流程（消费者和兜底任务共用）。
     *
     * <p><b>CAS 关单是唯一的并发控制手段，不需要分布式锁。</b>
     */
    public void handleTimeout(Long orderId) {
        boolean closed = closeIfWaitPay(orderId);
        if (!closed) {
            // 已经不是待支付了（用户已支付 / 已取消 / 重复消息），什么都不用做
            return;
        }
        // 只有真正完成状态流转的那一次，才会执行后续业务。
        // 每个都是幂等动作：重复执行不会造成库存虚增/券重复退还。
        try {
            orderStockService.releaseByClosedOrder(orderId);
        } catch (Exception e) {
            // 关单已成功，资源释放失败 → 不能让消息重投去重跑关单（CAS 会直接 exit），
            // 所以这里要靠「对账任务」补偿：扫「已关闭 但 流水仍为占用中」的记录。
            // 我在 catch 里抛出去是为了让 MQ 重投；如果你选择不重投，就必须有对账补偿。
            log.error("release resource fail after closed, need reconcile. orderId={}", orderId, e);
            throw e;
        }
        log.info("order timeout closed, orderId={}", orderId);
    }

    /**
     * CAS 关单：仅当仍是「待支付」才关。
     *
     * @return true = 这次是我关的（有效操作）；false = 不用处理
     */
    public boolean closeIfWaitPay(Long orderId) {
        int rows = orderMapper.closeIfWaitPay(orderId);
        if (rows == 0) {
            log.info("order not wait-pay or already closed, skip. orderId={}", orderId);
            return false;
        }
        return true;
    }

    /** 状态名称仅用于日志可读性 */
    static String statusName(int status) {
        return OrderStatus.name(status);
    }
}
