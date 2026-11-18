package com.enterprise.dsp.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通用 DAO：POJO 公共字段自动映射下划线列名。
 * 约定：实体类字段均为 public，列名 = 驼峰转下划线。
 */
public class BaseDao<T> {

    protected final JdbcTemplate jdbc;
    protected final Class<T> type;
    protected final String table;

    public BaseDao(JdbcTemplate jdbc, Class<T> type, String table) {
        this.jdbc = jdbc;
        this.type = type;
        this.table = table;
    }

    private static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private List<Field> fields() {
        List<Field> fs = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            fs.add(f);
        }
        return fs;
    }

    /** 插入，返回自增 id */
    public long insert(T obj) throws Exception {
        List<String> cols = new ArrayList<>();
        List<String> ph = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        for (Field f : fields()) {
            Object v = f.get(obj);
            if (v == null || "id".equals(f.getName())) continue;
            cols.add(snake(f.getName()));
            ph.add("?");
            vals.add(v);
        }
        String sql = "INSERT INTO " + table + " (" + String.join(",", cols) + ") VALUES (" + String.join(",", ph) + ")";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < vals.size(); i++) ps.setObject(i + 1, vals.get(i));
            return ps;
        }, kh);
        // H2 2.x 对含 DEFAULT CURRENT_TIMESTAMP 的表会返回多个生成键，需从键列表按列名取 id
        try {
            Number key = kh.getKey();
            return key == null ? 0 : key.longValue();
        } catch (Exception multi) {
            List<Map<String, Object>> kl = kh.getKeyList();
            if (!kl.isEmpty()) {
                Map<String, Object> k0 = kl.get(0);
                Object v = k0.containsKey("id") ? k0.get("id") : (k0.containsKey("ID") ? k0.get("ID") : null);
                if (v instanceof Number) return ((Number) v).longValue();
            }
            return 0;
        }
    }

    /** 按 id 更新非空字段 */
    public int updateById(long id, T obj) throws Exception {
        List<String> sets = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        for (Field f : fields()) {
            if ("id".equals(f.getName())) continue;
            Object v = f.get(obj);
            if (v == null) continue;
            sets.add(snake(f.getName()) + "=?");
            vals.add(v);
        }
        vals.add(id);
        String sql = "UPDATE " + table + " SET " + String.join(",", sets) + " WHERE id=?";
        return jdbc.update(sql, vals.toArray());
    }

    /** 查单条，返回 Map（列名下划线 -> 值） */
    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> list = jdbc.queryForList("SELECT * FROM " + table + " WHERE id=?", id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Map<String, Object>> listAll(String orderBy) {
        return jdbc.queryForList("SELECT * FROM " + table + (orderBy == null || orderBy.isEmpty() ? "" : " ORDER BY " + orderBy));
    }

    /** 通用条件分页查询 */
    public Map<String, Object> page(String where, Object[] args, String orderBy, int pageNo, int pageSize) {
        where = (where == null || where.trim().isEmpty()) ? "1=1" : where;
        orderBy = (orderBy == null || orderBy.trim().isEmpty()) ? "id DESC" : orderBy;
        int total = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, args);
        int offset = (pageNo - 1) * pageSize;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + table + " WHERE " + where + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?",
                append(args, pageSize, offset));
        java.util.Map<String, Object> res = new java.util.LinkedHashMap<>();
        res.put("total", total);
        res.put("pageNo", pageNo);
        res.put("pageSize", pageSize);
        res.put("rows", rows);
        return res;
    }

    private static Object[] append(Object[] args, Object... more) {
        Object[] all = new Object[(args == null ? 0 : args.length) + more.length];
        int i = 0;
        if (args != null) for (Object a : args) all[i++] = a;
        for (Object a : more) all[i++] = a;
        return all;
    }

    public int deleteById(long id) {
        return jdbc.update("DELETE FROM " + table + " WHERE id=?", id);
    }
}
