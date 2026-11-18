-- 数据服务平台元数据库结构（H2 / MySQL 兼容）
-- 对应《企业级数据服务平台需求说明书》DSP-SRS-2026-001

-- ============ 系统管理（4.11） ============

CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE,
    password    VARCHAR(128) NOT NULL,
    real_name   VARCHAR(64)  DEFAULT '',
    role        VARCHAR(64)  DEFAULT 'ADMIN',      -- 角色：ADMIN/DEV/OWNER/SEC/AUDIT（多角色逗号分隔）
    status      INT          DEFAULT 1,
    create_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_config (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    cfg_key     VARCHAR(128) NOT NULL UNIQUE,
    cfg_value   VARCHAR(2048) DEFAULT '',
    cfg_desc    VARCHAR(512)  DEFAULT '',
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 平台管理操作审计（DS-SYS-05）
CREATE TABLE IF NOT EXISTS op_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64),
    action      VARCHAR(128),
    detail      VARCHAR(2048) DEFAULT '',
    client_ip   VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 变更公告（DS-PORTAL-08）
CREATE TABLE IF NOT EXISTS notice (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(256) NOT NULL,
    content     TEXT,
    level       VARCHAR(20) DEFAULT 'INFO',      -- INFO/WARN/CRITICAL
    publisher   VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============ 数据源（4.1 / DS-CAT-01,02） ============

CREATE TABLE IF NOT EXISTS ds_info (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    ds_name      VARCHAR(128) NOT NULL,
    ds_type      VARCHAR(32)  NOT NULL,          -- mysql / oceanbase / oracle / gbase8a / redis
    host         VARCHAR(128) NOT NULL,
    port         INT          NOT NULL,
    db_name      VARCHAR(128) DEFAULT '',
    username     VARCHAR(128) DEFAULT '',
    password     VARCHAR(512) DEFAULT '',        -- AES 加密存储（DS-CAT-02）
    params       VARCHAR(1024) DEFAULT '',
    pool_size    INT          DEFAULT 5,
    read_only    INT          DEFAULT 1,
    timeout_sec  INT          DEFAULT 60,
    status       INT          DEFAULT 1,
    remark       VARCHAR(512) DEFAULT '',
    create_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- ============ 元数据与资产（4.1 / DS-CAT-03~08） ============

-- 元数据采集任务日志
CREATE TABLE IF NOT EXISTS meta_task (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    ds_id       BIGINT NOT NULL,
    task_type   VARCHAR(20) DEFAULT 'FULL',      -- FULL 全量 / INCR 增量
    status      VARCHAR(20) DEFAULT 'RUNNING',   -- RUNNING/SUCCESS/FAIL
    table_cnt   INT DEFAULT 0,
    column_cnt  INT DEFAULT 0,
    error_msg   VARCHAR(1024) DEFAULT '',
    cost_ms     INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 采集到的表元数据
CREATE TABLE IF NOT EXISTS meta_table (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    ds_id         BIGINT NOT NULL,
    table_name    VARCHAR(128) NOT NULL,
    table_type    VARCHAR(20) DEFAULT 'TABLE',     -- TABLE / VIEW
    comment       VARCHAR(1024) DEFAULT '',
    catalog_id    BIGINT,                          -- 资产目录节点（DS-CAT-04）
    tags          VARCHAR(512) DEFAULT '',         -- 标签，逗号分隔
    owner         VARCHAR(64) DEFAULT '',          -- 数据责任人
    level         INT DEFAULT -1,                  -- 表级最高敏感等级 -1未识别 0公开 1内部 2敏感 3机密
    svc_ready     INT DEFAULT 0,                   -- 可对外服务标识（DS-CAT-08）
    refresh_freq  VARCHAR(32) DEFAULT '',          -- 更新频率：REALTIME/DAILY/MONTHLY...
    last_collect  TIMESTAMP,
    UNIQUE(ds_id, table_name)
);

-- 采集到的字段元数据
CREATE TABLE IF NOT EXISTS meta_column (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    ds_id         BIGINT NOT NULL,
    table_name    VARCHAR(128) NOT NULL,
    col_name      VARCHAR(128) NOT NULL,
    data_type     VARCHAR(64) DEFAULT '',
    nullable      INT DEFAULT 1,
    col_key       VARCHAR(20) DEFAULT '',          -- PK/FK/IDX/UNI/''
    remarks       VARCHAR(1024) DEFAULT '',
    biz_name      VARCHAR(128) DEFAULT '',         -- 业务名称（DS-CAT-05）
    biz_desc      VARCHAR(1024) DEFAULT '',        -- 业务定义
    enum_values   VARCHAR(1024) DEFAULT '',        -- 枚举码值
    unit          VARCHAR(64) DEFAULT '',          -- 计量单位
    level         INT DEFAULT -1,                  -- 敏感等级 -1未识别 0公开 1内部 2敏感 3机密（DS-CAT-06）
    rule_id       BIGINT,                          -- 命中的敏感规则
    level_time    TIMESTAMP,                       -- 打标时间
    UNIQUE(ds_id, table_name, col_name)
);

-- 资产目录树（DS-CAT-04）
CREATE TABLE IF NOT EXISTS asset_catalog (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id   BIGINT DEFAULT 0,
    cat_name    VARCHAR(128) NOT NULL,
    cat_type    VARCHAR(20) DEFAULT 'DOMAIN',    -- DOMAIN 业务域 / THEME 主题 / LAYER 分层
    sort_no     INT DEFAULT 0
);

-- 血缘关系（DS-CAT-07）：服务SQL解析出的 表级血缘
CREATE TABLE IF NOT EXISTS lineage (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    api_id      BIGINT NOT NULL,
    ds_id       BIGINT,
    table_name  VARCHAR(128) NOT NULL,
    col_names   VARCHAR(2048) DEFAULT '',        -- 涉及字段
    direction   VARCHAR(10) DEFAULT 'SRC',       -- SRC 来源（一期只做来源解析）
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============ 数据服务开发（4.2） ============

CREATE TABLE IF NOT EXISTS api_info (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    api_name      VARCHAR(128) NOT NULL,
    api_path      VARCHAR(128) NOT NULL,          -- 网关调用路径 /dsapi/{api_path}（版本路由）
    method        VARCHAR(10)  DEFAULT 'POST',
    mode          VARCHAR(10)  NOT NULL,          -- wizard / sql / redis / script / proxy
    ds_id         BIGINT,
    wizard_json   TEXT,
    sql_text      TEXT,
    redis_json    TEXT,
    proxy_url     VARCHAR(512) DEFAULT '',        -- 接口代理目标地址（DS-DEV-07）
    script_text   TEXT,                           -- 脚本类服务（一期占位，受限沙箱）
    params_json   TEXT,                           -- [{name,type,required,defaultValue,min,max,len,desc}]
    out_fields    TEXT,                           -- 出参定义 [{name,type,biz,masked}]
    auth_type     VARCHAR(10)  DEFAULT 'app',     -- app=应用签名 / token=应用令牌 / open=开放
    cache_ttl     INT          DEFAULT 0,
    rate_limit    INT          DEFAULT 0,
    max_rows      INT          DEFAULT 1000,      -- 单次最大返回行数（DS-DEV-04）
    timeout_ms    INT          DEFAULT 10000,     -- 单次最大查询时间
    version       VARCHAR(20)  DEFAULT 'v1.0.0',  -- 当前版本
    env_status    VARCHAR(20)  DEFAULT 'DRAFT',   -- DRAFT/PENDING/PUBLISHED/OFFLINE（DS-DEV-12）
    mock_json     TEXT,                          -- Mock 数据（DS-DEV-11）
    gray_percent  INT          DEFAULT 0,        -- 灰度切流百分比（DS-GW-05）
    owner         VARCHAR(64)  DEFAULT '',        -- 服务责任人
    sla_desc      VARCHAR(256) DEFAULT '',        -- SLA 说明
    risk_level    VARCHAR(10)  DEFAULT 'LOW',     -- LOW/MID/HIGH，决定审批强度（DS-GW-10）
    catalog_id    BIGINT,                        -- 所属资产目录节点
    status        INT          DEFAULT 1,        -- 1=启用 0=停用
    remark        VARCHAR(512) DEFAULT '',
    create_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 服务定义版本历史（DS-DEV-13）
CREATE TABLE IF NOT EXISTS api_version (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    api_id       BIGINT NOT NULL,
    version      VARCHAR(20),
    snapshot     TEXT,                             -- 定义 JSON 快照
    changed_by   VARCHAR(64) DEFAULT '',
    change_desc  VARCHAR(512) DEFAULT '',
    create_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 血缘随服务保存（见 lineage 表）

-- ============ 网关与服务发布（4.4） ============

-- 上架审批（DS-GW-10）：type=PUBLISH 发布 / SUBSCRIBE 订阅 / PLAINVIEW 明文 / EXPORT 导出 / OFFLINE 下线
CREATE TABLE IF NOT EXISTS approval (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    type         VARCHAR(20) NOT NULL,
    biz_id       BIGINT NOT NULL,                 -- api_id / subscription_id / app_id
    title        VARCHAR(256) DEFAULT '',
    detail       TEXT,                            -- 申请说明（数据范围/敏感清单/预估调用量）
    apply_user   VARCHAR(64) DEFAULT '',
    need_roles   VARCHAR(256) DEFAULT 'OWNER,SEC,ADMIN',  -- 需要哪些角色会签
    approved     VARCHAR(256) DEFAULT '',         -- 已批准角色
    status       VARCHAR(20) DEFAULT 'PENDING',   -- PENDING/APPROVED/REJECTED/CANCELLED
    opinions     TEXT,                            -- 审批意见留痕 JSON
    create_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    finish_time  TIMESTAMP
);

CREATE TABLE IF NOT EXISTS app_info (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    app_name     VARCHAR(128) NOT NULL,
    app_key      VARCHAR(64)  NOT NULL UNIQUE,
    app_secret   VARCHAR(64)  NOT NULL,
    ip_whitelist VARCHAR(512) DEFAULT '',
    api_scope    TEXT,
    owner        VARCHAR(64)  DEFAULT '',         -- 应用负责人（DS-AUTH-01）
    status       INT          DEFAULT 1,
    remark       VARCHAR(512) DEFAULT '',
    create_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 应用访问令牌（DS-AUTH-02）
CREATE TABLE IF NOT EXISTS app_token (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    app_key      VARCHAR(64) NOT NULL,
    token        VARCHAR(64) NOT NULL UNIQUE,
    expire_at    TIMESTAMP,
    status       INT DEFAULT 1,                   -- 1有效 0注销
    create_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 服务订阅授权（DS-AUTH-03~07）
CREATE TABLE IF NOT EXISTS subscription (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    app_id        BIGINT NOT NULL,
    api_id        BIGINT NOT NULL,
    api_version   VARCHAR(20) DEFAULT '',         -- 授权到具体版本
    fields_scope  VARCHAR(2048) DEFAULT '*',      -- 授权字段白名单，逗号分隔，*=全部
    plain_fields  VARCHAR(2048) DEFAULT '',       -- 明文查看（免脱敏）字段，逗号分隔
    quota_daily   INT DEFAULT 10000,              -- 日配额（DS-BIZ-02）
    quota_monthly INT DEFAULT 200000,
    expire_date   DATE,                           -- 授权到期（DS-AUTH-06）
    status        VARCHAR(20) DEFAULT 'PENDING',  -- PENDING/ACTIVE/REJECTED/REVOKED/EXPIRED
    apply_desc    VARCHAR(2048) DEFAULT '',       -- 使用场景/调用量预估
    emergency     INT DEFAULT 0,                  -- 紧急停用标记（DS-AUTH-08）
    create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============ 数据安全（4.6） ============

-- 脱敏规则库（DS-SEC-03）
CREATE TABLE IF NOT EXISTS mask_rule (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_name    VARCHAR(128) NOT NULL,
    match_regex  VARCHAR(512) DEFAULT '',         -- 字段名/注释识别正则
    match_keys   VARCHAR(512) DEFAULT '',         -- 关键词，逗号分隔
    rule_type    VARCHAR(30) NOT NULL,            -- NAME/IDCARD/PHONE/BANK/EMAIL/ADDRESS/AMOUNT/CUSTOM
    mask_type    VARCHAR(20) NOT NULL,            -- MASK 部分遮蔽 / FULL 全遮蔽 / NULL 置空 / REPLACE 替换 / HASH 哈希 / GENERALIZE 泛化
    keep_prefix  INT DEFAULT 0,
    keep_suffix  INT DEFAULT 0,
    replace_str  VARCHAR(128) DEFAULT '',
    level        INT DEFAULT 2,                   -- 命中后打的敏感等级
    enabled      INT DEFAULT 1
);

-- 明文查看审批记录在 approval 表（type=PLAINVIEW），通过后写入 subscription.plain_fields

-- 安全事件（DS-DEV-05 / DS-SEC-10,11）
CREATE TABLE IF NOT EXISTS sec_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type  VARCHAR(40),                     -- SQL_INJECT / RATE_LIMIT / BLACK_IP / OVER_QUOTA / ABNORMAL_FREQ ...
    level       VARCHAR(10) DEFAULT 'WARN',      -- INFO/WARN/HIGH
    detail      VARCHAR(2048) DEFAULT '',
    app_key     VARCHAR(64) DEFAULT '',
    client_ip   VARCHAR(64) DEFAULT '',
    api_path    VARCHAR(128) DEFAULT '',
    blocked     INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============ 监控运维（4.7） ============

CREATE TABLE IF NOT EXISTS api_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    api_path      VARCHAR(128),
    api_version   VARCHAR(20) DEFAULT '',
    app_key       VARCHAR(64)   DEFAULT '',
    trace_id      VARCHAR(64)  DEFAULT '',        -- 全链路追踪标识（DS-OPS-02）
    params        TEXT,
    sql_text      TEXT,
    status        VARCHAR(20),                   -- SUCCESS/FAIL/DENY/MOCK/CACHE
    error_msg     VARCHAR(1024) DEFAULT '',
    cost_ms       INT           DEFAULT 0,
    rows_returned INT           DEFAULT 0,
    client_ip     VARCHAR(64)   DEFAULT '',
    create_time   TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_api_log_path_time ON api_log(api_path, create_time);
CREATE INDEX IF NOT EXISTS idx_api_log_trace ON api_log(trace_id);

-- 告警规则（DS-OPS-03）
CREATE TABLE IF NOT EXISTS alert_rule (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_name    VARCHAR(128) NOT NULL,
    metric       VARCHAR(30) NOT NULL,           -- ERROR_RATE / AVG_COST / SLOW_COUNT / CALL_COUNT / DENY_COUNT
    target       VARCHAR(128) DEFAULT '*',       -- api_path 或 * 全局
    op           VARCHAR(10) DEFAULT '>',        -- > / <
    threshold    DECIMAL(20,2) DEFAULT 0,
    window_min   INT DEFAULT 5,                  -- 统计窗口分钟
    level        VARCHAR(10) DEFAULT 'WARN',     -- INFO/WARN/CRITICAL
    enabled      INT DEFAULT 1,
    receivers    VARCHAR(512) DEFAULT ''
);

-- 告警记录
CREATE TABLE IF NOT EXISTS alert_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id     BIGINT,
    rule_name   VARCHAR(128),
    level       VARCHAR(10) DEFAULT 'WARN',
    metric      VARCHAR(30),
    target      VARCHAR(128),
    cur_value   VARCHAR(64),
    threshold   VARCHAR(64),
    status      VARCHAR(20) DEFAULT 'OPEN',      -- OPEN/ACK/CLOSED
    ack_by      VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 服务健康检查（DS-OPS-04）
CREATE TABLE IF NOT EXISTS health_check (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    api_id      BIGINT NOT NULL,
    api_path    VARCHAR(128),
    status      VARCHAR(20) DEFAULT 'UP',        -- UP/DOWN/RECOVERING
    last_ok     TIMESTAMP,
    last_fail   TIMESTAMP,
    fail_msg    VARCHAR(1024) DEFAULT '',
    fail_cnt    INT DEFAULT 0,
    UNIQUE(api_id)
);

-- ============ 计量运营（4.8） ============

-- 配额用量计数（DS-BIZ-01,02）
CREATE TABLE IF NOT EXISTS quota_usage (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    app_id     BIGINT NOT NULL,
    api_id     BIGINT NOT NULL,
    stat_day   VARCHAR(10) NOT NULL,             -- yyyy-MM-dd
    stat_month VARCHAR(7)  NOT NULL,             -- yyyy-MM
    call_cnt   BIGINT DEFAULT 0,
    fail_cnt   BIGINT DEFAULT 0,
    rows_cnt   BIGINT DEFAULT 0,
    cost_sum   BIGINT DEFAULT 0,
    UNIQUE(app_id, api_id, stat_day)
);

-- ============ 批量数据交换（4.9） ============

CREATE TABLE IF NOT EXISTS batch_task (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_name    VARCHAR(128) NOT NULL,
    ds_id        BIGINT NOT NULL,
    sql_text     TEXT,
    file_name    VARCHAR(256) DEFAULT '',        -- 导出文件名
    file_format  VARCHAR(10) DEFAULT 'CSV',      -- CSV（分隔符可配）
    charset      VARCHAR(20) DEFAULT 'UTF-8',
    schedule     VARCHAR(64) DEFAULT '',         -- 定时 HH:mm，空=手动
    encrypt      INT DEFAULT 0,                  -- 文件加密（DS-BAT-01）
    watermark    VARCHAR(256) DEFAULT '',        -- 导出水印（DS-SEC-08）
    status       VARCHAR(20) DEFAULT 'IDLE',     -- IDLE/RUNNING/SUCCESS/FAIL
    last_run     TIMESTAMP,
    last_rows    BIGINT DEFAULT 0,
    last_file    VARCHAR(512) DEFAULT '',
    error_msg    VARCHAR(1024) DEFAULT '',
    owner        VARCHAR(64) DEFAULT '',
    create_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 批量任务运行记录（DS-BAT-06）
CREATE TABLE IF NOT EXISTS batch_run (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id     BIGINT NOT NULL,
    status      VARCHAR(20),
    rows_cnt    BIGINT DEFAULT 0,
    file_path   VARCHAR(512) DEFAULT '',
    error_msg   VARCHAR(1024) DEFAULT '',
    start_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    end_time    TIMESTAMP
);
