package com.zc.api.demo.order.mq;

import com.rabbitmq.client.Channel;
import com.zc.api.demo.common.api.BizException;
import com.zc.api.demo.common.trace.TraceContext;
import com.zc.api.demo.config.RabbitConfig;
import com.zc.api.demo.order.service.OrderTimeoutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 场景4：超时关单消费者。
 *
 * <p><b>消费侧三件套（缺一个都会出事）：</b>
 * <ol>
 *   <li><b>幂等</b>：靠 {@code closeIfWaitPay} 的 CAS —— 重复投递直接 exit。</li>
 *   <li><b>手动 ack</b>：处理成功才 ack。自动 ack 在抛异常时会丢消息。</li>
 *   <li><b>失败分类处理</b>：业务失败不重投（重投一万次也不会成功，只会堆积），
 *       系统失败才重投/进死信。</li>
 * </ol>
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li>消费者里不要做重活：只做「CAS 改状态 + 通知下游」。重活会让消费变慢，
 *       消息堆积，超时重投，最后雪崩。</li>
 *   <li>{@code basicNack(..., requeue=true)} 是<b>反模式</b>：一条永远处理不了的消息会被
 *       无限重投，把消费者 CPU 吃满。要重试就配死信队列 + 延迟重试，或 Spring Retry
 *       （见 application.yml 的 listener.simple.retry）。</li>
 *   <li>这里必须给队列配死信交换机（生产环境），否则 {@code requeue=false} 等于消息丢失。</li>
 *   <li>MQ 消费线程里<b>没有</b> traceId（MDC 是 ThreadLocal）。生产方案是「消息头带上 traceId，
 *       消费时取出塞进 MDC」，这里用订单号自己生成一个，保证日志可串。</li>
 * </ol>
 */
@Component
public class OrderTimeoutListener {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutListener.class);

    private final OrderTimeoutService orderTimeoutService;

    public OrderTimeoutListener(OrderTimeoutService orderTimeoutService) {
        this.orderTimeoutService = orderTimeoutService;
    }

    @RabbitListener(queues = RabbitConfig.ORDER_TIMEOUT_QUEUE)
    public void onTimeout(Long orderId,
                          Channel channel,
                          Message message,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        // 跨线程传递 traceId：MQ 线程拿不到 Web 线程的 MDC，这里自己造一个
        String traceId = message.getMessageProperties().getHeader(TraceContext.TRACE_ID_HEADER);
        TraceContext.setTraceId(traceId == null ? "mq-" + orderId : traceId);
        try {
            log.info("order timeout message received, orderId={}, redelivered={}",
                    orderId, message.getMessageProperties().isRedelivered());

            // ★ handleTimeout 内部是 CAS 关单 + 幂等释放资源，重复投递天然安全
            orderTimeoutService.handleTimeout(orderId);

            channel.basicAck(deliveryTag, false);
        } catch (BizException e) {
            // 业务失败：重投也不会成功，必须 ack 掉，否则消息会无限循环
            log.warn("order timeout handle biz fail, ack and drop. orderId={}, code={}, msg={}",
                    orderId, e.getErrorCode().getCode(), e.getMessage());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 系统异常：不重回原队列（避免无脑循环），交给死信队列/重试机制与人工兜底。
            // 生产环境这里必须配 DLX + 告警，否则等于丢消息。
            log.error("order timeout handle system error, nack to dlx. orderId={}", orderId, e);
            channel.basicNack(deliveryTag, false, false);
        } finally {
            TraceContext.clear();
        }
    }
}
