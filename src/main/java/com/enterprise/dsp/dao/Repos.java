package com.enterprise.dsp.dao;

import com.enterprise.dsp.common.CryptoUtil;
import com.enterprise.dsp.common.DspProps;
import com.enterprise.dsp.entity.ApiInfo;
import com.enterprise.dsp.entity.AppInfo;
import com.enterprise.dsp.entity.DsInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.UUID;

/** 各业务实体 DAO 汇总 */
@Component
public class Repos {

    @Autowired private JdbcTemplate metaJdbc;
    @Autowired public DspProps props;

    public BaseDao<DsInfo> ds;
    public BaseDao<ApiInfo> api;
    public BaseDao<AppInfo> app;
    public JdbcTemplate meta() { return metaJdbc; }

    @PostConstruct
    public void init() {
        ds = new BaseDao<>(metaJdbc, DsInfo.class, "ds_info");
        api = new BaseDao<>(metaJdbc, ApiInfo.class, "api_info");
        app = new BaseDao<>(metaJdbc, AppInfo.class, "app_info");
    }

    // ---------------- 数据源密码加解密 ----------------

    public void encryptDsPassword(Map<String, Object> row) {
        Object pwd = row.get("password");
        if (pwd instanceof String && !((String) pwd).isEmpty()) {
            row.put("password", CryptoUtil.aesDecrypt((String) pwd, props.aesKey));
        }
    }

    // ---------------- 调用日志 ----------------

    public void saveLog(String apiPath, String appKey, String params, String sqlText,
                        String status, String error, int costMs, int rows, String ip,
                        String traceId, String version) {
        metaJdbc.update("INSERT INTO api_log (api_path, app_key, params, sql_text, status, error_msg, cost_ms, rows_returned, client_ip, trace_id, api_version) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?)", apiPath, appKey, params, sqlText, status,
                error == null ? "" : truncate(error, 1000), costMs, rows, ip,
                traceId == null ? "" : traceId, version == null ? "" : version);
    }

    public Map<String, Object> pageLog(String apiPath, String status, int pageNo, int pageSize) {
        StringBuilder w = new StringBuilder("1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (apiPath != null && !apiPath.isEmpty()) { w.append(" AND api_path LIKE ?"); args.add("%" + apiPath + "%"); }
        if (status != null && !status.isEmpty()) { w.append(" AND status=?"); args.add(status); }
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Map<String, Object>> rowClass = (Class) Map.class;
        return new BaseDao<Map<String, Object>>(metaJdbc, rowClass, "api_log")
                .page(w.toString(), args.toArray(), "id DESC", pageNo, pageSize);
    }

    // ---------------- 用户 ----------------

    public Map<String, Object> findUser(String username) {
        java.util.List<Map<String, Object>> list =
                metaJdbc.queryForList("SELECT * FROM sys_user WHERE username=? AND status=1", username);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 确保初始管理员存在 */
    @PostConstruct
    public void ensureAdmin() {
        Integer n = metaJdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, props.adminUser);
        if (n != null && n == 0) {
            metaJdbc.update("INSERT INTO sys_user (username, password, real_name, role) VALUES (?,?,?,?)",
                    props.adminUser, CryptoUtil.sha256(props.adminPass), "管理员", "admin");
        }
    }

    /** 确保演示应用存在（首次启动生成） */
    @PostConstruct
    public void ensureDemoApp() {
        Integer n = metaJdbc.queryForObject("SELECT COUNT(*) FROM app_info", Integer.class);
        if (n != null && n == 0) {
            AppInfo a = new AppInfo();
            a.appName = "演示应用";
            a.appKey = "dsp-" + UUID.randomUUID().toString().substring(0, 8);
            a.appSecret = CryptoUtil.randomToken(32);
            a.remark = "平台初始化生成的演示应用";
            try { app.insert(a); } catch (Exception ignore) { }
        }
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
