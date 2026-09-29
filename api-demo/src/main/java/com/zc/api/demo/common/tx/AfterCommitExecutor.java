package com.zc.api.demo.common.tx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 「事务提交后再执行」的小工具。
 *
 * <p><b>解决什么问题：</b>业务代码里常见的错误是「在事务里发 MQ / 调用远程接口」。
 * 此时事务还没提交，消费者或下游服务拿到通知去查数据，查到的可能是 null 或旧状态；
 * 更糟的是事务随后回滚了 —— 下游却已经按「业务成功」处理完了，脏数据就这样产生。
 *
 * <p>正确姿势有两种：
 * <ol>
 *   <li><b>事务提交后发送</b>（本工具做的事）—— 简单，覆盖 90% 场景。
 *       缺点是「提交成功但发送失败」的消息会丢，需要配合下面这种更彻底的方案。</li>
 *   <li><b>本地消息表 / 事务消息</b> —— 业务数据与「待发送消息」在同一个本地事务写入，
 *       再由独立的投递线程扫描发送，做到「业务成功则消息必达」。
 *       对账场景（支付、订单）建议上这套。</li>
 * </ol>
 *
 * <p><b>注意点：</b>
 * <ul>
 *   <li>没有事务上下文时（定时任务、MQ 消费者）直接执行，不要报错。</li>
 *   <li>afterCommit 里抛异常会影响调用方感知（异常会往上传播），
 *       所以里面的逻辑要自己 try-catch，不能让它影响主流程。</li>
 *   <li>注意嵌套事务：{@code REQUIRES_NEW} 的内部事务提交时也会触发回调，
 *       如果你的回调依赖外层数据，需要额外判断（这是容易被忽略的坑）。</li>
 * </ul>
 * @author admin
 */
@Component
public class AfterCommitExecutor {

    private static final Logger log = LoggerFactory.getLogger(AfterCommitExecutor.class);

    public void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeRun(action);
                }
            });
        } else {
            safeRun(action);
        }
    }

    private void safeRun(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            // 不能把异常抛回调用方：业务事务已经提交，这里失败要靠补偿/对账解决
            log.error("after-commit action fail", e);
        }
    }
}
