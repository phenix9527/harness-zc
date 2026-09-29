package com.zc.api.demo.common.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把「异常」翻译成「契约」的地方。
 *
 * <p><b>三分类处理，这是本类存在的全部意义：</b>
 * <pre>
 * BizException                  -> HTTP 200 + 业务码（1xxxx/2xxxx）  ：别重试
 * 参数校验/解析异常               -> HTTP 200 + 10001/10002           ：别重试
 * 其它 Throwable                -> HTTP 500 + 30001               ：可以重试
 * </pre>
 *
 * <p><b>★ 注意点：框架自己抛的「客户端错误」必须逐个显式接管。</b>
 * Spring MVC 的 {@code MissingRequestHeaderException} / {@code MethodArgumentTypeMismatchException}
 * / {@code HttpMessageNotReadableException} 等默认都继承自普通异常，不写 handler 就会掉进下面
 * 的 {@code Throwable} 兜底分支，被翻译成 <b>500 + 30001「系统繁忙」</b> ——
 * 语义上等于告诉调用方「是服务端故障，可以重试」，但真相是<b>参数传错了，重试一万次也一样</b>。
 * 这类误判会直接把对方的重试策略带偏（疯狂重试 + 告警风暴），所以有一个写一个。
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li>兜底分支必须 <code>log.error</code> 带上 traceId 和完整堆栈，但<b>绝不能把堆栈/SQL 返回给前端</b>
 *       —— 那是渗透测试的送分题。</li>
 *   <li>不要在这里吞掉 {@code Error}/{@code OutOfMemoryError}；也不要把
 *       {@code AccessDeniedException} 这类安全异常卷进 500，会被安全扫描挑出来。</li>
 *   <li>只有 {@link BizException} 系列走 200。若某个 BizException 实际是系统问题（比如下游超时包装成业务码），
 *       会让调用方放弃重试 —— 分包时别偷懒。</li>
 *   <li>参数类异常统一 HTTP 200 + 1xxxx（不返回 400/405），因为全局约定是
 *       「调用方只看 code 决定要不要重试」。混进 Spring 默认的 4xx 会破坏这个唯一判据。</li>
 * </ol>
 * @author admin
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务失败：预期内，返回 200，让调用方一眼看出「别重试」 */
    @ExceptionHandler(BizException.class)
    public Result<Object> handleBizException(BizException e) {
        // 业务异常用 warn 而不是 error：它不是故障，打 error 会把告警系统淹掉
        log.warn("biz fail, code={}, msg={}", e.getErrorCode().getCode(), e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage(), e.getData());
    }

    /** @Valid 校验失败（JSON body） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Object> handleValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("param invalid, msg={}", msg);
        return Result.fail(ErrorCode.PARAM_INVALID, msg);
    }

    /** 表单绑定失败 */
    @ExceptionHandler(BindException.class)
    public Result<Object> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(ErrorCode.PARAM_INVALID, msg);
    }

    /** 缺少必填查询参数（GET 接口常见） */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Object> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail(ErrorCode.PARAM_MISSING, "缺少参数: " + e.getParameterName());
    }

    /**
     * 缺少必填请求头（例如 {@code X-User-Id}）。
     *
     * <p>注意点：不接管就会掉进 500 + 30001「系统繁忙」，把「调用方漏传 header」误报成服务端故障。
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public Result<Object> handleMissingHeader(MissingRequestHeaderException e) {
        log.warn("missing request header, header={}", e.getHeaderName());
        return Result.fail(ErrorCode.PARAM_MISSING, "缺少请求头: " + e.getHeaderName());
    }

    /**
     * 参数类型不匹配（路径变量/查询参数转不成 Long、枚举值非法等）。
     *
     * <p>注意点：这里只回显<b>参数名</b>，不回显具体值和候选枚举 —— 否则等于把内部数据结构透出去。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Object> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("param type mismatch, name={}, value={}", e.getName(), e.getValue());
        return Result.fail(ErrorCode.PARAM_INVALID, "参数类型不合法: " + e.getName());
    }

    /**
     * 请求体无法解析（JSON 语法错误、字段类型对不上）。
     *
     * <p>注意点：<b>不能把 e.getMessage() 原样返回</b>，Jackson 的报错里带着
     * 内部类全限定名和字段路径，等于免费给攻击者画出你的 DTO 结构。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Object> handleNotReadable(HttpMessageNotReadableException e) {
        // 只记日志，不落库、不回显细节
        log.warn("request body not readable: {}", e.getMessage());
        return Result.fail(ErrorCode.PARAM_INVALID, "请求体格式不合法");
    }

    /** 请求方法不支持（拿 GET 调了只收 POST 的接口） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Object> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("method not supported, method={}", e.getMethod());
        return Result.fail(ErrorCode.PARAM_INVALID, "请求方法不支持: " + e.getMethod());
    }

    /** 兜底：真正的系统异常 -> HTTP 5xx，告诉调用方「可以重试」 */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Result<Object>> handleThrowable(Throwable e) {
        log.error("system error, traceId={}", com.zc.api.demo.common.trace.TraceContext.getTraceId(), e);
        Result<Object> body = Result.fail(ErrorCode.SYSTEM_ERROR);
        // 显式用 500：这是调用方判断「能不能重试」的唯一依据
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
