package com.zc.api.demo.common.api;

import lombok.Getter;

/**
 * 系统异常：代表「非预期的、重试可能成功的失败」。
 *
 * <p><b>和 {@link BizException} 的区别就一句话：</b>
 * <pre>
 * BizException    -> 业务失败：规则不允许，重试一万次也一样，调用方不该重试
 * SystemException -> 系统异常：环境/依赖出问题，调用方可以重试
 * </pre>
 *
 * <p><b>为什么要有这个类，而不是直接用 RuntimeException？</b>
 * 因为「重不重试」这个判断会出现在很多地方：
 * <ul>
 *   <li>HTTP 层：{@code GlobalExceptionHandler} 把它转成 5xx；</li>
 *   <li>MQ 消费层：catch BizException → ack 掉不再投；catch SystemException → 重投/进死信；</li>
 *   <li>支付回调层：业务失败回 SUCCESS 让网关别再推；系统异常回 FAIL 让网关重推。</li>
 * </ul>
 * 没有这个类型，就得在每个地方靠「异常类名判断」或「message 里找关键词」，早晚出错。
 *
 * <p>注意点：不要为了「顺便返回一个错误码」把系统问题包装成 {@link BizException}，
 * 那会让调用方放弃重试 —— 一个 DB 抖动就会被判成永久失败。
 * @author admin
 */
@Getter
public class SystemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public SystemException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public SystemException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SystemException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

}
