package com.zc.api.demo.common.api;

import com.zc.api.demo.common.trace.TraceContext;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 统一返回体。
 *
 * <p><b>核心约定（整份文档最重要的一条）：</b>
 * <pre>
 * 业务失败  -> HTTP 200 + code != 0
 * 系统异常  -> HTTP 5xx + code = 3xxxx
 * </pre>
 * 调用方看到 <code>200 + code != 0</code> 就知道「这事办不成，别重试」；
 * 看到 5xx 就知道「对面出问题了，可以重试」。两者混在一起，调用方只能瞎猜，
 * 结果要么把重试变成重放（超发/重复扣款），要么该重试的不重试。
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li>{@code traceId} 一定要回传，否则线上出问题对着日志捞不到那一次请求。</li>
 *   <li>不要用 HTTP 状态码表达业务语义（比如「已领取」返回 409）。
 *       网关/Feign/前端拦截器对非 200 的处理五花八门，业务码放 body 里最稳。</li>
 * </ol>
 *
 * <p>Lombok：{@code @Getter/@Setter} 保留（Jackson 需要 setter 才能反序列化，
 * 客户端/单测里构造响应体也要用）；{@code @ToString} 刻意<b>排除 data</b> ——
 * 业务数据可能很大（导出行、列表），也可能含敏感字段，日志里只留 code/message/traceId 就够定位。
 *
 * @param <T> 业务数据类型
 */
@Getter
@Setter
@ToString(of = {"code", "message", "traceId"})
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int SUCCESS_CODE = 0;

    /** 0=成功；非 0=失败，语义见 {@link ErrorCode} */
    private int code;

    /** 给用户看的文案 */
    private String message;

    /** 链路追踪 ID，方便日志串联 */
    private String traceId;

    /** 业务数据；失败时可为 null，也可携带补充信息（如 retryAfter） */
    private T data;

    public Result() {
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = TraceContext.getTraceId();
    }

    public static <T> Result<T> ok() {
        return new Result<>(SUCCESS_CODE, "ok", null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS_CODE, "ok", data);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 需要覆盖默认文案时使用（例如频控场景要带上「请 x 秒后重试」） */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    /** 失败但需要把结构化信息给到前端（如 data.retryAfter 做倒计时） */
    public static <T> Result<T> fail(ErrorCode errorCode, String message, T data) {
        return new Result<>(errorCode.getCode(), message, data);
    }

    public boolean isSuccess() {
        return this.code == SUCCESS_CODE;
    }
}
