-- ============================================================================
-- 微服务接口设计小场景最佳实践 —— DDL
--
-- 执行方式：应用启动时自动执行（spring.sql.init.mode=always），或手工执行一次。
-- 全部使用 CREATE TABLE IF NOT EXISTS，可重复执行。
-- 生产环境注意：应用账号不应有 DDL 权限，把 mode 改成 never，走发布流程执行。
--
-- ★ 全篇最重要的一条：下面这些 uk_ 开头的唯一索引，是「幂等」唯一的正确性保证。
--   Redis、分布式锁、本地缓存都只是「挡在前面减少无效请求」的手段，它们会挂、会过期、
--   会写错，不能作为正确性依据。删掉唯一索引 = 直接超发。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 场景1：活动 + 优惠券
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_activity
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    activity_code VARCHAR(64)  NOT NULL COMMENT '活动编码（对外暴露，别用自增 id 传参，防遍历）',
    name          VARCHAR(128) NOT NULL COMMENT '活动名称',
    -- 状态机字段：0=未开始 1=进行中 2=已结束 3=已下线
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0未开始 1进行中 2已结束 3已下线',
    start_time    DATETIME     NOT NULL COMMENT '开始时间',
    end_time      DATETIME     NOT NULL COMMENT '结束时间',
    total_stock   INT          NOT NULL DEFAULT 0 COMMENT '总库存（DB 为最终事实，Redis 只是缓存）',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 活动编码是业务唯一键：接口按 code 查，必须有唯一索引，否则可能查出两条
    UNIQUE KEY uk_activity_code (activity_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='活动表';

CREATE TABLE IF NOT EXISTS t_coupon
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    coupon_no   VARCHAR(32) NOT NULL COMMENT '券号（对外唯一标识）',
    user_id     BIGINT      NOT NULL COMMENT '用户ID',
    activity_id BIGINT      NOT NULL COMMENT '活动ID',
    -- 券状态机：0=未使用 1=已使用 2=已过期
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0未使用 1已使用 2已过期',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- ★★★ 一人一单的最终防线。并发穿透 Redis 后靠它兜底（捕获 DuplicateKeyException）。
    --     注意：这里必须是「业务唯一键」，不能有 status 之类的可变字段，
    --     否则用户领第二次时 status 变了，唯一索引就不再约束了。
    UNIQUE KEY uk_user_activity (user_id, activity_id),
    UNIQUE KEY uk_coupon_no (coupon_no),
    -- 支撑「我的券列表」按用户倒序查询
    KEY idx_user_status_create (user_id, status, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='优惠券表';

-- ----------------------------------------------------------------------------
-- 场景2、场景4：订单（支付回调 / 超时关闭共用）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS txm_order
(
    id            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no      VARCHAR(32)    NOT NULL COMMENT '业务订单号（对外）',
    out_trade_no  VARCHAR(64)    NOT NULL COMMENT '支付网关侧的商户订单号（回调按它对账）',
    user_id       BIGINT         NOT NULL COMMENT '用户ID',
    goods_id      BIGINT         NOT NULL COMMENT '商品ID',
    num           INT            NOT NULL DEFAULT 1 COMMENT '购买数量（释放库存时用）',
    -- 金额以分为单位用 BIGINT，或 DECIMAL(12,2)；禁止用 double/float
    amount        DECIMAL(12, 2) NOT NULL COMMENT '订单金额（以订单表为准，不信任回调金额）',
    pay_channel   TINYINT        NOT NULL DEFAULT 0 COMMENT '0未支付 1支付宝 2微信 4余额',
    -- 状态机：1=待支付 2=已支付 3=已发货 4=已关闭（取消）5=已完成
    status        TINYINT        NOT NULL DEFAULT 1 COMMENT '1待支付 2已支付 3已发货 4已关闭 5已完成',
    pay_time      DATETIME       NULL COMMENT '支付时间',
    close_time    DATETIME       NULL COMMENT '关闭时间',
    create_time   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- ★ 回调幂等第一道：一个商户订单号只能有一条订单
    UNIQUE KEY uk_out_trade_no (out_trade_no),
    UNIQUE KEY uk_order_no (order_no),
    -- ★ 兜底扫表任务用：(status, create_time) 组合索引，避免全表扫描
    --   扫「待支付且创建时间早于 30 分钟前」时必须能走这个索引
    KEY idx_status_create_time (status, create_time),
    KEY idx_user_create_time (user_id, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='订单表';

-- ----------------------------------------------------------------------------
-- 场景4：库存（关单后释放）+ 库存占用流水（保证释放动作自身幂等）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_goods_stock
(
    id           BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    goods_id     BIGINT   NOT NULL COMMENT '商品ID',
    stock        INT      NOT NULL DEFAULT 0 COMMENT '可售库存',
    locked_stock INT      NOT NULL DEFAULT 0 COMMENT '已锁定（下单未支付）库存',
    update_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_goods_id (goods_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品库存表';

CREATE TABLE IF NOT EXISTS t_order_stock_lock
(
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id    BIGINT   NOT NULL COMMENT '订单ID',
    goods_id    BIGINT   NOT NULL COMMENT '商品ID',
    num         INT      NOT NULL COMMENT '占用数量',
    -- 1=占用中 2=已释放 3=已扣减（支付成功转为真实扣减）
    status      TINYINT  NOT NULL DEFAULT 1 COMMENT '1占用中 2已释放 3已扣减',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- ★ 一单一商品只有一条占用记录：释放动作靠「条件更新这条记录」来保证幂等，
    --   不能因为「订单已关闭」就假设「库存一定没释放过」
    UNIQUE KEY uk_order_goods (order_id, goods_id),
    KEY idx_status_create (status, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='库存占用流水';

-- ----------------------------------------------------------------------------
-- 场景5：导出任务
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_export_task
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    task_id     VARCHAR(32)  NOT NULL COMMENT '任务号（对外，形如 E20260929001）',
    request_id  VARCHAR(64)  NOT NULL COMMENT '调用方请求号（幂等键，与 user_id 组成唯一索引）',
    user_id     BIGINT       NOT NULL COMMENT '提交人',
    biz_type    VARCHAR(32)  NOT NULL COMMENT '业务类型',
    query_json  TEXT         NOT NULL COMMENT '查询条件快照（JSON，任务重建时用）',
    -- 状态机：PENDING -> RUNNING -> SUCCESS / FAILED（严格单向，不允许回退）
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    progress    INT          NOT NULL DEFAULT 0 COMMENT '进度 0-100',
    total_rows  INT          NULL COMMENT '总行数（可选，知道进度才有意义）',
    result_url  VARCHAR(512) NULL COMMENT '结果文件地址',
    error_msg   VARCHAR(512) NULL COMMENT '失败原因（脱敏后给用户看）',
    expire_at   DATETIME     NULL COMMENT '结果文件过期时间',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- ★ 幂等：同一用户同一 requestId 只允许一个任务
    UNIQUE KEY uk_request_user (request_id, user_id),
    UNIQUE KEY uk_task_id (task_id),
    -- 定时清理过期文件、扫描卡住的任务都靠它
    KEY idx_status_update_time (status, update_time),
    KEY idx_expire_at (expire_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='导出任务表';

-- ----------------------------------------------------------------------------
-- 初始化演示数据（可重复执行）
-- ----------------------------------------------------------------------------
INSERT INTO t_activity (activity_code, name, status, start_time, end_time, total_stock)
VALUES ('SPRING_2026', '春节领券活动', 1, '2026-01-01 00:00:00', '2026-12-31 23:59:59', 100)
ON DUPLICATE KEY UPDATE update_time = CURRENT_TIMESTAMP;

INSERT INTO t_goods_stock (goods_id, stock, locked_stock)
VALUES (1001, 500, 0)
ON DUPLICATE KEY UPDATE update_time = CURRENT_TIMESTAMP;
