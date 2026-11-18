package com.enterprise.dsp.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监控与告警服务（4.7 / DS-OPS-01~06）
 *   实时指标聚合（从调用日志统计）、阈值告警、熔断器状态、服务健康检查、慢服务识别
 */
@Service
public class MonitorService {

    @Resource private MetaService metaService;
    private final JdbcTemplate meta;

    // ---------------- 熔断器（DS-GW-07） ----------------

    static class Breaker {
        volatile long openUntil = 0;      // 熔断打开截止时间
        volatile int failCnt = 0;         // 连续失败数
        volatile long lastFail = 0;
        volatile boolean halfOpen = false;
    }
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    public MonitorService(JdbcTemplate meta) { this.meta = meta; }

    private static final int FAIL_THRESHOLD = 5;      // 连续失败阈值
    private static final long OPEN_MS = 30_000;       // 熔断时长 30s，之后半开探测

    /** 熔断器是否放行（半开时放行探测请求） */
    public boolean allow(String key) {
        Breaker b = breakers.computeIfAbsent(key, k -> new Breaker());
        long now = System.currentTimeMillis();
        if (b.openUntil > now) return false;
        if (b.openUntil > 0 && b.openUntil <= now) b.halfOpen = true;   // 半开探测
        return true;
    }

    public boolean isHalfOpen(String key) {
        Breaker b = breakers.get(key);
        return b != null && b.halfOpen;
    }

    public void recordSuccess(String key) {
        Breaker b = breakers.get(key);
        if (b != null) { b.failCnt = 0; b.openUntil = 0; b.halfOpen = false; }
    }

    public void recordFailure(String key) {
        Breaker b = breakers.computeIfAbsent(key, k -> new Breaker());
        b.failCnt++;
        b.lastFail = System.currentTimeMillis();
        if (b.failCnt >= FAIL_THRESHOLD) {
            b.openUntil = System.currentTimeMillis() + OPEN_MS;
            b.halfOpen = false;
            logSec("CIRCUIT_OPEN", "HIGH", "服务 " + key + " 连续失败 " + b.failCnt + " 次，熔断 30 秒", "", "", key);
        }
    }

    // ============================================================
    // 指标聚合（DS-OPS-01）
    // ============================================================

    /** 窗口内指标：map key = api_path */
    public Map<String, Map<String, Object>> metrics(int windowMinutes) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = meta.queryForList(
                "SELECT api_path, COUNT(*) cnt, SUM(CASE WHEN status='SUCCESS' THEN 1 ELSE 0 END) ok_cnt, " +
                " SUM(CASE WHEN status='DENY' THEN 1 ELSE 0 END) deny_cnt, AVG(cost_ms) avg_cost, MAX(cost_ms) max_cost, " +
                " SUM(rows_returned) rows_cnt " +
                "FROM api_log WHERE create_time >= ? GROUP BY api_path",
                new java.sql.Timestamp(System.currentTimeMillis() - windowMinutes * 60_000L));
        for (Map<String, Object> r : rows) {
            String path = String.valueOf(r.get("api_path") != null ? r.get("api_path") : r.get("API_PATH"));
            long cnt = n(r, "cnt", "CNT");
            long ok = n(r, "ok_cnt", "OK_CNT");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            m.put("calls", cnt);
            m.put("success", ok);
            m.put("errorRate", cnt == 0 ? 0 : Math.round((cnt - ok) * 10000.0 / cnt) / 100.0);
            m.put("deny", n(r, "deny_cnt", "DENY_CNT"));
            m.put("avgCost", Math.round(n(r, "avg_cost", "AVG_COST")));
            m.put("maxCost", n(r, "max_cost", "MAX_COST"));
            m.put("rows", n(r, "rows_cnt", "ROWS_CNT"));
            out.put(path, m);
        }
        return out;
    }

    // ============================================================
    // 告警评估（DS-OPS-03）：每分钟执行
    // ============================================================

    @Scheduled(cron = "0 * * * * ?")
    public void evaluateAlerts() {
        try {
            List<Map<String, Object>> rules = meta.queryForList("SELECT * FROM alert_rule WHERE enabled=1");
            Map<String, Map<String, Object>> m = metrics(10);
            Map<String, Object> global = new LinkedHashMap<>();
            long gCnt = 0, gOk = 0, gDeny = 0; double gCost = 0;
            for (Map<String, Object> v : m.values()) {
                gCnt += n(v, "calls", "CALLS"); gOk += n(v, "success", "SUCCESS");
                gDeny += n(v, "deny", "DENY"); gCost += n(v, "avgCost", "AVG_COST");
            }
            global.put("ERROR_RATE", gCnt == 0 ? 0 : Math.round((gCnt - gOk) * 10000.0 / gCnt) / 100.0);
            global.put("AVG_COST", gCnt == 0 ? 0 : Math.round(gCost / Math.max(1, m.size())));
            global.put("DENY_COUNT", gDeny);
            global.put("CALL_COUNT", gCnt);

            for (Map<String, Object> rule : rules) {
                String metric = str(rule, "metric", "METRIC");
                String target = str(rule, "target", "TARGET");
                String op = str(rule, "op", "OP");
                double threshold = ((Number) (rule.get("threshold") != null ? rule.get("threshold") : rule.get("THRESHOLD"))).doubleValue();
                // 计算当前值
                List<Double> cur = new ArrayList<>();
                List<String> targets = new ArrayList<>();
                if ("*".equals(target)) {
                    Object v = global.get(metric);
                    if (v != null) { cur.add(((Number) v).doubleValue()); targets.add("*"); }
                } else {
                    Map<String, Object> mm = m.get(target);
                    if (mm != null) {
                        Object v = mm.get(metric.equals("ERROR_RATE") ? "errorRate" : metric.equals("AVG_COST") ? "avgCost" : metric);
                        if (v != null) { cur.add(((Number) v).doubleValue()); targets.add(target); }
                    }
                }
                for (int i = 0; i < cur.size(); i++) {
                    boolean fired = ">".equals(op) ? cur.get(i) > threshold : cur.get(i) < threshold;
                    if (fired) {
                        // 同一规则同一目标 10 分钟内不重复告警（告警抑制）
                        int recent = meta.queryForObject(
                                "SELECT COUNT(*) FROM alert_log WHERE rule_id=? AND target=? AND create_time >= ?",
                                Integer.class, rule.get("id") != null ? rule.get("id") : rule.get("ID"),
                                targets.get(i), new java.sql.Timestamp(System.currentTimeMillis() - 600_000));
                        if (recent == 0) {
                            meta.update("INSERT INTO alert_log (rule_id, rule_name, level, metric, target, cur_value, threshold) " +
                                            "VALUES (?,?,?,?,?,?,?)",
                                    rule.get("id") != null ? rule.get("id") : rule.get("ID"),
                                    str(rule, "rule_name", "RULE_NAME"), str(rule, "level", "LEVEL"),
                                    metric, targets.get(i), String.valueOf(cur.get(i)), String.valueOf(threshold));
                            logSec("ALERT", str(rule, "level", "LEVEL"),
                                    "告警: " + str(rule, "rule_name", "RULE_NAME") + " " + metric + "=" + cur.get(i) + " " + op + " " + threshold,
                                    "", "", targets.get(i));
                        }
                    }
                }
            }
        } catch (Exception ignore) { }
    }

    // ============================================================
    // 服务健康检查（DS-OPS-04）：每 5 分钟对已发布服务探活
    // ============================================================

    @Scheduled(cron = "0 */5 * * * ?")
    public void healthCheck() {
        try {
            List<Map<String, Object>> apis = meta.queryForList(
                    "SELECT id, api_path FROM api_info WHERE env_status='PUBLISHED' AND status=1");
            for (Map<String, Object> api : apis) {
                long apiId = ((Number) (api.get("id") != null ? api.get("id") : api.get("ID"))).longValue();
                String path = str(api, "api_path", "API_PATH");
                // 取最近 10 分钟调用情况判断健康
                Integer fail = meta.queryForObject(
                        "SELECT COUNT(*) FROM api_log WHERE api_path=? AND status='FAIL' AND create_time >= ?",
                        Integer.class, path, new java.sql.Timestamp(System.currentTimeMillis() - 600_000));
                Integer total = meta.queryForObject(
                        "SELECT COUNT(*) FROM api_log WHERE api_path=? AND create_time >= ?",
                        Integer.class, path, new java.sql.Timestamp(System.currentTimeMillis() - 600_000));
                String status = "UP";
                if (total != null && total >= 5 && fail != null && fail * 2 >= total) status = "DOWN";
                int n = meta.queryForObject("SELECT COUNT(*) FROM health_check WHERE api_id=?", Integer.class, apiId);
                if (n == 0) {
                    meta.update("INSERT INTO health_check (api_id, api_path, status, last_ok, last_fail, fail_cnt) VALUES (?,?,?,?,?,?)",
                            apiId, path, status, "UP".equals(status) ? new Timestamp(System.currentTimeMillis()) : null,
                            "DOWN".equals(status) ? new Timestamp(System.currentTimeMillis()) : null,
                            fail == null ? 0 : fail);
                } else {
                    meta.update("UPDATE health_check SET status=?, last_ok=CASE WHEN ?='UP' THEN CURRENT_TIMESTAMP ELSE last_ok END, " +
                            "last_fail=CASE WHEN ?='DOWN' THEN CURRENT_TIMESTAMP ELSE last_fail END, fail_cnt=? WHERE api_id=?",
                            status, status, status, fail == null ? 0 : fail, apiId);
                }
            }
        } catch (Exception ignore) { }
    }

    /** 慢服务识别（DS-DEV-14）：取最近 24h 平均耗时 TOP */
    public List<Map<String, Object>> slowApis(int top) {
        return meta.queryForList(
                "SELECT api_path, COUNT(*) cnt, AVG(cost_ms) avg_cost, MAX(cost_ms) max_cost " +
                "FROM api_log WHERE create_time >= ? GROUP BY api_path ORDER BY avg_cost DESC LIMIT " + top,
                new java.sql.Timestamp(System.currentTimeMillis() - 86400_000L));
    }

    /** 安全/异常事件记录（DS-SEC-10,11） */
    public void logSec(String type, String level, String detail, String appKey, String clientIp, String apiPath) {
        try {
            meta.update("INSERT INTO sec_event (event_type, level, detail, app_key, client_ip, api_path) VALUES (?,?,?,?,?,?)",
                    type, level, detail == null ? "" : detail, appKey == null ? "" : appKey,
                    clientIp == null ? "" : clientIp, apiPath == null ? "" : apiPath);
        } catch (Exception ignore) { }
    }

    public String today() { return new SimpleDateFormat("yyyy-MM-dd").format(new Date()); }

    private long n(Map<String, Object> row, String... keys) {
        for (String k : keys) { Object v = row.get(k); if (v instanceof Number) return ((Number) v).longValue(); }
        return 0;
    }

    private String str(Map<String, Object> row, String... keys) {
        for (String k : keys) { Object v = row.get(k); if (v != null) return String.valueOf(v); }
        return "";
    }
}
