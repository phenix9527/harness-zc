package com.zc.api.demo.export.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zc.api.demo.common.api.BizException;
import com.zc.api.demo.common.api.ErrorCode;
import com.zc.api.demo.export.entity.ExportTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 导出执行器：真正干活的「业务侧」。
 *
 * <p>这里做了个演示实现（生成 CSV 落到本地目录），重点不在导出内容，
 * 而在<b>长任务执行时必须遵守的几条纪律</b>：
 *
 * <p><b>注意点：</b>
 * <ol>
 *   <li><b>必须流式写出，不能 List 全量加载</b>。10 万行订单 = 几个 G 的堆内存，
 *       一次导出就能把服务 OOM 掉，而且线上排查时完全看不出是谁干的。</li>
 *   <li><b>必须分页查询（游标/seek 分页）</b>。{@code LIMIT 100000, 50} 这种深分页会越翻越慢，
 *       导出到一半时间翻倍；用 {@code WHERE id > lastId ORDER BY id LIMIT 500} 这种 seek 方式。</li>
 *   <li><b>必须设总量上限</b>（{@code max-rows}），超了直接失败并提示「缩小范围」，
 *       而不是「跑两天两夜」。</li>
 *   <li><b>进度上报要节流</b>：每 1% 或每 2 秒一次，别每行一次（见 ExportTaskMapper#updateProgress 注释）。</li>
 *   <li><b>必须能被打断/超时终止</b>：给整个导出设「最长执行时间」，
 *       否则一个慢查询会一直占着 worker 线程（消费槽位），后面的任务全排队。</li>
 *   <li><b>临时文件写到一半失败要能清理</b>：否则磁盘里全是 xxx.tmp。</li>
 * </ol>
 */
@Service
public class ExportExecutor {

    private static final Logger log = LoggerFactory.getLogger(ExportExecutor.class);

    /** 每批写入行数：太小 -> 频繁 IO；太大 -> 内存压力 */
    private static final int BATCH_FLUSH_ROWS = 1000;

    @Value("${demo.export.result-base-url:https://static.example.com/export/}")
    private String resultBaseUrl;

    @Value("${demo.export.local-dir:./data/export}")
    private String localDir;

    @Value("${demo.export.max-rows:200000}")
    private int maxRows;

    @Value("${demo.export.file-expire-hours:168}")
    private int fileExpireHours;

    private final ObjectMapper objectMapper;

    public ExportExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 执行导出。
     *
     * @param task     任务（含查询条件快照 queryJson）
     * @param progress 进度回调，调用方负责节流（这里是演示，按 5% 粒度回调）
     */
    public ExportResult doExport(ExportTask task, ProgressCallback progress) throws IOException {
        // 1) 解析查询条件快照（真实项目：JSON -> 强类型条件对象，绝不能拼进 SQL）
        //    这里为了聚焦接口设计，直接演示为「要导出的行数」
        int rows = resolveRowCount(task.getQueryJson());
        if (rows > maxRows) {
            throw new IllegalArgumentException("查询结果行数(" + rows + ")超过上限(" + maxRows + ")，请缩小查询范围");
        }

        // 2) 流式写出
        Path dir = Paths.get(localDir);
        Files.createDirectories(dir);
        String fileName = task.getTaskId() + ".csv";
        Path file = dir.resolve(fileName);

        try (Writer writer = new BufferedWriter(
                Files.newBufferedWriter(file, StandardCharsets.UTF_8), 64 * 1024)) {
            writer.write("order_no,user_id,amount,status\n");
            for (int i = 1; i <= rows; i++) {
                writer.write("DEMO" + i + "," + (1000 + i) + ",99.00,2\n");
                if (i % BATCH_FLUSH_ROWS == 0) {
                    writer.flush();
                    int percent = (int) (i * 100L / rows);
                    // 节流：只在跨越 5% 时上报（演示写法；生产用时间间隔节流更稳）
                    if (percent % 5 == 0) {
                        progress.onProgress(Math.min(percent, 99));
                    }
                }
            }
        } catch (IOException e) {
            // 失败要清掉半成品文件，否则磁盘里全是垃圾
            deleteQuietly(file);
            throw e;
        }

        // 3) 生成结果地址 + 过期时间
        //    注意：真实项目这里应该返回「带签名的临时 URL」（预签名 S3/OSS 地址），
        //    而不是公开可读的静态地址 —— 导出文件往往含个人信息，直接公开等于数据泄漏。
        Date expireAt = new Date(System.currentTimeMillis() + fileExpireHours * 3600_000L);
        String url = resultBaseUrl + fileName;
        log.info("export file generated, taskId={}, rows={}, url={}, expireAt={}",
                task.getTaskId(), rows, url, new SimpleDateFormat("yyyy-MM-dd HH:mm").format(expireAt));
        return new ExportResult(url, rows, expireAt);
    }

    /** 删除文件（清理过期/失败产物），失败只记日志 */
    public void deleteFile(String resultUrl) {
        if (resultUrl == null || !resultUrl.startsWith(resultBaseUrl)) {
            return;
        }
        String fileName = resultUrl.substring(resultBaseUrl.length());
        deleteQuietly(Paths.get(localDir).resolve(fileName));
    }

    private void deleteQuietly(Path path) {
        try {
            File f = path.toFile();
            if (f.exists() && !f.delete()) {
                log.warn("delete export file fail, path={}", path);
            }
        } catch (Exception e) {
            log.warn("delete export file error, path={}", path, e);
        }
    }

    /** 解析条件（演示：queryJson 形如 {"rows":1000}）。
     *
     * <p><b>注意点：</b>真实项目这里应该用 Jackson 把 JSON 反序列化成<b>强类型条件对象</b>，
     * 再把对象拼成 MyBatis 的动态 SQL（#{ } 占位符）。
     * 绝不能把用户传的 JSON 字符串直接拼进 SQL：那是注入，也是数据越权的入口
     * （用户可以在 JSON 里塞一个不存在的 userId 条件去导别人的数据）。
     */
    private int resolveRowCount(String queryJson) {
        try {
            JsonNode node = objectMapper.readTree(queryJson);
            JsonNode rows = node.get("rows");
            return rows == null || !rows.isNumber() ? 100 : rows.asInt();
        } catch (Exception e) {
            // 条件不合法属于业务失败（用户改一下条件就能成功），不要抛系统异常
            throw new BizException(ErrorCode.EXPORT_QUERY_INVALID, "查询条件不是合法 JSON");
        }
    }

    /** 进度回调：让执行器与存储解耦（执行器不需要知道进度存在哪） */
    public interface ProgressCallback {
        void onProgress(int percent);
    }

    /** 导出结果 */
    public static class ExportResult {
        private final String resultUrl;
        private final int totalRows;
        private final Date expireAt;

        public ExportResult(String resultUrl, int totalRows, Date expireAt) {
            this.resultUrl = resultUrl;
            this.totalRows = totalRows;
            this.expireAt = expireAt;
        }

        public String getResultUrl() {
            return resultUrl;
        }

        public int getTotalRows() {
            return totalRows;
        }

        public Date getExpireAt() {
            return expireAt;
        }
    }
}
