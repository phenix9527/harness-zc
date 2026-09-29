package com.zc.api.demo.coupon.dto;

import com.zc.api.demo.coupon.entity.Coupon;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 领券响应。
 *
 * <p><b>设计要点：</b>幂等命中（用户重复点）时返回的也是这个对象 ——
 * 用的还是上次那张券的券号。用户看到「领取成功 + 券号」，前端体验是正常的；
 * 如果这里返回错误码，前端会以为失败并引导用户重试，反而放大流量。
 *
 * <p>注意：{@code status} 建议直接返回<b>可枚举的枚举名或数字码</b>，
 * 不要返回中文文案 —— 文案改动不该让前端跟着发版。
 *
 * <p>Lombok：保留手写的静态工厂 {@code of(...)}。这里刻意不做 setter 暴露之外的
 * 「胖构造器」—— 静态工厂方法名能表达语义（of 从实体转换），比 4 个参数的构造函数好读。
 */
@Getter
@Setter
@ToString
public class ClaimCouponResp {

    /** 券号 */
    private String couponNo;

    /** 券状态：0未使用 1已使用 2已过期 */
    private Integer status;

    /** 是否本次真正新领取（false = 幂等命中，返回的是上一次的结果）。调试期很有用，可不对外暴露 */
    private Boolean newlyClaimed;

    public static ClaimCouponResp of(Coupon coupon, boolean newlyClaimed) {
        ClaimCouponResp resp = new ClaimCouponResp();
        resp.setCouponNo(coupon.getCouponNo());
        resp.setStatus(coupon.getStatus());
        resp.setNewlyClaimed(newlyClaimed);
        return resp;
    }
}
