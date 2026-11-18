package com.enterprise.dsp.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** 统一响应结构 */
public class Result {

    public int code;          // 0=成功，非0=失败
    public String message = "";
    public Object data;

    public static Result ok(Object data) {
        Result r = new Result();
        r.code = 0;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static Result ok() { return ok(null); }

    public static Result fail(int code, String message) {
        Result r = new Result();
        r.code = code;
        r.message = message;
        return r;
    }

    public static Result fail(String message) { return fail(500, message); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("data", data);
        return m;
    }
}
