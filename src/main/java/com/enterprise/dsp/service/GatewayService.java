package com.enterprise.dsp.service;

import com.enterprise.dsp.common.DspProps;
import com.enterprise.dsp.common.Result;
import com.enterprise.dsp.common.SqlSecurityUtil;
import com.enterprise.dsp.dao.Repos;
import com.enterprise.dsp.datasource.DataSourceManager;
import com.enterprise.dsp.entity.ApiInfo;
import com.enterprise.dsp.entity.DsInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网关执行引擎：
 *   wizard 模式 —— 向导式条件构建 SQL（标识符白名单，全部 PreparedStatement 绑定）
 *   sql    模式 —— #{param} 占位符 SQL
 *   redis  模式 —— Redis 命令查询
 * 统一支持：参数校验、方言分页、结果缓存、限流、执行日志
 */
@Service
public class GatewayService {

    @Resource private Repos repos;
    @Resource private DataSourceManager dsManager;
    @Resource private DspProps props;
    @Resource private RateLimiter limiter;
    @Resource private ResponseCache cache;
    @Resource private AccessService access;
    @Resource private MonitorService monitor;
    @Resource private MaskingEngine masking;
    @Resource private com.enterprise.dsp.service.MetaService metaService;
    private final ObjectMapper om = new ObjectMapper();

    @PostConstruct
    public void init() {
        cache.configure(props.cacheMaxEntries);
        dsManager.loadDrivers(props.driversDir);
    }

    // ============================================================
    // 主入口（网关统一执行链：Mock -> 限流 -> 订阅/配额 -> 缓存 -> 熔断 -> 执行 -> 脱敏 -> 计量 -> 审计）
    // ============================================================

    public Result execute(ApiInfo api, Map<String, Object> callParams, String appKey, String clientIp, String traceId) {
        long start = System.currentTimeMillis();
        String logSql = "";
        String status = "SUCCESS";
        String error = "";
        int rows = 0;
        try {
            // 0. Mock 模式（DS-DEV-11）
            if (api.mockJson != null && !api.mockJson.trim().isEmpty()) {
                try {
                    Object mock = om.readValue(api.mockJson, Object.class);
                    status = "MOCK";
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("mock", true);
                    data.put("data", mock);
                    return Result.ok(data);
                } catch (Exception ignore) { }
            }

            // 1. 参数校验
            String err = validateParams(api, callParams);
            if (err != null) {
                status = "FAIL"; error = err;
                return Result.fail(400, err);
            }

            // 2. 限流（DS-GW-06）
            int limit = (api.rateLimit != null && api.rateLimit > 0) ? api.rateLimit : props.defaultRateLimit;
            if (!limiter.allow(api.apiPath + "|" + appKey, limit)) {
                status = "DENY"; error = "调用频率超限";
                monitor.logSec("RATE_LIMIT", "WARN", "限流触发 path=" + api.apiPath, appKey, clientIp, api.apiPath);
                return Result.fail(429, "调用频率超限，请稍后再试");
            }

            // 3. 熔断检查（DS-GW-07）
            String breakerKey = api.apiPath + "@" + (api.version == null ? "v1" : api.version);
            boolean halfOpen = monitor.isHalfOpen(breakerKey);
            if (!monitor.allow(breakerKey) && !halfOpen) {
                status = "DENY"; error = "服务熔断中";
                return Result.fail(503, "服务暂不可用（熔断保护中），请稍后再试");
            }

            // 4. 订阅授权与配额（DS-AUTH-05 / DS-BIZ-02；open 服务跳过）
            long appId = lookupAppId(appKey);
            Map<String, Object> sub = null;
            Set<String> plainFields = new HashSet<>();
            Set<String> fieldScope = null;
            if (!"open".equalsIgnoreCase(api.authType)) {
                if (appId <= 0) {
                    status = "FAIL"; error = "应用不存在";
                    return Result.fail(403, "应用不存在或未授权");
                }
                Map<String, Object> check = access.checkSubscription(appId, api.id);
                Object code = check.get("code");
                if (code instanceof Number && ((Number) code).intValue() != 0) {
                    status = "DENY"; error = String.valueOf(check.get("message"));
                    monitor.logSec("AUTH_DENY", "WARN", error, appKey, clientIp, api.apiPath);
                    return Result.fail(403, String.valueOf(check.get("message")));
                }
                sub = check;
                String quotaErr = access.checkQuota(appId, api.id, sub);
                if (quotaErr != null) {
                    status = "DENY"; error = quotaErr;
                    monitor.logSec("OVER_QUOTA", "WARN", quotaErr, appKey, clientIp, api.apiPath);
                    return Result.fail(429, quotaErr);
                }
                // 字段级授权范围（DS-AUTH-05）
                String scope = String.valueOf(sub.getOrDefault("fields_scope", sub.getOrDefault("FIELDS_SCOPE", "*")));
                if (!"*".equals(scope.trim())) {
                    fieldScope = new HashSet<>();
                    for (String f : scope.split(",")) fieldScope.add(f.trim().toLowerCase());
                }
                String pf = String.valueOf(sub.getOrDefault("plain_fields", sub.getOrDefault("PLAIN_FIELDS", "")));
                for (String f : pf.split(",")) if (!f.trim().isEmpty()) plainFields.add(f.trim().toLowerCase());
            }

            // 5. 缓存（DS-DEV-08）
            int ttl = api.cacheTtl == null ? 0 : api.cacheTtl;
            String cacheKey = api.apiPath + "|" + callParams;
            if (ttl > 0) {
                Object hit = cache.get(cacheKey);
                if (hit != null) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.putAll(((Result) hit).toMap());
                    m.put("fromCache", true);
                    status = "CACHE";
                    return Result.ok(m);
                }
            }

            // 6. 执行
            Result result;
            DsInfo ds = getDs(api.dsId);
            if (ds == null) {
                result = Result.fail(404, "API 绑定的数据源不存在或已删除");
                status = "FAIL"; error = "datasource not found";
            } else if ("redis".equals(ds.dsType) || "redis".equals(api.mode)) {
                result = execRedis(ds, api, callParams);
                logSql = redisDesc(api);
            } else if ("proxy".equals(api.mode)) {
                result = Result.fail(400, "接口代理服务请在网关外部转发层配置 proxy_url: " + api.proxyUrl);
                status = "FAIL"; error = "proxy mode";
            } else {
                result = execSql(ds, api, callParams);
            }

            if (result.code != 0) { status = "FAIL"; error = result.message; }
            if (result.data instanceof Map && ((Map<?, ?>) result.data).containsKey("rows")) {
                rows = ((List<?>) ((Map<?, ?>) result.data).get("rows")).size();
            }

            // 7. 字段级授权裁剪 + 动态脱敏（DS-AUTH-05 / DS-SEC-02）
            if (result.code == 0 && result.data instanceof Map) {
                applySecurity((Map<String, Object>) result.data, ds, api, fieldScope, plainFields);
            }

            // 8. 熔断计数
            if (result.code == 0) monitor.recordSuccess(breakerKey);
            else monitor.recordFailure(breakerKey);

            // 9. 计量（DS-BIZ-01）
            access.recordUsage(appId, api.id, result.code == 0, rows, System.currentTimeMillis() - start);

            if (result.code == 0 && ttl > 0) cache.put(cacheKey, result, ttl);
            return result;
        } catch (Exception e) {
            status = "FAIL";
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            monitor.recordFailure(api.apiPath);
            monitor.logSec("EXEC_ERROR", "WARN", "path=" + api.apiPath + " err=" + error, appKey, clientIp, api.apiPath);
            return Result.fail(500, "执行失败: " + error);
        } finally {
            long cost = System.currentTimeMillis() - start;
            Map<String, Object> brief = new LinkedHashMap<>(callParams);
            try {
                repos.saveLog(api.apiPath, appKey, om.writeValueAsString(brief), logSql, status, error, (int) cost, rows, clientIp, traceId, api.version);
            } catch (Exception ignore) { }
        }
    }

    /** 查应用 id */
    private long lookupAppId(String appKey) {
        if (appKey == null || appKey.isEmpty()) return -1;
        try {
            List<Map<String, Object>> list = repos.meta().queryForList("SELECT id FROM app_info WHERE app_key=?", appKey);
            if (list.isEmpty()) return -1;
            Object v = list.get(0).get("id") != null ? list.get(0).get("id") : list.get(0).get("ID");
            return v instanceof Number ? ((Number) v).longValue() : -1;
        } catch (Exception e) { return -1; }
    }

    /** 字段裁剪 + 动态脱敏 */
    private void applySecurity(Map<String, Object> data, DsInfo ds, ApiInfo api,
                               Set<String> fieldScope, Set<String> plainFields) {
        Object rowsObj = data.get("rows");
        if (!(rowsObj instanceof List) || ((List<?>) rowsObj).isEmpty()) return;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rowsObj;
        Map<String, Object> first = rows.get(0);

        // 字段级授权裁剪（DS-AUTH-05：未授权字段即使存在于底层表也不返回）
        if (fieldScope != null) {
            for (Map<String, Object> row : rows) {
                Set<String> keep = retainCaseInsensitive(row.keySet(), fieldScope);
                row.keySet().retainAll(keep);
            }
        }

        // 脱敏：列 -> [level, ruleId]
        Map<String, int[]> colMeta = new LinkedHashMap<>();
        int threshold = Integer.parseInt(metaService.getCfg("mask.threshold", "2"));
        try {
            if (ds != null && ds.id != null && ds.dsType != null && !"redis".equals(ds.dsType)) {
                Set<String> colNames = new LinkedHashSet<>();
                for (String c : first.keySet()) colNames.add(c);
                StringBuilder in = new StringBuilder();
                List<Object> args = new ArrayList<>();
                args.add(ds.id);
                for (String c : colNames) { if (in.length() > 0) in.append(","); in.append("?"); args.add(c.toLowerCase()); }
                List<Map<String, Object>> cols = repos.meta().queryForList(
                        "SELECT col_name, MAX(level) level, MIN(CASE WHEN rule_id > 0 THEN rule_id END) rule_id " +
                        "FROM meta_column WHERE ds_id=? AND LOWER(col_name) IN (" + in + ") GROUP BY col_name", args.toArray());
                for (Map<String, Object> c : cols) {
                    String col = String.valueOf(c.get("col_name") != null ? c.get("col_name") : c.get("COL_NAME"));
                    Object lv = c.get("level") != null ? c.get("level") : c.get("LEVEL");
                    Object rid = c.get("rule_id") != null ? c.get("rule_id") : c.get("RULE_ID");
                    int level = lv instanceof Number ? ((Number) lv).intValue() : 0;
                    long ruleId = rid instanceof Number ? ((Number) rid).longValue() : 0;
                    if (level >= threshold) colMeta.put(col, new int[]{level, (int) ruleId});
                }
            }
        } catch (Exception ignore) { }
        if (!colMeta.isEmpty()) {
            masking.maskRows(rows, colMeta, plainFields, threshold);
        }
    }

    private Set<String> retainCaseInsensitive(Set<String> keys, Set<String> allowed) {
        Set<String> keep = new LinkedHashSet<>();
        for (String k : keys) {
            if (allowed.contains(k.toLowerCase()) || k.equals("_pageNo") || k.equals("_pageSize")) keep.add(k);
        }
        return keep;
    }

    private String redisDesc(ApiInfo api) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = (Map<String, Object>) om.readValue(api.redisJson == null ? "{}" : api.redisJson, Map.class);
            return String.valueOf(cfg.getOrDefault("command", "")) + " " + cfg.getOrDefault("keyTemplate", "");
        } catch (Exception e) { return "redis"; }
    }

    private DsInfo getDs(Long dsId) throws Exception {
        Map<String, Object> row = repos.ds.findById(dsId);
        if (row == null) return null;
        DsInfo ds = om.convertValue(normalizeKeys(row), DsInfo.class);
        return ds;
    }

    /** H2 返回的列是下划线，转驼峰再映射到 POJO */
    private Map<String, Object> normalizeKeys(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
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

    // ============================================================
    // 参数校验
    // ============================================================

    private String validateParams(ApiInfo api, Map<String, Object> callParams) throws Exception {
        if (api.paramsJson == null || api.paramsJson.isEmpty()) return null;
        List<Map<String, Object>> defs = om.readValue(api.paramsJson,
                om.getTypeFactory().constructCollectionType(List.class, Map.class));
        for (Map<String, Object> def : defs) {
            String name = String.valueOf(def.get("name"));
            boolean required = Boolean.TRUE.equals(def.get("required")) || "1".equals(String.valueOf(def.get("required")));
            Object v = callParams.containsKey(name) ? callParams.get(name)
                    : def.get("defaultValue");
            if (required && (v == null || String.valueOf(v).isEmpty())) {
                return "缺少必填参数: " + name + (def.get("desc") != null ? "（" + def.get("desc") + "）" : "");
            }
            if (v == null) continue;
            String type = String.valueOf(def.getOrDefault("type", "string"));
            if ("number".equals(type) || "int".equals(type)) {
                try { Double.parseDouble(String.valueOf(v)); }
                catch (NumberFormatException e) { return "参数 " + name + " 应为数字，实际: " + v; }
            }
        }
        return null;
    }

    // ============================================================
    // SQL 执行（wizard / sql）
    // ============================================================

    private Result execSql(DsInfo ds, ApiInfo api, Map<String, Object> callParams) throws Exception {
        String driver = ds.driverClass();
        if (driver != null && !driver.isEmpty()) {
            try { Class.forName(driver); } catch (ClassNotFoundException e) {
                return Result.fail(500, "未找到驱动 " + driver + "，请将驱动 jar 放入 dsp.drivers-dir 目录: " + props.driversDir);
            }
        }
        HikariDataSource pool = dsManager.getPool(ds.id, driver, ds.jdbcUrl(),
                ds.username, decryptPwd(ds.password), ds.poolSize, ds.timeoutSec, ds.readOnly != null && ds.readOnly == 1);

        BuiltSql built;
        if ("wizard".equals(api.mode)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> wizard = (Map<String, Object>) om.readValue(api.wizardJson == null ? "{}" : api.wizardJson, Map.class);
            built = buildWizardSql(wizard, callParams);
        } else {
            built = buildSqlMode(api.sqlText, api.paramsJson, callParams);
        }
        if (built.error != null) return Result.fail(400, built.error);

        // 方言分页
        boolean paged = false;
        long pageNo = 1, pageSize = 20;
        Object pn = callParams.remove("_pageNo"), ps = callParams.remove("_pageSize");
        if (pn != null || ps != null) {
            try { pageNo = Long.parseLong(String.valueOf(pn == null ? 1 : pn)); } catch (Exception ignore) { }
            try { pageSize = Long.parseLong(String.valueOf(ps == null ? 20 : ps)); } catch (Exception ignore) { }
            pageSize = Math.min(Math.max(pageSize, 1), 1000);
            paged = true;
        }
        // 结果集管控（DS-DEV-04）：单服务可配最大行数与最大查询时间
        int maxRows = (api.maxRows != null && api.maxRows > 0) ? api.maxRows : props.maxRows;
        int timeoutSec = Math.max(1, (api.timeoutMs != null && api.timeoutMs > 0 ? api.timeoutMs : props.queryTimeoutSeconds * 1000) / 1000);
        String sql = built.sql;
        List<Object> args = new ArrayList<>(built.args);
        if (paged) {
            BuiltSql page = dialectPage(ds.dsType, sql, pageNo, pageSize);
            if (page.error != null) return Result.fail(400, page.error);
            sql = page.sql;
            args.addAll(page.args);
            // 计数
            long total = countRows(pool, built.sql, built.args, timeoutSec);
            Result r = runQuery(pool, sql, args, maxRows, timeoutSec);
            if (r.code == 0 && r.data instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) r.data;
                data.put("pageNo", pageNo);
                data.put("pageSize", pageSize);
                data.put("total", total);
            }
            return r;
        }
        return runQuery(pool, sql, args, maxRows, timeoutSec);
    }

    private String decryptPwd(String cipher) {
        try {
            return (cipher == null || cipher.isEmpty()) ? "" : com.enterprise.dsp.common.CryptoUtil.aesDecrypt(cipher, props.aesKey);
        } catch (Exception e) { return ""; }
    }

    /** 方言分页：MySQL/OceanBase/GBase8a 用 LIMIT；Oracle 用 ROWNUM 包装 */
    private BuiltSql dialectPage(String dsType, String sql, long pageNo, long pageSize) {
        BuiltSql b = new BuiltSql();
        long offset = (pageNo - 1) * pageSize;
        if ("oracle".equals(dsType)) {
            b.sql = "SELECT * FROM (SELECT t__.*, ROWNUM rn__ FROM (" + sql + ") t__ WHERE ROWNUM <= ?) WHERE rn__ > ?";
            b.args.add(offset + pageSize);
            b.args.add(offset);
        } else {
            b.sql = sql + " LIMIT ? OFFSET ?";
            b.args.add(pageSize);
            b.args.add(offset);
        }
        return b;
    }

    private long countRows(HikariDataSource pool, String sql, List<Object> args, int timeout) throws Exception {
        String countSql = "SELECT COUNT(*) FROM (" + sql + ") cnt__";
        try (Connection con = pool.getConnection();
             PreparedStatement ps = con.prepareStatement(countSql)) {
            ps.setQueryTimeout(timeout);
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0;
    }

    private Result runQuery(HikariDataSource pool, String sql, List<Object> args, int maxRows, int timeout) throws Exception {
        try (Connection con = pool.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setQueryTimeout(timeout);
            ps.setMaxRows(maxRows + 1);
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int colCount = md.getColumnCount();
                List<String> cols = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) cols.add(md.getColumnLabel(i));
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next() && rows.size() < maxRows) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) row.put(cols.get(i - 1), rs.getObject(i));
                    rows.add(row);
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("columns", cols);
                data.put("rows", rows);
                return Result.ok(data);
            }
        } catch (java.sql.SQLException e) {
            return Result.fail(500, "SQL 执行错误: " + e.getMessage());
        }
    }

    // ============================================================
    // 向导式 SQL 构建
    // ============================================================

    static class BuiltSql {
        String sql;
        List<Object> args = new ArrayList<>();
        String error;
    }

    @SuppressWarnings("unchecked")
    private BuiltSql buildWizardSql(Map<String, Object> wizard, Map<String, Object> callParams) {
        BuiltSql b = new BuiltSql();
        String table = String.valueOf(wizard.getOrDefault("table", ""));
        if (!SqlSecurityUtil.isValidIdentifier(table)) {
            b.error = "非法表名: " + table;
            return b;
        }
        // 字段
        List<String> fields = new ArrayList<>();
        Object fobj = wizard.get("fields");
        if (fobj instanceof List && !((List<?>) fobj).isEmpty()) {
            for (Object f : (List<?>) fobj) {
                String fname = String.valueOf(f).trim();
                // 支持 "col AS alias" 写法
                if (fname.contains(" ")) {
                    String[] parts = fname.split("\\s+(?i)as\\s+");
                    if (parts.length == 2 && SqlSecurityUtil.isValidIdentifier(parts[0]) && SqlSecurityUtil.isValidIdentifier(parts[1])) {
                        fields.add(parts[0] + " AS " + parts[1]);
                        continue;
                    }
                    b.error = "非法字段定义: " + fname;
                    return b;
                }
                if (fname.equals("*")) { fields.add("*"); continue; }
                if (!SqlSecurityUtil.isValidIdentifier(fname)) {
                    b.error = "非法字段名: " + fname;
                    return b;
                }
                fields.add(fname);
            }
        }
        if (fields.isEmpty()) fields.add("*");
        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", fields)).append(" FROM ").append(table);

        // 条件
        StringBuilder where = new StringBuilder();
        Object conds = wizard.get("conditions");
        if (conds instanceof List) {
            for (Object c : (List<?>) conds) {
                if (!(c instanceof Map)) continue;
                Map<String, Object> cond = (Map<String, Object>) c;
                String err = SqlSecurityUtil.checkCondition(cond);
                if (err != null) { b.error = err; return b; }
                String field = String.valueOf(cond.get("field")).trim();
                String op = String.valueOf(cond.get("op")).trim().toUpperCase();
                String param = String.valueOf(cond.getOrDefault("param", field));
                boolean required = Boolean.TRUE.equals(cond.get("required")) || "1".equals(String.valueOf(cond.get("required")));
                Object v = callParams.containsKey(param) ? callParams.get(param) : cond.get("defaultValue");

                if ("IS NULL".equals(op) || "IS NOT NULL".equals(op)) {
                    where.append(where.length() == 0 ? "" : " AND ").append(field).append(" ").append(op);
                    continue;
                }
                if (v == null || String.valueOf(v).isEmpty()) {
                    if (required) {
                        b.error = "缺少必填条件参数: " + param;
                        return b;
                    }
                    continue;   // 非必填条件，参数为空时跳过
                }
                if ("IN".equals(op) || "NOT IN".equals(op)) {
                    String[] items = String.valueOf(v).split(",");
                    if (items.length == 0) { b.error = "IN 条件参数不能为空: " + param; return b; }
                    StringBuilder ph = new StringBuilder("(");
                    for (int i = 0; i < items.length; i++) {
                        ph.append(i > 0 ? ",?" : "?");
                        b.args.add(tryNumber(items[i].trim()));
                    }
                    ph.append(")");
                    where.append(where.length() == 0 ? "" : " AND ").append(field).append(" ").append(op).append(" ").append(ph);
                    continue;
                }
                if ("BETWEEN".equals(op)) {
                    String[] parts = String.valueOf(v).split(",");
                    if (parts.length != 2) { b.error = "BETWEEN 条件参数需为 'min,max' 格式: " + param; return b; }
                    where.append(where.length() == 0 ? "" : " AND ")
                         .append(field).append(" BETWEEN ? AND ?");
                    b.args.add(tryNumber(parts[0].trim()));
                    b.args.add(tryNumber(parts[1].trim()));
                    continue;
                }
                // 普通 = != > >= < <= LIKE
                where.append(where.length() == 0 ? "" : " AND ").append(field).append(" ").append(op).append(" ?");
                if (op.contains("LIKE")) {
                    String sv = String.valueOf(v);
                    String mode = String.valueOf(cond.getOrDefault("likeMode", "both"));
                    if ("left".equals(mode)) sv = "%" + sv;
                    else if ("right".equals(mode)) sv = sv + "%";
                    else if ("both".equals(mode)) sv = "%" + sv + "%";
                    b.args.add(sv);
                } else {
                    b.args.add(tryNumber(String.valueOf(v)));
                }
            }
        }
        if (where.length() > 0) sql.append(" WHERE ").append(where);

        // 分组 / 排序 / HAVING
        String groupBy = str(wizard.get("groupBy"));
        if (!groupBy.isEmpty()) {
            for (String g : groupBy.split(",")) {
                if (!SqlSecurityUtil.isValidIdentifier(g.trim())) { b.error = "非法 GROUP BY 字段: " + g; return b; }
            }
            sql.append(" GROUP BY ").append(groupBy);
        }
        String having = str(wizard.get("having"));     // HAVING 允许简单聚合表达式（管理员配置）
        if (!having.isEmpty()) sql.append(" HAVING ").append(having);
        String orderBy = str(wizard.get("orderBy"));
        if (!orderBy.isEmpty()) {
            for (String o : orderBy.split(",")) {
                String t = o.trim();
                String[] parts = t.split("\\s+");
                if (!SqlSecurityUtil.isValidIdentifier(parts[0])) { b.error = "非法排序字段: " + o; return b; }
                if (parts.length == 2 && !parts[1].equalsIgnoreCase("ASC") && !parts[1].equalsIgnoreCase("DESC")) {
                    b.error = "非法排序方向: " + o; return b;
                }
            }
            sql.append(" ORDER BY ").append(orderBy);
        }
        b.sql = sql.toString();
        return b;
    }

    // ============================================================
    // SQL 模式构建
    // ============================================================

    private BuiltSql buildSqlMode(String sqlText, String paramsJson, Map<String, Object> callParams) throws Exception {
        BuiltSql b = new BuiltSql();
        String err = SqlSecurityUtil.checkSqlMode(sqlText);
        if (err != null) { b.error = err; return b; }
        Object[] parsed = SqlSecurityUtil.parseSqlParams(sqlText);
        b.sql = (String) parsed[0];
        List<String> names = (List<String>) parsed[1];

        // 参数默认值
        if (paramsJson != null && !paramsJson.isEmpty()) {
            List<Map<String, Object>> defs = om.readValue(paramsJson,
                    om.getTypeFactory().constructCollectionType(List.class, Map.class));
            for (Map<String, Object> def : defs) {
                String n = String.valueOf(def.get("name"));
                if (!callParams.containsKey(n) && def.get("defaultValue") != null) {
                    callParams.put(n, def.get("defaultValue"));
                }
            }
        }
        for (String n : names) {
            Object v = callParams.get(n);
            b.args.add(v == null ? null : tryNumber(String.valueOf(v)));
        }
        return b;
    }

    // ============================================================
    // Redis 执行
    // ============================================================

    private Result execRedis(DsInfo ds, ApiInfo api, Map<String, Object> callParams) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) om.readValue(api.redisJson == null ? "{}" : api.redisJson, Map.class);
        String command = String.valueOf(cfg.getOrDefault("command", "GET")).trim().toUpperCase();
        String key = renderTemplate(String.valueOf(cfg.getOrDefault("keyTemplate", "")), callParams);
        if (key.isEmpty()) return Result.fail(400, "Redis key 不能为空");

        JedisPoolHolder holder = jedisHolder(ds);
        try (redis.clients.jedis.Jedis jedis = holder.pool.getResource()) {
            switch (command) {
                case "GET": {
                    String v = jedis.get(key);
                    return Result.ok(single("value", v));
                }
                case "HGET": {
                    String field = renderTemplate(String.valueOf(cfg.getOrDefault("fieldTemplate", "")), callParams);
                    String v = jedis.hget(key, field);
                    return Result.ok(single("value", v));
                }
                case "HGETALL":
                    return Result.ok(jedis.hgetAll(key));
                case "LRANGE": {
                    long start = numParam(callParams, "start", 0), end = numParam(callParams, "end", -1);
                    return Result.ok(jedis.lrange(key, start, end));
                }
                case "SMEMBERS":
                    return Result.ok(jedis.smembers(key));
                case "ZREVRANGE": {
                    long start = numParam(callParams, "start", 0), end = numParam(callParams, "end", 9);
                    return Result.ok(jedis.zrevrange(key, start, end));
                }
                case "SCAN": {
                    String pattern = key.replace('*', '*');
                    java.util.List<String> keys = new ArrayList<>();
                    String cursor = "0";
                    int batch = 100;
                    for (int i = 0; i < 20; i++) {
                        redis.clients.jedis.resps.ScanResult<String> sr = jedis.scan(cursor,
                                new redis.clients.jedis.params.ScanParams().match(pattern).count(batch));
                        keys.addAll(sr.getResult());
                        cursor = sr.getCursor();
                        if ("0".equals(cursor) || keys.size() >= 1000) break;
                    }
                    return Result.ok(single("keys", keys));
                }
                case "TTL":
                    return Result.ok(single("ttl", jedis.ttl(key)));
                case "EXISTS":
                    return Result.ok(single("exists", jedis.exists(key)));
                default:
                    return Result.fail(400, "不支持的 Redis 命令: " + command + "（支持 GET/HGET/HGETALL/LRANGE/SMEMBERS/ZREVRANGE/SCAN/TTL/EXISTS）");
            }
        } catch (Exception e) {
            return Result.fail(500, "Redis 执行错误: " + e.getMessage());
        }
    }

    private Map<String, Object> single(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private long numParam(Map<String, Object> params, String name, long def) {
        Object v = params.get(name);
        if (v == null) return def;
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    /** 模板渲染：user:{id} -> user:123 */
    private String renderTemplate(String tpl, Map<String, Object> params) {
        if (tpl == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(\\w+)}").matcher(tpl);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String v = String.valueOf(params.getOrDefault(m.group(1), ""));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ============================================================
    // Redis 池持有（懒加载）
    // ============================================================

    private final Map<Long, JedisPoolHolder> redisMap = new LinkedHashMap<>();

    static class JedisPoolHolder {
        JedisPoolHolder(String host, int port, String pass, int size) {
            redis.clients.jedis.JedisPoolConfig cfg = new redis.clients.jedis.JedisPoolConfig();
            cfg.setMaxTotal(Math.max(2, size));
            pool = (pass == null || pass.isEmpty())
                    ? new redis.clients.jedis.JedisPool(cfg, host, port, 5000)
                    : new redis.clients.jedis.JedisPool(cfg, host, port, 5000, pass);
        }
        final redis.clients.jedis.JedisPool pool;
    }

    private JedisPoolHolder jedisHolder(DsInfo ds) {
        synchronized (redisMap) {
            JedisPoolHolder h = redisMap.get(ds.id);
            if (h == null) {
                h = new JedisPoolHolder(ds.host, ds.port, decryptPwd(ds.password), ds.poolSize == null ? 5 : ds.poolSize);
                redisMap.put(ds.id, h);
            }
            return h;
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    private Object tryNumber(String s) {
        if (s == null) return null;
        try {
            if (s.matches("-?\\d+")) return Long.parseLong(s);
            if (s.matches("-?\\d+\\.\\d+")) return Double.parseDouble(s);
        } catch (Exception ignore) { }
        return s;
    }

    private String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
}
