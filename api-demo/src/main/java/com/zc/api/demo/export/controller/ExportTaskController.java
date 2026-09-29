package com.zc.api.demo.export.controller;

import com.zc.api.demo.common.api.Result;
import com.zc.api.demo.export.dto.ExportTaskReq;
import com.zc.api.demo.export.dto.ExportTaskResp;
import com.zc.api.demo.export.entity.ExportTask;
import com.zc.api.demo.export.service.ExportTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 场景5：异步导出报表接口。
 *
 * <pre>
 * POST /inner/export/tasks
 *   Req : ExportTaskReq { String requestId; String bizType; String queryJson; }
 *   Resp: { taskId: "E20260929001", status: "PENDING", progress: 0 }
 *
 * GET  /inner/export/tasks/{taskId}
 *   Resp: { status: "RUNNING", progress: 65, resultUrl: null, expireAt: null }
 *         { status: "SUCCESS", progress: 100, resultUrl: "https://...", expireAt: "2026-10-06 10:00" }
 * </pre>
 *
 * <p><b>接口设计注意点（长任务的三个语义）：</b>
 * <ol>
 *   <li><b>提交要毫秒级返回</b>：只做「建任务 + 投递」，绝不在这里等导出结果。
 *       任何耗时超过 3 秒的操作，都不该用同步接口扛。</li>
 *   <li><b>查询要能回答三个问题</b>：现在什么状态（status）、进行到哪了（progress）、
 *       好了以后去哪拿（resultUrl + expireAt）。
 *       只有 status 没有 progress 的接口，用户体验就是「转圈转到放弃」。</li>
 *   <li><b>结果必须有过期时间</b>，并且要真的有定时清理。没有有效期的导出文件，
 *       半年后磁盘爆掉，而且没人承认是自己写的功能。</li>
 * </ol>
 *
 * <p><b>越权提醒：</b>{@code taskId} 是「猜得到的字符串」，查询接口必须校验归属。
 * 这里为了演示用 {@code userId} 请求头/参数传入；<b>真实项目必须从登录态（网关鉴权后的
 * 上下文）取 userId，绝不能由调用方自己传</b>——否则越权校验形同虚设。
 */
@RestController
@RequestMapping("/inner/export")
public class ExportTaskController {

    private static final Logger log = LoggerFactory.getLogger(ExportTaskController.class);

    private final ExportTaskService exportTaskService;

    public ExportTaskController(ExportTaskService exportTaskService) {
        this.exportTaskService = exportTaskService;
    }

    /**
     * 提交导出任务。
     *
     * <p>幂等语义：同一 userId + requestId 重复提交，返回<b>同一个 taskId</b>（不报错）。
     * 这是长任务接口的正确幂等语义 —— 报错会让客户端以为失败而换一个 requestId 重提，
     * 结果生成一堆重复任务，一起把 DB 打满。
     */
    @PostMapping("/tasks")
    public Result<ExportTaskResp> submit(@Valid @RequestBody ExportTaskReq req,
                                         @RequestHeader("X-User-Id") Long userId) {
        log.info("submit export task, userId={}, bizType={}, requestId={}",
                userId, req.getBizType(), req.getRequestId());
        ExportTask task = exportTaskService.createOrGet(req, userId);
        return Result.ok(ExportTaskResp.of(task));
    }

    /**
     * 查询导出任务。
     *
     * <p>轮询建议：客户端要退避（1s -> 2s -> 5s -> 10s），或者干脆用回调通知，
     * 别让人家 200ms 轮一次 —— 一个用户就能把你刷成 DDoS。
     * 服务端也可以顺手在响应头里给个 {@code Retry-After}，明确告诉客户端下次什么时候来。
     */
    @GetMapping("/tasks/{taskId}")
    public Result<ExportTaskResp> query(@PathVariable("taskId") String taskId,
                                        @RequestParam("userId") Long userId) {
        ExportTask task = exportTaskService.query(taskId, userId);
        return Result.ok(ExportTaskResp.of(task));
    }
}
