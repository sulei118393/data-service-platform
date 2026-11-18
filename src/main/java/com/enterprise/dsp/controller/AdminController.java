package com.enterprise.dsp.controller;

import com.enterprise.dsp.common.Result;
import com.enterprise.dsp.dao.Repos;
import com.enterprise.dsp.entity.ApiInfo;
import com.enterprise.dsp.service.AdminService;
import com.enterprise.dsp.service.GatewayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/** 管理端 REST 接口（除 login 外需 X-Token） */
@RestController
@RequestMapping("/dsp/api")
public class AdminController {

    @Resource private AdminService admin;
    @Resource private GatewayService gateway;
    @Resource private Repos repos;
    private final ObjectMapper om = new ObjectMapper();

    // ---------------- 登录 ----------------

    @PostMapping("/login")
    public Result login(@RequestBody Map<String, Object> body) {
        return admin.login(str(body.get("username")), str(body.get("password")));
    }

    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "X-Token", required = false) String token) {
        if (token != null) com.enterprise.dsp.common.AuthInterceptor.TOKENS.remove(token);
        return Result.ok();
    }

    // ---------------- 数据源 ----------------

    @GetMapping("/ds/list")
    public Result listDs() { return Result.ok(admin.listDs()); }

    @PostMapping("/ds/save")
    public Result saveDs(@RequestBody Map<String, Object> body) { return admin.saveDs(body); }

    @PostMapping("/ds/delete/{id}")
    public Result deleteDs(@PathVariable long id) { return admin.deleteDs(id); }

    @PostMapping("/ds/test")
    public Result testDs(@RequestBody Map<String, Object> body) { return admin.testDs(body); }

    @GetMapping("/ds/tables/{id}")
    public Result listTables(@PathVariable long id) { return admin.listTables(id); }

    @GetMapping("/ds/columns/{id}")
    public Result listColumns(@PathVariable long id, @RequestParam String table) {
        return admin.listColumns(id, table);
    }

    // ---------------- API 管理 ----------------

    @GetMapping("/api/list")
    public Result listApi(@RequestParam(required = false) String keyword,
                          @RequestParam(defaultValue = "1") int pageNo,
                          @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(admin.listApi(keyword, pageNo, pageSize));
    }

    @GetMapping("/api/detail/{id}")
    public Result apiDetail(@PathVariable long id) { return Result.ok(repos.api.findById(id)); }

    @PostMapping("/api/save")
    public Result saveApi(@RequestBody Map<String, Object> body) { return admin.saveApi(body); }

    @PostMapping("/api/delete/{id}")
    public Result deleteApi(@PathVariable long id) { return admin.deleteApi(id); }

    @PostMapping("/api/preview")
    public Result preview(@RequestBody Map<String, Object> body) { return admin.preview(body); }

    /** API 试运行（管理端在线调试，DS-DEV-11；免订阅，绕过熔断/配额） */
    @PostMapping("/api/dryrun/{id}")
    public Result dryRun(@PathVariable long id, @RequestBody(required = false) Map<String, Object> params) {
        ApiInfo api = new ApiInfo();
        Map<String, Object> row = repos.api.findById(id);
        if (row == null) return Result.fail("API 不存在");
        api.id = id;
        api.apiPath = String.valueOf(row.get("api_path"));
        api.mode = String.valueOf(row.get("mode"));
        api.authType = "open";   // 调试模式跳过订阅校验
        Object dsId = row.get("ds_id");
        api.dsId = dsId instanceof Number ? ((Number) dsId).longValue() : null;
        api.wizardJson = str(row.get("wizard_json"));
        api.sqlText = str(row.get("sql_text"));
        api.redisJson = str(row.get("redis_json"));
        api.paramsJson = str(row.get("params_json"));
        api.cacheTtl = 0;
        api.maxRows = 200;
        api.timeoutMs = 10000;
        if (params == null) params = new java.util.HashMap<>();
        return gateway.execute(api, params, "dryrun", "admin", "debug-" + id);
    }

    // ---------------- 应用管理 ----------------

    @GetMapping("/app/list")
    public Result listApp() { return Result.ok(admin.listApp()); }

    @PostMapping("/app/save")
    public Result saveApp(@RequestBody Map<String, Object> body) { return admin.saveApp(body); }

    @PostMapping("/app/delete/{id}")
    public Result deleteApp(@PathVariable long id) { return admin.deleteApp(id); }

    @PostMapping("/app/resetSecret/{id}")
    public Result resetSecret(@PathVariable long id) { return admin.resetSecret(id); }

    // ---------------- 日志 ----------------

    @GetMapping("/log/list")
    public Result listLog(@RequestParam(required = false) String apiPath,
                          @RequestParam(required = false) String status,
                          @RequestParam(defaultValue = "1") int pageNo,
                          @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(repos.pageLog(apiPath, status, pageNo, pageSize));
    }

    // ---------------- 仪表盘 ----------------

    @GetMapping("/dashboard")
    public Result dashboard() { return Result.ok(admin.dashboard()); }

    private String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
