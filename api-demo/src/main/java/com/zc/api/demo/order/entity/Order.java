package com.zc.api.demo.order.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 订单实体（场景2 支付回调 / 场景4 超时关闭 共用）。
 *
 * <p>Lombok 注意点：{@code @ToString} 里带了 {@code amount} 便于排查金额问题，
 * 但<b>不要因为方便就把 userId、outTradeNo 全塞进去</b> —— 日志是长期留存且广泛可见的，
 * 字段进日志前先问一句「这算不算个人信息」。
 */
@Getter
@Setter
@EqualsAndHashCode(of = "id")
@ToString(of = {"orderNo", "status", "amount"})
public class Order {

    private Long id;
    private String orderNo;
    private String outTradeNo;
    private Long userId;
    private Long goodsId;
    private Integer num;
    /** 金额用 BigDecimal，禁止 double/float（浮点误差在金额上是事故级问题） */
    private BigDecimal amount;
    private Integer payChannel;
    private Integer status;
    private Date payTime;
    private Date closeTime;
    private Date createTime;
    private Date updateTime;
}
