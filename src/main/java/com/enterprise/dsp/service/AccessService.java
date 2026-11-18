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
 * 订阅授权与审批服务（4.5 / DS-AUTH-01~09 + DS-GW-10）
 *   申请 -> 多角色会签审批 -> 生效；字段级授权范围、配额、期限、紧急停用
 */
@Service
public class AccessService {

    @Resource private MetaService metaService;
    private final JdbcTemplate meta;

    /** 认证令牌缓存（管理端） */
    public static final Map<String, Long> TOKENS = new ConcurrentHashMap<>();

    public AccessService(JdbcTemplate meta) { this.meta = meta; }

    // ============================================================
    // 订阅与授权（DS-AUTH-03~07）
    // ============================================================

    /** 提交订阅申请 */
    public long applySubscribe(long appId, long apiId, String fieldsScope, String quotaDaily,
                               String quotaMonthly, String expireDate, String applyDesc, String applyUser) {
        meta.update("INSERT INTO subscription (app_id, api_id, fields_scope, quota_daily, quota_monthly, expire_date, status, apply_desc) " +
                        "VALUES (?,?,?,?,?,?, 'PENDING', ?)",
                appId, apiId,
                fieldsScope == null || fieldsScope.isEmpty() ? "*" : fieldsScope,
                parseInt(quotaDaily, 10000), parseInt(quotaMonthly, 200000),
                expireDate == null || expireDate.isEmpty() ? null : expireDate, applyDesc);
        Long sid = meta.queryForObject("SELECT MAX(id) FROM subscription", Long.class);
        createApproval("SUBSCRIBE", sid, "服务订阅申请 #" + sid, applyDesc, applyUser, "OWNER,SEC");
        return sid;
    }

    /** 创建审批单（DS-AUTH-04 / DS-GW-10） */
    public long createApproval(String type, long bizId, String title, String detail, String applyUser, String needRoles) {
        meta.update("INSERT INTO approval (type, biz_id, title, detail, apply_user, need_roles, approved, status, opinions) " +
                        "VALUES (?,?,?,?,?,?,'','PENDING','[]')",
                type, bizId, title, detail == null ? "" : detail,
                applyUser == null ? "" : applyUser, needRoles);
        Long id = meta.queryForObject("SELECT MAX(id) FROM approval", Long.class);
        return id == null ? 0 : id;
    }

    /** 审批动作：approve / reject。会签模式：所有 need_roles 批准后通过 */
    public Map<String, Object> act(long approvalId, String action, String role, String user, String opinion) {
        Map<String, Object> row = findOne("approval", approvalId);
        if (row == null) return err("审批单不存在");
        String status = str(row, "status", "STATUS");
        if (!"PENDING".equals(status)) return err("审批单已处理: " + status);
        String needRoles = str(row, "need_roles", "NEED_ROLES");
        String approved = str(row, "approved", "APPROVED");

        if ("reject".equalsIgnoreCase(action)) {
            meta.update("UPDATE approval SET status='REJECTED', finish_time=CURRENT_TIMESTAMP, " +
                    "opinions = CONCAT(opinions, ?) WHERE id=?", "[{role:\"" + role + "\",user:\"" + user
                    + "\",op:\"" + (opinion == null ? "" : opinion.replace("\"", "")) + "\",reject:1}]", approvalId);
            onApprovalFinished(approvalId, false);
            Map<String, Object> r = ok("已驳回");
            return r;
        }
        // approve
        Set<String> got = new LinkedHashSet<>(Arrays.asList(approved.split(",")));
        got.add(role.toUpperCase());
        Set<String> need = new LinkedHashSet<>(Arrays.asList(needRoles.split(",")));
        need.removeIf(String::isEmpty);
        boolean adminPass = "ADMIN".equals(role.toUpperCase());   // ADMIN 一票通过/驳回
        if (!adminPass && !need.contains(role.toUpperCase())) return err("该审批需要以下角色之一会签: " + needRoles + "，当前角色: " + role);
        String nowApproved = String.join(",", got);
        boolean allDone = adminPass || got.containsAll(need);
        meta.update("UPDATE approval SET approved=?, status=?, opinions = CONCAT(opinions, ?) WHERE id=?",
                nowApproved, allDone ? "APPROVED" : "PENDING",
                "[{role:\"" + role + "\",user:\"" + user + "\",op:\"" + (opinion == null ? "" : opinion.replace("\"", "")) + "\"}]",
                approvalId);
        if (allDone) onApprovalFinished(approvalId, true);
        return ok(allDone ? "审批全部通过" : "已批准，等待其他角色会签");
    }

    /** 审批完成后的业务动作 */
    private void onApprovalFinished(long approvalId, boolean passed) {
        Map<String, Object> ap = findOne("approval", approvalId);
        if (ap == null) return;
        String type = str(ap, "type", "TYPE");
        long bizId = num(ap, "biz_id", "BIZ_ID");
        if ("PUBLISH".equals(type)) {
            meta.update("UPDATE api_info SET env_status=? WHERE id=?", passed ? "PUBLISHED" : "DRAFT", bizId);
        } else if ("SUBSCRIBE".equals(type)) {
            meta.update("UPDATE subscription SET status=?, update_time=CURRENT_TIMESTAMP WHERE id=?", passed ? "ACTIVE" : "REJECTED", bizId);
            if (passed) {
                Map<String, Object> sub = findOne("subscription", bizId);
                if (sub != null) {
                    long apiId = num(sub, "api_id", "API_ID");
                    meta.update("UPDATE api_info SET env_status='PUBLISHED' WHERE id=? AND env_status='DRAFT'", apiId);
                }
            }
        } else if ("OFFLINE".equals(type)) {
            if (passed) {
                meta.update("UPDATE api_info SET env_status='OFFLINE', status=0 WHERE id=?", bizId);
                meta.update("UPDATE subscription SET status='REVOKED' WHERE api_id=?", bizId);
            }
        } else if ("PLAINVIEW".equals(type)) {
            // 明文审批通过：写入 subscription.plain_fields
            if (passed) {
                Map<String, Object> ap2 = findOne("approval", approvalId);
                String fields = str(ap2, "detail", "DETAIL");   // detail 约定为字段清单
                meta.update("UPDATE subscription SET plain_fields=?, update_time=CURRENT_TIMESTAMP WHERE id=?", fields, bizId);
            }
        }
    }

    /** 订阅有效性校验（网关调用时） */
    public Map<String, Object> checkSubscription(long appId, long apiId) {
        List<Map<String, Object>> list = meta.queryForList(
                "SELECT * FROM subscription WHERE app_id=? AND api_id=? ORDER BY id DESC", appId, apiId);
        if (list.isEmpty()) return err("未订阅该服务（DS-AUTH-03：先申请后调用）");
        Map<String, Object> sub = list.get(0);
        String status = str(sub, "status", "STATUS");
        int emergency = (int) str2num(sub.get("emergency") != null ? sub.get("emergency") : sub.get("EMERGENCY"));
        if ("PENDING".equals(status)) return err("订阅申请审批中");
        if ("REJECTED".equals(status)) return err("订阅申请已被驳回");
        if ("REVOKED".equals(status) || emergency == 1) return err("授权已被停用（DS-AUTH-08）");
        if ("EXPIRED".equals(status)) return err("授权已过期（DS-AUTH-06）");
        Object expire = sub.get("expire_date") != null ? sub.get("expire_date") : sub.get("EXPIRE_DATE");
        if (expire instanceof java.sql.Date && ((java.sql.Date) expire).before(new java.util.Date())) {
            meta.update("UPDATE subscription SET status='EXPIRED' WHERE id=?", num(sub, "id", "ID"));
            return err("授权已过期（DS-AUTH-06）");
        }
        return sub;
    }

    // ============================================================
    // 配额计量（DS-BIZ-01,02）
    // ============================================================

    /** 调用前检查日/月配额；返回 null 表示通过，否则为错误消息 */
    public String checkQuota(long appId, long apiId, Map<String, Object> sub) {
        if (sub == null) return null;
        long qd = num(sub, "quota_daily", "QUOTA_DAILY");
        long qm = num(sub, "quota_monthly", "QUOTA_MONTHLY");
        if (qd <= 0 && qm <= 0) return null;
        String day = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String month = new SimpleDateFormat("yyyy-MM").format(new Date());
        // 当日/当月尚无计数行时返回 0（不能用 queryForObject，空结果会抛异常导致 500）
        Integer d = meta.queryForObject(
                "SELECT COALESCE(SUM(call_cnt),0) FROM quota_usage WHERE app_id=? AND api_id=? AND stat_day=?", Integer.class, appId, apiId, day);
        if (qd > 0 && d != null && d >= qd) return "已达日调用配额 " + qd + " 次（DS-BIZ-02）";
        Integer m = meta.queryForObject(
                "SELECT COALESCE(SUM(call_cnt),0) FROM quota_usage WHERE app_id=? AND api_id=? AND stat_month=?", Integer.class, appId, apiId, month);
        if (qm > 0 && m != null && m >= (int) qm) return "已达月调用配额 " + qm + " 次（DS-BIZ-02）";
        return null;
    }

    /** 调用后计数 */
    public void recordUsage(long appId, long apiId, boolean success, int rows, long costMs) {
        if (appId <= 0) return;
        String day = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String month = new SimpleDateFormat("yyyy-MM").format(new Date());
        int n = meta.update("UPDATE quota_usage SET call_cnt=call_cnt+1, fail_cnt=fail_cnt+?, rows_cnt=rows_cnt+?, cost_sum=cost_sum+? " +
                        "WHERE app_id=? AND api_id=? AND stat_day=?",
                success ? 0 : 1, rows, costMs, appId, apiId, day);
        if (n == 0) {
            try {
                meta.update("INSERT INTO quota_usage (app_id, api_id, stat_day, stat_month, call_cnt, fail_cnt, rows_cnt, cost_sum) VALUES (?,?,?,?,1,?,?,?)",
                        appId, apiId, day, month, success ? 0 : 1, rows, costMs);
            } catch (Exception ignore) { }
        }
    }

    // ============================================================
    // 操作审计（DS-SYS-05）
    // ============================================================

    public void logOp(String username, String action, String detail, String ip) {
        try {
            meta.update("INSERT INTO op_log (username, action, detail, client_ip) VALUES (?,?,?,?)",
                    username, action, detail == null ? "" : detail, ip == null ? "" : ip);
        } catch (Exception ignore) { }
    }

    // ============================================================
    // 到期扫描（DS-AUTH-06）
    // ============================================================

    @Scheduled(cron = "0 0 1 * * ?")
    public void expireScan() {
        meta.update("UPDATE subscription SET status='EXPIRED' WHERE status='ACTIVE' AND expire_date IS NOT NULL AND expire_date < CURRENT_DATE");
        meta.update("UPDATE app_token SET status=0 WHERE status=1 AND expire_at IS NOT NULL AND expire_at < CURRENT_TIMESTAMP");
    }

    // ============================================================
    // 工具
    // ============================================================

    public Map<String, Object> findOne(String table, long id) {
        List<Map<String, Object>> list = meta.queryForList("SELECT * FROM " + table + " WHERE id=?", id);
        return list.isEmpty() ? null : list.get(0);
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private long num(Map<String, Object> row, String... keys) {
        for (String k : keys) { Object v = row.get(k); if (v instanceof Number) return ((Number) v).longValue(); }
        return 0;
    }

    private String str(Map<String, Object> row, String... keys) {
        for (String k : keys) { Object v = row.get(k); if (v != null) return String.valueOf(v); }
        return "";
    }

    private double str2num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    private Map<String, Object> ok(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 0); m.put("message", msg);
        return m;
    }

    private Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 403); m.put("message", msg);
        return m;
    }

    @SuppressWarnings("unused")
    private Timestamp now() { return new Timestamp(System.currentTimeMillis()); }
}
