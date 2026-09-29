package com.zc.api.demo.coupon.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Date;

/**
 * 优惠券实体。
 *
 * <p>字段与 t_coupon 一一对应。注意 {@code (userId, activityId)} 上的唯一索引
 * 就是本场景「幂等」的正确性来源，实体里不需要额外加什么「幂等标记」字段。
 *
 * <p>Lombok：{@code @EqualsAndHashCode(of = "id")} 显式指定按主键比较 ——
 * 数据库实体的「相等」语义就是主键相等，用全字段比较会让「改了状态后对象查不到」这类
 * 诡异的集合行为出现。注意 id 为 null（未入库）时两个对象会被判成相等，这是取舍。
 */
@Getter
@Setter
@EqualsAndHashCode(of = "id")
@ToString(of = {"couponNo", "userId", "activityId", "status"})
public class Coupon {

    public static final int STATUS_UNUSED = 0;
    public static final int STATUS_USED = 1;
    public static final int STATUS_EXPIRED = 2;

    private Long id;
    private String couponNo;
    private Long userId;
    private Long activityId;
    private Integer status;
    private Date createTime;
    private Date updateTime;
}
