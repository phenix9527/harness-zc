package com.zc.api.demo.common.api;

import lombok.Getter;

/**
 * 业务异常：代表「确定性的业务失败」，全局异常处理器会把它转成 HTTP 200 + code != 0。
 *
 * <p><b>为什么要单独一个异常类？</b>
 * <ol>
 *   <li>把「业务失败」和「系统异常」在代码层面就分开，而不是靠 <code>return null</code> 或 magic string
 *       去猜。catch 到 BizException 说明流程是通的，只是规则不允许。</li>
 *   <li>在支付回调、MQ 消费这类场景里，两者的处理策略完全相反：
 *       业务失败要「当作成功处理，别让对方重试」，系统异常要「返回失败，让对方重试」。见
 *       {@code WxPayNotifyController} 与 {@code OrderTimeoutListener}。</li>
 * </ol>
 *
 * <p><b>注意点：</b>
 * <ul>
 *   <li>不要用它包装系统异常（DB 连不上、NPE 之类），那会误导调用方「不用重试」。</li>
 *   <li>它是 RuntimeException，注意别在事务里被吞掉导致坏数据提交（本工程示例里没有事务边界混合问题，
 *       但真实项目里 <code>@Transactional</code> + catch(BizException) 是经典翻车点）。</li>
 *   <li>不要放堆栈敏感信息到 message 里，message 会直接进 Result 返回给前端。</li>
 * </ul>
 * @author admin
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /** 可选的补充数据，例如频控场景的 retryAfter 秒数 */
    private final transient Object data;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.data = null;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.data = null;
    }

    public BizException(ErrorCode errorCode, String message, Object data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }

    /**
     * 业务异常不需要堆栈：它表示「预期内的失败」，每次频控命中都抓一次调用栈是纯浪费。
     * 覆盖此方法可以省掉 getStackTrace() 的开销，高频接口值得加。
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }

}
