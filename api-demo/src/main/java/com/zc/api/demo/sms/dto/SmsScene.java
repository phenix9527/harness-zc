package com.zc.api.demo.sms.dto;

/**
 * 短信场景枚举。
 *
 * <p><b>为什么必须是枚举而不是字符串：</b>
 * <ol>
 *   <li><b>防短信轰炸</b>：如果 scene 由调用方随意传，前端可以传 100 个不同 scene 绕过频控
 *       （很多频控是按 scene 维度做的）。白名单枚举从类型上就堵住了。</li>
 *   <li><b>防模板注入</b>：模板 id 与场景绑定在服务端，调用方只能选场景，不能指定模板内容。</li>
 *   <li><b>文案可控</b>：场景决定「你在做什么」，登录场景统一返回「验证码已发送」，
 *       不暴露手机号是否已注册 —— 这是防用户枚举的基本要求。</li>
 * </ol>
 *
 * <p>注意点：新增场景时不要复用已有枚举值，也不要改已有值的含义
 * （Redis 里可能还存着老数据，语义一变就成了脏数据）。
 */
public enum SmsScene {

    /** 登录（注意：文案不能暴露手机号是否已注册） */
    LOGIN("SMS_LOGIN_TEMPLATE", 300),

    /** 注册 */
    REGISTER("SMS_REGISTER_TEMPLATE", 300),

    /** 重置密码（安全等级更高，建议更严格频控 + 强制图形验证码） */
    RESET_PWD("SMS_RESET_PWD_TEMPLATE", 300),

    /** 绑定手机号 */
    BIND_MOBILE("SMS_BIND_MOBILE_TEMPLATE", 300);

    /** 短信模板编号（服务端配置，调用方不可指定） */
    private final String template;

    /** 验证码有效期（秒） */
    private final int codeTtlSeconds;

    SmsScene(String template, int codeTtlSeconds) {
        this.template = template;
        this.codeTtlSeconds = codeTtlSeconds;
    }

    public String getTemplate() {
        return template;
    }

    public int getCodeTtlSeconds() {
        return codeTtlSeconds;
    }
}
