package com.zc.api.demo.pay.service;

import com.zc.api.demo.common.api.BizException;
import com.zc.api.demo.common.api.ErrorCode;
import com.zc.api.demo.common.api.SystemException;
import com.zc.api.demo.config.RabbitConfig;
import com.zc.api.demo.order.constant.OrderStatus;
import com.zc.api.demo.order.entity.Order;
import com.zc.api.demo.order.mapper.OrderMapper;
import com.zc.api.demo.pay.dto.PayNotify;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 场景2：支付回调处理 —— 幂等 + 状态机守卫的教科书。
 *
 * <p><b>这个场景最难的地方在于：对面什么时候推、推几次、推的顺序，你完全控制不了。</b>
 * 网关的规则一般是「按指数退避重试 8 次，约 24 小时」，
 * 期间业务可能已经处理过了、订单可能被用户取消了、运维可能手动改过状态。
 * 所以这个接口的每一行都必须在「重复 / 乱序 / 状态已变」的前提下仍然正确。
 *
 * <p><b>处理顺序（顺序本身也是设计的一部分）：</b>
 * <pre>
 * 1. 验签              —— 不通过直接拒，防止伪造回调
 * 2. 事件类型过滤      —— 只处理支付成功，其它事件直接忽略
 * 3. 处理中去重标记    —— 省资源（可失败，不影响正确性）
 * 4. 定位订单          —— 查不到要「返回失败让网关重试」，不能当作成功吞掉
 * 5. 金额校验          —— 以订单表为准，不一致必须告警 + 人工介入，不能自动置为已支付
 * 6. CAS 状态流转      —— ★ 一行 SQL 同时解决幂等 + 状态机守卫
 * 7. 落库 + 发 MQ      —— 重业务全部异步，回调只做最少的事
 * </pre>
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li><b>不要依赖回调里的金额</b>。以自己订单表的金额为准 —— 回调报文是「别人说的」，
 *       订单表才是「我们确认过的」。反过来会让伪造/篡改有可乘之机。</li>
 *   <li><b>不要在这个方法上随便加 {@code @Transactional} 把整段包起来</b>：
 *       一旦包了事务，MQ 必须在 afterCommit 里发（见 {@code OrderTimeoutService#registerTimeout}），
 *       否则消费者可能在事务提交前就查不到数据。本示例只有一条 UPDATE，
 *       自动提交，所以可以直接发。</li>
 *   <li><b>CAS 返回 0 时不要报错</b>，也不要「再查一次然后手动改」。
 *       它代表「这次不是我应该处理的那一次」，静默返回是唯一正确的处理。</li>
 *   <li>回调天然会重复，所以「下游动作」也必须幂等。本示例把下游放到 MQ，
 *       消费侧靠 {@code t_order_stock_lock} 的状态 CAS 保证幂等。</li>
 * </ol>
 */
@Service
public class PayNotifyService {

    private static final Logger log = LoggerFactory.getLogger(PayNotifyService.class);

    /** 处理成功后的标记 TTL：覆盖网关重试窗口，避免被重复回调反复打 DB */
    private static final Duration PROCESSED_MARK_TTL = Duration.ofHours(24);

    private final OrderMapper orderMapper;
    private final RabbitTemplate rabbitTemplate;
    private final WxPayVerifier wxPayVerifier;

    public PayNotifyService(OrderMapper orderMapper,
                            RabbitTemplate rabbitTemplate,
                            WxPayVerifier wxPayVerifier) {
        this.orderMapper = orderMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.wxPayVerifier = wxPayVerifier;
    }

    /**
     * 处理支付成功通知（带「处理中」标记的外层包装）。
     *
     * <p>异常语义（由 Controller 翻译成网关要的返回体）：
     * <ul>
     *   <li>{@link BizException} → 业务失败／已处理：返回 SUCCESS，别让网关一直重试</li>
     *   <li>{@link SystemException} 或其它异常 → 返回 FAIL，让网关重试</li>
     * </ul>
     *
     * <p><b>为什么要包一层：</b>Redis 的「处理中」标记必须在异常路径上<b>释放</b>，
     * 否则第一次处理失败后，网关的重试会被自己的去重标记挡在门外 —— 表面上是「去重」，
     * 实际上是把重试机制屏蔽了，用户付了钱订单永远是待支付（这类事故真实存在）。
     */
    public void handlePaid(PayNotify notify) {
        boolean marked = wxPayVerifier.tryMarkProcessing(notify.getNotifyId());
        if (!marked) {
            // 同一通知正在被处理：返回 FAIL 让网关稍后重试，不要返回 SUCCESS（那等于放弃这次通知）
            throw new SystemException(ErrorCode.CONCURRENT_CONFLICT, "通知处理中，请稍后重试");
        }
        try {
            doHandlePaid(notify);
            // 成功：把标记延长到覆盖网关重试窗口，挡住后续重复回调
            wxPayVerifier.extendMark(notify.getNotifyId(), PROCESSED_MARK_TTL);
        } catch (RuntimeException e) {
            // 失败：释放标记，保证网关的重试能正常进入
            wxPayVerifier.releaseMark(notify.getNotifyId());
            throw e;
        }
    }

    /**
     * 真正的处理逻辑（7 步流程见类注释）。
     */
    private void doHandlePaid(PayNotify notify) {
        // 1) 只处理支付成功事件；退款/关闭等事件走各自的处理器（这里先忽略并记日志）
        if (!"SUCCESS".equalsIgnoreCase(notify.getTradeState())) {
            log.info("ignore non-success pay notify, outTradeNo={}, state={}",
                    notify.getOutTradeNo(), notify.getTradeState());
            return;
        }

        // 2) 定位订单：走唯一索引 uk_out_trade_no
        Order order = orderMapper.selectByOutTradeNo(notify.getOutTradeNo());
        if (order == null) {
            // ★ 这里是最容易做错的地方：不能「查不到也返回 SUCCESS」。
            //   付了钱却没有订单 = 资金风险，宁可让网关重试（约 24 小时 8 次），
            //   同时告警 + 对账兜底。重复回调本身是安全的（CAS 幂等）。
            log.error("order not found by outTradeNo, PAYMENT RISK! outTradeNo={}, transactionId={}",
                    notify.getOutTradeNo(), notify.getTransactionId());
            throw new SystemException(ErrorCode.ORDER_NOT_FOUND, "订单不存在，等待重试");
        }

        // 3) 金额校验：以订单表为准
        if (!amountMatches(order.getAmount(), notify.getAmountTotal())) {
            // 金额不一致绝不能自动置为已支付，也绝不能一直重试（重试解决不了数据问题）。
            // 正确姿势：告警 + 人工/自动退款流程 + 保留原始报文备查。
            log.error("AMOUNT MISMATCH! orderId={}, outTradeNo={}, orderAmount={}, notifyAmount={}",
                    order.getId(), notify.getOutTradeNo(), order.getAmount(), notify.getAmountTotal());
            // 抛业务异常 -> Controller 返回 SUCCESS（停止重试），但告警已经打出去了
            throw new BizException(ErrorCode.ORDER_AMOUNT_MISMATCH);
        }

        // 4) ★★ 一行 CAS 同时解决「幂等」和「状态机守卫」
        //    UPDATE txm_order SET status=2, pay_time=NOW() WHERE id=? AND status=1
        //    rows=1：我是第一个把它从「待支付」改成「已支付」的 -> 继续执行后续业务
        //    rows=0：重复通知 / 已被关闭 / 已完成 -> 静默返回，不报错、不做任何事
        int rows = orderMapper.casToPaid(order.getId(), OrderStatus.WAIT_PAY, OrderStatus.PAID,
                notify.getPayChannel());
        if (rows == 0) {
            // 这里必须 debug/info 级别记录，而不是 error —— 它是正常现象。
            // 但也别忘了：如果这条日志量突然暴涨，说明有重复回调风暴，需要查网关侧问题。
            Order latest = orderMapper.selectById(order.getId());
            log.info("pay notify ignored, duplicate or illegal state. orderId={}, currentStatus={}",
                    order.getId(), latest == null ? "null" : latest.getStatus());
            return;
        }

        // 5) 只有真正完成状态流转的那一次，才会执行后续业务
        //    重业务全部异步：回调要有 5s 内返回的自觉，做得越多越容易超时被重推
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.ORDER_PAID_EXCHANGE,
                    RabbitConfig.ORDER_PAID_ROUTING_KEY, order.getId());
            log.info("order paid, event sent. orderId={}, outTradeNo={}", order.getId(), notify.getOutTradeNo());
        } catch (Exception e) {
            // ★ MQ 发送失败怎么办？这是必须想清楚的问题。
            //   订单状态已经置为已支付（事实已经成立），不能回滚；
            //   消息丢了意味着下游（库存/积分/通知）不会执行 -> 必须有补偿：
            //   ① 生产端开启 publisher-confirm + 本地消息表重投；
            //   ② 或由对账任务扫「已支付但下游未完成」的订单补发。
            //   这里抛出去是为了让网关重试（CAS 会拦住重复置状态，但重试能触发下面重发 MQ 吗？不能——
            //   所以真正的兜底是对账任务）。这一行注释是本节最值钱的部分：别指望 MQ 一定发得出去。
            log.error("send order paid event fail, need reconcile. orderId={}", order.getId(), e);
            throw new SystemException(ErrorCode.DEPENDENCY_ERROR, "支付事件投递失败，需对账补偿", e);
        }
    }

    /**
     * 金额比对：订单表是元（BigDecimal），网关是分（Integer）。
     *
     * <p>注意点：<b>不同单位的金额比较一定要显式换算</b>，
     * 而且不要在比较时做除法（{@code notifyAmount / 100.0}）—— 浮点误差会导致 0.1 元这种金额比较失败。
     */
    private boolean amountMatches(BigDecimal orderAmount, Integer notifyAmountFen) {
        if (orderAmount == null || notifyAmountFen == null) {
            return false;
        }
        BigDecimal notifyYuan = BigDecimal.valueOf(notifyAmountFen).movePointLeft(2);
        return orderAmount.compareTo(notifyYuan) == 0;
    }
}
