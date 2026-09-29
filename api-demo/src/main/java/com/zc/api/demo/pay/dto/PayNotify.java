package com.zc.api.demo.pay.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 支付回调通知报文（以微信支付 v3 的字段语义为例）。
 *
 * <p><b>契约注意点：</b>
 * <ol>
 *   <li><b>金额字段一律是「分」，用 Integer/Long。</b> 回调报文里绝对不会有小数，
 *       如果你收到小数说明对面不是官方网关。自己内部用 BigDecimal(元) 时要显式换算，
 *       别在代码里出现 {@code notify.getAmount() / 100.0} 这种浮点运算。</li>
 *   <li><b>不要把回调字段直接映射到订单实体</b>。回调是「别人给的数据」，
 *       订单是我们的数据。混在一起会让「以谁为准」变得含糊 —— 而这条边界恰恰是资金安全的核心。</li>
 *   <li>字段只声明你<b>真正会用</b>的。声明一大堆用不到的字段，
 *       上游改字段时你会被迫跟着改，而且容易误信某个字段（例如回调里的 amount）。</li>
 * </ol>
 *
 * <p>Lombok：全字段 {@code @Getter/@Setter} —— 回调报文是<b>反序列化目标</b>，
 * Jackson 必须有 setter（或构造器绑定），这类 DTO 就是「特意要可变」的场景。
 * {@code @ToString} 只留对账与排查必需的字段：回调报文随时可能被上游加字段，
 * 整个对象打日志的话，某天上游一加字段你的日志量就翻倍。
 * @author admin
 */
@Getter
@Setter
@ToString(of = {"notifyId", "outTradeNo", "transactionId", "tradeState", "amountTotal"})
public class PayNotify {

    /** 通知的唯一 id（网关侧），可用于去重 */
    private String notifyId;

    /** 事件类型，例如 TRANSACTION.SUCCESS */
    private String eventType;

    /** 商户订单号（我们下单时传给网关的 out_trade_no） */
    private String outTradeNo;

    /** 网关侧交易号，用于对账，不能用于定位我们自己的订单 */
    private String transactionId;

    /** 交易状态：SUCCESS / REFUND / CLOSED */
    private String tradeState;

    /** 支付完成时间（网关侧时间字符串） */
    private String successTime;

    /** 用户实付金额，单位：分 */
    private Integer payerTotal;

    /** 订单总金额，单位：分 */
    private Integer amountTotal;

    /** 支付渠道标识，用于落库区分（本示例直接用网关回调推断） */
    private Integer payChannel;
}
