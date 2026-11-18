package com.enterprise.dsp.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 响应缓存（按 API+参数指纹，TTL 秒） */
@Component
public class ResponseCache {

    private static class Entry {
        final Object value;
        final long expireAt;
        Entry(Object v, long expireAt) { this.value = v; this.expireAt = expireAt; }
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private int maxEntries = 2000;

    public void configure(int maxEntries) { this.maxEntries = Math.max(10, maxEntries); }

    public Object get(String key) {
        Entry e = cache.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() > e.expireAt) {
            cache.remove(key);
            return null;
        }
        return e.value;
    }

    public void put(String key, Object value, int ttlSeconds) {
        if (ttlSeconds <= 0) return;
        if (cache.size() >= maxEntries) {
            // 淘汰最早的 1/4
            cache.entrySet().stream()
                    .sorted(java.util.Comparator.comparingLong(e -> e.getValue().expireAt))
                    .limit(maxEntries / 4)
                    .forEach(e -> cache.remove(e.getKey()));
        }
        cache.put(key, new Entry(value, System.currentTimeMillis() + ttlSeconds * 1000L));
    }

    public void clear() { cache.clear(); }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("size", cache.size());
        m.put("maxEntries", maxEntries);
        return m;
    }
}
