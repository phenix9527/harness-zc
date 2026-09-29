package com.zc.api.demo.coupon.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

/**
 * 领券请求。
 *
 * <p><b>契约相关注意点：</b>
 * <ol>
 *   <li>字段名与语义要在 API 文档里写死；{@code userId} 是「谁领」，
 *       如果接口是给前端调的，<b>userId 必须从登录态取，不能由前端传</b>
 *       （否则可以给别人领券 / 遍历用户）。本示例按内部接口（服务间调用）写，
 *       所以显式接收 userId，并在 Controller 注释里标注风险。</li>
 *   <li>用包装类型 Long 而不是 long：基本类型无法表达「没传」，
 *       校验注解 {@code @NotNull} 也失效（默认值 0 会当成合法值）。</li>
 *   <li>加 {@code @Positive} 是便宜的防御：userId=0/-1 一定是脏数据，
 *       早点在入口拦掉，别让它打到 DB。</li>
 * </ol>
 *
 * <p>Lombok：请求 DTO 用 {@code @Getter/@Setter}，<b>不建议 {@code @Data}</b> ——
 * 它顺带生成 equals/hashCode 和构造函数，对「一次性入参对象」没有意义，
 * 反而会让「两个请求对象被误判相等」这类问题更难发现。
 */
@Getter
@Setter
@ToString(of = "userId")
public class ClaimCouponReq {

    /** 领取人用户ID（内部接口由上游服务透传；开放接口必须从登录态取） */
    @NotNull(message = "userId 不能为空")
    @Positive(message = "userId 必须为正数")
    private Long userId;
}
