package com.enterprise.dsp.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 平台配置属性（从 application.yml dsp.* 读取）
 */
@Component
public class DspProps {

    @Value("${dsp.aes-key}")
    public String aesKey;

    @Value("${dsp.drivers-dir}")
    public String driversDir;

    @Value("${dsp.admin-user}")
    public String adminUser;

    @Value("${dsp.admin-pass}")
    public String adminPass;

    @Value("${dsp.token-ttl-minutes}")
    public long tokenTtlMinutes;

    @Value("${dsp.query-timeout-seconds}")
    public int queryTimeoutSeconds;

    @Value("${dsp.max-rows}")
    public int maxRows;

    @Value("${dsp.cache-max-entries}")
    public int cacheMaxEntries;

    @Value("${dsp.default-rate-limit}")
    public int defaultRateLimit;
}
