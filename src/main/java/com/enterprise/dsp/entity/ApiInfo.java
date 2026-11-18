package com.enterprise.dsp.entity;

import com.fasterxml.jackson.annotation.JsonAlias;

/** API 定义 */
public class ApiInfo {
    public Long id;
    @JsonAlias("api_name") public String apiName;
    @JsonAlias("api_path") public String apiPath;
    public String method = "POST";
    public String mode;        // wizard / sql / redis / script / proxy
    @JsonAlias("ds_id") public Long dsId;
    @JsonAlias("wizard_json") public String wizardJson;
    @JsonAlias("sql_text") public String sqlText;
    @JsonAlias("redis_json") public String redisJson;
    @JsonAlias("proxy_url") public String proxyUrl;
    @JsonAlias("script_text") public String scriptText;
    @JsonAlias("params_json") public String paramsJson;
    @JsonAlias("out_fields") public String outFields;
    @JsonAlias("auth_type") public String authType = "app";
    @JsonAlias("cache_ttl") public Integer cacheTtl = 0;
    @JsonAlias("rate_limit") public Integer rateLimit = 0;
    @JsonAlias("max_rows") public Integer maxRows = 1000;
    @JsonAlias("timeout_ms") public Integer timeoutMs = 10000;
    public String version = "v1.0.0";
    @JsonAlias("env_status") public String envStatus = "DRAFT";
    @JsonAlias("mock_json") public String mockJson;
    @JsonAlias("gray_percent") public Integer grayPercent = 0;
    public String owner = "";
    @JsonAlias("sla_desc") public String slaDesc = "";
    @JsonAlias("risk_level") public String riskLevel = "LOW";
    @JsonAlias("catalog_id") public Long catalogId;
    public Integer status = 1;
    public String remark = "";
    @JsonAlias("create_time") public String createTime;
    @JsonAlias("update_time") public String updateTime;
}
