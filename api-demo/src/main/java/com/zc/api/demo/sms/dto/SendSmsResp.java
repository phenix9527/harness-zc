package com.zc.api.demo.sms.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 发送短信验证码响应。
 *
 * <p><b>为什么返回 expiresIn：</b>前端可以直接用服务端的有效期做倒计时，
 * 避免前后端各写一套时长（后端改配置前端不知道，就会出现「倒计时结束但验证码还有效」）。
 *
 * <p><b>注意点：</b>响应里<b>绝对不能</b>带「该手机号是否已注册」「验证码是什么」这类信息。
 * 前者是用户枚举漏洞，后者等于把风控送人。
 *
 * <p>Lombok：{@code @AllArgsConstructor} 替换手写构造函数；同时保留
 * {@code @NoArgsConstructor}，因为 Jackson 等序列化/反序列化框架需要无参构造。
 * 只加 {@code @AllArgsConstructor} 会把默认无参构造挤掉 ——
 * 这类问题在<b>运行时</b>才暴露（反序列化报 no suitable constructor），编译期一点提示都没有。
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class SendSmsResp {

    /** 验证码有效期（秒） */
    private Integer expiresIn;
}
