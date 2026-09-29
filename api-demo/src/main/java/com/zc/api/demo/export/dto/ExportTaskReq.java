package com.zc.api.demo.export.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 提交导出任务请求。
 *
 * <p><b>注意点（导出接口是「查库外挂」，很容易变成数据泄漏与 DB 杀手）：</b>
 * <ol>
 *   <li>{@code requestId} 是幂等键：客户端生成（建议 UUID）。<b>必须要求客户端传</b> ——
 *       服务端生成就没有幂等能力了（每次调用都是新 requestId）。
 *       重复提交（用户连点两次 / 网络重试）会返回同一个 taskId，而不是生成两个任务。</li>
 *   <li>{@code queryJson} 是<b>任意结构的用户输入</b>，属于高风险字段：
 *       必须做长度限制（这里 {@code @Size}）、JSON 合法性校验、
 *       并且在业务层解析成强类型对象后再拼 SQL ——<b>绝对不能把用户 JSON 直接拼进 SQL</b>。
 *       更进一步：导出条件建议用<b>强类型 DTO</b>，而不是透传 JSON，
 *       否则字段一多，权限过滤（比如只能导出自己的数据）就会漏。</li>
 *   <li>{@code bizType} 必须做白名单校验（见 Service），否则调用方可以指定任意类型
 *       绕过某些类型的行数/权限限制。</li>
 *   <li>提交接口<b>不要带查询条件之外的数据</b>（比如直接传 SQL），那是典型的设计事故。</li>
 * </ol>
 *
 * <p>Lombok：{@code @ToString(exclude = "queryJson")} —— 查询条件可能有 4KB，
 * 一行日志能顶掉半个屏幕，排查时反而找不到重点。
 */
@Getter
@Setter
@ToString(exclude = "queryJson")
public class ExportTaskReq {

    @NotBlank(message = "requestId 不能为空（用于幂等）")
    @Size(max = 64, message = "requestId 过长")
    private String requestId;

    @NotBlank(message = "bizType 不能为空")
    @Size(max = 32, message = "bizType 过长")
    private String bizType;

    @NotBlank(message = "queryJson 不能为空")
    @Size(max = 4096, message = "查询条件过大，请缩小范围")
    private String queryJson;
}
