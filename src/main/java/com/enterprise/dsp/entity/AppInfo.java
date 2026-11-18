package com.enterprise.dsp.entity;

import com.fasterxml.jackson.annotation.JsonAlias;

/** 接入应用 */
public class AppInfo {
    public Long id;
    @JsonAlias("app_name") public String appName;
    @JsonAlias("app_key") public String appKey;
    @JsonAlias("app_secret") public String appSecret;
    @JsonAlias("ip_whitelist") public String ipWhitelist = "";
    @JsonAlias("api_scope") public String apiScope = "";
    public Integer status = 1;
    public String remark = "";
    @JsonAlias("create_time") public String createTime;
}
