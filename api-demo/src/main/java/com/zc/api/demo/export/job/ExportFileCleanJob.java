package com.zc.api.demo.export.job;

import com.zc.api.demo.common.lock.RedissonLock;
import com.zc.api.demo.export.entity.ExportTask;
import com.zc.api.demo.export.service.ExportTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 导出任务的「收尾」定时任务，两个职责：
 * <ol>
 *   <li><b>清理过期结果文件</b>：结果文件必须有有效期 + 定时清理，
 *       否则半年后磁盘爆掉，而且没人承认是自己写的功能。</li>
 *   <li><b>发现卡住的任务</b>：worker 挂了 / 消费 ack 后进程崩溃 / 消息丢了，
 *       任务会永远停在 PENDING 或 RUNNING。必须能被发现并告警，
 *       否则用户会一直看到「导出中」，然后来问「到底好了没」。</li>
 * </ol>
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li><b>先删文件、再清 DB 引用。</b>反过来的话，如果文件删除失败，
 *       你就永远找不到这个文件了（磁盘只能靠人工翻）—— 「先做不可逆的事，再做可逆的事」。</li>
 *   <li>清理任务同样要限流分批 + 加锁（多副本）。</li>
 *   <li>这一层是<b>兜底</b>，不是主流程：即使它挂了，也应该是「磁盘慢慢变满」而不是「数据出错」。</li>
 * </ol>
 */
@Component
public class ExportFileCleanJob {

    private static final Logger log = LoggerFactory.getLogger(ExportFileCleanJob.class);

    private static final String LOCK_KEY = "lock:job:export-file-clean";
    private static final int CLEAN_BATCH = 500;
    /** 任务超过多少分钟没更新就算「卡住」，需要告警 */
    private static final int STUCK_MINUTES = 30;

    private final ExportTaskService exportTaskService;
    private final RedissonLock redissonLock;

    public ExportFileCleanJob(ExportTaskService exportTaskService, RedissonLock redissonLock) {
        this.exportTaskService = exportTaskService;
        this.redissonLock = redissonLock;
    }

    /** 每小时清理一次过期文件（文件有效期默认 7 天，不需要跑太勤） */
    @Scheduled(initialDelay = 300_000, fixedDelay = 3600_000)
    public void cleanExpiredFiles() {
        // 注意点：清理任务耗时跟「过期文件数」正相关，可能远超前几次的估算值。
        // 用固定 TTL 的锁（旧实现给 10 分钟）总有跑超的一天，超了就会有两个实例同时删文件；
        // 现在交给 Redisson 看门狗续期，不用再赌「10 分钟够不够」。
        redissonLock.tryLockAndRun(LOCK_KEY, Duration.ZERO, () -> {
            int cleaned = exportTaskService.cleanExpiredFiles(CLEAN_BATCH);
            if (cleaned > 0) {
                log.info("export expired files cleaned, count={}", cleaned);
            }
        });
    }

    /**
     * 每 10 分钟扫一次卡住的任务。
     *
     * <p>这里只告警不自动处理：自动「把 RUNNING 改回 PENDING 再重投」听起来很美好，
     * 但如果任务其实还在跑（只是慢），就会造成两个 worker 同时跑同一个任务 ——
     * 而 markRunning 的 CAS 挡不住这种「状态被人工/任务改回去」的场景。
     * 所以卡住任务交给人工判断，或者引入「任务级心跳」机制后再自动化。
     */
    @Scheduled(initialDelay = 120_000, fixedDelay = 600_000)
    public void reportStuckTasks() {
        List<ExportTask> stuck = exportTaskService.findStuckTasks(STUCK_MINUTES, 50);
        if (stuck.isEmpty()) {
            return;
        }
        for (ExportTask task : stuck) {
            // 生产环境这里应该打点/告警（Prometheus + Alertmanager），而不是只写日志
            log.error("EXPORT TASK STUCK! taskId={}, status={}, progress={}, updateTime={}, bizType={}",
                    task.getTaskId(), task.getStatus(), task.getProgress(), task.getUpdateTime(), task.getBizType());
        }
        log.error("export stuck task count={}, need manual handling", stuck.size());
    }
}
