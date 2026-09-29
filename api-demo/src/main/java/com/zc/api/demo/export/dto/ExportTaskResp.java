package com.zc.api.demo.export.dto;

import com.zc.api.demo.export.constant.ExportTaskStatus;
import com.zc.api.demo.export.entity.ExportTask;
import lombok.Getter;
import lombok.ToString;

import java.util.Date;

/**
 * 导出任务响应（提交与查询共用）。
 *
 * <p><b>三个语义必须明确（长任务接口的全部）：</b>
 * <ol>
 *   <li><b>提交</b>返回 {@code taskId} + {@code status=PENDING}；
 *       客户端拿 taskId 去轮询，<b>不要</b>在提交接口里等结果。</li>
 *   <li><b>查询</b>返回 {@code status} + {@code progress}；
 *       前端据此画进度条（没有 progress 的等待 = 用户流失）。</li>
 *   <li><b>成功</b>时返回 {@code resultUrl} + {@code expireAt}；
 *       必须告诉用户文件什么时候过期，否则他半年来点一次链接会以为系统坏了。</li>
 * </ol>
 *
 * <p><b>注意点：</b>未成功时 {@code resultUrl}/{@code expireAt} 显式返回 null（而不是省略字段），
 * 前端可以统一按字段判空，不用写「字段可能不存在」的兼容代码。
 * 另外失败时要返回 {@code errorMsg}，但<b>不能</b>返回内部异常堆栈/SQL ——
 * 导出失败文案应该是「查询条件过于宽泛，请缩小范围」这种可执行建议。
 *
 * <p><b>Lombok：这里只加 {@code @Getter}，不加 {@code @Setter}</b> ——
 * 响应对象的字段在构造时确定，不给外部留「改一半」的口子；
 * 唯一入口是下面的静态工厂 {@code of(...)}，保证任何字段组合都是合法状态。
 * 这是 Lombok 用得好和用得滥的分界线：能少暴露一个 setter，就少一类「谁把它改了」的排查。
 */
@Getter
@ToString
public class ExportTaskResp {

    private String taskId;
    private String status;
    private Integer progress;
    private Integer totalRows;
    private String resultUrl;
    private Date expireAt;
    private String errorMsg;

    public static ExportTaskResp of(ExportTask task) {
        ExportTaskResp resp = new ExportTaskResp();
        resp.taskId = task.getTaskId();
        resp.status = task.getStatus();
        resp.progress = task.getProgress();
        resp.totalRows = task.getTotalRows();
        if (ExportTaskStatus.SUCCESS.name().equals(task.getStatus())) {
            resp.resultUrl = task.getResultUrl();
            resp.expireAt = task.getExpireAt();
        }
        if (ExportTaskStatus.FAILED.name().equals(task.getStatus())) {
            resp.errorMsg = task.getErrorMsg();
        }
        return resp;
    }
}
