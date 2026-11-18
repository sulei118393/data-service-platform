package com.enterprise.dsp.entity;

import com.fasterxml.jackson.annotation.JsonAlias;

/** 数据源配置 */
public class DsInfo {
    public Long id;
    @JsonAlias("ds_name") public String dsName;
    @JsonAlias("ds_type") public String dsType;      // mysql / oceanbase / oracle / gbase8a / redis / h2
    public String host = "";
    public Integer port = 0;
    @JsonAlias("db_name") public String dbName = "";
    public String username = "";
    public String password = "";    // 明文仅在创建/编辑时传入，库中存 AES 密文
    public String params = "";
    @JsonAlias("pool_size") public Integer poolSize = 5;
    @JsonAlias("read_only") public Integer readOnly = 1;
    @JsonAlias("timeout_sec") public Integer timeoutSec = 60;
    public Integer status = 1;
    public String remark = "";
    @JsonAlias("create_time") public String createTime;
    @JsonAlias("update_time") public String updateTime;

    public String jdbcUrl() {
        String p = params == null ? "" : params;
        switch (dsType) {
            case "mysql":
                return String.format("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai%s",
                        host, port, dbName == null ? "" : dbName, p);
            case "oceanbase":
                return String.format("jdbc:oceanbase://%s:%d/%s?pool=false%s", host, port, dbName == null ? "" : dbName, p);
            case "gbase8a":
                return String.format("jdbc:gbase://%s:%d/%s?useUnicode=true&characterEncoding=utf8%s",
                        host, port, dbName == null ? "" : dbName, p);
            case "oracle":
                return String.format("jdbc:oracle:thin:@//%s:%d/%s%s", host, port, dbName == null ? "orcl" : dbName, p);
            case "h2":
                // dbName 直接填 H2 连接串后半段，如 ./data/dsp_meta 或 mem:demo；参数可带 ;MODE=MySQL 等
                return String.format("jdbc:h2:%s%s", dbName == null ? "" : dbName, p);
            default:
                return "";
        }
    }

    public String driverClass() {
        switch (dsType) {
            case "mysql":     return "com.mysql.cj.jdbc.Driver";
            case "oceanbase": return "com.alipay.oceanbase.jdbc.Driver";
            case "gbase8a":   return "com.gbase.jdbc.Driver";
            case "oracle":    return "oracle.jdbc.OracleDriver";
            case "h2":        return "org.h2.Driver";
            default:          return "";
        }
    }
}
