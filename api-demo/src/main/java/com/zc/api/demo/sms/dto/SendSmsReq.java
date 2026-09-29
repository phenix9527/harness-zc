package com.zc.api.demo.sms.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

/**
 * 发送短信验证码请求。
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li>手机号用 {@code @Pattern} 做格式校验 —— 格式都不对就没必要查 Redis、发短信（省钱）。</li>
 *   <li>{@code scene} 用 {@code @NotNull} 且类型是枚举：Spring 会做<b>严格枚举转换</b>，
 *       传非法值直接 400，不会进到业务逻辑（比手工 parse 更安全）。</li>
 *   <li>手机号属于个人信息，<b>日志里必须脱敏</b>（见 {@code SmsCodeService#mask}），
 *       否则日志系统一搜就是全量手机号，合规检查必挂。</li>
 * </ol>
 *
 * <p><b>★ Lombok 的 {@code @ToString(exclude = "mobile")} 就是「日志脱敏」的第一道闸门：</b>
 * 只要有 {@code @ToString}，任何地方顺手 {@code log.info("req={}", req)} 都会打印全部字段 ——
 * 这是手机号进日志最常见的途径（比显式打印更难发现，因为它是「无心之失」）。
 * 显式排除掉，让别人想打也打不出来。
 */
@Getter
@Setter
@ToString(exclude = "mobile")
public class SendSmsReq {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String mobile;

    @NotNull(message = "scene 不能为空")
    private SmsScene scene;
}
