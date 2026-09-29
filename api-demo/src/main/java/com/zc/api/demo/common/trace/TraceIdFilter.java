package com.zc.api.demo.common.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 可观测性第一块砖：给每个请求打上 traceId，并回写到响应头。
 *
 * <p>规则：
 * <ul>
 *   <li>上游（网关/前端/Feign）带了 {@code X-Trace-Id} 就沿用 —— 这样一次跨服务调用只用一个 id；</li>
 *   <li>没带就本地生成，保证「每次请求必有 traceId」。</li>
 * </ul>
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li>一定要用 {@link OncePerRequestFilter}，否则 forward/include 场景会重复执行，
 *       生成出两个 traceId。</li>
 *   <li>顺序要尽量靠前（这里用 {@link Ordered#HIGHEST_PRECEDENCE}+1，排在编码过滤器之后），
 *       保证后续所有日志都带上。</li>
 *   <li>响应头要暴露给浏览器 JS：跨域场景还需在 CORS 配置里
 *       <code>addExposedHeader("X-Trace-Id")</code>，否则前端读不到。</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TraceContext.TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = TraceContext.newTraceId();
        }
        long start = System.currentTimeMillis();
        TraceContext.setTraceId(traceId);
        response.setHeader(TraceContext.TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 访问日志：只打一条，包含耗时与状态码，是最便宜的接口监控
            log.info("uri={} method={} status={} cost={}ms",
                    request.getRequestURI(), request.getMethod(), response.getStatus(),
                    System.currentTimeMillis() - start);
            TraceContext.clear();
        }
    }
}
