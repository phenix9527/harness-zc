package com.zc.api.demo.export.mq;

import com.rabbitmq.client.Channel;
import com.zc.api.demo.config.RabbitConfig;
import com.zc.api.demo.export.service.ExportTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 导出任务消费者（= 执行器侧）。
 *
 * <p><b>执行流程（对应文档里的执行器写法）：</b>
 * <pre>
 * markRunning(taskId)   -- WHERE status = 'PENDING'
 * rows == 0  -> return  -- 已被别的 worker 抢走，直接退出（幂等）
 * doExport(taskId)      -- 流式写文件 + 上报进度
 * markSuccess(...)      -- WHERE status = 'RUNNING'
 * </pre>
 *
 * <p><b>★ 长任务放在 MQ 消费者线程里的注意事项（这是本场景最容易踩的地方）：</b>
 * <ol>
 *   <li>RabbitMQ 有 {@code consumer_timeout}（默认 30 分钟）：一个消息处理太久，
 *       broker 会主动关闭 channel，消息重投。好在 {@code markRunning} 是 CAS，
 *       重投进来的消息会直接退出，不会重复导出（这就是「状态 CAS」的价值）。</li>
 *   <li>消费者并发被长任务占满后，后面的任务只能排队。生产建议：
 *       <b>MQ 只做「触发器」</b>，消费到消息后丢到<b>专用的有界线程池</b>执行并立刻 ack，
 *       或者按 bizType 拆队列、按任务量独立扩容消费者。</li>
 *   <li>如果采用「立刻 ack」的方案，就必须有 {@code findStuckTasks} 这类卡住任务告警 ——
 *       因为 ack 之后进程挂掉，消息不会重投，任务会永远停在 RUNNING。
 *       <b>不存在「既不丢又不重复又不卡住」的银弹，只有「选了哪种失败模式 + 怎么发现它」。</b></li>
 * </ol>
 */
@Component
public class ExportTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExportTaskConsumer.class);

    private final ExportTaskService exportTaskService;

    public ExportTaskConsumer(ExportTaskService exportTaskService) {
        this.exportTaskService = exportTaskService;
    }

    @RabbitListener(queues = RabbitConfig.EXPORT_TASK_QUEUE)
    public void onExportTask(String taskId,
                             Channel channel,
                             Message message,
                             @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            log.info("export task message received, taskId={}, redelivered={}",
                    taskId, message.getMessageProperties().isRedelivered());

            // execute 内部自己消化异常并落终态（FAILED），所以这里正常情况都能拿到 ack
            exportTaskService.execute(taskId);

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 能进到这里的基本是「落库失败」这类系统异常：此时任务状态未知，
            // 重投是合理的（markRunning 的 CAS 保证不会重复导出）
            log.error("export task consume error, taskId={}", taskId, e);
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
