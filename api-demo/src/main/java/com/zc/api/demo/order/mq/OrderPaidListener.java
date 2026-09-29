package com.zc.api.demo.order.mq;

import com.rabbitmq.client.Channel;
import com.zc.api.demo.common.api.BizException;
import com.zc.api.demo.config.RabbitConfig;
import com.zc.api.demo.order.service.OrderStockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 支付成功事件消费者：把「库存占用」转为「真实扣减」。
 *
 * <p><b>为什么支付回调不直接做这件事，而是发消息异步做？</b>
 * <ol>
 *   <li>支付网关有 5s 超时（微信），超时就重推。回调里只做「落库 + 发 MQ」最安全，
 *       做得越多越容易超时，超时就是新一轮重推。</li>
 *   <li>下游动作会越来越多（积分、优惠券、发货单、消息通知、数据报表）。
 *       全塞在回调里等于把整个下游稳定性绑死在支付回调上。</li>
 * </ol>
 *
 * <p><b>这个消费者本身就是「下游任务幂等」的范例：</b>
 * 下游任务不能假设「回调只来一次」，所以每一个下游动作都要自带幂等依据。
 * 这里用的是 {@code t_order_stock_lock} 的状态 CAS（1占用中 → 3已扣减），
 * 而不是「看订单是不是已支付」—— 因为订单已支付这件事和库存是否处理过是两回事。
 *
 * <p>真实项目里这个类会变成「订单支付成功事件的分发器」，
 * 每个下游动作一个 handler，各自保证幂等（唯一索引 / 状态 CAS / 消息去重表）。
 */
@Component
public class OrderPaidListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidListener.class);

    private final OrderStockService orderStockService;

    public OrderPaidListener(OrderStockService orderStockService) {
        this.orderStockService = orderStockService;
    }

    @RabbitListener(queues = RabbitConfig.ORDER_PAID_QUEUE)
    public void onOrderPaid(Long orderId,
                            Channel channel,
                            Message message,
                            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            log.info("order paid event received, orderId={}, redelivered={}",
                    orderId, message.getMessageProperties().isRedelivered());

            orderStockService.deductByPaidOrder(orderId);

            // TODO 真实项目在这里继续分发其余下游动作，每一个都必须自带幂等依据：
            //   pointService.grantByOrder(orderId)     -> t_user_point_log 上 uk_order_id
            //   couponService.issueByOrder(orderId)    -> t_coupon 上 uk_order_id
            //   notifyService.pushPaidMsg(orderId)     -> 通知去重表
            //   deliveryService.createDelivery(orderId)-> t_delivery 上 uk_order_id

            channel.basicAck(deliveryTag, false);
        } catch (BizException e) {
            // 业务上已经处理过 —— 正常现象（重复投递），ack 掉，别让它循环
            log.info("order paid event idempotent hit, ack. orderId={}, msg={}", orderId, e.getMessage());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("order paid event handle error, nack to dlx. orderId={}", orderId, e);
            // 不重回原队列：requeue=true 会让这条消息无限循环打爆消费者
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
