package com.enterprise.dsp.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.management.MBeanServer;
import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 系统级监控服务：服务器 CPU/内存/磁盘/JVM/线程/GC 实时指标 + 1 小时趋势采样（每 10 秒一个点）
 * 纯 JDK 实现（com.sun.management.OperatingSystemMXBean），不依赖 OSHI，内网离线可用。
 */
@Service
public class SysMetricsService {

    private final JdbcTemplate meta;

    /** 趋势环形缓冲：最多保留 360 个采样点（10 秒/点 = 1 小时） */
    private static final int MAX_SAMPLES = 360;
    private final ConcurrentLinkedDeque<Map<String, Object>> history = new ConcurrentLinkedDeque<>();

    /** 上一次 GC 采样基线（用于计算区间增量） */
    private long lastGcCnt = -1, lastGcTime = -1, lastSampleAt = 0;

    public SysMetricsService(JdbcTemplate meta) { this.meta = meta; }

    private com.sun.management.OperatingSystemMXBean os() {
        return (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    }

    /** 实时快照 + 趋势历史 */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        com.sun.management.OperatingSystemMXBean os = os();
        Runtime rt = Runtime.getRuntime();
        long totalMem = os.getTotalPhysicalMemorySize();
        long freeMem = os.getFreePhysicalMemorySize();
        double sysCpu = os.getSystemCpuLoad() < 0 ? 0 : Math.round(os.getSystemCpuLoad() * 1000) / 10.0;
        double procCpu = os.getProcessCpuLoad() < 0 ? 0 : Math.round(os.getProcessCpuLoad() * 1000) / 10.0;

        // JVM 内存
        long heapMax = rt.maxMemory(), heapUsed = rt.totalMemory() - rt.freeMemory();
        double heapPct = heapMax <= 0 ? 0 : Math.round(heapUsed * 1000.0 / heapMax) / 10.0;

        // 线程 / GC
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        long gcCnt = 0, gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCnt += gc.getCollectionCount(); gcTime += gc.getCollectionTime();
        }
        double gcPerMin = 0;
        long now = System.currentTimeMillis();
        if (lastGcCnt >= 0 && now > lastSampleAt) {
            double mins = (now - lastSampleAt) / 60000.0;
            gcPerMin = Math.max(0, Math.round((gcCnt - lastGcCnt) / mins * 10) / 10.0);
        }
        lastGcCnt = gcCnt; lastGcTime = gcTime; lastSampleAt = now;

        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

        m.put("time", new SimpleDateFormat("HH:mm:ss").format(new Date()));
        m.put("osName", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        m.put("osArch", System.getProperty("os.arch"));
        m.put("javaVersion", System.getProperty("java.version"));
        m.put("javaHome", System.getProperty("java.home"));
        m.put("userDir", System.getProperty("user.dir"));
        m.put("cores", os.getAvailableProcessors());
        m.put("sysLoadAvg", Math.round(os.getSystemLoadAverage() * 100) / 100.0);
        m.put("sysCpu", sysCpu);
        m.put("procCpu", procCpu);
        m.put("memTotal", totalMem);
        m.put("memFree", freeMem);
        m.put("memUsed", totalMem - freeMem);
        m.put("memPct", totalMem <= 0 ? 0 : Math.round((totalMem - freeMem) * 1000.0 / totalMem) / 10.0);
        m.put("procMem", os.getCommittedVirtualMemorySize());
        m.put("heapMax", heapMax);
        m.put("heapUsed", heapUsed);
        m.put("heapPct", heapPct);
        m.put("threadCount", tm.getThreadCount());
        m.put("threadPeak", tm.getPeakThreadCount());
        m.put("daemonCount", tm.getDaemonThreadCount());
        m.put("gcCount", gcCnt);
        m.put("gcTimeMs", gcTime);
        m.put("gcPerMin", gcPerMin);
        m.put("uptimeMs", uptimeMs);
        m.put("uptimeText", uptimeMs / 86400000 + "天" + uptimeMs / 3600000 % 24 + "小时" + uptimeMs / 60000 % 60 + "分");

        // 磁盘（所有根分区）
        List<Map<String, Object>> disks = new ArrayList<>();
        File[] roots = File.listRoots();
        for (File r : roots) {
            long t = r.getTotalSpace(), f = r.getFreeSpace();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("mount", r.getAbsolutePath());
            d.put("total", t);
            d.put("free", f);
            d.put("used", t - f);
            d.put("pct", t <= 0 ? 0 : Math.round((t - f) * 1000.0 / t) / 10.0);
            disks.add(d);
        }
        m.put("disks", disks);

        // 最近 1 小时趋势
        m.put("history", new ArrayList<>(history));
        return m;
    }

    /** 每 10 秒采样一次：CPU / 内存 / JVM 堆 / 网关 QPS（60 秒窗口）/ 平均耗时 */
    @Scheduled(fixedDelay = 10_000, initialDelay = 5_000)
    public void sample() {
        try {
            com.sun.management.OperatingSystemMXBean os = os();
            Runtime rt = Runtime.getRuntime();
            long now = System.currentTimeMillis();
            double sysCpu = os.getSystemCpuLoad() < 0 ? 0 : Math.round(os.getSystemCpuLoad() * 1000) / 10.0;
            double memPct = os.getTotalPhysicalMemorySize() <= 0 ? 0 :
                    Math.round((os.getTotalPhysicalMemorySize() - os.getFreePhysicalMemorySize())
                            * 1000.0 / os.getTotalPhysicalMemorySize()) / 10.0;
            long heapMax = rt.maxMemory(), heapUsed = rt.totalMemory() - rt.freeMemory();
            double heapPct = heapMax <= 0 ? 0 : Math.round(heapUsed * 1000.0 / heapMax) / 10.0;

            long qps = 0, avgCost = 0;
            try {
                Map<String, Object> r = meta.queryForMap(
                        "SELECT COUNT(*) c, AVG(cost_ms) a FROM api_log WHERE create_time >= ?",
                        new java.sql.Timestamp(now - 60_000));
                Object c = r.get("c") != null ? r.get("c") : r.get("C");
                Object a = r.get("a") != null ? r.get("a") : r.get("A");
                qps = c instanceof Number ? ((Number) c).longValue() : 0;
                avgCost = a instanceof Number ? Math.round(((Number) a).doubleValue()) : 0;
            } catch (Exception ignore) { }

            Map<String, Object> p = new LinkedHashMap<>();
            p.put("t", new SimpleDateFormat("HH:mm:ss").format(new Date(now)));
            p.put("cpu", sysCpu);
            p.put("mem", memPct);
            p.put("heap", heapPct);
            p.put("qps", qps);
            p.put("cost", avgCost);
            history.addLast(p);
            while (history.size() > MAX_SAMPLES) history.pollFirst();
        } catch (Exception ignore) { }
    }
}
