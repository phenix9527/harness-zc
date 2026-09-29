package com.zc.api.demo.coupon.controller;

import com.zc.api.demo.common.api.Result;
import com.zc.api.demo.common.web.WebUtils;
import com.zc.api.demo.coupon.dto.ClaimCouponReq;
import com.zc.api.demo.coupon.dto.ClaimCouponResp;
import com.zc.api.demo.coupon.service.CouponClaimService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

/**
 * 场景1：优惠券领取接口。
 *
 * <pre>
 * POST /inner/coupon/{activityCode}/claim
 * Req : ClaimCouponReq  { Long userId }
 * Resp: Result&lt;ClaimCouponResp { String couponNo; Integer status }&gt;
 * </pre>
 *
 * <p><b>接口设计注意点：</b>
 * <ol>
 *   <li><b>路径语义</b>：{@code /inner/} 前缀表示「内部接口」，只应由网关之后的内部服务调用，
 *       不能暴露到公网。对外接口不要复用它（对外要鉴权、要防刷、要做版本管理）。</li>
 *   <li><b>资源用业务编码不用自增 id</b>：{@code {activityCode}} 而不是 {@code {id}}，
 *       否则可以遍历 ID 探测活动总量、发券量。</li>
 *   <li><b>写操作必须 POST</b>，且要做幂等设计（这里已由唯一索引保证）。
 *       若要给第三方调用，建议支持 {@code Idempotency-Key} 请求头（Stripe 那套做法）。</li>
 *   <li><b>返回体不要直接吐实体</b>，必须有独立的 Resp DTO：实体加字段时不会意外泄露给调用方
 *       （这是最常见的字段越权泄漏来源）。</li>
 *   <li>接口层只做「取参数 -> 调服务 -> 转返回」，业务逻辑一律放 Service，
 *       否则限流、幂等这些横切逻辑会被复制到每个 Controller。</li>
 * </ol>
 * @author admin
 */
@RestController
@RequestMapping("/inner/coupon")
@Validated
public class CouponController {

    private static final Logger log = LoggerFactory.getLogger(CouponController.class);

    private final CouponClaimService couponClaimService;

    public CouponController(CouponClaimService couponClaimService) {
        this.couponClaimService = couponClaimService;
    }

    /**
     * 领取优惠券。
     *
     * <p>返回语义（调用方按此处理，不要再猜）：
     * <ul>
     *   <li>{@code code=0}：领取成功，或重复领取（返回上次券号）</li>
     *   <li>{@code code=20002}：已抢光 —— 别重试</li>
     *   <li>{@code code=20003}：活动未开始/已结束 —— 别重试</li>
     *   <li>{@code code=20010}：过于频繁，{@code data.retryAfter} 有剩余秒数 —— 倒计时后再点</li>
     *   <li>HTTP 5xx：系统异常 —— 可以重试（幂等已保证不会重复发券）</li>
     * </ul>
     */
    @PostMapping("/{activityCode}/claim")
    public Result<ClaimCouponResp> claim(@PathVariable("activityCode") String activityCode,
                                         @Valid @RequestBody ClaimCouponReq req,
                                         HttpServletRequest request) {
        String clientIp = WebUtils.getClientIp(request);
        log.info("coupon claim start, activityCode={}, userId={}, ip={}",
                activityCode, req.getUserId(), clientIp);
        ClaimCouponResp resp = couponClaimService.claim(activityCode, req, clientIp);
        return Result.ok(resp);
    }
}
