package com.enterprise.dsp.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态多数据源管理器：
 *  - SQL 类数据源：HikariCP 连接池，按需创建/销毁
 *  - Redis 数据源：JedisPool
 *  - 驱动动态加载：drivers-dir 下的 jar 自动注册（GBase/Oracle 等）
 */
@Component
public class DataSourceManager {

    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final Map<Long, JedisPool> redisPools = new ConcurrentHashMap<>();

    /** 已注册的驱动 jar 加载器 */
    private volatile URLClassLoader driverLoader;

    /** 初始化时扫描并加载驱动目录 */
    public void loadDrivers(String driversDir) {
        try {
            File dir = new File(driversDir);
            File[] jars = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
            if (jars == null || jars.length == 0) return;
            List<URL> urls = new ArrayList<>();
            for (File jar : jars) urls.add(jar.toURI().toURL());
            driverLoader = new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
            for (URL u : urls) {
                try (java.util.jar.JarFile jf = new java.util.jar.JarFile(new File(u.toURI()))) {
                    java.util.jar.JarEntry e = jf.getJarEntry("META-INF/services/java.sql.Driver");
                    if (e == null) continue;
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(jf.getInputStream(e)))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty() || line.startsWith("#")) continue;
                            try {
                                Class<?> cls = driverLoader.loadClass(line);
                                java.sql.Driver d = (java.sql.Driver) cls.newInstance();
                                DriverManager.registerDriver(new DriverShim(d));
                            } catch (Throwable ignore) { }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[DSP] 加载驱动目录失败: " + e.getMessage());
        }
    }

    /** DriverManager 只认本加载器注册的驱动，包一层以便加载子加载器驱动 */
    static class DriverShim implements java.sql.Driver {
        private final java.sql.Driver delegate;
        DriverShim(java.sql.Driver d) { this.delegate = d; }
        @Override public Connection connect(String url, java.util.Properties info) throws java.sql.SQLException { return delegate.connect(url, info); }
        @Override public boolean acceptsURL(String url) throws java.sql.SQLException { return delegate.acceptsURL(url); }
        @Override public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) throws java.sql.SQLException { return delegate.getPropertyInfo(url, info); }
        @Override public int getMajorVersion() { return delegate.getMajorVersion(); }
        @Override public int getMinorVersion() { return delegate.getMinorVersion(); }
        @Override public boolean jdbcCompliant() { return delegate.jdbcCompliant(); }
        @Override public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    }

    public synchronized HikariDataSource getPool(long dsId, String driverClass, String url,
                                                 String user, String pass, int poolSize, int timeoutSec, boolean readOnly) {
        HikariDataSource existing = pools.get(dsId);
        if (existing != null && !existing.isClosed()) return existing;
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        if (driverClass != null && !driverClass.isEmpty()) cfg.setDriverClassName(driverClass);
        if (user != null) cfg.setUsername(user);
        if (pass != null) cfg.setPassword(pass);
        cfg.setMaximumPoolSize(Math.max(1, poolSize));
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout((timeoutSec + 5) * 1000L);
        cfg.setMaxLifetime(1800000);
        cfg.setReadOnly(readOnly);
        cfg.setPoolName("dsp-ds-" + dsId);
        HikariDataSource ds = new HikariDataSource(cfg);
        pools.put(dsId, ds);
        return ds;
    }

    public synchronized JedisPool getRedisPool(long dsId, String host, int port, String password, int poolSize) {
        JedisPool existing = redisPools.get(dsId);
        if (existing != null && !existing.isClosed()) return existing;
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(Math.max(2, poolSize));
        cfg.setMaxIdle(poolSize);
        cfg.setMinIdle(1);
        JedisPool pool = (password == null || password.isEmpty())
                ? new JedisPool(cfg, host, port, 5000)
                : new JedisPool(cfg, host, port, 5000, password);
        redisPools.put(dsId, pool);
        return pool;
    }

    public void remove(long dsId) {
        HikariDataSource p = pools.remove(dsId);
        if (p != null) p.close();
        JedisPool rp = redisPools.remove(dsId);
        if (rp != null) rp.close();
    }
}
