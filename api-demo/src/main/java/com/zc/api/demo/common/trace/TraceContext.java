package com.zc.api.demo.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * traceId 上下文：用 MDC 承载，日志模板里打成 %X{traceId} 就能全链路串起来。
 *
 * <p><b>注意点（这几条是真会踩的坑）：</b>
 * <ol>
 *   <li>MDC 底层是 ThreadLocal。<b>异步线程、线程池、MQ 消费线程里拿不到</b>，必须显式传递。
 *       本工程的做法：跨线程前先 {@code getTraceId()}，进入新线程后 {@code setTraceId()}，
 *       用完后 {@code clear()}，否则线程池复用会导致 traceId 串味。</li>
 *   <li>必须在 finally 里 clear，Tomcat 复用线程不清理 = 下个请求带着上个请求的 id。</li>
 *   <li>MDC 依赖 slf4j 实现（logback 自带）；用 log4j2 也可以，API 一致。</li>
 * </ol>
 */
public final class TraceContext {

    /** 与网关、前端、下游统一约定的 header 名，别各写各的 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    public static final String MDC_KEY = "traceId";

    private TraceContext() {
    }

    /** 生成一个新的 traceId（去掉短横线，32 位，便于拼接与打印） */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static void setTraceId(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    public static String getTraceId() {
        return MDC.get(MDC_KEY);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
