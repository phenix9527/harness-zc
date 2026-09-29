package com.zc.api.demo.export.constant;

/**
 * 导出任务状态机。
 *
 * <pre>
 *  PENDING ──markRunning(CAS)──▶ RUNNING ──markSuccess──▶ SUCCESS
 *     │                             │
 *     └────────markFailed───────────┴──────▶ FAILED
 * </pre>
 *
 * <p><b>严格单向流转，不允许回退</b>（不做 RUNNING -> PENDING 的「重试」，
 * 要重试就新建一个任务，否则状态机就变成了状态网，所有判断都会失效）。
 *
 * <p>落库用字符串而不是数字：导出任务量级不大，字符串在排查时肉眼可读（DBA 直接看表就懂），
 * 这点可读性收益远大于那点存储代价。高频大表才用数字码。
 */
public enum ExportTaskStatus {

    /** 已提交，等待执行 */
    PENDING,
    /** 执行中 */
    RUNNING,
    /** 成功（终态） */
    SUCCESS,
    /** 失败（终态） */
    FAILED;

    /** 终态：进入后不允许再流转 */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}
