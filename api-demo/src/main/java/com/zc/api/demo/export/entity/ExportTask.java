package com.zc.api.demo.export.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Date;

/**
 * 导出任务实体。
 *
 * <p>字段设计要点：
 * <ul>
 *   <li>{@code requestId} —— 幂等键，与 user_id 组成唯一索引 uk_request_user</li>
 *   <li>{@code queryJson} —— 查询条件<b>快照</b>：任务执行时环境可能已经变了，
 *       必须把当时的条件冻结下来，否则「重跑一次」会得到不同结果，排查时无法复现</li>
 *   <li>{@code progress} —— 给用户看的进度，没有它的长任务体验就是「转圈转到死」</li>
 *   <li>{@code expireAt} —— 结果文件有效期，没有它的系统半年后磁盘必爆</li>
 * </ul>
 *
 * <p>Lombok 注意点：{@code @ToString} 刻意<b>排除 queryJson 与 errorMsg</b> ——
 * 前者可能很长（一行日志被撑爆），后者可能是完整堆栈（信息泄露 + 日志膨胀）。
 */
@Getter
@Setter
@EqualsAndHashCode(of = "id")
@ToString(of = {"taskId", "userId", "status", "progress"})
public class ExportTask {

    private Long id;
    private String taskId;
    private String requestId;
    private Long userId;
    private String bizType;
    private String queryJson;
    private String status;
    private Integer progress;
    private Integer totalRows;
    private String resultUrl;
    private String errorMsg;
    private Date expireAt;
    private Date createTime;
    private Date updateTime;
}
