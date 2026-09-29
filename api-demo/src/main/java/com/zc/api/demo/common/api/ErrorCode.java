package com.zc.api.demo.common.api;

/**
 * 统一错误码。
 *
 * <p><b>分段约定（全公司统一，别自己另起一套）：</b>
 * <pre>
 * 0     成功
 * 1xxxx 参数类   —— 调用方传错了，改参数再来，重试没用
 * 2xxxx 业务类   —— 业务规则不允许（已领取/已抢光/状态不对），重试没用
 * 3xxxx 系统类   —— 我方异常（DB 抖动、下游超时），可以重试
 * </pre>
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li>错误码一旦上线就不能改语义、不能复用，只能新增（AIP-180 向后兼容）。</li>
 *   <li>1xxxx/2xxxx 属于「确定性失败」，返回 HTTP 200；3xxxx 才返回 5xx。
 *       调用方据此判断「能不能重试」，这是唯一判断依据。</li>
 *   <li>message 是给用户/前端看的文案，不要塞堆栈、SQL、内部 IP。</li>
 * </ol>
 */
public enum ErrorCode {

    /* ---------------- 1xxxx 参数类 ---------------- */
    PARAM_INVALID(10001, "参数不合法"),
    PARAM_MISSING(10002, "缺少必填参数"),

    /* ---------------- 2xxxx 业务类 ---------------- */
    ACTIVITY_NOT_FOUND(20000, "活动不存在"),
    COUPON_ALREADY_CLAIMED(20001, "已领取过"),
    COUPON_SOLD_OUT(20002, "已抢光"),
    COUPON_NOT_RUNNING(20003, "活动未开始或已结束"),
    SEND_TOO_FREQUENT(20004, "发送过于频繁"),
    SMS_CODE_INVALID(20005, "验证码错误或已过期"),
    ORDER_NOT_FOUND(20006, "订单不存在"),
    ORDER_STATE_ILLEGAL(20007, "订单状态不允许该操作"),
    EXPORT_TASK_NOT_FOUND(20008, "导出任务不存在"),
    EXPORT_QUERY_INVALID(20009, "导出查询条件不合法"),
    REQUEST_TOO_FREQUENT(20010, "请求过于频繁"),
    /** 回调金额与订单金额不一致：属于「不能自动处理」的业务异常，必须告警 + 人工介入 */
    ORDER_AMOUNT_MISMATCH(20012, "订单金额校验不一致"),

    /* ---------------- 3xxxx 系统类 ---------------- */
    SYSTEM_ERROR(30001, "系统繁忙，请稍后重试"),
    DEPENDENCY_ERROR(30002, "依赖服务异常，请稍后重试"),
    /**
     * 并发冲突：结果「既没成功也还没失败」，调用方稍后重试即可。
     * <p>刻意放在 3xxxx 而不是 2xxxx —— 因为 2xxxx 的语义是「确定性失败，别重试」，
     * 混进去会破坏「看错误码就知道要不要重试」这个全局约定。
     */
    CONCURRENT_CONFLICT(30003, "操作正在处理中，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
