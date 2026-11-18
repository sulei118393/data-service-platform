package com.enterprise.dsp.service;

import com.enterprise.dsp.common.CryptoUtil;
import com.enterprise.dsp.dao.Repos;
import com.enterprise.dsp.datasource.DataSourceManager;
import com.enterprise.dsp.entity.DsInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 元数据与资产管理（DS-CAT-03~08）：
 *   元数据自动采集（手动 + 定时）、资产目录编目、敏感数据识别打标、血缘登记
 */
@Service
public class MetaService {

    @Resource private Repos repos;
    @Resource private DataSourceManager dsManager;
    @Resource private com.enterprise.dsp.common.DspProps props;
    private final JdbcTemplate meta;
    private final ObjectMapper om = new ObjectMapper();

    public MetaService(JdbcTemplate meta) { this.meta = meta; }

    // ============================================================
    // 初始化：内置脱敏规则库（DS-SEC-03）+ 默认资产目录
    // ============================================================

    @PostConstruct
    public void initRules() {
        Integer n = meta.queryForObject("SELECT COUNT(*) FROM mask_rule", Integer.class);
        if (n != null && n > 0) return;
        Object[][] rules = {
                {"姓名", "", "姓名,客户名,持卡人,cust_name,user_name,real_name", "NAME", "MASK", 1, 0, ""},
                {"证件号", "(身份证|证件|护照)[号码]?", "id_card,cert_no,idcard,identity", "IDCARD", "MASK", 6, 2, ""},
                {"手机号", "(手机|电话|联系)[号码]?", "mobile,phone,tel,contact", "PHONE", "MASK", 3, 4, ""},
                {"银行卡号", "(卡号|账号|账户)[号码]?", "bank_card,card_no,acct_no,account_no", "BANK", "MASK", 4, 4, ""},
                {"邮箱", "邮箱|邮件|e?mail", "email,mail_addr", "EMAIL", "MASK", 2, 0, ""},
                {"地址", "地址|住址|住desc", "addr,address", "ADDRESS", "MASK", 0, 6, ""},
                {"金额", "", "amount,balance,amt", "AMOUNT", "MASK", 1, 0, ""},
        };
        for (Object[] r : rules) {
            meta.update("INSERT INTO mask_rule (rule_name, match_regex, match_keys, rule_type, mask_type, keep_prefix, keep_suffix, replace_str, level, enabled) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,1)",
                    r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], 2);
        }
        Integer c = meta.queryForObject("SELECT COUNT(*) FROM asset_catalog", Integer.class);
        if (c != null && c == 0) {
            Object[][] cats = {{"客户域", "DOMAIN"}, {"账户域", "DOMAIN"}, {"交易域", "DOMAIN"},
                    {"产品域", "DOMAIN"}, {"公共维表", "THEME"}, {"贴源层", "LAYER"}, {"明细层", "LAYER"}, {"汇总层", "LAYER"}};
            for (Object[] c2 : cats) {
                meta.update("INSERT INTO asset_catalog (cat_name, cat_type) VALUES (?,?)", c2[0], c2[1]);
            }
        }
        Integer ar = meta.queryForObject("SELECT COUNT(*) FROM alert_rule", Integer.class);
        if (ar != null && ar == 0) {
            meta.update("INSERT INTO alert_rule (rule_name, metric, target, op, threshold, window_min, level) VALUES " +
                    "('服务错误率过高','ERROR_RATE','*','>',10,5,'CRITICAL')");
            meta.update("INSERT INTO alert_rule (rule_name, metric, target, op, threshold, window_min, level) VALUES " +
                    "('平均响应时间过高','AVG_COST','*','>',2000,5,'WARN')");
            meta.update("INSERT INTO alert_rule (rule_name, metric, target, op, threshold, window_min, level) VALUES " +
                    "('拒绝请求突增','DENY_COUNT','*','>',50,5,'WARN')");
        }
        Integer sc = meta.queryForObject("SELECT COUNT(*) FROM sys_config", Integer.class);
        if (sc != null && sc == 0) {
            meta.update("INSERT INTO sys_config (cfg_key, cfg_value, cfg_desc) VALUES ('mask.threshold','2','敏感等级≥该值返回时默认脱敏')");
            meta.update("INSERT INTO sys_config (cfg_key, cfg_value, cfg_desc) VALUES ('meta.auto.cron.enabled','1','元数据定时采集开关')");
            meta.update("INSERT INTO sys_config (cfg_key, cfg_value, cfg_desc) VALUES ('batch.export.dir','./exports','批量导出文件目录')");
        }
    }

    // ============================================================
    // 元数据采集（DS-CAT-03）
    // ============================================================

    /** 手动触发采集 */
    public Map<String, Object> collect(long dsId, boolean full) {
        long start = System.currentTimeMillis();
        Map<String, Object> res = new LinkedHashMap<>();
        meta.update("INSERT INTO meta_task (ds_id, task_type, status) VALUES (?,?,'RUNNING')", dsId, full ? "FULL" : "INCR");
        long taskId = meta.queryForObject("SELECT MAX(id) FROM meta_task", Long.class);
        try {
            DsInfo ds = getDs(dsId);
            if (ds == null) throw new IllegalArgumentException("数据源不存在");
            Class.forName(ds.driverClass());
            com.zaxxer.hikari.HikariDataSource pool = dsManager.getPool(ds.id, ds.driverClass(), ds.jdbcUrl(),
                    ds.username, CryptoUtil.aesDecrypt(ds.password, props.aesKey),
                    ds.poolSize == null ? 3 : ds.poolSize, ds.timeoutSec == null ? 60 : ds.timeoutSec, true);
            int tables = 0, columns = 0;
            try (Connection con = pool.getConnection()) {
                DatabaseMetaData md = con.getMetaData();
                String catalog = ("mysql".equals(ds.dsType) || "oceanbase".equals(ds.dsType) || "gbase8a".equals(ds.dsType))
                        ? ds.dbName : null;
                String schema = "oracle".equals(ds.dsType) ? ds.username.toUpperCase() : null;
                // 主键集合
                Set<String> pkCols = new HashSet<>();
                try (ResultSet rs = md.getPrimaryKeys(catalog, schema, "%")) {
                    while (rs.next()) pkCols.add(rs.getString("TABLE_NAME") + "\u0001" + rs.getString("COLUMN_NAME"));
                }
                // 外键集合
                Set<String> fkCols = new HashSet<>();
                try (ResultSet rs = md.getImportedKeys(catalog, schema, "%")) {
                    while (rs.next()) fkCols.add(rs.getString("TABLE_NAME") + "\u0001" + rs.getString("FKCOLUMN_NAME"));
                }
                // 索引列集合
                Set<String> idxCols = new HashSet<>();
                try (ResultSet rs = md.getIndexInfo(catalog, schema, "%", false, false)) {
                    while (rs.next()) {
                        String cn = rs.getString("COLUMN_NAME");
                        if (cn != null) idxCols.add(rs.getString("TABLE_NAME") + "\u0001" + cn);
                    }
                }
                // 表 + 字段
                List<String> tableNames = new ArrayList<>();
                try (ResultSet rs = md.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
                    while (rs.next() && tableNames.size() < 20000) {
                        String tbl = rs.getString("TABLE_NAME");
                        if (tbl == null) continue;
                        String type = rs.getString("TABLE_TYPE");
                        String remark = rs.getString("REMARKS");
                        tableNames.add(tbl);
                        upsertTable(dsId, tbl, type == null || type.contains("TABLE") ? "TABLE" : "VIEW", remark);
                        tables++;
                    }
                }
                for (String tbl : tableNames) {
                    try (ResultSet rs = md.getColumns(catalog, schema, tbl, "%")) {
                        while (rs.next()) {
                            String col = rs.getString("COLUMN_NAME");
                            String key = pkCols.contains(tbl + "\u0001" + col) ? "PK"
                                    : fkCols.contains(tbl + "\u0001" + col) ? "FK"
                                    : idxCols.contains(tbl + "\u0001" + col) ? "IDX" : "";
                            upsertColumn(dsId, tbl, col, rs.getString("TYPE_NAME"),
                                    rs.getInt("NULLABLE") == 1 ? 1 : 0, key, rs.getString("REMARKS"));
                            columns++;
                        }
                    }
                }
            }
            // 采集完成后做敏感识别
            int tagged = scanSensitive(dsId);
            long cost = System.currentTimeMillis() - start;
            meta.update("UPDATE meta_task SET status='SUCCESS', table_cnt=?, column_cnt=?, cost_ms=?, error_msg='' WHERE id=?",
                    tables, columns, (int) cost, taskId);
            res.put("tables", tables);
            res.put("columns", columns);
            res.put("tagged", tagged);
            res.put("costMs", cost);
            return res;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            meta.update("UPDATE meta_task SET status='FAIL', cost_ms=?, error_msg=? WHERE id=?",
                    (int) cost, e.getMessage() == null ? e.toString() : e.getMessage(), taskId);
            res.put("error", e.getMessage());
            return res;
        }
    }

    /** 定时采集：每天 02:30（DS-CAT-03 定时方式） */
    @Scheduled(cron = "0 30 2 * * ?")
    public void scheduledCollect() {
        String enabled = getCfg("meta.auto.cron.enabled", "1");
        if (!"1".equals(enabled)) return;
        for (Map<String, Object> row : meta.queryForList("SELECT id FROM ds_info WHERE status=1 AND ds_type <> 'redis'")) {
            try { collect(((Number) row.get("id")).longValue(), false); } catch (Exception ignore) { }
        }
    }

    private void upsertTable(long dsId, String tbl, String type, String remark) {
        int n = meta.queryForObject("SELECT COUNT(*) FROM meta_table WHERE ds_id=? AND table_name=?", Integer.class, dsId, tbl);
        if (n == 0) {
            meta.update("INSERT INTO meta_table (ds_id, table_name, table_type, comment, last_collect) VALUES (?,?,?,?,CURRENT_TIMESTAMP)",
                    dsId, tbl, type, remark == null ? "" : remark);
        } else {
            meta.update("UPDATE meta_table SET table_type=?, comment=?, last_collect=CURRENT_TIMESTAMP WHERE ds_id=? AND table_name=?",
                    type, remark == null ? "" : remark, dsId, tbl);
        }
    }

    private void upsertColumn(long dsId, String tbl, String col, String type, int nullable, String key, String remarks) {
        int n = meta.queryForObject("SELECT COUNT(*) FROM meta_column WHERE ds_id=? AND table_name=? AND col_name=?",
                Integer.class, dsId, tbl, col);
        if (n == 0) {
            meta.update("INSERT INTO meta_column (ds_id, table_name, col_name, data_type, nullable, col_key, remarks) VALUES (?,?,?,?,?,?,?)",
                    dsId, tbl, col, type == null ? "" : type, nullable, key, remarks == null ? "" : remarks);
        } else {
            meta.update("UPDATE meta_column SET data_type=?, nullable=?, col_key=?, remarks=? WHERE ds_id=? AND table_name=? AND col_name=?",
                    type == null ? "" : type, nullable, key, remarks == null ? "" : remarks, dsId, tbl, col);
        }
    }

    // ============================================================
    // 敏感数据识别与分级打标（DS-CAT-06 / DS-SEC-01）
    // ============================================================

    /** 按规则库对指定数据源的字段自动识别打标；返回打标字段数 */
    public int scanSensitive(long dsId) {
        List<Map<String, Object>> rules = meta.queryForList("SELECT * FROM mask_rule WHERE enabled=1");
        List<Pattern> pats = new ArrayList<>();
        List<List<String>> keys = new ArrayList<>();
        List<Object> levels = new ArrayList<>();
        List<Long> ruleIds = new ArrayList<>();
        for (Map<String, Object> r : rules) {
            String regex = String.valueOf(r.getOrDefault("MATCH_REGEX", r.get("match_regex")));
            String kw = String.valueOf(r.getOrDefault("MATCH_KEYS", r.get("match_keys")));
            pats.add(regex == null || regex.isEmpty() ? null : Pattern.compile(regex));
            keys.add(kw == null || kw.isEmpty() ? Collections.emptyList() : Arrays.asList(kw.split(",")));
            levels.add(r.getOrDefault("LEVEL", r.get("level")));
            ruleIds.add(((Number) r.getOrDefault("ID", r.get("id"))).longValue());
        }
        List<Map<String, Object>> cols = meta.queryForList(
                "SELECT id, col_name, remarks FROM meta_column WHERE ds_id=?", dsId);
        int tagged = 0;
        for (Map<String, Object> c : cols) {
            String name = String.valueOf(c.get("col_name") != null ? c.get("col_name") : c.get("COL_NAME")).toLowerCase();
            String remark = String.valueOf(c.get("remarks") != null ? c.get("remarks") : c.get("REMARKS")).toLowerCase();
            int level = 0; long ruleId = 0;
            for (int i = 0; i < pats.size(); i++) {
                boolean hit = false;
                Pattern p = pats.get(i);
                if (p != null && (p.matcher(name).find() || p.matcher(remark).find())) hit = true;
                if (!hit) for (String k : keys.get(i)) {
                    k = k.trim().toLowerCase();
                    if (!k.isEmpty() && (name.contains(k) || remark.contains(k))) { hit = true; break; }
                }
                if (hit) {
                    int lv = ((Number) levels.get(i)).intValue();
                    if (lv > level) { level = lv; ruleId = ruleIds.get(i); }
                }
            }
            meta.update("UPDATE meta_column SET level=?, rule_id=?, level_time=CURRENT_TIMESTAMP WHERE id=?",
                    level, ruleId, c.get("id") != null ? c.get("id") : c.get("ID"));
            if (level > 0) tagged++;
        }
        // 表级等级 = 字段最高等级
        meta.update("UPDATE meta_table t SET level = COALESCE((SELECT MAX(level) FROM meta_column c WHERE c.ds_id=t.ds_id AND c.table_name=t.table_name), 0) WHERE t.ds_id=?", dsId);
        return tagged;
    }

    // ============================================================
    // 血缘登记（DS-CAT-07）
    // ============================================================

    /** 从服务 SQL/wizard 解析表级血缘并登记 */
    public void saveLineage(long apiId, long dsId, String mode, String sqlText, String wizardJson) {
        meta.update("DELETE FROM lineage WHERE api_id=?", apiId);
        LinkedHashSet<String> tables = new LinkedHashSet<>();
        String cols = "";
        try {
            if ("wizard".equals(mode) && wizardJson != null) {
                Map<?, ?> w = om.readValue(wizardJson, Map.class);
                Object t = w.get("table");
                if (t != null) tables.add(String.valueOf(t));
                Object f = w.get("fields");
                if (f instanceof List) cols = String.join(",", (List<String>) f);
            } else if (sqlText != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(?i)\\bFROM\\s+([A-Za-z_][\\w.$]*|\\(\\s*SELECT)", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(sqlText);
                while (m.find()) {
                    String t = m.group(1);
                    if (!t.toUpperCase().startsWith("(")) tables.add(t);
                }
                java.util.regex.Matcher j = java.util.regex.Pattern
                        .compile("(?i)\\bJOIN\\s+([A-Za-z_][\\w.$]*)", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(sqlText);
                while (j.find()) tables.add(j.group(1));
            }
        } catch (Exception ignore) { }
        for (String t : tables) {
            meta.update("INSERT INTO lineage (api_id, ds_id, table_name, col_names, direction) VALUES (?,?,?,?,'SRC')",
                    apiId, dsId, t, cols);
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    public String getCfg(String key, String def) {
        try {
            List<Map<String, Object>> list = meta.queryForList("SELECT cfg_value FROM sys_config WHERE cfg_key=?", key);
            if (list.isEmpty()) return def;
            Object v = list.get(0).get("CFG_VALUE");
            return v == null || String.valueOf(v).isEmpty() ? def : String.valueOf(v);
        } catch (Exception e) { return def; }
    }

    public void setCfg(String key, String value) {
        meta.update("MERGE INTO sys_config (cfg_key, cfg_value, update_time) KEY(cfg_key) VALUES (?,?,CURRENT_TIMESTAMP)", key, value);
    }

    private DsInfo getDs(long dsId) {
        Map<String, Object> row = repos.ds.findById(dsId);
        if (row == null) return null;
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(normalizeKeys(row), DsInfo.class);
    }

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
}
