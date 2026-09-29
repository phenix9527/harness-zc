package com.zc.api.demo.export.service;

import com.zc.api.demo.common.api.BizException;
import com.zc.api.demo.common.api.ErrorCode;
import com.zc.api.demo.common.tx.AfterCommitExecutor;
import com.zc.api.demo.config.RabbitConfig;
import com.zc.api.demo.export.constant.ExportTaskStatus;
import com.zc.api.demo.export.dto.ExportTaskReq;
import com.zc.api.demo.export.entity.ExportTask;
import com.zc.api.demo.export.mapper.ExportTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 场景5：异步导出任务服务。
 *
 * <p><b>为什么长任务必须异步（而不是同步接口扛）：</b>
 * <pre>
 * 同步方案会遇到的四道墙，任何一道都会把你坑死：
 *   Feign 默认超时（通常 1~10s）   -> 调用方先失败，你还在跑
 *   网关/Nginx 超时（30~60s）      -> 用户看到 502，但任务其实成功了
 *   浏览器/客户端断开              -> 你以为失败了，其实服务端线程还在跑
 *   线程被长任务占住               -> 并发几个大导出，整个服务不可用
 * </pre>
 * 正确姿势：提交返回 taskId（毫秒级返回），执行放消息队列/线程池，查询接口读状态。
 *
 * <p><b>幂等（提交接口）：</b>{@code requestId + userId} 唯一索引。
 * 用户连点两次、网络重试、客户端重发 —— 返回的都是同一个 taskId，不会生成两个任务。
 * 和场景1 一样的套路：<b>先查，再插，撞唯一索引后回查</b>。
 * @author admin
 * TODO zhoucong export/service/ExportTaskService.java  导出失败为什么不重投
 */
@Service
public class ExportTaskService {

    private static final Logger log = LoggerFactory.getLogger(ExportTaskService.class);

    /** bizType 白名单：不给白名单，调用方就能指定任意类型绕过行数与权限限制 */
    private static final Set<String> BIZ_TYPE_WHITELIST =
            new HashSet<>(Arrays.asList("ORDER", "USER", "COUPON"));

    /** error_msg 字段长度上限的兜底（写入超长会再抛一个异常，掩盖真实原因） */
    private static final int ERROR_MSG_MAX_LEN = 200;

    private final ExportTaskMapper exportTaskMapper;
    private final ExportExecutor exportExecutor;
    private final RabbitTemplate rabbitTemplate;
    private final AfterCommitExecutor afterCommitExecutor;

    public ExportTaskService(ExportTaskMapper exportTaskMapper,
                             ExportExecutor exportExecutor,
                             RabbitTemplate rabbitTemplate,
                             AfterCommitExecutor afterCommitExecutor) {
        this.exportTaskMapper = exportTaskMapper;
        this.exportExecutor = exportExecutor;
        this.rabbitTemplate = rabbitTemplate;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    /**
     * 提交导出任务（幂等）。
     *
     * <p>注意点：这里用 {@code @Transactional} 包住「查 + 插」，
     * 并在<b>事务提交后</b>才发 MQ。如果先发 MQ，worker 可能在任务记录还不可见时就开跑，
     * {@code selectByTaskId} 查到 null，任务直接失败（这是很典型的「发了消息但查不到数据」事故）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExportTask createOrGet(ExportTaskReq req, Long userId) {
        validate(req);

        ExportTask exist = exportTaskMapper.selectByRequestIdAndUser(req.getRequestId(), userId);
        if (exist != null) {
            log.info("export task idempotent hit, requestId={}, taskId={}", req.getRequestId(), exist.getTaskId());
            return exist;
        }

        ExportTask task = new ExportTask();
        task.setTaskId(generateTaskId());
        task.setRequestId(req.getRequestId());
        task.setUserId(userId);
        task.setBizType(req.getBizType());
        task.setQueryJson(req.getQueryJson());
        task.setStatus(ExportTaskStatus.PENDING.name());
        task.setProgress(0);
        try {
            exportTaskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 并发：两个请求同时通过了上面的查询。唯一索引拦住一个，这里回查返回已有任务。
            // ★ 注意：MySQL 在唯一键冲突后事务仍然可用，所以可以继续查询；
            //   如果换成 PostgreSQL，事务此时已被标记中止，必须用 REQUIRES_NEW 独立事务去查，
            //   否则会报「current transaction is aborted」。这是数据库差异，很容易踩。
            ExportTask concurrent = exportTaskMapper.selectByRequestIdAndUser(req.getRequestId(), userId);
            if (concurrent == null) {
                throw e;
            }
            log.info("export task concurrent duplicate, reuse taskId={}", concurrent.getTaskId());
            return concurrent;
        }

        // 事务提交后再投递：避免 worker 查到「还不存在的任务」
        String taskId = task.getTaskId();
        afterCommitExecutor.run(() -> sendTaskMessage(taskId));
        log.info("export task submitted, taskId={}, bizType={}", taskId, req.getBizType());
        return task;
    }

    /**
     * 查询任务（必须校验归属）。
     *
     * <p><b>★ 注意点：taskId 是可枚举的（E + 日期 + 数字），如果不校验 userId，
     * 任何人都能通过猜 taskId 下载别人的导出文件 —— 那是数据泄漏。</b>
     * 越权校验必须放在服务端；前端「不展示别人的按钮」不算安全措施。
     */
    public ExportTask query(String taskId, Long userId) {
        ExportTask task = exportTaskMapper.selectByTaskId(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.EXPORT_TASK_NOT_FOUND);
        }
        if (userId != null && !userId.equals(task.getUserId())) {
            // 返回「不存在」而不是「无权限」：不要泄露「这个 taskId 是存在的」这一信息
            log.warn("export task access denied, taskId={}, owner={}, requester={}",
                    taskId, task.getUserId(), userId);
            throw new BizException(ErrorCode.EXPORT_TASK_NOT_FOUND);
        }
        return task;
    }

    /**
     * 执行任务（worker 调用）。<b>幂等靠 markRunning 的 CAS。</b>
     *
     * <p><b>为什么失败后不重投消息（和订单超时场景的处理不同）：</b>
     * 订单超时关单必须最终成功（库存一直占着是业务损失），所以要重投 + 兜底扫表；
     * 导出任务是「用户可重试」的操作，失败后标记 FAILED、给出可执行的原因、
     * 让用户改条件重新提交，用户体验更好，也避免失败任务在队列里无限重投。
     * ——同一个「MQ 消费」，两个场景的失败策略可以完全相反，取决于「是否必须最终成功」。
     */
    public void execute(String taskId) {
        // ★ CAS 抢任务：PENDING -> RUNNING。返回 0 = 已被其他 worker 抢走 / 已终态
        int rows = exportTaskMapper.markRunning(taskId);
        if (rows == 0) {
            log.info("export task not pending, skip execution. taskId={}", taskId);
            return;
        }

        ExportTask task = exportTaskMapper.selectByTaskId(taskId);
        if (task == null) {
            log.error("export task disappeared after running, taskId={}", taskId);
            return;
        }
        long start = System.currentTimeMillis();
        try {
            ExportExecutor.ExportResult result = exportExecutor.doExport(task,
                    percent -> exportTaskMapper.updateProgress(taskId, percent));

            int done = exportTaskMapper.markSuccess(taskId, result.getResultUrl(),
                    result.getTotalRows(), result.getExpireAt());
            if (done == 0) {
                // 状态已经不是 RUNNING（例如被人工置失败 / 超时置失败）：
                // 结果文件成了「孤儿文件」，必须删掉，否则磁盘慢慢被吃满
                exportExecutor.deleteFile(result.getResultUrl());
                log.error("mark success failed, status changed. taskId={}", taskId);
                return;
            }
            log.info("export task success, taskId={}, rows={}, cost={}ms",
                    taskId, result.getTotalRows(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            String userMsg = toUserMessage(e);
            exportTaskMapper.markFailed(taskId, userMsg);
            // 打完整堆栈给运维看，但只把「可执行的一句话」写进 DB 给用户看
            log.error("export task failed, taskId={}, userMsg={}", taskId, userMsg, e);
            // 不 rethrow：任务已是终态，重投也进不来（markRunning 会挡住），
            // 靠告警 + 用户重新提交解决。若你确实要自动重试，请新建一个任务，而不是复活旧任务。
        }
    }

    /** 清理过期结果文件（定时任务调用），返回清理数量 */
    public int cleanExpiredFiles(int limit) {
        List<ExportTask> expired = exportTaskMapper.selectExpiredFiles(new Date(), limit);
        int count = 0;
        for (ExportTask task : expired) {
            // 先删文件，再清 DB 引用：反过来的话文件删失败就再也找不到它了（磁盘泄漏）
            exportExecutor.deleteFile(task.getResultUrl());
            exportTaskMapper.clearResultUrl(task.getTaskId());
            count++;
        }
        return count;
    }

    /** 卡住任务扫描（定时任务调用）：worker 挂了 / 消息丢了 -> 告警，人工介入 */
    public List<ExportTask> findStuckTasks(int minutes, int limit) {
        Date deadline = new Date(System.currentTimeMillis() - minutes * 60_000L);
        return exportTaskMapper.selectStuckTasks(deadline, limit);
    }

    /* ==================== 内部方法 ==================== */

    private void validate(ExportTaskReq req) {
        if (!BIZ_TYPE_WHITELIST.contains(req.getBizType())) {
            // 白名单不通过 = 调用方传错了，属于业务失败（不是系统异常）
            throw new BizException(ErrorCode.PARAM_INVALID, "不支持的 bizType: " + req.getBizType());
        }
        String json = req.getQueryJson();
        if (json == null || json.trim().isEmpty() || !json.trim().startsWith("{")) {
            throw new BizException(ErrorCode.EXPORT_QUERY_INVALID);
        }
    }

    private void sendTaskMessage(String taskId) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.EXPORT_TASK_EXCHANGE,
                    RabbitConfig.EXPORT_TASK_ROUTING_KEY, taskId);
        } catch (Exception e) {
            // 消息发不出去：任务会一直停在 PENDING，靠 findStuckTasks 告警发现并人工重投
            log.error("send export task message fail, taskId={}", taskId, e);
        }
    }

    /**
     * 任务号生成：E + 日期 + 5 位随机。
     *
     * <p>注意点：<b>不要用「当天自增序号」</b>（需要额外的计数服务/Redis INCR，且并发下要保证唯一），
     * 也不要暴露任务总量。冲突概率很低，且 {@code uk_task_id} 会兜底（撞了用户会看到提交失败）。
     */
    private String generateTaskId() {
        String date = new SimpleDateFormat("yyyyMMdd").format(new Date());
        return "E" + date + String.format("%05d", (int) (Math.random() * 100000));
    }

    /**
     * 把异常翻译成「给用户看的、可执行的」一句话。
     *
     * <p>注意点：<b>绝不能把堆栈/表名/SQL 写进 DB 再返回给前端</b>；
     * 并且要<b>截断长度</b>，否则字段超长会抛出新的异常，把真实原因掩盖掉（这类二次异常很难查）。
     */
    private String toUserMessage(Exception e) {
        String msg;
        if (e instanceof BizException) {
            msg = ((BizException) e).getMessage();
        } else if (e instanceof IllegalArgumentException) {
            msg = e.getMessage();
        } else {
            msg = "导出执行失败，请稍后重试或缩小查询范围";
        }
        if (msg == null) {
            msg = "导出执行失败";
        }
        return msg.length() > ERROR_MSG_MAX_LEN ? msg.substring(0, ERROR_MSG_MAX_LEN) : msg;
    }
}
