package com.zc.api.demo.common.web;

import org.springframework.util.StreamUtils;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Web 层零散工具：读取原始报文、取真实 IP。
 *
 * <p>为什么单独抽出来：这两段代码几乎每个项目都会写错一次，写对了就该固化成工具。
 */
public final class WebUtils {

    private WebUtils() {
    }

    /**
     * 读取原始 body。
     *
     * <p><b>注意点：</b>
     * <ol>
     *   <li>验签（微信/支付宝回调）必须用<b>原始报文</b>，不能是反序列化再序列化的字符串，
     *       字段顺序/空格/转义一变签名就验不过。</li>
     *   <li>一旦用 {@code getInputStream()} 读过，body 就不能再被 {@code @RequestBody} 读第二次
     *       （Servlet 流只能读一次）。所以回调接口用 {@code HttpServletRequest} 收原始报文，
     *       或加 ContentCachingRequestWrapper。</li>
     *   <li>显式指定 UTF-8，避免机器默认编码不同导致验签随机失败。</li>
     * </ol>
     */
    public static String readBody(HttpServletRequest request) throws IOException {
        return StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
    }

    /**
     * 取客户端真实 IP（用于频控/风控）。
     *
     * <p><b>注意点：</b> X-Forwarded-For 是<b>可伪造</b>的，只有在网关层做了覆盖写、
     * 且应用不可被绕过直连时才能信任。用于限流时建议取链路上「最后一个可信代理」写入的值。
     */
    public static String getClientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String header : headers) {
            String value = request.getHeader(header);
            if (value != null && !value.isEmpty() && !"unknown".equalsIgnoreCase(value)) {
                // XFF 是逗号分隔的列表，第一个是最初的客户端
                int idx = value.indexOf(',');
                return idx > 0 ? value.substring(0, idx).trim() : value.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
