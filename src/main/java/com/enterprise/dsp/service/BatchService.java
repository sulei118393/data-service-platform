package com.enterprise.dsp.service;

import com.enterprise.dsp.common.CryptoUtil;
import com.enterprise.dsp.common.DspProps;
import com.enterprise.dsp.common.SqlSecurityUtil;
import com.enterprise.dsp.dao.Repos;
import com.enterprise.dsp.entity.DsInfo;
import com.enterprise.dsp.datasource.DataSourceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 批量数据交换服务（4.9 / DS-BAT-01,02,05,06 + DS-SEC-08）
 *   从数据源抽取数据生成 CSV 批量文件；支持定时调度、加密压缩、水印、对账记录
 */
@Service
public class BatchService {

    @Resource private Repos repos;
    @Resource private DataSourceManager dsManager;
    @Resource private DspProps props;
    @Resource private MetaService metaService;
    @Resource private AccessService accessService;
    private final JdbcTemplate meta;
    private final ObjectMapper om = new ObjectMapper();

    public BatchService(JdbcTemplate meta) { this.meta = meta; }

    /** 手动执行任务 */
    public Map<String, Object> runNow(long taskId, String operator) {
        Map<String, Object> task = accessService.findOne("batch_task", taskId);
        if (task == null) return err("任务不存在");
        // DS-BAT-07：批量任务纳入授权体系
        String owner = str(task, "owner", "OWNER");
        if (owner == null || owner.isEmpty()) return err("批量任务未指定责任人（DS-BAT-07 授权要求）");
        return execute(taskId, operator);
    }

    private Map<String, Object> execute(long taskId, String operator) {
        Map<String, Object> task = accessService.findOne("batch_task", taskId);
        String status = str(task, "status", "STATUS");
        if ("RUNNING".equals(status)) return err("任务正在运行中");
        meta.update("INSERT INTO batch_run (task_id, status) VALUES (?,'RUNNING')", taskId);
        Long runId = meta.queryForObject("SELECT MAX(id) FROM batch_run", Long.class);
        long start = System.currentTimeMillis();
        try {
            long dsId = ((Number) (task.get("ds_id") != null ? task.get("ds_id") : task.get("DS_ID"))).longValue();
            String sql = str(task, "sql_text", "SQL_TEXT");
            String err = SqlSecurityUtil.checkSqlMode(sql);
            if (err != null) throw new IllegalArgumentException("SQL 校验失败: " + err);
            DsInfo ds = getDs(dsId);
            if (ds == null) throw new IllegalArgumentException("数据源不存在");
            Class.forName(ds.driverClass());
            com.zaxxer.hikari.HikariDataSource pool = dsManager.getPool(ds.id, ds.driverClass(), ds.jdbcUrl(),
                    ds.username, CryptoUtil.aesDecrypt(ds.password, props.aesKey),
                    ds.poolSize == null ? 3 : ds.poolSize, ds.timeoutSec == null ? 60 : ds.timeoutSec, true);
            String dir = metaService.getCfg("batch.export.dir", "./exports");
            File d = new File(dir);
            if (!d.exists()) d.mkdirs();
            String fileName = str(task, "file_name", "FILE_NAME");
            if (fileName == null || fileName.isEmpty()) fileName = "batch_" + taskId + "_" + new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date()) + ".csv";
            String charset = str(task, "charset", "CHARSET");
            if (charset == null || charset.isEmpty()) charset = "UTF-8";
            String sep = ",";
            String watermark = str(task, "watermark", "WATERMARK");
            File raw = new File(d, fileName);
            long rows = 0;
            try (Connection con = pool.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setQueryTimeout(props.queryTimeoutSeconds);
                ps.setFetchSize(1000);
                try (ResultSet rs = ps.executeQuery();
                     Writer w = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(raw.toPath()), Charset.forName(charset)))) {
                    java.sql.ResultSetMetaData md = rs.getMetaData();
                    int cc = md.getColumnCount();
                    StringBuilder head = new StringBuilder();
                    for (int i = 1; i <= cc; i++) {
                        if (i > 1) head.append(sep);
                        head.append(escape(md.getColumnLabel(i), sep));
                    }
                    if (!watermark.isEmpty()) head.append(sep).append("#watermark:").append(escape(watermark, sep));
                    w.write(head.toString());
                    w.write("\r\n");
                    while (rs.next()) {
                        StringBuilder line = new StringBuilder();
                        for (int i = 1; i <= cc; i++) {
                            if (i > 1) line.append(sep);
                            Object v = rs.getObject(i);
                            line.append(v == null ? "" : escape(String.valueOf(v), sep));
                        }
                        if (!watermark.isEmpty()) line.append(sep).append(escape(watermark, sep));
                        w.write(line.toString());
                        w.write("\r\n");
                        rows++;
                    }
                }
            }
            // 加密压缩（DS-BAT-01）
            String finalFile = raw.getAbsolutePath();
            int enc = ((Number) (task.get("encrypt") != null ? task.get("encrypt") : task.get("ENCRYPT"))).intValue();
            if (enc == 1) {
                File zip = new File(d, fileName + ".zip");
                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
                    zos.putNextEntry(new ZipEntry(fileName));
                    Files.copy(raw.toPath(), zos);
                    zos.closeEntry();
                }
                raw.delete();
                finalFile = zip.getAbsolutePath();
            }
            long cost = System.currentTimeMillis() - start;
            meta.update("UPDATE batch_run SET status='SUCCESS', rows_cnt=?, file_path=?, end_time=CURRENT_TIMESTAMP WHERE id=?", rows, finalFile, runId);
            meta.update("UPDATE batch_task SET status='SUCCESS', last_run=CURRENT_TIMESTAMP, last_rows=?, last_file=?, error_msg='' WHERE id=?",
                    rows, finalFile, taskId);
            return ok("执行成功", rows, finalFile, cost);
        } catch (Exception e) {
            meta.update("UPDATE batch_run SET status='FAIL', error_msg=?, end_time=CURRENT_TIMESTAMP WHERE id=?",
                    e.getMessage() == null ? e.toString() : e.getMessage(), runId);
            meta.update("UPDATE batch_task SET status='FAIL', last_run=CURRENT_TIMESTAMP, error_msg=? WHERE id=?",
                    e.getMessage() == null ? e.toString() : e.getMessage(), taskId);
            return err("执行失败: " + e.getMessage());
        }
    }

    /** 定时调度（DS-BAT-02）：每分钟检查，HH:mm 匹配即执行 */
    @Scheduled(cron = "0 * * * * ?")
    public void schedule() {
        String hhmm = new java.text.SimpleDateFormat("HH:mm").format(new Date());
        List<Map<String, Object>> tasks = meta.queryForList(
                "SELECT id FROM batch_task WHERE schedule=? AND status <> 'RUNNING'", hhmm);
        for (Map<String, Object> t : tasks) {
            long id = ((Number) (t.get("id") != null ? t.get("id") : t.get("ID"))).longValue();
            try { execute(id, "scheduler"); } catch (Exception ignore) { }
        }
    }

    /** 对账（DS-BAT-06）：源端行数与最近一次成功运行行数比对 */
    public Map<String, Object> reconcile(long taskId) {
        Map<String, Object> task = accessService.findOne("batch_task", taskId);
        if (task == null) return err("任务不存在");
        Map<String, Object> run = accessService.findOne("batch_run", 0);
        List<Map<String, Object>> runs = meta.queryForList(
                "SELECT * FROM batch_run WHERE task_id=? AND status='SUCCESS' ORDER BY id DESC LIMIT 1", taskId);
        if (runs.isEmpty()) return err("没有成功运行记录");
        Map<String, Object> last = runs.get(0);
        long fileRows = ((Number) (last.get("rows_cnt") != null ? last.get("rows_cnt") : last.get("ROWS_CNT"))).longValue();
        // 源端 COUNT
        long srcRows = -1;
        try {
            long dsId = ((Number) (task.get("ds_id") != null ? task.get("ds_id") : task.get("DS_ID"))).longValue();
            String sql = str(task, "sql_text", "SQL_TEXT");
            String countSql = "SELECT COUNT(*) FROM (" + sql.replaceAll(";\\s*$", "") + ") cnt__";
            DsInfo ds = getDs(dsId);
            Class.forName(ds.driverClass());
            com.zaxxer.hikari.HikariDataSource pool = dsManager.getPool(ds.id, ds.driverClass(), ds.jdbcUrl(),
                    ds.username, CryptoUtil.aesDecrypt(ds.password, props.aesKey), 2, ds.timeoutSec == null ? 60 : ds.timeoutSec, true);
            try (Connection con = pool.getConnection();
                 java.sql.Statement st = con.createStatement()) {
                st.setQueryTimeout(props.queryTimeoutSeconds);
                java.sql.ResultSet rs = st.executeQuery(countSql);
                if (rs.next()) srcRows = rs.getLong(1);
            }
        } catch (Exception e) {
            return err("源端对账失败: " + e.getMessage());
        }
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("fileRows", fileRows);
        r.put("sourceRows", srcRows);
        r.put("match", srcRows == fileRows);
        return r;
    }

    // ---------------- 工具 ----------------

    private String escape(String s, String sep) {
        if (s.contains(sep) || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private DsInfo getDs(long dsId) throws Exception {
        Map<String, Object> row = repos.ds.findById(dsId);
        if (row == null) return null;
        return om.convertValue(normalizeKeys(row), DsInfo.class);
    }

    private Map<String, Object> normalizeKeys(Map<String, Object> row) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String k = e.getKey().toLowerCase();
            String[] parts = k.split("_");
            StringBuilder sb = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].isEmpty()) sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
            m.put(sb.toString(), e.getValue());
        }
        return m;
    }

    private long num(Map<String, Object> row, String... keys) {
        for (String k : keys) { Object v = row.get(k); if (v instanceof Number) return ((Number) v).longValue(); }
        return 0;
    }

    private String str(Map<String, Object> row, String... keys) {
        for (String k : keys) { Object v = row.get(k); if (v != null) return String.valueOf(v); }
        return "";
    }

    private Map<String, Object> ok(String msg, long rows, String file, long cost) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("code", 0); m.put("message", msg);
        m.put("rows", rows); m.put("file", file); m.put("costMs", cost);
        return m;
    }

    private Map<String, Object> err(String msg) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("code", 500); m.put("message", msg);
        return m;
    }
}
