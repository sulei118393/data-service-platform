package com.enterprise.dsp.controller;

import com.enterprise.dsp.common.AuthInterceptor;
import com.enterprise.dsp.common.Result;
import com.enterprise.dsp.dao.Repos;
import com.enterprise.dsp.entity.ApiInfo;
import com.enterprise.dsp.service.AccessService;
import com.enterprise.dsp.service.BatchService;
import com.enterprise.dsp.service.MaskingEngine;
import com.enterprise.dsp.service.MetaService;
import com.enterprise.dsp.service.MonitorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台扩展模块管理接口（需求 4.1/4.3/4.5/4.6/4.7/4.8/4.9/4.10/4.11）
 */
@RestController
@RequestMapping("/dsp/api")
public class ManageController {

    @Resource private Repos repos;
    @Resource private MetaService metaService;
    @Resource private AccessService access;
    @Resource private MonitorService monitor;
    @Resource private BatchService batch;
    @Resource private MaskingEngine masking;
    @Resource private com.enterprise.dsp.service.GatewayService gateway;
    @Resource private com.enterprise.dsp.service.SysMetricsService sysMetrics;
    private final JdbcTemplate meta;
    private final ObjectMapper om = new ObjectMapper();

    public ManageController(JdbcTemplate meta) { this.meta = meta; }

    /** 兼容 snake_case / camelCase 两种请求键名（UI 表单提交 snake_case） */
    private Map<String, Object> normalizeBodyKeys(Map<String, Object> body) {
        if (body == null) return new LinkedHashMap<>();
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : body.entrySet()) {
            String k = e.getKey();
            if (k != null && k.contains("_")) {
                StringBuilder sb = new StringBuilder();
                for (String p : k.split("_")) {
                    if (p.isEmpty()) continue;
                    if (sb.length() == 0) sb.append(p);
                    else sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
                }
                k = sb.toString();
            }
            m.put(k, e.getValue());
        }
        return m;
    }

    // ============================================================
    // 元数据与资产（DS-CAT-03~08）
    // ============================================================

    /** 触发元数据采集（手动） */
    @PostMapping("/meta/collect/{dsId}")
    public Result collect(@PathVariable long dsId, @RequestParam(defaultValue = "1") int full) {
        return Result.ok(metaService.collect(dsId, full == 1));
    }

    @GetMapping("/meta/tasks")
    public Result metaTasks(@RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(page("meta_task", "1=1", new Object[]{}, "id DESC", pageNo, pageSize));
    }

    @GetMapping("/meta/tables")
    public Result metaTables(@RequestParam(required = false) Long dsId, @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) Integer level,
                             @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        StringBuilder w = new StringBuilder("1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (dsId != null) { w.append(" AND ds_id=?"); args.add(dsId); }
        if (keyword != null && !keyword.isEmpty()) {
            w.append(" AND (table_name LIKE ? OR comment LIKE ?)");
            args.add("%" + keyword + "%"); args.add("%" + keyword + "%");
        }
        if (level != null) { w.append(" AND level=?"); args.add(level); }
        return Result.ok(page("meta_table", w.toString(), args.toArray(), "ds_id, table_name", pageNo, pageSize));
    }

    @GetMapping("/meta/columns")
    public Result metaColumns(@RequestParam Long dsId, @RequestParam String table) {
        return Result.ok(meta.queryForList(
                "SELECT * FROM meta_column WHERE ds_id=? AND table_name=? ORDER BY col_name", dsId, table));
    }

    /** 字段敏感等级人工调整（DS-CAT-06 人工打标），可选同时指定命中的脱敏规则 */
    @PostMapping("/meta/level")
    public Result setLevel(@RequestBody Map<String, Object> body) {
        long id = Long.parseLong(String.valueOf(body.get("id")));
        int level = Integer.parseInt(String.valueOf(body.get("level")));
        Object rid = body.get("ruleId");
        if (rid != null && !String.valueOf(rid).isEmpty()) {
            meta.update("UPDATE meta_column SET level=?, rule_id=?, level_time=CURRENT_TIMESTAMP WHERE id=?",
                    level, Long.parseLong(String.valueOf(rid)), id);
        } else {
            meta.update("UPDATE meta_column SET level=?, level_time=CURRENT_TIMESTAMP WHERE id=?", level, id);
        }
        opLog(body, "调整字段敏感等级", "colId=" + id + " level=" + level + " ruleId=" + rid);
        return Result.ok();
    }

    // ---- 资产目录 ----

    @GetMapping("/asset/catalog")
    public Result catalog() {
        return Result.ok(meta.queryForList("SELECT * FROM asset_catalog ORDER BY cat_type, sort_no, id"));
    }

    @PostMapping("/asset/catalog/save")
    public Result saveCatalog(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("catName", ""));
        if (name.isEmpty()) return Result.fail("目录名称不能为空");
        Object id = body.get("id");
        if (id == null || String.valueOf(id).isEmpty()) {
            meta.update("INSERT INTO asset_catalog (parent_id, cat_name, cat_type, sort_no) VALUES (?,?,?,?)",
                    body.getOrDefault("parentId", 0), name, body.getOrDefault("catType", "DOMAIN"),
                    body.getOrDefault("sortNo", 0));
        } else {
            meta.update("UPDATE asset_catalog SET parent_id=?, cat_name=?, cat_type=?, sort_no=? WHERE id=?",
                    body.getOrDefault("parentId", 0), name, body.getOrDefault("catType", "DOMAIN"),
                    body.getOrDefault("sortNo", 0), id);
        }
        opLog(body, "维护资产目录", name);
        return Result.ok();
    }

    @PostMapping("/asset/catalog/delete/{id}")
    public Result delCatalog(@PathVariable long id) {
        Integer used = meta.queryForObject("SELECT COUNT(*) FROM meta_table WHERE catalog_id=?", Integer.class, id);
        if (used != null && used > 0) return Result.fail("目录下仍挂有 " + used + " 个资产，请先移出");
        meta.update("DELETE FROM asset_catalog WHERE id=?", id);
        return Result.ok();
    }

    /** 资产编目/打标/责任人（DS-CAT-04,05,08） */
    @PostMapping("/asset/tag")
    public Result tagAsset(@RequestBody Map<String, Object> body) {
        long dsId = Long.parseLong(String.valueOf(body.get("dsId")));
        String table = String.valueOf(body.get("tableName"));
        meta.update("UPDATE meta_table SET catalog_id=?, tags=?, owner=?, svc_ready=?, refresh_freq=? " +
                        "WHERE ds_id=? AND table_name=?",
                body.get("catalogId"), body.getOrDefault("tags", ""), body.getOrDefault("owner", ""),
                body.getOrDefault("svcReady", 0), body.getOrDefault("refreshFreq", ""), dsId, table);
        opLog(body, "资产编目", dsId + "." + table);
        return Result.ok();
    }

    /** 资产全文检索（DS-CAT-04） */
    @GetMapping("/asset/search")
    public Result assetSearch(@RequestParam String keyword) {
        String k = "%" + keyword + "%";
        List<Map<String, Object>> tables = meta.queryForList(
                "SELECT t.*, (SELECT COUNT(*) FROM meta_column c WHERE c.ds_id=t.ds_id AND c.table_name=t.table_name) col_cnt " +
                "FROM meta_table t WHERE t.table_name LIKE ? OR t.comment LIKE ? OR t.tags LIKE ? LIMIT 50", k, k, k);
        List<Map<String, Object>> cols = meta.queryForList(
                "SELECT ds_id, table_name, col_name, remarks, biz_name, level FROM meta_column " +
                "WHERE col_name LIKE ? OR remarks LIKE ? OR biz_name LIKE ? LIMIT 50", k, k, k);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tables", tables);
        r.put("columns", cols);
        return Result.ok(r);
    }

    /** 血缘查询（DS-CAT-07）：服务 -> 来源表 */
    @GetMapping("/lineage/api/{apiId}")
    public Result lineageOfApi(@PathVariable long apiId) {
        return Result.ok(meta.queryForList("SELECT * FROM lineage WHERE api_id=?", apiId));
    }

    /** 血缘反查（DS-CAT-07）：表被哪些服务使用 */
    @GetMapping("/lineage/table/{table}")
    public Result lineageOfTable(@PathVariable String table) {
        List<Map<String, Object>> lin = meta.queryForList(
                "SELECT l.*, a.api_name, a.env_status FROM lineage l LEFT JOIN api_info a ON a.id=l.api_id WHERE l.table_name=?", table);
        return Result.ok(lin);
    }

    // ============================================================
    // 脱敏规则（DS-SEC-02,03）
    // ============================================================

    @GetMapping("/mask/rules")
    public Result maskRules() {
        return Result.ok(meta.queryForList("SELECT * FROM mask_rule ORDER BY id"));
    }

    @PostMapping("/mask/save")
    public Result saveMaskRule(@RequestBody Map<String, Object> rawBody) {
        Map<String, Object> body = normalizeBodyKeys(rawBody);
        Object id = body.get("id");
        if (id == null || String.valueOf(id).isEmpty()) {
            meta.update("INSERT INTO mask_rule (rule_name, match_regex, match_keys, rule_type, mask_type, keep_prefix, keep_suffix, replace_str, level, enabled) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    body.get("ruleName"), body.getOrDefault("matchRegex", ""), body.getOrDefault("matchKeys", ""),
                    body.get("ruleType"), body.get("maskType"), body.getOrDefault("keepPrefix", 0),
                    body.getOrDefault("keepSuffix", 0), body.getOrDefault("replaceStr", ""),
                    body.getOrDefault("level", 2), body.getOrDefault("enabled", 1));
        } else {
            meta.update("UPDATE mask_rule SET rule_name=?, match_regex=?, match_keys=?, rule_type=?, mask_type=?, " +
                            "keep_prefix=?, keep_suffix=?, replace_str=?, level=?, enabled=? WHERE id=?",
                    body.get("ruleName"), body.getOrDefault("matchRegex", ""), body.getOrDefault("matchKeys", ""),
                    body.get("ruleType"), body.get("maskType"), body.getOrDefault("keepPrefix", 0),
                    body.getOrDefault("keepSuffix", 0), body.getOrDefault("replaceStr", ""),
                    body.getOrDefault("level", 2), body.getOrDefault("enabled", 1), id);
        }
        opLog(body, "维护脱敏规则", String.valueOf(body.get("ruleName")));
        return Result.ok();
    }

    @PostMapping("/mask/delete/{id}")
    public Result delMaskRule(@PathVariable long id) {
        meta.update("DELETE FROM mask_rule WHERE id=?", id);
        return Result.ok();
    }

    /** 脱敏效果预览 */
    @PostMapping("/mask/preview")
    public Result maskPreview(@RequestBody Map<String, Object> body) {
        int ruleId = Integer.parseInt(String.valueOf(body.get("ruleId")));
        String value = String.valueOf(body.get("value"));
        List<Map<String, Object>> rules = meta.queryForList("SELECT * FROM mask_rule WHERE id=?", ruleId);
        if (rules.isEmpty()) return Result.fail("规则不存在");
        Map<String, Object> r = rules.get(0);
        Object[] rule = new Object[]{
                r.get("rule_type"), r.get("mask_type"),
                r.get("keep_prefix"), r.get("keep_suffix"), r.get("replace_str")};
        return Result.ok(masking.mask(value, rule));
    }

    // ============================================================
    // 发布/下线/版本（DS-DEV-12,13 / DS-GW-05,10,11）
    // ============================================================

    /** 提交发布审批 */
    @PostMapping("/api/publish/{id}")
    public Result publishApi(@PathVariable long id, HttpServletRequest req) {
        Map<String, Object> api = repos.api.findById(id);
        if (api == null) return Result.fail("API 不存在");
        meta.update("UPDATE api_info SET env_status='PENDING' WHERE id=?", id);
        String user = currentUser(req);
        long aid = access.createApproval("PUBLISH", id, "服务发布申请: " + api.get("api_name"),
                "服务: " + api.get("api_name") + " / 路径: " + api.get("api_path") + " / 风险等级: " + api.get("risk_level")
                        + " / SLA: " + api.get("sla_desc"), user,
                "HIGH".equals(api.get("risk_level")) ? "OWNER,SEC,ADMIN" : "OWNER,ADMIN");
        opLog(req, "提交发布审批", "apiId=" + id + " approvalId=" + aid);
        return Result.ok(aid);
    }

    /** 提交下线申请（DS-GW-11） */
    @PostMapping("/api/offline/{id}")
    public Result offlineApi(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        String user = currentUser(req);
        String reason = body == null ? "" : String.valueOf(body.getOrDefault("reason", ""));
        long aid = access.createApproval("OFFLINE", id, "服务下线申请 #" + id, reason, user, "ADMIN");
        opLog(req, "提交下线申请", "apiId=" + id);
        return Result.ok(aid);
    }

    @GetMapping("/api/versions/{id}")
    public Result apiVersions(@PathVariable long id) {
        return Result.ok(meta.queryForList("SELECT id, api_id, version, changed_by, change_desc, create_time " +
                "FROM api_version WHERE api_id=? ORDER BY id DESC", id));
    }

    /** 版本回滚（DS-DEV-13） */
    @PostMapping("/api/rollback/{id}/{version}")
    public Result rollback(@PathVariable long id, @PathVariable String version, HttpServletRequest req) {
        List<Map<String, Object>> snaps = meta.queryForList(
                "SELECT * FROM api_version WHERE api_id=? AND version=? ORDER BY id DESC LIMIT 1", id, version);
        if (snaps.isEmpty()) return Result.fail("版本不存在");
        try {
            ApiInfo snap = om.readValue(String.valueOf(snaps.get(0).get("snapshot") != null
                    ? snaps.get(0).get("snapshot") : snaps.get(0).get("SNAPSHOT")), ApiInfo.class);
            snap.id = id;
            repos.api.updateById(id, snap);
            opLog(req, "版本回滚", "apiId=" + id + " -> " + version);
            return Result.ok("已回滚到 " + version);
        } catch (Exception e) {
            return Result.fail("回滚失败: " + e.getMessage());
        }
    }

    // ============================================================
    // 订阅授权（DS-AUTH-03~09）
    // ============================================================

    @PostMapping("/sub/apply")
    public Result applySub(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        long appId = Long.parseLong(String.valueOf(body.get("appId")));
        long apiId = Long.parseLong(String.valueOf(body.get("apiId")));
        long sid = access.applySubscribe(appId, apiId,
                String.valueOf(body.getOrDefault("fieldsScope", "*")),
                String.valueOf(body.getOrDefault("quotaDaily", "10000")),
                String.valueOf(body.getOrDefault("quotaMonthly", "200000")),
                String.valueOf(body.getOrDefault("expireDate", "")),
                String.valueOf(body.getOrDefault("applyDesc", "")), currentUser(req));
        opLog(req, "提交订阅申请", "appId=" + appId + " apiId=" + apiId);
        return Result.ok(sid);
    }

    /** 明文查看申请（DS-SEC-04）：detail 约定为免脱敏字段清单 */
    @PostMapping("/sub/plainview")
    public Result applyPlainView(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        long subId = Long.parseLong(String.valueOf(body.get("subId")));
        String fields = String.valueOf(body.getOrDefault("plainFields", ""));
        long aid = access.createApproval("PLAINVIEW", subId, "敏感字段明文查看申请 #" + subId, fields, currentUser(req), "SEC,ADMIN");
        opLog(req, "提交明文查看申请", "subId=" + subId + " fields=" + fields);
        return Result.ok(aid);
    }

    @GetMapping("/sub/list")
    public Result subList(@RequestParam(required = false) Long appId, @RequestParam(required = false) String status) {
        StringBuilder w = new StringBuilder("1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (appId != null) { w.append(" AND app_id=?"); args.add(appId); }
        if (status != null && !status.isEmpty()) { w.append(" AND status=?"); args.add(status); }
        List<Map<String, Object>> rows = meta.queryForList(
                "SELECT s.*, a.app_name, p.api_name, p.api_path, p.version FROM subscription s " +
                "LEFT JOIN app_info a ON a.id=s.app_id LEFT JOIN api_info p ON p.id=s.api_id " +
                "WHERE " + w + " ORDER BY s.id DESC LIMIT 500", args.toArray());
        return Result.ok(rows);
    }

    /** 紧急停用（DS-AUTH-08） */
    @PostMapping("/sub/emergency/{id}")
    public Result emergencyStop(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        meta.update("UPDATE subscription SET emergency=1, status='REVOKED', update_time=CURRENT_TIMESTAMP WHERE id=?", id);
        monitor.logSec("EMERGENCY_STOP", "HIGH", "紧急停用订阅 #" + id + " 原因: " + (body == null ? "" : body.get("reason")), "", "", "");
        opLog(req, "紧急停用订阅", "subId=" + id);
        return Result.ok("已紧急停用");
    }

    /** 续期（DS-AUTH-06）；紧急停用的授权不允许续期恢复，必须重新申请订阅（DS-AUTH-08） */
    @PostMapping("/sub/renew/{id}")
    public Result renew(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> sub = access.findOne("subscription", id);
        if (sub == null) return Result.fail("订阅不存在");
        Object em = sub.get("emergency") != null ? sub.get("emergency") : sub.get("EMERGENCY");
        if (em instanceof Number && ((Number) em).intValue() == 1) {
            return Result.fail(403, "授权已被紧急停用（DS-AUTH-08），不允许续期恢复，请重新申请订阅");
        }
        meta.update("UPDATE subscription SET expire_date=?, status='ACTIVE', update_time=CURRENT_TIMESTAMP WHERE id=?",
                body.get("expireDate"), id);
        opLog(req, "授权续期", "subId=" + id + " -> " + body.get("expireDate"));
        return Result.ok();
    }

    // ============================================================
    // 审批中心（DS-AUTH-04 / DS-GW-10）
    // ============================================================

    @GetMapping("/approval/list")
    public Result approvalList(@RequestParam(required = false) String status, @RequestParam(required = false) String type) {
        StringBuilder w = new StringBuilder("1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isEmpty()) { w.append(" AND status=?"); args.add(status); }
        if (type != null && !type.isEmpty()) { w.append(" AND type=?"); args.add(type); }
        return Result.ok(page("approval", w.toString(), args.toArray(), "id DESC", 1, 200));
    }

    /** 审批动作（通过/驳回），按当前用户角色会签 */
    @PostMapping("/approval/act")
    public Result approvalAct(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String token = req.getHeader("X-Token");
        String user = AuthInterceptor.TOKEN_USER.getOrDefault(token, "");
        List<String> roles = AuthInterceptor.rolesOf(user);
        // 审批角色匹配：优先 OWNER>SEC>DEV>ADMIN? 实际用用户拥有的首个匹配角色
        Map<String, Object> row = access.findOne("approval", Long.parseLong(String.valueOf(body.get("id"))));
        if (row == null) return Result.fail("审批单不存在");
        String needRoles = String.valueOf(row.get("need_roles") != null ? row.get("need_roles") : row.get("NEED_ROLES"));
        String role = null;
        for (String r : needRoles.split(",")) {
            if (roles.contains(r.trim())) { role = r.trim(); break; }
        }
        // ADMIN 总管理员拥有一票审批权（会签旁路）
        if (role == null && roles.contains("ADMIN")) role = "ADMIN";
        if (role == null) return Result.fail(403, "当前角色 " + roles + " 无权审批（需要 " + needRoles + "）");
        Result r = Result.ok(access.act(Long.parseLong(String.valueOf(body.get("id"))),
                String.valueOf(body.getOrDefault("action", "approve")), role, user,
                String.valueOf(body.getOrDefault("opinion", ""))));
        opLog(req, "审批-" + body.get("action"), "id=" + body.get("id") + " role=" + role);
        return r;
    }

    // ============================================================
    // 监控告警（DS-OPS-01,03,04 / DS-DEV-14）
    // ============================================================

    @GetMapping("/monitor/metrics")
    public Result metrics(@RequestParam(defaultValue = "10") int window) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("metrics", monitor.metrics(window));
        r.put("slow", monitor.slowApis(10));
        return Result.ok(r);
    }

    /** 服务器系统级指标：CPU/内存/磁盘/JVM/线程/GC + 1 小时趋势（DS-OPS 服务器监控） */
    @GetMapping("/sys/metrics")
    public Result sysMetrics() { return Result.ok(sysMetrics.snapshot()); }

    @GetMapping("/monitor/health")
    public Result health() {
        return Result.ok(meta.queryForList("SELECT * FROM health_check ORDER BY status DESC, api_path"));
    }

    @GetMapping("/alert/rules")
    public Result alertRules() { return Result.ok(meta.queryForList("SELECT * FROM alert_rule ORDER BY id")); }

    @PostMapping("/alert/save")
    public Result saveAlert(@RequestBody Map<String, Object> rawBody) {
        Map<String, Object> body = normalizeBodyKeys(rawBody);
        Object id = body.get("id");
        if (id == null || String.valueOf(id).isEmpty()) {
            meta.update("INSERT INTO alert_rule (rule_name, metric, target, op, threshold, window_min, level, enabled) VALUES (?,?,?,?,?,?,?,?)",
                    body.get("ruleName"), body.get("metric"), body.getOrDefault("target", "*"),
                    body.getOrDefault("op", ">"), body.getOrDefault("threshold", 0),
                    body.getOrDefault("windowMin", 5), body.getOrDefault("level", "WARN"), body.getOrDefault("enabled", 1));
        } else {
            meta.update("UPDATE alert_rule SET rule_name=?, metric=?, target=?, op=?, threshold=?, window_min=?, level=?, enabled=? WHERE id=?",
                    body.get("ruleName"), body.get("metric"), body.getOrDefault("target", "*"),
                    body.getOrDefault("op", ">"), body.getOrDefault("threshold", 0),
                    body.getOrDefault("windowMin", 5), body.getOrDefault("level", "WARN"), body.getOrDefault("enabled", 1), id);
        }
        opLog(body, "维护告警规则", String.valueOf(body.get("ruleName")));
        return Result.ok();
    }

    @PostMapping("/alert/delete/{id}")
    public Result delAlert(@PathVariable long id) { meta.update("DELETE FROM alert_rule WHERE id=?", id); return Result.ok(); }

    @GetMapping("/alert/logs")
    public Result alertLogs() { return Result.ok(meta.queryForList("SELECT * FROM alert_log ORDER BY id DESC LIMIT 200")); }

    @PostMapping("/alert/ack/{id}")
    public Result ackAlert(@PathVariable long id, HttpServletRequest req) {
        meta.update("UPDATE alert_log SET status='ACK', ack_by=? WHERE id=?", currentUser(req), id);
        return Result.ok();
    }

    @GetMapping("/sec/events")
    public Result secEvents() { return Result.ok(meta.queryForList("SELECT * FROM sec_event ORDER BY id DESC LIMIT 200")); }

    // ============================================================
    // 计量报表（DS-BIZ-01~03）
    // ============================================================

    @GetMapping("/biz/usage")
    public Result usage(@RequestParam(required = false) Long appId, @RequestParam(required = false) Long apiId) {
        StringBuilder w = new StringBuilder("1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (appId != null) { w.append(" AND q.app_id=?"); args.add(appId); }
        if (apiId != null) { w.append(" AND q.api_id=?"); args.add(apiId); }
        List<Map<String, Object>> rows = meta.queryForList(
                "SELECT q.stat_day, q.app_id, a.app_name, q.api_id, p.api_name, q.call_cnt, q.fail_cnt, q.rows_cnt, q.cost_sum " +
                "FROM quota_usage q LEFT JOIN app_info a ON a.id=q.app_id LEFT JOIN api_info p ON p.id=q.api_id " +
                "WHERE " + w + " ORDER BY q.stat_day DESC, q.call_cnt DESC LIMIT 500", args.toArray());
        return Result.ok(rows);
    }

    @GetMapping("/biz/report")
    public Result bizReport() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("byApp", meta.queryForList(
                "SELECT a.app_name, SUM(q.call_cnt) total, SUM(q.fail_cnt) fails, SUM(q.rows_cnt) rows_sum " +
                "FROM quota_usage q JOIN app_info a ON a.id=q.app_id GROUP BY a.app_name ORDER BY total DESC LIMIT 50"));
        r.put("byApi", meta.queryForList(
                "SELECT p.api_name, p.api_path, SUM(q.call_cnt) total, SUM(q.fail_cnt) fails " +
                "FROM quota_usage q JOIN api_info p ON p.id=q.api_id GROUP BY p.api_name, p.api_path ORDER BY total DESC LIMIT 50"));
        r.put("byMonth", meta.queryForList(
                "SELECT stat_month, SUM(call_cnt) total FROM quota_usage GROUP BY stat_month ORDER BY stat_month DESC LIMIT 24"));
        return Result.ok(r);
    }

    // ============================================================
    // 批量任务（4.9）
    // ============================================================

    @GetMapping("/batch/list")
    public Result batchList() { return Result.ok(meta.queryForList("SELECT * FROM batch_task ORDER BY id DESC")); }

    @PostMapping("/batch/save")
    public Result batchSave(@RequestBody Map<String, Object> rawBody) {
        Map<String, Object> body = normalizeBodyKeys(rawBody);   // 兼容 sql_text/task_name/file_format 等 snake_case 键
        Object sqlRaw = body.containsKey("sqlText") ? body.get("sqlText") : body.get("sql_text");
        String sql = String.valueOf(sqlRaw == null ? "" : sqlRaw);
        String err = com.enterprise.dsp.common.SqlSecurityUtil.checkSqlMode(sql);
        if (err != null) return Result.fail(400, err);
        Object id = body.get("id");
        if (id == null || String.valueOf(id).isEmpty()) {
            meta.update("INSERT INTO batch_task (task_name, ds_id, sql_text, file_name, file_format, charset, schedule, encrypt, watermark, owner) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    body.get("taskName"), body.get("dsId"), sql, body.getOrDefault("fileName", ""),
                    body.getOrDefault("fileFormat", "CSV"), body.getOrDefault("charset", "UTF-8"),
                    body.getOrDefault("schedule", ""), body.getOrDefault("encrypt", 0),
                    body.getOrDefault("watermark", ""), body.getOrDefault("owner", ""));
        } else {
            meta.update("UPDATE batch_task SET task_name=?, ds_id=?, sql_text=?, file_name=?, file_format=?, charset=?, schedule=?, encrypt=?, watermark=?, owner=? WHERE id=?",
                    body.get("taskName"), body.get("dsId"), sql, body.getOrDefault("fileName", ""),
                    body.getOrDefault("fileFormat", "CSV"), body.getOrDefault("charset", "UTF-8"),
                    body.getOrDefault("schedule", ""), body.getOrDefault("encrypt", 0),
                    body.getOrDefault("watermark", ""), body.getOrDefault("owner", ""), id);
        }
        opLog(body, "维护批量任务", String.valueOf(body.get("taskName")));
        return Result.ok();
    }

    @PostMapping("/batch/delete/{id}")
    public Result batchDelete(@PathVariable long id) { meta.update("DELETE FROM batch_task WHERE id=?", id); return Result.ok(); }

    @PostMapping("/batch/run/{id}")
    public Result batchRun(@PathVariable long id, HttpServletRequest req) {
        Result r = Result.ok(batch.runNow(id, currentUser(req)));
        opLog(req, "执行批量任务", "taskId=" + id);
        return r;
    }

    @GetMapping("/batch/runs/{taskId}")
    public Result batchRuns(@PathVariable long taskId) {
        return Result.ok(meta.queryForList("SELECT * FROM batch_run WHERE task_id=? ORDER BY id DESC LIMIT 50", taskId));
    }

    @PostMapping("/batch/reconcile/{id}")
    public Result batchReconcile(@PathVariable long id) { return Result.ok(batch.reconcile(id)); }

    /** 导出文件下载（鉴权由拦截器完成：/batch/ 需要 ADMIN/DEV 角色） */
    @GetMapping("/batch/download/{runId}")
    public void download(@PathVariable long runId, javax.servlet.http.HttpServletResponse resp) throws Exception {
        Map<String, Object> run = access.findOne("batch_run", runId);
        if (run == null) { resp.setStatus(404); return; }
        String file = String.valueOf(run.get("file_path") != null ? run.get("file_path") : run.get("FILE_PATH"));
        java.io.File f = new java.io.File(file);
        if (!f.exists()) { resp.setStatus(404); return; }
        resp.setContentType("application/octet-stream");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + f.getName() + "\"");
        java.nio.file.Files.copy(f.toPath(), resp.getOutputStream());
    }

    // ============================================================
    // 开发者门户（4.10）
    // ============================================================

    /** 服务市场（DS-PORTAL-01）：已发布服务目录 */
    @GetMapping("/portal/catalog")
    public Result portalCatalog(@RequestParam(required = false) String keyword) {
        StringBuilder w = new StringBuilder("env_status='PUBLISHED' AND status=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isEmpty()) {
            w.append(" AND (api_name LIKE ? OR remark LIKE ? OR api_path LIKE ?)");
            args.add("%" + keyword + "%"); args.add("%" + keyword + "%"); args.add("%" + keyword + "%");
        }
        return Result.ok(page("api_info", w.toString(), args.toArray(), "id DESC", 1, 100));
    }

    /** 接口文档自动生成（DS-PORTAL-02） */
    @GetMapping("/portal/doc/{id}")
    public Result apiDoc(@PathVariable long id) {
        Map<String, Object> api = repos.api.findById(id);
        if (api == null) return Result.fail("API 不存在");
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("apiName", api.get("api_name"));
        doc.put("path", "/dsapi/" + api.get("api_path"));
        doc.put("method", api.get("method"));
        doc.put("version", api.get("version"));
        doc.put("sla", api.get("sla_desc"));
        doc.put("remark", api.get("remark"));
        doc.put("authType", api.get("auth_type"));
        doc.put("rateLimit", api.get("rate_limit"));
        doc.put("maxRows", api.get("max_rows"));
        // 入参
        Object paramsJson = api.get("params_json");
        java.util.List<Map<String, Object>> params = new java.util.ArrayList<>();
        try {
            if (paramsJson != null && !String.valueOf(paramsJson).isEmpty()) {
                params = om.readValue(String.valueOf(paramsJson),
                        om.getTypeFactory().constructCollectionType(List.class, Map.class));
            }
        } catch (Exception ignore) { }
        doc.put("requestParams", params);
        // 出参：来自元数据或向导字段
        java.util.List<Map<String, Object>> outs = new java.util.ArrayList<>();
        try {
            Object wizardJson = api.get("wizard_json");
            if (wizardJson != null && String.valueOf(wizardJson).contains("table")) {
                Map<?, ?> w = om.readValue(String.valueOf(wizardJson), Map.class);
                Object dsId = api.get("ds_id");
                Object tbl = w.get("table");
                Object fields = w.get("fields");
                if (dsId != null && tbl != null) {
                    StringBuilder sql = new StringBuilder(
                            "SELECT col_name, data_type, remarks, level FROM meta_column WHERE ds_id=? AND table_name=?");
                    if (fields instanceof List && !((List<?>) fields).isEmpty() && !((List<?>) fields).contains("*")) {
                        sql.append(" AND col_name IN (");
                        boolean first = true;
                        for (Object f : (List<?>) fields) {
                            if (!first) sql.append(",");
                            sql.append("'").append(String.valueOf(f).replace("'", "")).append("'");
                            first = false;
                        }
                        sql.append(")");
                    }
                    outs = meta.queryForList(sql.toString(), dsId, tbl);
                }
            }
        } catch (Exception ignore) { }
        doc.put("responseFields", outs);
        doc.put("errorCodes", ERROR_CODES);
        doc.put("example", "curl -X POST '" + api.get("api_path") + "' 前缀 http://<host>:8086/dsapi/\n" +
                "Header: X-App-Key / X-App-Secret / X-Timestamp（签名=md5(appKey+secret+ts)）");
        return Result.ok(doc);
    }

    private static final List<Map<String, Object>> ERROR_CODES = new java.util.ArrayList<Map<String, Object>>() {{
        add(err("400", "参数校验失败", "调用方"));
        add(err("401", "鉴权失败", "调用方"));
        add(err("403", "未订阅/未授权/未发布", "权限"));
        add(err("429", "限流或配额超限", "管控"));
        add(err("500", "服务执行异常", "服务"));
        add(err("503", "熔断降级中", "管控"));
    }};

    private static Map<String, Object> err(String code, String desc, String layer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code); m.put("desc", desc); m.put("layer", layer);
        return m;
    }

    /** 在线调试（DS-PORTAL-03）：走网关执行链但不计配额 */
    @PostMapping("/portal/debug/{id}")
    public Result portalDebug(@PathVariable long id, @RequestBody(required = false) Map<String, Object> params) {
        Map<String, Object> row = repos.api.findById(id);
        if (row == null) return Result.fail("API 不存在");
        ApiInfo api = new ApiInfo();
        api.id = id;
        api.apiPath = String.valueOf(row.get("api_path"));
        api.mode = String.valueOf(row.get("mode"));
        api.authType = "open";
        Object dsId = row.get("ds_id");
        api.dsId = dsId instanceof Number ? ((Number) dsId).longValue() : null;
        api.wizardJson = String.valueOf(row.get("wizard_json") == null ? "" : row.get("wizard_json"));
        api.sqlText = String.valueOf(row.get("sql_text") == null ? "" : row.get("sql_text"));
        api.redisJson = String.valueOf(row.get("redis_json") == null ? "" : row.get("redis_json"));
        api.paramsJson = String.valueOf(row.get("params_json") == null ? "" : row.get("params_json"));
        api.cacheTtl = 0;
        api.maxRows = 100;
        api.timeoutMs = 10000;
        if (params == null) params = new java.util.HashMap<>();
        return gateway.execute(api, params, "debug", "portal", "portal-debug");
    }

    @GetMapping("/portal/notices")
    public Result notices() { return Result.ok(meta.queryForList("SELECT * FROM notice ORDER BY id DESC LIMIT 50")); }

    // ============================================================
    // 系统管理（4.11）
    // ============================================================

    @GetMapping("/user/list")
    public Result userList() {
        return Result.ok(meta.queryForList("SELECT id, username, real_name, role, status, create_time FROM sys_user ORDER BY id"));
    }

    @PostMapping("/user/save")
    public Result userSave(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String username = String.valueOf(body.getOrDefault("username", ""));
        String password = String.valueOf(body.getOrDefault("password", ""));
        String role = String.valueOf(body.getOrDefault("role", "DEV"));
        Object id = body.get("id");
        if (id == null || String.valueOf(id).isEmpty()) {
            if (username.isEmpty() || password.isEmpty()) return Result.fail("用户名与密码不能为空");
            meta.update("INSERT INTO sys_user (username, password, real_name, role) VALUES (?,?,?,?)",
                    username, com.enterprise.dsp.common.CryptoUtil.sha256(password),
                    body.getOrDefault("realName", ""), role);
        } else {
            if (password.isEmpty()) {
                meta.update("UPDATE sys_user SET real_name=?, role=? WHERE id=?",
                        body.getOrDefault("realName", ""), role, id);
            } else {
                meta.update("UPDATE sys_user SET password=?, real_name=?, role=? WHERE id=?",
                        com.enterprise.dsp.common.CryptoUtil.sha256(password), body.getOrDefault("realName", ""), role, id);
            }
        }
        opLog(req, "维护用户", username + " role=" + role);
        return Result.ok();
    }

    @PostMapping("/user/delete/{id}")
    public Result userDelete(@PathVariable long id, HttpServletRequest req) {
        meta.update("DELETE FROM sys_user WHERE id=?", id);
        opLog(req, "删除用户", "id=" + id);
        return Result.ok();
    }

    @GetMapping("/config/list")
    public Result configList() { return Result.ok(meta.queryForList("SELECT * FROM sys_config ORDER BY cfg_key")); }

    @PostMapping("/config/save")
    public Result configSave(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        metaService.setCfg(String.valueOf(body.get("cfgKey")), String.valueOf(body.getOrDefault("cfgValue", "")));
        opLog(req, "参数配置变更", body.get("cfgKey") + "=" + body.get("cfgValue"));
        return Result.ok();
    }

    @GetMapping("/oplog")
    public Result opLogList(@RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "30") int pageSize) {
        return Result.ok(page("op_log", "1=1", new Object[]{}, "id DESC", pageNo, pageSize));
    }

    @PostMapping("/notice/save")
    public Result noticeSave(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        meta.update("INSERT INTO notice (title, content, level, publisher) VALUES (?,?,?,?)",
                body.get("title"), body.getOrDefault("content", ""), body.getOrDefault("level", "INFO"), currentUser(req));
        opLog(req, "发布公告", String.valueOf(body.get("title")));
        return Result.ok();
    }

    // ============================================================
    // 工具
    // ============================================================

    private Map<String, Object> page(String table, String where, Object[] args, String orderBy, int pageNo, int pageSize) {
        where = where == null || where.isEmpty() ? "1=1" : where;
        int total = meta.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, args);
        List<Map<String, Object>> rows = meta.queryForList(
                "SELECT * FROM " + table + " WHERE " + where + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?",
                java.util.stream.Stream.concat(java.util.Arrays.stream(args), java.util.stream.Stream.of(pageSize, (pageNo - 1) * pageSize)).toArray());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", total); r.put("pageNo", pageNo); r.put("pageSize", pageSize); r.put("rows", rows);
        return r;
    }

    private String currentUser(HttpServletRequest req) {
        String token = req.getHeader("X-Token");
        return AuthInterceptor.TOKEN_USER.getOrDefault(token, "");
    }

    private void opLog(HttpServletRequest req, String action, String detail) {
        access.logOp(currentUser(req), action, detail, req.getRemoteAddr());
    }

    private void opLog(Map<String, Object> body, String action, String detail) {
        access.logOp(String.valueOf(body.getOrDefault("_user", "")), action, detail, "");
    }
}
