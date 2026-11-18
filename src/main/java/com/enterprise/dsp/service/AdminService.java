package com.enterprise.dsp.service;

import com.enterprise.dsp.common.AuthInterceptor;
import com.enterprise.dsp.common.CryptoUtil;
import com.enterprise.dsp.common.DspProps;
import com.enterprise.dsp.common.Result;
import com.enterprise.dsp.dao.Repos;
import com.enterprise.dsp.datasource.DataSourceManager;
import com.enterprise.dsp.entity.ApiInfo;
import com.enterprise.dsp.entity.AppInfo;
import com.enterprise.dsp.entity.DsInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

/** 管理端服务：登录 / 数据源 / API / 应用 管理 */
@Service
public class AdminService {

    @Resource private Repos repos;
    @Resource private DataSourceManager dsManager;
    @Resource private DspProps props;
    @Resource private ResponseCache cache;
    @Resource private com.enterprise.dsp.service.MetaService metaService;
    private final ObjectMapper om = new ObjectMapper();

    // ---------------- 登录 ----------------

    public Result login(String username, String password) {
        Map<String, Object> user = repos.findUser(username);
        if (user == null || !CryptoUtil.sha256(password).equals(String.valueOf(user.get("password")))) {
            return Result.fail(401, "用户名或密码错误");
        }
        String token = AuthInterceptor.createToken();
        String roleStr = String.valueOf(user.get("role") == null ? "ADMIN" : user.get("role"));
        java.util.List<String> roles = new java.util.ArrayList<>();
        for (String r : roleStr.toUpperCase().split(",")) {
            if (!r.trim().isEmpty()) roles.add(r.trim());
        }
        AuthInterceptor.registerUser(token, username, roles);
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("username", user.get("username"));
        info.put("realName", user.get("real_name"));
        info.put("role", String.join(",", roles));
        info.put("token", token);
        return Result.ok(info);
    }

    // ---------------- 数据源管理 ----------------

    public Map<String, Object> listDs() { return repos.ds.page(null, null, "id DESC", 1, 1000); }

    public Result saveDs(Map<String, Object> body) {
        try {
            DsInfo ds = om.convertValue(body, DsInfo.class);
            if (ds.dsName == null || ds.dsName.isEmpty()) return Result.fail("数据源名称不能为空");
            if (ds.dsType == null) return Result.fail("数据源类型不能为空");
            String pwd = ds.password;
            // 编辑时密码留空表示不修改
            if (ds.id != null && (pwd == null || pwd.isEmpty())) {
                Map<String, Object> old = repos.ds.findById(ds.id);
                ds.password = old == null ? null : String.valueOf(old.get("password"));
            } else if (pwd != null && !pwd.isEmpty()) {
                ds.password = CryptoUtil.aesEncrypt(pwd, props.aesKey);
            } else {
                ds.password = null;
            }
            ds.updateTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
            if (ds.id == null) {
                ds.id = repos.ds.insert(ds);
            } else {
                repos.ds.updateById(ds.id, ds);
            }
            return Result.ok(ds.id);
        } catch (Exception e) {
            return Result.fail("保存失败: " + e.getMessage());
        }
    }

    public Result deleteDs(long id) {
        dsManager.remove(id);
        repos.ds.deleteById(id);
        return Result.ok();
    }

    /** 测试连接 */
    public Result testDs(Map<String, Object> body) {
        try {
            DsInfo ds = om.convertValue(body, DsInfo.class);
            if ("redis".equals(ds.dsType)) {
                redis.clients.jedis.JedisPool pool = dsManager.getRedisPool(0, ds.host, ds.port,
                        ds.password == null ? "" : ds.password, 1);
                try (redis.clients.jedis.Jedis j = pool.getResource()) { j.ping(); }
                return Result.ok("Redis 连接成功");
            }
            String driver = ds.driverClass();
            Class.forName(driver);
            String pwd = ds.password == null ? "" : ds.password;
            try (Connection con = java.sql.DriverManager.getConnection(ds.jdbcUrl(), ds.username, pwd)) {
                return Result.ok("连接成功: " + con.getMetaData().getDatabaseProductName()
                        + " " + con.getMetaData().getDatabaseProductVersion());
            }
        } catch (ClassNotFoundException e) {
            return Result.fail(500, "驱动未找到: " + e.getMessage() + "，请将驱动 jar 放入 " + props.driversDir + " 目录");
        } catch (Exception e) {
            return Result.fail(500, "连接失败: " + e.getMessage());
        }
    }

    /** 查询库中表清单（向导式第 2 步） */
    public Result listTables(long dsId) {
        try {
            DsInfo ds = om.convertValue(normalize(repos.ds.findById(dsId)), DsInfo.class);
            if (ds == null) return Result.fail("数据源不存在");
            if ("redis".equals(ds.dsType)) return Result.ok(new String[0]);
            String driver = ds.driverClass();
            Class.forName(driver);
            HikariHolder holder = poolFor(ds);
            try (Connection con = holder.ds.getConnection()) {
                java.sql.DatabaseMetaData md = con.getMetaData();
                String catalog = ("mysql".equals(ds.dsType) || "oceanbase".equals(ds.dsType) || "gbase8a".equals(ds.dsType))
                        ? ds.dbName : null;
                String schema = "oracle".equals(ds.dsType) ? ds.username.toUpperCase() : null;
                java.util.List<String> tables = new java.util.ArrayList<>();
                try (java.sql.ResultSet rs = md.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
                    while (rs.next() && tables.size() < 5000) tables.add(rs.getString("TABLE_NAME"));
                }
                return Result.ok(tables);
            }
        } catch (Exception e) {
            return Result.fail("查询表清单失败: " + e.getMessage());
        }
    }

    /** 查询表字段清单（向导式第 3 步） */
    public Result listColumns(long dsId, String table) {
        try {
            DsInfo ds = om.convertValue(normalize(repos.ds.findById(dsId)), DsInfo.class);
            if (ds == null) return Result.fail("数据源不存在");
            Class.forName(ds.driverClass());
            HikariHolder holder = poolFor(ds);
            try (Connection con = holder.ds.getConnection()) {
                java.sql.DatabaseMetaData md = con.getMetaData();
                String catalog = ("mysql".equals(ds.dsType) || "oceanbase".equals(ds.dsType) || "gbase8a".equals(ds.dsType))
                        ? ds.dbName : null;
                String schema = "oracle".equals(ds.dsType) ? ds.username.toUpperCase() : null;
                List<Map<String, Object>> cols = new java.util.ArrayList<>();
                try (java.sql.ResultSet rs = md.getColumns(catalog, schema, table, "%")) {
                    while (rs.next() && cols.size() < 500) {
                        Map<String, Object> c = new java.util.LinkedHashMap<>();
                        c.put("name", rs.getString("COLUMN_NAME"));
                        c.put("type", rs.getString("TYPE_NAME"));
                        c.put("length", rs.getInt("COLUMN_SIZE"));
                        c.put("nullable", rs.getInt("NULLABLE") == 1);
                        c.put("comment", rs.getString("REMARKS"));
                        cols.add(c);
                    }
                }
                return Result.ok(cols);
            }
        } catch (Exception e) {
            return Result.fail("查询字段失败: " + e.getMessage());
        }
    }

    /** SQL 模式 / 向导模式的预览执行（管理端） */
    public Result preview(Map<String, Object> body) {
        try {
            long dsId = Long.parseLong(String.valueOf(body.get("dsId")));
            DsInfo ds = om.convertValue(normalize(repos.ds.findById(dsId)), DsInfo.class);
            if (ds == null) return Result.fail("数据源不存在");
            Class.forName(ds.driverClass());
            HikariHolder holder = poolFor(ds);
            String sql = String.valueOf(body.get("sql"));
            String err = com.enterprise.dsp.common.SqlSecurityUtil.checkSqlMode(sql);
            if (err != null) return Result.fail(400, err);
            Object[] parsed = com.enterprise.dsp.common.SqlSecurityUtil.parseSqlParams(sql);
            String exec = (String) parsed[0];
            @SuppressWarnings("unchecked")
            List<String> names = (List<String>) parsed[1];
            Map<String, Object> params = (Map<String, Object>) body.getOrDefault("params", new java.util.HashMap<>());
            try (Connection con = holder.ds.getConnection();
                 java.sql.PreparedStatement ps = con.prepareStatement(exec)) {
                ps.setQueryTimeout(props.queryTimeoutSeconds);
                ps.setMaxRows(200);
                for (int i = 0; i < names.size(); i++) ps.setObject(i + 1, params.get(names.get(i)));
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    java.sql.ResultSetMetaData md = rs.getMetaData();
                    List<String> cols = new java.util.ArrayList<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnLabel(i));
                    List<Map<String, Object>> rows = new java.util.ArrayList<>();
                    while (rs.next() && rows.size() < 200) {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) row.put(cols.get(i - 1), rs.getObject(i));
                        rows.add(row);
                    }
                    return Result.ok(new java.util.LinkedHashMap<String, Object>() {{
                        put("columns", cols); put("rows", rows);
                    }});
                }
            }
        } catch (ClassNotFoundException e) {
            return Result.fail(500, "驱动未找到: " + e.getMessage());
        } catch (Exception e) {
            return Result.fail(500, "预览失败: " + e.getMessage());
        }
    }

    private HikariHolder poolFor(DsInfo ds) {
        String pwd = (ds.password == null || ds.password.isEmpty()) ? "" : decrypt(ds.password);
        HikariDataSource h = dsManager.getPool(ds.id, ds.driverClass(), ds.jdbcUrl(), ds.username, pwd,
                ds.poolSize == null ? 3 : ds.poolSize, ds.timeoutSec == null ? 60 : ds.timeoutSec, false);
        return new HikariHolder(h);
    }

    static class HikariHolder { final HikariDataSource ds; HikariHolder(HikariDataSource d) { ds = d; } }

    private String decrypt(String cipher) {
        try { return CryptoUtil.aesDecrypt(cipher, props.aesKey); }
        catch (Exception e) { return cipher; }
    }

    private Map<String, Object> normalize(Map<String, Object> row) {
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

    // ---------------- API 管理 ----------------

    public Map<String, Object> listApi(String keyword, int pageNo, int pageSize) {
        String where = "1=1";
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isEmpty()) {
            where = "api_name LIKE ? OR api_path LIKE ?";
            args.add("%" + keyword + "%"); args.add("%" + keyword + "%");
        }
        return repos.api.page(where, args.toArray(), "id DESC", pageNo, pageSize);
    }

    public Result saveApi(Map<String, Object> body) {
        try {
            ApiInfo api = om.convertValue(body, ApiInfo.class);
            if (api.apiName == null || api.apiName.isEmpty()) return Result.fail("API 名称不能为空");
            if (api.apiPath == null || api.apiPath.isEmpty()) return Result.fail("API 路径不能为空");
            if (!api.apiPath.matches("[a-zA-Z0-9\\-_/.]{2,120}")) return Result.fail("API 路径仅允许字母/数字/中划线/下划线/斜杠");
            if ("sql".equals(api.mode) && api.sqlText != null) {
                String err = com.enterprise.dsp.common.SqlSecurityUtil.checkSqlMode(api.sqlText);
                if (err != null) return Result.fail(400, err);
            }
            api.updateTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
            boolean isNew = api.id == null;
            if (isNew) {
                api.id = repos.api.insert(api);
            } else {
                // 编辑前保存旧版本快照（DS-DEV-13）
                snapshotVersion(api.id);
                repos.api.updateById(api.id, api);
            }
            // 血缘登记（DS-CAT-07）
            metaService.saveLineage(api.id, api.dsId == null ? 0 : api.dsId, api.mode, api.sqlText, api.wizardJson);
            return Result.ok(api.id);
        } catch (Exception e) {
            return Result.fail("保存失败: " + e.getMessage());
        }
    }

    public Result deleteApi(long id) { repos.api.deleteById(id); return Result.ok(); }

    /** 保存当前 API 定义为版本快照 */
    private void snapshotVersion(long apiId) {
        try {
            Map<String, Object> row = repos.api.findById(apiId);
            if (row == null) return;
            ApiInfo cur = om.convertValue(normalize(row), ApiInfo.class);
            String snap = om.writeValueAsString(cur);
            repos.meta().update("INSERT INTO api_version (api_id, version, snapshot, changed_by, change_desc) " +
                            "SELECT ?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM api_version WHERE api_id=? AND snapshot=?)",
                    apiId, cur.version, snap, "system", "保存前自动快照", apiId, snap);
        } catch (Exception ignore) { }
    }

    // ---------------- 应用管理 ----------------

    public Map<String, Object> listApp() { return repos.app.page(null, null, "id DESC", 1, 1000); }

    public Result saveApp(Map<String, Object> body) {
        try {
            AppInfo app = om.convertValue(body, AppInfo.class);
            if (app.appName == null || app.appName.isEmpty()) return Result.fail("应用名称不能为空");
            if (app.appKey == null || app.appKey.isEmpty()) {
                app.appKey = "dsp-" + CryptoUtil.randomToken(8).toLowerCase();
            }
            if (app.appSecret == null || app.appSecret.isEmpty()) {
                app.appSecret = CryptoUtil.randomToken(32);
            }
            if (app.id == null) {
                app.id = repos.app.insert(app);
            } else {
                repos.app.updateById(app.id, app);
            }
            return Result.ok(app.id);
        } catch (Exception e) {
            return Result.fail("保存失败: " + e.getMessage());
        }
    }

    public Result deleteApp(long id) { repos.app.deleteById(id); return Result.ok(); }

    /** 重新生成密钥 */
    public Result resetSecret(long id) {
        try {
            AppInfo a = new AppInfo();
            a.id = id;
            a.appSecret = CryptoUtil.randomToken(32);
            repos.app.updateById(id, a);
            Map<String, Object> row = repos.app.findById(id);
            return Result.ok(row == null ? null : row.get("app_secret"));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    // ---------------- 仪表盘 ----------------

    public Map<String, Object> dashboard() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("dsCount", count("SELECT COUNT(*) FROM ds_info"));
        m.put("apiCount", count("SELECT COUNT(*) FROM api_info"));
        m.put("appCount", count("SELECT COUNT(*) FROM app_info"));
        m.put("logToday", count("SELECT COUNT(*) FROM api_log WHERE create_time >= CURRENT_DATE"));
        m.put("logFailToday", count("SELECT COUNT(*) FROM api_log WHERE create_time >= CURRENT_DATE AND status <> 'SUCCESS'"));
        m.put("topApis", repos.meta().queryForList(
                "SELECT api_path, COUNT(*) cnt, AVG(cost_ms) avg_cost FROM api_log WHERE create_time >= CURRENT_DATE - 7 " +
                "GROUP BY api_path ORDER BY cnt DESC LIMIT 10"));
        m.put("cache", cache.stats());

        // 近 7 天调用趋势（图表用）：每天总量与失败量
        m.put("trend7d", repos.meta().queryForList(
                "SELECT FORMATDATETIME(create_time, 'MM-dd') day_label, COUNT(*) cnt, " +
                " SUM(CASE WHEN status <> 'SUCCESS' THEN 1 ELSE 0 END) fail_cnt " +
                "FROM api_log WHERE create_time >= CURRENT_DATE - 6 GROUP BY FORMATDATETIME(create_time, 'MM-dd') ORDER BY day_label"));

        // 今日调用状态分布（图表用）
        Map<String, Object> st = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> srows = repos.meta().queryForList(
                "SELECT status, COUNT(*) c FROM api_log WHERE create_time >= CURRENT_DATE GROUP BY status");
        for (Map<String, Object> r : srows) {
            String k = String.valueOf(r.get("status") != null ? r.get("status") : r.get("STATUS"));
            Object c = r.get("c") != null ? r.get("c") : r.get("C");
            st.put(k, c instanceof Number ? ((Number) c).longValue() : 0);
        }
        m.put("statusToday", st);
        return m;
    }

    private int count(String sql) {
        Integer n = repos.meta().queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
