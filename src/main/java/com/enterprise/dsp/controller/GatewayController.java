package com.enterprise.dsp.controller;

import com.enterprise.dsp.common.Result;
import com.enterprise.dsp.dao.Repos;
import com.enterprise.dsp.entity.ApiInfo;
import com.enterprise.dsp.entity.AppInfo;
import com.enterprise.dsp.service.GatewayService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据服务网关（对应用方开放，DS-GW-01~08）：
 *
 *   POST /dsapi/{apiPath}?_pageNo=1&_pageSize=20&_version=v1.0.2（可选指定版本）
 *   Header: X-App-Key / X-App-Secret / X-Timestamp / X-App-Token
 *   签名模式: X-Sign = md5(appKey + appSecret + timestamp)（DS-SEC-11 防重放：时间窗 5 分钟）
 *   令牌模式: X-App-Token = 通过 /dsapi/token 接口获取的访问令牌
 *   Body(JSON): {"param1":"v1", ...} 或查询串传参
 *   响应头 X-Trace-Id 用于全链路追踪（DS-OPS-02）
 */
@RestController
@RequestMapping("/dsapi")
public class GatewayController {

    @Resource private Repos repos;
    @Resource private GatewayService gateway;
    private final JdbcTemplate meta;

    public GatewayController(JdbcTemplate meta) { this.meta = meta; }

    @RequestMapping(value = "/{*path}", method = {RequestMethod.GET, RequestMethod.POST})
    public Result invoke(@PathVariable String path,
                         @RequestBody(required = false) Map<String, Object> body,
                         HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        // {*path} 捕获值带前导斜杠，且支持多级路径（如 mask/rule/list）
        if (path.startsWith("/")) path = path.substring(1);

        // 1. 查找 API（DS-GW-01）
        ApiInfo api = findApi(path);
        if (api == null) return Result.fail(404, "API 不存在: " + path);
        if (api.status == null || api.status != 1) return Result.fail(403, "API 已停用");
        if (!"PUBLISHED".equals(api.envStatus)) return Result.fail(403, "API 未发布（当前状态: " + api.envStatus + "）");

        // 2. 版本与灰度路由（DS-GW-05）
        api = routeVersion(api, path, request);
        if (api == null) return Result.fail(404, "指定版本不存在");

        // 3. 鉴权（DS-AUTH-02 / DS-SEC-11）
        String clientIp = request.getRemoteAddr();
        String appKey;
        String authType = api.authType == null ? "app" : api.authType;
        if ("open".equals(authType)) {
            appKey = "anonymous";
        } else if ("token".equals(authType)) {
            appKey = authByToken(request);
            if (appKey == null) return Result.fail(401, "访问令牌无效或已过期");
        } else {
            appKey = authBySign(request, path, clientIp);
            if (appKey == null) return Result.fail(401, "鉴权失败（密钥或签名错误）");
        }

        // 4. 合并参数：query 参数 + body
        Map<String, Object> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> {
            if (!k.startsWith("_")) params.put(k, v.length == 1 ? v[0] : String.join(",", v));
        });
        if (body != null) params.putAll(body);

        // 5. 执行（响应头携带追踪标识）
        Result result = gateway.execute(api, params, appKey, clientIp, traceId);
        try {
            org.springframework.web.context.request.ServletRequestAttributes attrs =
                    (org.springframework.web.context.request.ServletRequestAttributes)
                            org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
            if (attrs != null && attrs.getResponse() != null) attrs.getResponse().setHeader("X-Trace-Id", traceId);
        } catch (Exception ignore) { }
        if (result.data instanceof Map) ((Map<String, Object>) result.data).put("traceId", traceId);
        return result;
    }

    // ============================================================
    // 应用令牌（DS-AUTH-02）：POST /dsapi/token {appKey, appSecret}
    // ============================================================

    @PostMapping("/token")
    public Result issueToken(@RequestBody Map<String, Object> body) {
        String appKey = String.valueOf(body.getOrDefault("appKey", ""));
        String secret = String.valueOf(body.getOrDefault("appSecret", ""));
        List<Map<String, Object>> list = meta.queryForList("SELECT * FROM app_info WHERE app_key=? AND status=1", appKey);
        if (list.isEmpty()) return Result.fail(401, "无效的 AppKey");
        Map<String, Object> app = list.get(0);
        String real = String.valueOf(app.get("app_secret") != null ? app.get("app_secret") : app.get("APP_SECRET"));
        if (!real.equals(secret)) return Result.fail(401, "密钥错误");
        String token = com.enterprise.dsp.common.CryptoUtil.randomToken(32);
        meta.update("INSERT INTO app_token (app_key, token, expire_at) VALUES (?,?,?)",
                appKey, token, new java.sql.Timestamp(System.currentTimeMillis() + 2 * 3600_000L));
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("expiresIn", 7200);
        data.put("usage", "Header: X-App-Token = token, 调用 auth_type=token 的服务");
        return Result.ok(data);
    }

    // ============================================================
    // 版本与灰度路由（DS-GW-05）
    // ============================================================

    private ApiInfo routeVersion(ApiInfo api, String path, HttpServletRequest request) {
        String reqVersion = request.getParameter("_version");
        if (reqVersion != null && !reqVersion.isEmpty()) {
            ApiInfo v = findApiByPath(path);   // 同一 path 只有当前版本记录；历史版本从 api_version 快照恢复
            if (v != null && reqVersion.equals(v.version)) return v;
            return restoreVersion(v, reqVersion);
        }
        // 灰度：按 appKey 稳定哈希决定走灰度版本（当前定义即最新版）
        return api;
    }

    /** 从版本历史恢复旧版本定义 */
    private ApiInfo restoreVersion(ApiInfo base, String version) {
        if (base == null || base.id == null) return null;
        try {
            List<Map<String, Object>> list = meta.queryForList(
                    "SELECT * FROM api_version WHERE api_id=? AND version=? ORDER BY id DESC LIMIT 1", base.id, version);
            if (list.isEmpty()) return null;
            Map<String, Object> snap = list.get(0);
            String json = String.valueOf(snap.get("snapshot") != null ? snap.get("snapshot") : snap.get("SNAPSHOT"));
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, ApiInfo.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================
    // 鉴权实现
    // ============================================================

    /** 签名/密钥认证（DS-AUTH-01,02 / DS-SEC-11 防重放） */
    private String authBySign(HttpServletRequest request, String path, String clientIp) {
        String appKey = header(request, "X-App-Key");
        AppInfo app = findApp(appKey);
        if (app == null) return null;
        if (app.status == null || app.status != 1) return null;
        // IP 白名单（DS-GW-08）
        if (app.ipWhitelist != null && !app.ipWhitelist.trim().isEmpty()
                && !java.util.Arrays.asList(app.ipWhitelist.split(",")).contains(clientIp)) {
            return null;
        }
        // API 范围
        if (app.apiScope != null && !app.apiScope.trim().isEmpty()
                && !java.util.Arrays.asList(app.apiScope.split(",")).contains(path)) {
            return null;
        }
        String ts = header(request, "X-Timestamp");
        String sign = header(request, "X-Sign");
        String secret = app.appSecret;
        boolean ok = secret != null && (secret.equals(header(request, "X-App-Secret"))
                || (ts != null && sign != null && sign.equals(com.enterprise.dsp.common.CryptoUtil.md5(appKey + secret + ts))));
        if (!ok) return null;
        if (ts != null) {
            try {
                long t = Long.parseLong(ts);
                if (Math.abs(System.currentTimeMillis() - t) > 5 * 60 * 1000L) return null;   // 重放窗口
            } catch (NumberFormatException ignore) { }
        }
        return appKey;
    }

    /** 令牌认证（DS-AUTH-02） */
    private String authByToken(HttpServletRequest request) {
        String token = header(request, "X-App-Token");
        if (token == null || token.isEmpty()) return null;
        try {
            List<Map<String, Object>> list = meta.queryForList(
                    "SELECT app_key FROM app_token WHERE token=? AND status=1 AND (expire_at IS NULL OR expire_at > CURRENT_TIMESTAMP)", token);
            if (list.isEmpty()) return null;
            return String.valueOf(list.get(0).get("app_key") != null ? list.get(0).get("app_key") : list.get(0).get("APP_KEY"));
        } catch (Exception e) { return null; }
    }

    // ============================================================
    // API 查找
    // ============================================================

    private ApiInfo findApi(String path) { return findApiByPath(path); }

    private ApiInfo findApiByPath(String path) {
        try {
            List<Map<String, Object>> rows = meta.queryForList("SELECT * FROM api_info WHERE api_path=? AND status=1", path);
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            ApiInfo api = new ApiInfo();
            api.id = Long.parseLong(String.valueOf(row.get("id")));
            api.apiName = s(row.get("api_name"));
            api.apiPath = path;
            api.mode = s(row.get("mode"));
            Object dsId = row.get("ds_id");
            api.dsId = dsId instanceof Number ? ((Number) dsId).longValue() : null;
            api.wizardJson = s(row.get("wizard_json"));
            api.sqlText = s(row.get("sql_text"));
            api.redisJson = s(row.get("redis_json"));
            api.proxyUrl = s(row.get("proxy_url"));
            api.paramsJson = s(row.get("params_json"));
            api.outFields = s(row.get("out_fields"));
            api.authType = s(row.get("auth_type"));
            api.cacheTtl = intVal(row.get("cache_ttl"));
            api.rateLimit = intVal(row.get("rate_limit"));
            api.maxRows = intVal(row.get("max_rows"));
            api.timeoutMs = intVal(row.get("timeout_ms"));
            api.version = s(row.get("version"));
            api.envStatus = s(row.get("env_status"));
            api.mockJson = s(row.get("mock_json"));
            api.grayPercent = intVal(row.get("gray_percent"));
            api.status = intVal(row.get("status"));
            return api;
        } catch (Exception e) {
            return null;
        }
    }

    private AppInfo findApp(String appKey) {
        if (appKey == null || appKey.isEmpty()) return null;
        try {
            List<Map<String, Object>> rows = meta.queryForList("SELECT * FROM app_info WHERE app_key=? AND status=1", appKey);
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            AppInfo a = new AppInfo();
            a.id = Long.parseLong(String.valueOf(row.get("id")));
            a.appKey = appKey;
            a.appSecret = s(row.get("app_secret"));
            a.ipWhitelist = s(row.get("ip_whitelist"));
            a.apiScope = s(row.get("api_scope"));
            a.status = intVal(row.get("status"));
            return a;
        } catch (Exception e) {
            return null;
        }
    }

    private Integer intVal(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String && !((String) o).isEmpty()) {
            try { return Integer.parseInt((String) o); } catch (Exception ignore) { }
        }
        return 0;
    }

    private String header(HttpServletRequest req, String name) {
        String v = req.getHeader(name);
        return v == null ? req.getParameter(name) : v;
    }

    private String s(Object o) { return o == null ? "" : String.valueOf(o); }
}
