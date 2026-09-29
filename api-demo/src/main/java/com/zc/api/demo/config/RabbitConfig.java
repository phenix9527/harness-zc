package com.zc.api.demo.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置：延迟队列（订单超时）+ 普通业务队列（异步导出）。
 *
 * <p><b>延迟消息的三种实现与选型：</b>
 * <pre>
 * 方案                        适用                     本项目
 * RocketMQ 延迟等级            已用 RocketMQ 的项目      ——（最简单，推荐）
 * RabbitMQ TTL + 死信(DLX)    已用 RabbitMQ 的项目      ✅ 本实现
 * Redis ZSet + 定时扫描        无 MQ / 延迟粒度灵活      ——（可作兜底）
 * </pre>
 *
 * <p><b>TTL + 死信方案的关键机制：</b>
 * 消息先投到 <code>delay.queue</code>（不消费），队列级 TTL 到期后消息变成死信，
 * 被转发到 <code>x-dead-letter-exchange</code> 指定的交换机，再路由到真正被消费的
 * <code>order.timeout.queue</code>。所以「延迟」= 在 delay 队列里躺够 TTL。
 *
 * <p><b>注意点（踩过的坑，全部要记住）：</b>
 * <ol>
 *   <li><b>队列级 TTL 有「队头阻塞」问题</b>：消息是队头先过期才检查，如果同一队列里既有 30 分钟
 *       又有 5 分钟的消息，后进的 5 分钟消息要等前面的 30 分钟消息过期才能出来。
 *       所以：<b>一个延迟等级一个队列</b>（本项目只有 30 分钟一档，正好安全）。
 *       要精确的任意延迟，用 RocketMQ 延迟等级或 Redis ZSet 轮询。</li>
 *   <li><b>消息体用 JSON 序列化</b>：默认 JDK 序列化在控制台看是一堆乱码，且跨版本不兼容，
 *       队列里堆积时完全无法排查。</li>
 *   <li><b>延迟队列必须配兜底扫表</b>：MQ 重启/队列被误删，消息就永久丢了，
 *       订单会一直挂着占库存。见 {@code OrderTimeoutCompensateJob}。</li>
 *   <li>生产环境要给队列配 DLX（死信队列）接收「消费失败多次」的消息，否则会一直重投。</li>
 *   <li>不要在消费者里做重活：网关/中间件都有超时，超时即重投，重活容易雪崩。
 *       本项目的消费者只做「CAS 关单 + 发释放资源的短逻辑」。</li>
 * </ol>
 * @author admin
 * TODO zhoucong config/RabbitConfig.java:95-102   队列 TTL 创建后不可改，改了要换队列名
 */
@Configuration
public class RabbitConfig {

    /* ---------------- 订单超时：延迟队列链路 ---------------- */

    public static final String ORDER_TIMEOUT_DELAY_EXCHANGE = "order.timeout.delay.exchange";
    public static final String ORDER_TIMEOUT_DELAY_QUEUE = "order.timeout.delay.queue";
    public static final String ORDER_TIMEOUT_DELAY_ROUTING_KEY = "order.timeout.delay";

    public static final String ORDER_TIMEOUT_EXCHANGE = "order.timeout.exchange";
    public static final String ORDER_TIMEOUT_QUEUE = "order.timeout.queue";
    public static final String ORDER_TIMEOUT_ROUTING_KEY = "order.timeout";

    /** 延迟时长（毫秒）。默认 30 分钟；调短便于本地自测，例如 60000 = 1 分钟 */
    private static final int DELAY_MILLIS = 30 * 60 * 1000;

    /* ---------------- 异步导出 ---------------- */

    public static final String EXPORT_TASK_EXCHANGE = "export.task.exchange";
    public static final String EXPORT_TASK_QUEUE = "export.task.queue";
    public static final String EXPORT_TASK_ROUTING_KEY = "export.task";

    /* ---------------- 支付成功事件（下游异步处理） ---------------- */

    public static final String ORDER_PAID_EXCHANGE = "order.paid.exchange";
    public static final String ORDER_PAID_QUEUE = "order.paid.queue";
    public static final String ORDER_PAID_ROUTING_KEY = "order.paid";

    /**
     * 消息转换器：统一 JSON。
     *
     * <p><b>注意点：</b> 发送方和消费方的 MessageConverter 必须一致，否则报
     * {@code MessageConversionException}，而且错误信息通常是「类型不匹配」，很难定位。
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public DirectExchange orderTimeoutDelayExchange() {
        return new DirectExchange(ORDER_TIMEOUT_DELAY_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange orderTimeoutExchange() {
        return new DirectExchange(ORDER_TIMEOUT_EXCHANGE, true, false);
    }

    /**
     * 延迟队列：核心在于两个参数
     * <ul>
     *   <li>{@code x-message-ttl}：消息在这里躺多久</li>
     *   <li>{@code x-dead-letter-exchange}：TTL 到期后去哪（转成死信重新路由）</li>
     * </ul>
     * 注意队列一旦创建，TTL 等参数不可改；改了要换队列名或删队列重建。
     */
    @Bean
    public Queue orderTimeoutDelayQueue() {
        return QueueBuilder.durable(ORDER_TIMEOUT_DELAY_QUEUE)
                .ttl(DELAY_MILLIS)
                .deadLetterExchange(ORDER_TIMEOUT_EXCHANGE)
                .deadLetterRoutingKey(ORDER_TIMEOUT_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue orderTimeoutQueue() {
        return QueueBuilder.durable(ORDER_TIMEOUT_QUEUE).build();
    }

    @Bean
    public Binding orderTimeoutDelayBinding() {
        return BindingBuilder.bind(orderTimeoutDelayQueue())
                .to(orderTimeoutDelayExchange())
                .with(ORDER_TIMEOUT_DELAY_ROUTING_KEY);
    }

    @Bean
    public Binding orderTimeoutBinding() {
        return BindingBuilder.bind(orderTimeoutQueue())
                .to(orderTimeoutExchange())
                .with(ORDER_TIMEOUT_ROUTING_KEY);
    }

    @Bean
    public DirectExchange exportTaskExchange() {
        return new DirectExchange(EXPORT_TASK_EXCHANGE, true, false);
    }

    @Bean
    public Queue exportTaskQueue() {
        return QueueBuilder.durable(EXPORT_TASK_QUEUE).build();
    }

    @Bean
    public Binding exportTaskBinding() {
        return BindingBuilder.bind(exportTaskQueue())
                .to(exportTaskExchange())
                .with(EXPORT_TASK_ROUTING_KEY);
    }

    @Bean
    public DirectExchange orderPaidExchange() {
        return new DirectExchange(ORDER_PAID_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderPaidQueue() {
        return QueueBuilder.durable(ORDER_PAID_QUEUE).build();
    }

    @Bean
    public Binding orderPaidBinding() {
        return BindingBuilder.bind(orderPaidQueue())
                .to(orderPaidExchange())
                .with(ORDER_PAID_ROUTING_KEY);
    }
}
