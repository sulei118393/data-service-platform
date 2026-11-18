package com.enterprise.dsp.common;

import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端拦截器（DS-SYS-02 RBAC）：
 *   1. 校验 X-Token 登录态
 *   2. 按路径前缀做角色鉴权（ADMIN 拥有全部权限）
 *   角色定义：ADMIN 平台管理员 / DEV 服务开发者 / OWNER 数据责任人 / SEC 安全管理员 / AUDIT 审计人员
 */
public class AuthInterceptor implements HandlerInterceptor {

    /** token -> 过期时间戳 */
    public static final Map<String, Long> TOKENS = new java.util.concurrent.ConcurrentHashMap<>();
    /** token -> 用户名 */
    public static final Map<String, String> TOKEN_USER = new java.util.concurrent.ConcurrentHashMap<>();
    /** 用户名 -> 角色 */
    public static final Map<String, List<String>> ROLE_LIST = new java.util.concurrent.ConcurrentHashMap<>();

    /** 路径前缀 -> 允许角色（未列出的路径仅要求登录） */
    private static final Map<String, List<String>> PATH_ROLES = new LinkedHashMap<>();
    static {
        PATH_ROLES.put("/dsp/api/user/",    Arrays.asList("ADMIN"));                    // 用户管理
        PATH_ROLES.put("/dsp/api/ds/",      Arrays.asList("ADMIN", "DEV"));             // 数据源管理
        PATH_ROLES.put("/dsp/api/api/",     Arrays.asList("ADMIN", "DEV"));             // API 管理
        PATH_ROLES.put("/dsp/api/meta/",    Arrays.asList("ADMIN", "DEV"));             // 元数据采集
        PATH_ROLES.put("/dsp/api/mask/",    Arrays.asList("ADMIN", "SEC"));             // 脱敏规则
        PATH_ROLES.put("/dsp/api/asset/",   Arrays.asList("ADMIN", "DEV", "OWNER", "SEC", "AUDIT")); // 资产
        PATH_ROLES.put("/dsp/api/lineage",  Arrays.asList("ADMIN", "DEV", "OWNER", "SEC", "AUDIT"));
        PATH_ROLES.put("/dsp/api/batch/",   Arrays.asList("ADMIN", "DEV"));             // 批量任务
        PATH_ROLES.put("/dsp/api/monitor/", Arrays.asList("ADMIN", "DEV", "SEC", "AUDIT")); // 监控
        PATH_ROLES.put("/dsp/api/sys/",     Arrays.asList("ADMIN", "DEV", "SEC", "AUDIT")); // 服务器监控
        PATH_ROLES.put("/dsp/api/alert/",   Arrays.asList("ADMIN", "SEC", "AUDIT"));    // 告警
        PATH_ROLES.put("/dsp/api/sec/",     Arrays.asList("ADMIN", "SEC", "AUDIT"));    // 安全事件
        PATH_ROLES.put("/dsp/api/log/",     Arrays.asList("ADMIN", "AUDIT"));           // 调用日志
        PATH_ROLES.put("/dsp/api/oplog",    Arrays.asList("ADMIN", "AUDIT"));           // 操作审计
        PATH_ROLES.put("/dsp/api/app/",     Arrays.asList("ADMIN"));                    // 应用管理
        PATH_ROLES.put("/dsp/api/config",   Arrays.asList("ADMIN"));                    // 参数配置
        PATH_ROLES.put("/dsp/api/notice/",  Arrays.asList("ADMIN"));                    // 公告发布
        // approval / subscription / portal / dashboard / catalog 登录即可访问（审批动作再校验具体角色）
    }

    private final long ttlMillis;

    public AuthInterceptor(long ttlMinutes) {
        this.ttlMillis = ttlMinutes * 60 * 1000;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        String token = req.getHeader("X-Token");
        if (token == null) token = req.getParameter("_token");
        Long ts = token == null ? null : TOKENS.get(token);
        boolean valid = ts != null && (System.currentTimeMillis() - ts) < ttlMillis;
        if (valid) {
            TOKENS.put(token, System.currentTimeMillis());   // 滑动续期
            // RBAC 角色校验（DS-SYS-02）
            String uri = req.getRequestURI();
            for (Map.Entry<String, List<String>> e : PATH_ROLES.entrySet()) {
                if (uri.startsWith(e.getKey())) {
                    String username = TOKEN_USER.getOrDefault(token, "");
                    List<String> roles = rolesOf(username);
                    if (!roles.contains("ADMIN") && e.getValue().stream().noneMatch(roles::contains)) {
                        resp.setStatus(200);
                        resp.setContentType("application/json;charset=UTF-8");
                        resp.getWriter().write("{\"code\":403,\"message\":\"权限不足（需要角色 " + e.getValue()
                                + "，当前角色 " + roles + "）\"}");
                        return false;
                    }
                    break;
                }
            }
            return true;
        }
        if (token != null) { TOKENS.remove(token); TOKEN_USER.remove(token); }
        resp.setStatus(200);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"code\":401,\"message\":\"登录已失效，请重新登录\"}");
        return false;
    }

    /** 从登录注册信息取用户角色 */
    public static List<String> rolesOf(String username) {
        List<String> roles = ROLE_LIST.get(username);
        return roles == null ? Arrays.asList("ADMIN") : roles;
    }

    /** 登录成功时由 AdminService 注册角色 */
    public static void registerUser(String token, String username, List<String> roles) {
        TOKEN_USER.put(token, username);
        ROLE_LIST.put(username, roles);
        TOKENS.put(token, System.currentTimeMillis());   // 注册登录态（拦截器据此校验有效期）
    }

    public static String createToken() {
        return CryptoUtil.randomToken(32);
    }
}
