package com.enterprise.dsp.common;

import java.util.regex.Pattern;

/**
 * SQL 安全工具：标识符白名单校验 + SQL 模式参数解析
 */
public class SqlSecurityUtil {

    /** 合法 SQL 标识符（表名/字段名）：字母、数字、下划线、$，可带 schema 前缀 */
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?$");

    /** 向导式条件允许的操作符 */
    private static final java.util.Set<String> ALLOWED_OPS = new java.util.HashSet<>(java.util.Arrays.asList(
            "=", "!=", "<>", ">", ">=", "<", "<=", "LIKE", "NOT LIKE", "IN", "NOT IN", "IS NULL", "IS NOT NULL", "BETWEEN"));

    /** SQL 模式参数占位符 #{name} */
    private static final Pattern PARAM = Pattern.compile("#\\{\\s*(\\w+)\\s*}");

    /** 拦截 SQL 模式中的危险语句（网关只提供查询能力） */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)\\b(drop|truncate|alter|create|grant|revoke|merge|insert|update|delete|exec|execute|call)\\b");

    public static boolean isValidIdentifier(String name) {
        return name != null && IDENT.matcher(name.trim()).matches();
    }

    public static boolean isAllowedOp(String op) {
        return op != null && ALLOWED_OPS.contains(op.trim().toUpperCase());
    }

    /**
     * 解析 SQL 模板中的 #{param} 占位符。
     * @return [0]=替换为 ? 后的 SQL；[1]=按出现顺序的参数名列表
     */
    public static Object[] parseSqlParams(String sqlTemplate) {
        java.util.List<String> names = new java.util.ArrayList<>();
        StringBuffer sb = new StringBuffer();
        java.util.regex.Matcher m = PARAM.matcher(sqlTemplate);
        while (m.find()) {
            names.add(m.group(1));
            m.appendReplacement(sb, "?");
        }
        m.appendTail(sb);
        return new Object[]{sb.toString(), names};
    }

    /** 校验 SQL 模式语句：只允许 SELECT / WITH 开头且不含 DML/DDL */
    public static String checkSqlMode(String sql) {
        String s = sql == null ? "" : sql.trim();
        if (s.isEmpty()) return "SQL 语句不能为空";
        String lower = s.toLowerCase();
        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            return "仅允许 SELECT / WITH 查询语句";
        }
        if (s.contains(";")) {
            // 允许末尾单个分号
            String noTail = lower.endsWith(";") ? s.substring(0, s.length() - 1) : s;
            if (noTail.contains(";")) return "不允许执行多条语句";
        }
        java.util.regex.Matcher fm = FORBIDDEN.matcher(lower);
        if (fm.find()) {
            return "检测到禁止的关键字: " + fm.group(1).toUpperCase();
        }
        return null; // null 表示通过
    }

    /** 校验向导式条件结构 */
    public static String checkCondition(java.util.Map<String, Object> cond) {
        Object field = cond.get("field");
        Object op = cond.get("op");
        if (field == null || !isValidIdentifier(String.valueOf(field))) {
            return "非法字段名: " + field;
        }
        if (op == null || !isAllowedOp(String.valueOf(op))) {
            return "非法操作符: " + op + "，允许: " + ALLOWED_OPS;
        }
        return null;
    }
}
