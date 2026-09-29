package com.zc.api.demo.sms.client;

import com.zc.api.demo.sms.dto.SmsScene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 短信发送客户端（这里是演示实现，真实项目替换为厂商 SDK 调用）。
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li>短信是按条计费的，「发送失败」和「发送成功但用户没收到」都要能被统计到，
 *       否则月底对账会发现钱花得莫名其妙。</li>
 *   <li>调用外部 HTTP 接口必须设置<b>连接/读取超时</b>，且要短（1~2 秒）。
 *       厂商接口抖动 + 无超时 = 你的线程全部挂住。</li>
 *   <li>发送失败不能只记日志：要能触发「回滚频控计数」（见 SmsCodeService），
 *       否则用户被自己的失败卡 60 秒，体验极差且会来投诉。</li>
 *   <li>不要在调用里重试太多次：短信重复发送是真的花钱，也要防「用户收到 3 条一样的验证码」。</li>
 * </ol>
 */
@Component
public class SmsClient {

    private static final Logger log = LoggerFactory.getLogger(SmsClient.class);

    /**
     * 发送验证码短信。
     *
     * @param mobile   手机号（日志中必须脱敏）
     * @param template 服务端配置的模板编号
     * @param code     验证码
     * @throws RuntimeException 发送失败（本示例用假实现，按手机号尾号模拟失败）
     */
    public void send(String mobile, String template, String code) {
        // 演示：以尾号 0 模拟厂商返回失败，方便你跑通「回滚频控计数」这条分支
        if (mobile != null && mobile.endsWith("0")) {
            throw new IllegalStateException("mock sms provider error");
        }
        // 真实实现：HTTP 调用厂商接口（带超时 + 重试上限 + 结果码判断 + 监控埋点）
        log.info("sms sent, mobile={}, template={}, code=****", mask(mobile), template);
    }

    /** 手机号脱敏：日志/监控里只允许出现这种形式 */
    private String mask(String mobile) {
        if (mobile == null || mobile.length() < 7) {
            return "***";
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }
}
