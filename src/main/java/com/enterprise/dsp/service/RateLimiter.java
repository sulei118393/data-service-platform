package com.enterprise.dsp.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 简单滑动窗口限流器（每 API 每 App 每分钟） */
@Component
public class RateLimiter {

    private static class Window {
        long windowStart;
        int count;
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * 尝试获取调用配额
     * @return true=允许
     */
    public boolean allow(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) return true;
        long now = System.currentTimeMillis();
        long minute = now / 60000L * 60000L;
        synchronized (windows) {
            Window w = windows.computeIfAbsent(key, k -> new Window());
            if (w.windowStart != minute) {
                w.windowStart = minute;
                w.count = 0;
            }
            if (w.count >= limitPerMinute) return false;
            w.count++;
            // 清理过期窗口
            if (windows.size() > 10000) {
                windows.entrySet().removeIf(e -> e.getValue().windowStart < minute);
            }
            return true;
        }
    }
}
