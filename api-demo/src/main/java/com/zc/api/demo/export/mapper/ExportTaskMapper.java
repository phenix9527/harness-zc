package com.zc.api.demo.export.mapper;

import com.zc.api.demo.export.entity.ExportTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

/**
 * 导出任务 Mapper。
 *
 * <p><b>★ 这里的每一个 {@code markXxx} 都是 CAS：</b>
 * {@code UPDATE ... SET status = '目标态' WHERE task_id = ? AND status = '源态'}。
 * 它同时解决了两件事：
 * <ol>
 *   <li><b>多 worker 并发抢任务</b>：{@code markRunning} 返回 0 说明别人已经抢走了，直接退出。
 *       不需要分布式锁 —— 一行 SQL 比一把锁可靠得多（锁会丢、会过期、Redis 会挂）。</li>
 *   <li><b>状态机守卫</b>：终态（SUCCESS/FAILED）的任务不会被任何流转改写。</li>
 * </ol>
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li>除非有「合法的回退」，否则<b>不要</b>写无条件的 {@code update ... set status = #{status}}。
 *       状态机的价值全部体现在 WHERE 上。</li>
 *   <li>这里的 CAS 都走 {@code uk_task_id} 唯一索引，不会锁全表。</li>
 *   <li>{@code markFailed} 允许从 PENDING 或 RUNNING 进来（提交后立刻失败、执行中失败都要能标），
 *       但仍要带 {@code status in (...)}，不能无条件写。</li>
 * </ol>
 */
public interface ExportTaskMapper {

    @Select("SELECT id, task_id, request_id, user_id, biz_type, query_json, status, progress, "
            + "total_rows, result_url, error_msg, expire_at, create_time, update_time "
            + "FROM t_export_task WHERE task_id = #{taskId}")
    ExportTask selectByTaskId(@Param("taskId") String taskId);

    /** 幂等查询：同一用户同一 requestId 只应有一个任务（走 uk_request_user） */
    @Select("SELECT id, task_id, request_id, user_id, biz_type, query_json, status, progress, "
            + "total_rows, result_url, error_msg, expire_at, create_time, update_time "
            + "FROM t_export_task WHERE request_id = #{requestId} AND user_id = #{userId}")
    ExportTask selectByRequestIdAndUser(@Param("requestId") String requestId,
                                       @Param("userId") Long userId);

    @Insert("INSERT INTO t_export_task (task_id, request_id, user_id, biz_type, query_json, status, progress) "
            + "VALUES (#{taskId}, #{requestId}, #{userId}, #{bizType}, #{queryJson}, #{status}, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ExportTask task);

    /** 抢占任务：PENDING -> RUNNING。返回 0 = 已被其他 worker 抢走（幂等出口） */
    @Update("UPDATE t_export_task SET status = 'RUNNING', progress = 0 "
            + "WHERE task_id = #{taskId} AND status = 'PENDING'")
    int markRunning(@Param("taskId") String taskId);

    /**
     * 更新进度。
     *
     * <p><b>注意点：</b>不要每处理一行就更新一次 DB —— 10 万行就是 10 万次写入，
     * DB 先被打死。按百分比 + 最小间隔（如每 1%/2 秒一次）节流。
     * 另外必须带 {@code status = 'RUNNING'}：任务被置失败后不能再写进度。
     */
    @Update("UPDATE t_export_task SET progress = #{progress} "
            + "WHERE task_id = #{taskId} AND status = 'RUNNING'")
    int updateProgress(@Param("taskId") String taskId, @Param("progress") int progress);

    /** 成功：写入结果地址、总行数、过期时间。返回 0 = 状态已被改（异常路径），调用方应清理已生成的文件 */
    @Update("UPDATE t_export_task SET status = 'SUCCESS', progress = 100, result_url = #{resultUrl}, "
            + "total_rows = #{totalRows}, expire_at = #{expireAt}, error_msg = NULL "
            + "WHERE task_id = #{taskId} AND status = 'RUNNING'")
    int markSuccess(@Param("taskId") String taskId,
                    @Param("resultUrl") String resultUrl,
                    @Param("totalRows") int totalRows,
                    @Param("expireAt") Date expireAt);

    /** 失败：只写「给用户看的」原因，绝不写堆栈/SQL */
    @Update("UPDATE t_export_task SET status = 'FAILED', error_msg = #{errorMsg} "
            + "WHERE task_id = #{taskId} AND status IN ('PENDING', 'RUNNING')")
    int markFailed(@Param("taskId") String taskId, @Param("errorMsg") String errorMsg);

    /** 扫描已过期且还有结果文件的任务（走 idx_expire_at） */
    @Select("SELECT task_id, result_url FROM t_export_task "
            + "WHERE status = 'SUCCESS' AND result_url IS NOT NULL AND expire_at IS NOT NULL "
            + "AND expire_at < #{deadline} ORDER BY id LIMIT #{limit}")
    List<ExportTask> selectExpiredFiles(@Param("deadline") Date deadline, @Param("limit") int limit);

    /**
     * 清理结果文件引用。
     *
     * <p>注意：这里<b>只把 result_url 置空，不改 status</b>（不引入 EXPIRED 新状态）。
     * 于是约定：{@code status=SUCCESS 且 resultUrl 为空} = 文件已过期，前端提示重新导出。
     * 这样状态机保持单向简单，不会因为「过期」这种横切属性多出一个状态。
     */
    @Update("UPDATE t_export_task SET result_url = NULL, error_msg = '结果文件已过期，请重新导出' "
            + "WHERE task_id = #{taskId} AND status = 'SUCCESS'")
    int clearResultUrl(@Param("taskId") String taskId);

    /** 卡住任务扫描：长时间停在 PENDING/RUNNING 的任务（worker 挂了 / 消息丢了），用于告警 */
    @Select("SELECT id, task_id, request_id, user_id, biz_type, query_json, status, progress, "
            + "total_rows, result_url, error_msg, expire_at, create_time, update_time "
            + "FROM t_export_task WHERE status IN ('PENDING', 'RUNNING') AND update_time < #{deadline} "
            + "ORDER BY id LIMIT #{limit}")
    List<ExportTask> selectStuckTasks(@Param("deadline") Date deadline, @Param("limit") int limit);
}
