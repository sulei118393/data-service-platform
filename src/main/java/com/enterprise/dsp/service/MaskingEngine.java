package com.enterprise.dsp.service;

import com.enterprise.dsp.common.CryptoUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;

/**
 * 动态脱敏引擎（DS-SEC-02/03）：
 *   按字段敏感等级与授权范围，在服务返回路径上对敏感字段实时变形，不改变底层存储。
 *   支持掩码遮蔽/全遮蔽/置空/替换/哈希/泛化。
 */
@Service
public class MaskingEngine {

    @Resource private MetaService metaService;
    private final JdbcTemplate meta;
    private volatile Map<String, Object[]> ruleCache = Collections.emptyMap();
    private volatile long ruleCacheTime = 0;

    public MaskingEngine(JdbcTemplate meta) { this.meta = meta; }

    private Map<String, Object[]> rules() {
        if (System.currentTimeMillis() - ruleCacheTime > 30_000) {
            Map<String, Object[]> m = new LinkedHashMap<>();
            for (Map<String, Object> r : meta.queryForList("SELECT * FROM mask_rule WHERE enabled=1")) {
                m.put(String.valueOf(r.get("id") != null ? r.get("id") : r.get("ID")),
                        new Object[]{
                                str(r, "rule_type", "RULE_TYPE"), str(r, "mask_type", "MASK_TYPE"),
                                num(r, "keep_prefix", "KEEP_PREFIX"), num(r, "keep_suffix", "KEEP_SUFFIX"),
                                str(r, "replace_str", "REPLACE_STR")});
            }
            ruleCache = m;
            ruleCacheTime = System.currentTimeMillis();
        }
        return ruleCache;
    }

    /**
     * 对结果集行应用脱敏。
     * @param columnLevels 列名 -> 敏感等级（来自 meta_column / api out_fields）
     * @param colRuleIds   列名 -> 规则 id
     * @param plainFields  该应用经审批的明文字段（免脱敏），小写
     * @param threshold    脱敏触发的最低等级
     */
    public List<Map<String, Object>> maskRows(List<Map<String, Object>> rows,
                                              Map<String, int[]> colMeta,
                                              Set<String> plainFields, int threshold) {
        if (rows == null || rows.isEmpty()) return rows;
        Map<String, Object[]> active = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : colMeta.entrySet()) {
            String col = e.getKey();
            int level = e.getValue()[0], ruleId = e.getValue()[1];
            if (level >= threshold && !plainFields.contains(col.toLowerCase()) && ruleId > 0) {
                Object[] rule = rules().get(String.valueOf(ruleId));
                if (rule != null) active.put(col, rule);
            }
        }
        if (active.isEmpty()) return rows;
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object[]> e : active.entrySet()) {
                String col = findKey(row, e.getKey());
                if (col == null) continue;
                Object v = row.get(col);
                if (v != null) row.put(col, mask(String.valueOf(v), e.getValue()));
            }
        }
        return rows;
    }

    /** 单值脱敏：rule = [type, maskType, keepPrefix, keepSuffix, replaceStr] */
    public String mask(String value, Object[] rule) {
        String maskType = String.valueOf(rule[1]);
        int pre = ((Number) rule[2]).intValue();
        int suf = ((Number) rule[3]).intValue();
        String rep = String.valueOf(rule[4]);
        switch (maskType) {
            case "NULL": return "";
            case "REPLACE": return rep == null || rep.isEmpty() ? "****" : rep;
            case "HASH":
                return CryptoUtil.sha256(value).substring(0, 16);
            case "GENERALIZE": {
                // 泛化：只保留类型化前缀信息，如手机号 -> 138****（这里取前3位+***）
                int keep = Math.max(1, pre);
                return value.substring(0, Math.min(keep, value.length())) + "***";
            }
            case "FULL": return rep == null || rep.isEmpty() ? "******" : rep;
            case "MASK":
            default: {
                if (value.length() <= pre + suf) {
                    return pre > 0 ? value.charAt(0) + "****" : "****";
                }
                StringBuilder sb = new StringBuilder();
                sb.append(value, 0, Math.min(pre, value.length()));
                int starEnd = value.length() - Math.min(suf, value.length());
                for (int i = pre; i < starEnd; i++) sb.append('*');
                if (suf > 0) sb.append(value.substring(starEnd));
                return sb.toString();
            }
        }
    }

    private String findKey(Map<String, Object> row, String col) {
        if (row.containsKey(col)) return col;
        for (String k : row.keySet()) {
            if (k.equalsIgnoreCase(col)) return k;
        }
        return null;
    }

    private String str(Map<String, Object> r, String... keys) {
        for (String k : keys) { Object v = r.get(k); if (v != null) return String.valueOf(v); }
        return "";
    }

    private Object num(Map<String, Object> r, String... keys) {
        for (String k : keys) { Object v = r.get(k); if (v instanceof Number) return v; }
        return 0;
    }
}
