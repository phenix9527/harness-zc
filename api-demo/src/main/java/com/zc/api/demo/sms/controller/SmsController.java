package com.zc.api.demo.sms.controller;

import com.zc.api.demo.common.api.Result;
import com.zc.api.demo.common.web.WebUtils;
import com.zc.api.demo.sms.dto.SendSmsReq;
import com.zc.api.demo.sms.dto.SendSmsResp;
import com.zc.api.demo.sms.service.SmsCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

/**
 * 场景3：短信验证码接口。
 *
 * <pre>
 * POST /inner/sms/code
 * Req : SendSmsReq  { String mobile; SmsScene scene }
 * Resp: Result&lt;SendSmsResp { Integer expiresIn }&gt;
 * </pre>
 *
 * <p><b>接口设计注意点：</b>
 * <ol>
 *   <li><b>返回文案要统一</b>：无论手机号是否已注册，都返回「验证码已发送」。
 *       登录接口返回「该手机号未注册」就是在帮攻击者做用户枚举。</li>
 *   <li><b>频控返回业务码 + retryAfter，而不是 429</b>。HTTP 429 会让前端拦截器
 *       把它当成通用错误弹「网络异常」，而这里其实需要「x 秒后重试」的专门处理。
 *       想用 429 也行，但必须和前端约定统计口径（重试策略、监控告警要能区分）。</li>
 *   <li><b>短信发送属于「外部依赖 + 花钱」的操作</b>，要考虑幂等：同一用户同一场景
 *       60 秒内重复提交只能发一次 —— 这就是频控的第一档在兼职做的事。</li>
 * </ol>
 */
@RestController
@Validated
public class SmsController {

    private static final Logger log = LoggerFactory.getLogger(SmsController.class);

    private final SmsCodeService smsCodeService;

    public SmsController(SmsCodeService smsCodeService) {
        this.smsCodeService = smsCodeService;
    }

    /**
     * 发送验证码。
     *
     * <p>注意：这里不要把手机号打进日志（log 里只留 scene + IP），脱敏发生在 Service 内部。
     */
    @PostMapping("/inner/sms/code")
    public Result<SendSmsResp> sendCode(@Valid @RequestBody SendSmsReq req, HttpServletRequest request) {
        String clientIp = WebUtils.getClientIp(request);
        log.info("sms code request, scene={}, ip={}", req.getScene(), clientIp);
        SendSmsResp resp = smsCodeService.sendCode(req.getMobile(), req.getScene(), clientIp);
        return Result.ok(resp);
    }
}
