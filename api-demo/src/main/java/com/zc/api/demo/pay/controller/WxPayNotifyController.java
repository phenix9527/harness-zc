package com.zc.api.demo.pay.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zc.api.demo.common.api.BizException;
import com.zc.api.demo.common.web.WebUtils;
import com.zc.api.demo.pay.dto.PayNotify;
import com.zc.api.demo.pay.service.PayNotifyService;
import com.zc.api.demo.pay.service.WxPayVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * 场景2：支付回调入口。
 *
 * <pre>
 * POST /notify/wechat/pay       ← 支付网关调用，不是前端调用
 * </pre>
 *
 * <p><b>★ 这个接口和业务接口的写法完全不同，四处反常，每一处都是必须的：</b>
 * <ol>
 *   <li><b>不套统一 Result。</b> 必须按网关规定的格式返回 {@code {"code":"SUCCESS"}} 这样的原样字符。
 *       返回错格式 = 网关认为失败 = 无限重试，能把你的服务打挂。</li>
 *   <li><b>用 HttpServletRequest 收原始报文</b>，不用 {@code @RequestBody}。
 *       验签必须对原始 body 计算，反序列化再序列化会改变字段顺序/转义导致验签失败；
 *       而且 body 流只能读一次（见 {@link WebUtils#readBody}）。</li>
 *   <li><b>不用统一异常处理器。</b> 这个接口自己吞掉异常并翻译成网关要的格式，
 *       因为「业务失败返回成功、系统失败返回失败」的规则和通用规则正好相反。</li>
 *   <li><b>不走统一登录鉴权</b>（网关调用没有登录态），但要单独做 IP 白名单 + 限流，
 *       否则这个公网接口就是被刷的靶子。</li>
 * </ol>
 *
 * <p><b>还有一个必须说清楚的点：「业务失败返回 SUCCESS」不代表「所有异常都返回 SUCCESS」。</b>
 * <ul>
 *   <li>已处理过 / 金额不一致（要人工介入）→ SUCCESS：重试解决不了问题，别让网关白刷 24 小时</li>
 *   <li>订单查不到 / DB 抖动 / MQ 发送失败 → FAIL：这些是「暂时不行」，重试是有意义的</li>
 * </ul>
 * 这个区分搞错，你一个 DB 抖动就会被网关判成永久失败 —— 用户的钱付了，订单永远是待支付。
 * @author admin
 */
@RestController
public class WxPayNotifyController {

    private static final Logger log = LoggerFactory.getLogger(WxPayNotifyController.class);

    /** 网关要求的成功体（此处以微信支付 v3 的格式为例） */
    private static final String SUCCESS_BODY = "{\"code\":\"SUCCESS\",\"message\":\"ok\"}";
    /** 网关要求的失败体：收到它就按重试策略再来一次 */
    private static final String FAIL_BODY = "{\"code\":\"FAIL\",\"message\":\"handle fail\"}";

    private final WxPayVerifier wxPayVerifier;
    private final PayNotifyService payNotifyService;
    private final ObjectMapper objectMapper;

    public WxPayNotifyController(WxPayVerifier wxPayVerifier,
                                 PayNotifyService payNotifyService,
                                 ObjectMapper objectMapper) {
        this.wxPayVerifier = wxPayVerifier;
        this.payNotifyService = payNotifyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/notify/wechat/pay")
    public String wxPayNotify(HttpServletRequest request) {
        String body;
        try {
            // 1) 读原始报文（只读一次，之后不能再读）
            body = WebUtils.readBody(request);
        } catch (Exception e) {
            log.error("read pay notify body fail", e);
            return FAIL_BODY;
        }

        String signature = request.getHeader("Wechatpay-Signature");
        String timestamp = request.getHeader("Wechatpay-Timestamp");
        String nonce = request.getHeader("Wechatpay-Nonce");
        String outTradeNoForLog = null;

        try {
            // 2) 验签，不通过直接拒（防止伪造回调把订单改成已支付）
            //    注意：验签只做一次，不要再在业务层重复验（浪费 CPU，且容易两处不一致）
            if (!wxPayVerifier.verify(signature, timestamp, nonce, body)) {
                log.warn("pay notify sign verify fail, timestamp={}, nonce={}", timestamp, nonce);
                // 验签失败不返回 FAIL 也可以返回 SUCCESS：反正不是我们认识的通知，重试也没用。
                // 这里返回 FAIL 是为了留下重试痕迹便于排查攻击行为，两者都可接受，按团队规范定。
                return FAIL_BODY;
            }

            PayNotify notify = objectMapper.readValue(body, PayNotify.class);
            outTradeNoForLog = notify.getOutTradeNo();
            log.info("pay notify received, outTradeNo={}, notifyId={}, tradeState={}",
                    notify.getOutTradeNo(), notify.getNotifyId(), notify.getTradeState());

            // 3) 业务处理（内部已做幂等：CAS + 状态机守卫）
            payNotifyService.handlePaid(notify);
            return SUCCESS_BODY;
        } catch (BizException e) {
            // 业务上已经处理过 / 数据不一致需人工介入 ——> 返回成功，别让网关一直重试
            log.warn("pay notify biz fail, treat as success to stop retry. outTradeNo={}, code={}, msg={}",
                    outTradeNoForLog, e.getErrorCode().getCode(), e.getMessage());
            return SUCCESS_BODY;
        } catch (Exception e) {
            // 系统异常（DB 抖动 / MQ 不可用 / 订单查不到）——> 返回失败让网关重试
            log.error("pay notify system error, ask gateway to retry. outTradeNo={}", outTradeNoForLog, e);
            return FAIL_BODY;
        }
    }
}
