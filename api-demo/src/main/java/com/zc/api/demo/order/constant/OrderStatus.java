package com.zc.api.demo.order.constant;

/**
 * 订单状态常量与状态机定义。
 *
 * <p><b>状态机（客户端的「五脏」之一，但经常被漏掉）：</b>
 * <pre>
 *          下单
 *           │
 *           ▼
 *      ┌─────────┐   支付回调 CAS    ┌────────┐   发货   ┌───────────┐
 *      │ 1待支付 │ ───────────────▶ │ 2已支付 │ ───────▶ │ 3已发货   │
 *      └─────────┘                  └────────┘          └───────────┘
 *           │                                                  │
 *           │ 超时/用户取消 CAS                                   ▼
 *           ▼                                            ┌───────────┐
 *      ┌─────────┐                                       │ 5已完成   │
 *      │ 4已关闭 │  ← 终态，不可再流转                       └───────────┘
 *      └─────────┘
 * </pre>
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li>状态用<b>数字码</b>入库、枚举名对外；不要用中文入库（改文案要刷数据）。</li>
 *   <li>终态（4已关闭 / 5已完成）不可逆。所有流转都必须带
 *       <code>WHERE status = 期望的源状态</code>，见 {@code OrderMapper}。</li>
 *   <li>不要把「支付中」「退款中」这类<b>过程态</b>塞进 status，那会让状态机爆炸；
 *       过程态应该放在独立的字段/子表里（如 pay_status、refund 表）。</li>
 * </ol>
 * @author admin
 *
 * TODO zhoucong order/constant/OrderStatus.java:22-29  为什么过程态不能塞进 status（会让状态机爆炸）
 */
public final class OrderStatus {

    /** 待支付 */
    public static final int WAIT_PAY = 1;
    /** 已支付 */
    public static final int PAID = 2;
    /** 已发货 */
    public static final int DELIVERED = 3;
    /** 已关闭（超时未支付或主动取消）—— 终态 */
    public static final int CLOSED = 4;
    /** 已完成 —— 终态 */
    public static final int FINISHED = 5;

    private OrderStatus() {
    }

    public static String name(int status) {
        switch (status) {
            case WAIT_PAY:
                return "WAIT_PAY";
            case PAID:
                return "PAID";
            case DELIVERED:
                return "DELIVERED";
            case CLOSED:
                return "CLOSED";
            case FINISHED:
                return "FINISHED";
            default:
                return "UNKNOWN(" + status + ")";
        }
    }
}
