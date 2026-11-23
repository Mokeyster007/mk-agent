-- =============================================================
-- agent_run：Agent 任务运行记录表（PostgreSQL 方言）
--
-- 设计要点：
-- 1. id：数据库主键，MyBatis-Plus ASSIGN_ID（雪花）生成，不依赖数据库自增；
-- 2. run_id：业务唯一键（AgentRunContext 生成的 UUID），唯一索引；
-- 3. user_id / state / created_at：高频查询维度，建立普通索引；
-- 4. user_prompt / final_answer / error_message：长文本，使用 TEXT；
-- 5. 安全约束：不保存 API Key、系统提示词、Cookie、敏感工具原始结果；
--    error_message 只保存脱敏后的异常摘要。
-- 6. 全部语句幂等（IF NOT EXISTS），应用启动时可重复执行。
-- =============================================================

CREATE TABLE IF NOT EXISTS agent_run
(
    id                BIGINT       NOT NULL,
    run_id            VARCHAR(64)  NOT NULL,
    user_id           VARCHAR(64)  NOT NULL,
    agent_type        VARCHAR(32)  NOT NULL,
    user_prompt       TEXT,
    state             VARCHAR(32)  NOT NULL,
    current_step      INT          NOT NULL DEFAULT 0,
    tool_call_count   INT          NOT NULL DEFAULT 0,
    tool_trace        TEXT,
    final_answer      TEXT,
    error_message     TEXT,
    model             VARCHAR(64),
    prompt_tokens     BIGINT,
    completion_tokens BIGINT,
    total_tokens      BIGINT,
    started_at        TIMESTAMP,
    finished_at       TIMESTAMP,
    total_cost_millis BIGINT,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_agent_run PRIMARY KEY (id)
);

-- =============================================================
-- 存量表增量升级（幂等）：
-- 已建过旧版 agent_run 的环境（如生产 RDS），
-- CREATE TABLE IF NOT EXISTS 不会修改已存在的表，
-- 因此用 ADD COLUMN IF NOT EXISTS 补齐 Usage 相关列。
-- 新建库（如 H2 测试）上面的 CREATE 已包含这些列，
-- 下面的语句仍幂等可重复执行。
-- =============================================================
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS model VARCHAR(64);
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS prompt_tokens BIGINT;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS completion_tokens BIGINT;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS total_tokens BIGINT;

-- 工具轨迹：按执行顺序的工具名序列（逗号分隔），
-- 例如 "web_search,web_search,web_scrape,terminate_task"。
-- 只存工具名，绝不存参数或结果原文（避免搜索词、路径、密钥入库）。
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS tool_trace TEXT;

-- run_id 唯一索引：按业务键查询任务（幂等）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_run_run_id
    ON agent_run (run_id);

-- 用户维度：分页查询"我的任务"。
CREATE INDEX IF NOT EXISTS idx_agent_run_user_id
    ON agent_run (user_id);

-- 状态维度：按 state 筛选。
CREATE INDEX IF NOT EXISTS idx_agent_run_state
    ON agent_run (state);

-- 时间维度：分页默认按创建时间倒序。
CREATE INDEX IF NOT EXISTS idx_agent_run_created_at
    ON agent_run (created_at);

COMMENT ON TABLE agent_run IS 'Agent 任务运行记录';
COMMENT ON COLUMN agent_run.run_id IS '任务业务唯一键（UUID）';
COMMENT ON COLUMN agent_run.user_id IS '任务归属用户（当前来自请求头 X-User-Id，占位方案）';
COMMENT ON COLUMN agent_run.agent_type IS 'Agent 类型：CHAT / MANUS / FILE / EVAL（EVAL 仅用于离线评测，评测不写本表）';
COMMENT ON COLUMN agent_run.user_prompt IS '用户输入的任务（不保存系统提示词）';
COMMENT ON COLUMN agent_run.state IS '任务状态：RUNNING / SUCCEEDED / FAILED / CANCELLED / TIMED_OUT / MAX_STEPS_REACHED';
COMMENT ON COLUMN agent_run.current_step IS '已执行的 Agent Loop 轮数';
COMMENT ON COLUMN agent_run.tool_call_count IS '累计工具调用次数';
COMMENT ON COLUMN agent_run.tool_trace IS '工具名执行序列（逗号分隔，如 web_search,web_scrape,terminate_task；不含参数与结果）';
COMMENT ON COLUMN agent_run.final_answer IS '模型最终回答';
COMMENT ON COLUMN agent_run.error_message IS '脱敏后的失败原因摘要（不含堆栈）';
COMMENT ON COLUMN agent_run.model IS '本次任务实际使用的模型名（来自模型响应 metadata）';
COMMENT ON COLUMN agent_run.prompt_tokens IS '累计输入 Token 数（模型未返回 usage 时为 NULL）';
COMMENT ON COLUMN agent_run.completion_tokens IS '累计输出 Token 数';
COMMENT ON COLUMN agent_run.total_tokens IS '累计总 Token 数（成本统计主键）';
COMMENT ON COLUMN agent_run.total_cost_millis IS '任务总耗时（毫秒）';

-- =============================================================
-- M3 审批恢复（批次 1）：运行记录扩展列
--
-- execution_epoch：执行代次。首次执行 0，每次"领取审批恢复" +1。
--   所有执行期写库都带该条件，旧执行线程的迟到写入影响 0 行，
--   不会覆盖后来恢复的新代次。
-- active_elapsed_millis：累计**执行**时间，不含审批等待。
-- approval_count：本任务创建过的审批数量，用于 max-per-run 限制。
-- error_code：稳定原因码，前端只认码，不解析中文 error_message。
-- =============================================================
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS error_code VARCHAR(64);
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS execution_epoch INT NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS config_version VARCHAR(64);
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS active_elapsed_millis BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS approval_count INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_agent_run_error_code
    ON agent_run (error_code);

COMMENT ON COLUMN agent_run.error_code IS '稳定原因码：APPROVAL_EXPIRED / USER_CANCELLED / PROCESS_RESTART / CHECKPOINT_* 等';
COMMENT ON COLUMN agent_run.execution_epoch IS '执行代次：首次 0，每次恢复 +1；执行期写库均以此做 CAS';
COMMENT ON COLUMN agent_run.config_version IS '暂停时的 Agent 配置指纹（提示词/工具/参数 Schema/审批标记），恢复必须精确相等';
COMMENT ON COLUMN agent_run.active_elapsed_millis IS '累计执行时间（毫秒），不含审批等待';
COMMENT ON COLUMN agent_run.approval_count IS '本任务创建过的审批数量（用于 max-per-run 限制）';

-- =============================================================
-- 审批记录：一次暂停一行
--
-- 决定（status）与执行（resume_state）分开存：
--   status       = PENDING / APPROVED / REJECTED / CANCELLED / EXPIRED / INVALIDATED
--   resume_state = PENDING_RESUME（决定已持久化，待领取）
--                / CLAIMED（已领取执行权，尚未提交执行）
--                / RUNNING（任务线程已启动）
--                / FINISHED / FAILED
--
-- 这样"已批准但尚未执行"与"正在执行"可区分，
-- 启动清理不会把前者误判为 FAILED。
--
-- 安全约束：只存用户可读摘要与参数哈希；
-- 参数原文在 agent_checkpoint 中（执行需要），不进入摘要、不下发前端。
-- =============================================================
CREATE TABLE IF NOT EXISTS agent_approval
(
    approval_id     VARCHAR(64)  NOT NULL,
    run_id          VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    agent_type      VARCHAR(32)  NOT NULL,
    step            INT          NOT NULL,
    primary_tool    VARCHAR(64)  NOT NULL,
    tool_count      INT          NOT NULL,
    args_hash       VARCHAR(64)  NOT NULL,
    summary_text    VARCHAR(500) NOT NULL,
    summary_json    TEXT,
    status          VARCHAR(16)  NOT NULL,
    resume_state    VARCHAR(16)  NOT NULL DEFAULT 'PENDING_RESUME',
    resume_attempts INT          NOT NULL DEFAULT 0,
    decided_at      TIMESTAMP,
    expires_at      TIMESTAMP    NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_agent_approval PRIMARY KEY (approval_id)
);

-- 同一 run 的同一 step 只允许一条审批（重放不会产生第二条）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_approval_run_step
    ON agent_approval (run_id, step);

CREATE INDEX IF NOT EXISTS idx_agent_approval_pending
    ON agent_approval (status, expires_at);

CREATE INDEX IF NOT EXISTS idx_agent_approval_resume
    ON agent_approval (resume_state);

CREATE INDEX IF NOT EXISTS idx_agent_approval_user
    ON agent_approval (user_id);

-- claimed_epoch：领取时写入的执行代次（M3 批次 2）。
-- 后台线程必须用 (approval_id, resume_state='CLAIMED', claimed_epoch=?) 做条件更新
-- 把 CLAIMED 改成 RUNNING，0 行就说明"这次领取已经失效/已被别人启动"，
-- 此时一个模型调用、一个工具都不能执行。
ALTER TABLE agent_approval ADD COLUMN IF NOT EXISTS claimed_epoch INT;

COMMENT ON COLUMN agent_approval.claimed_epoch IS '领取时绑定的执行代次；启动时与 execution_epoch 一起做条件更新';

COMMENT ON TABLE agent_approval IS '审批记录：一次写工具暂停一行（M3）';
COMMENT ON COLUMN agent_approval.summary_text IS '用户可读摘要（≤500 字符，不含参数原文/密钥）';
COMMENT ON COLUMN agent_approval.args_hash IS '整轮 pending 工具调用的规范化参数哈希，批准即绑定该组参数';
COMMENT ON COLUMN agent_approval.status IS '决定状态：PENDING/APPROVED/REJECTED/CANCELLED/EXPIRED/INVALIDATED';
COMMENT ON COLUMN agent_approval.resume_state IS '执行子状态：PENDING_RESUME/CLAIMED/RUNNING/FINISHED/FAILED';

-- =============================================================
-- 检查点：暂停点恢复所需的全部信息（每个 run 一行）
--
-- 不存 system prompt 原文，只存 config_version 指纹；
-- 恢复时版本不匹配即拒绝，不用新提示词顶替。
-- =============================================================
CREATE TABLE IF NOT EXISTS agent_checkpoint
(
    run_id         VARCHAR(64) NOT NULL,
    schema_version INT         NOT NULL,
    config_version VARCHAR(64) NOT NULL,
    payload_json   TEXT        NOT NULL,
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_agent_checkpoint PRIMARY KEY (run_id)
);

COMMENT ON TABLE agent_checkpoint IS '审批暂停点检查点（M3）；不存系统提示词原文';
COMMENT ON COLUMN agent_checkpoint.schema_version IS '检查点 JSON 结构版本，当前 1';
COMMENT ON COLUMN agent_checkpoint.config_version IS 'Agent 配置指纹，恢复必须精确相等';

-- =============================================================
-- 写工具幂等表（通用框架能力，表名 music_tool_idempotency 为历史兼容保留，
-- 不对存量库做破坏性改名/删表；电商售后与下单确认写工具共用本表）。
-- =============================================================
CREATE TABLE IF NOT EXISTS music_tool_idempotency
(
    user_id         VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    tool_name       VARCHAR(64)  NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    result_json     TEXT,
    CONSTRAINT pk_music_tool_idempotency PRIMARY KEY (user_id, idempotency_key)
);

ALTER TABLE music_tool_idempotency ADD COLUMN IF NOT EXISTS run_id VARCHAR(64);
ALTER TABLE music_tool_idempotency ADD COLUMN IF NOT EXISTS step INT;
ALTER TABLE music_tool_idempotency ADD COLUMN IF NOT EXISTS tool_call_id TEXT;
ALTER TABLE music_tool_idempotency
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- =============================================================
-- 会话（同一窗口连续聊天）
-- =============================================================
CREATE TABLE IF NOT EXISTS agent_conversation
(
    id              BIGINT       NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    agent_type      VARCHAR(32)  NOT NULL DEFAULT 'AFTER_SALES',
    title           VARCHAR(200),
    refs_json       TEXT,
    last_run_id     VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_agent_conversation PRIMARY KEY (id)
);

ALTER TABLE agent_conversation
    ADD COLUMN IF NOT EXISTS agent_type VARCHAR(32) NOT NULL DEFAULT 'MUSIC_USER';

UPDATE agent_conversation SET agent_type = 'MUSIC_USER' WHERE agent_type IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_conversation_id
    ON agent_conversation (conversation_id);

CREATE INDEX IF NOT EXISTS idx_agent_conversation_user
    ON agent_conversation (user_id, updated_at);

CREATE INDEX IF NOT EXISTS idx_agent_conversation_user_type
    ON agent_conversation (user_id, agent_type, updated_at);

CREATE TABLE IF NOT EXISTS agent_conversation_message
(
    id              BIGINT       NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    run_id          VARCHAR(64)  NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    content         TEXT,
    state           VARCHAR(32),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_agent_conversation_message PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_conversation_message_run_role
    ON agent_conversation_message (run_id, role);

CREATE INDEX IF NOT EXISTS idx_agent_conversation_message_conv
    ON agent_conversation_message (conversation_id, id);

ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_agent_run_conversation
    ON agent_run (conversation_id, state);

CREATE INDEX IF NOT EXISTS idx_agent_run_conversation_type
    ON agent_run (conversation_id, agent_type);

COMMENT ON TABLE agent_conversation IS '会话：一个窗口里的连续多轮（与 run 一对多）';
COMMENT ON COLUMN agent_conversation.refs_json IS '会话级业务引用（候选商品 / 订单 / 预览），只由真实工具结果写入';
COMMENT ON COLUMN agent_conversation.last_run_id IS '最近一次提交对应的 run（便于定位，不代表"当前活动 run"）';
COMMENT ON TABLE agent_conversation_message IS '会话消息：只存用户输入与模型最终回答文本，不存工具调用';
COMMENT ON COLUMN agent_conversation_message.state IS '该轮 run 的终态快照（FAILED/CANCELLED 等也要如实展示）';
COMMENT ON COLUMN agent_run.conversation_id IS '所属会话；历史独立任务为 NULL';
COMMENT ON TABLE music_tool_idempotency IS '写工具幂等记录（框架通用表，表名历史兼容保留）';

-- =============================================================
-- 商城（A0：最小商城 + 订单 + 发货出库）
--
-- 命名空间 shop_ 与音乐表完全分开：不复用 music_song / music_user。
-- 全部语句幂等（IF NOT EXISTS），PostgreSQL 与 H2（PostgreSQL 兼容模式）通用。
--
-- 这一批只建 A0 需要的六张表。
-- **不提前**建售后申请表、退货单、仓库验收表或 ERP 通用模型：
-- A1 / B 的表结构要在"资格校验规则"和"验收规则"定下来之后再加，
-- 提前建表只会把还没想清楚的状态机固化进数据库。
-- =============================================================

-- 商品。A0 里**一行就是一个可售 SKU**（单仓、单规格）。
--
-- 为什么不拆 product + sku 两张表：
-- 本轮没有颜色/尺码这类变体，拆开只会多一次 join 和一张永远只有一行的表。
-- 将来真出现多规格时，再加 shop_sku 表并让订单行指向 sku，
-- 而不是现在先把变体模型猜一遍。
--
-- price_cents 用"分"存整数：金额绝不进浮点数。
CREATE TABLE IF NOT EXISTS shop_product
(
    id          BIGINT       NOT NULL,
    sku         VARCHAR(64)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    subtitle    VARCHAR(200),
    price_cents BIGINT       NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_shop_product PRIMARY KEY (id),
    CONSTRAINT uk_shop_product_sku UNIQUE (sku),
    CONSTRAINT ck_shop_product_price CHECK (price_cents >= 0),
    CONSTRAINT ck_shop_product_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

-- 销售订单。一条订单在 A0 里只有一条明细（见下方唯一索引）。
--
-- 状态只有三条边：PENDING_SHIPMENT → SHIPPED → SIGNED，没有回退、没有取消。
-- CHECK 约束把这条状态机钉在数据库里：应用层写错一个字符串会直接失败，
-- 而不是悄悄产生一个"谁也不认识"的状态。
--
-- payment_mode 固定 DEMO：本轮没有真实资金交易，
-- 这一列存在的意义是**让"这是演示付款"成为可查询的事实**，
-- 而不是只写在页面文案里。
CREATE TABLE IF NOT EXISTS shop_order
(
    id           BIGINT      GENERATED BY DEFAULT AS IDENTITY,
    order_no     VARCHAR(32) NOT NULL,
    buyer_id     VARCHAR(64) NOT NULL,
    status       VARCHAR(24) NOT NULL,
    total_cents  BIGINT      NOT NULL,
    payment_mode VARCHAR(16) NOT NULL,
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    shipped_at   TIMESTAMP,
    shipped_by   VARCHAR(64),
    signed_at    TIMESTAMP,
    signed_by    VARCHAR(64),
    CONSTRAINT pk_shop_order PRIMARY KEY (id),
    CONSTRAINT uk_shop_order_no UNIQUE (order_no),
    CONSTRAINT ck_shop_order_status
        CHECK (status IN ('PENDING_SHIPMENT', 'SHIPPED', 'SIGNED')),
    CONSTRAINT ck_shop_order_total CHECK (total_cents >= 0),
    CONSTRAINT ck_shop_order_payment CHECK (payment_mode IN ('DEMO'))
);

CREATE INDEX IF NOT EXISTS idx_shop_order_buyer
    ON shop_order (buyer_id, created_at);
CREATE INDEX IF NOT EXISTS idx_shop_order_status
    ON shop_order (status, created_at);

-- 订单明细：SKU 关联 + **成交快照**（商品名、单价、数量、行小计）。
--
-- 为什么快照必须落在订单行上：
-- 商品改名、调价之后，历史订单仍然要显示"当时买的是什么、多少钱"。
-- 只存 product_id 再 join 商品表，会让历史订单随商品一起变形。
--
-- uk_shop_order_item_order：A0 明确"一张订单一条 SKU 明细"，
-- 用唯一索引表达，而不是靠服务层自觉。
CREATE TABLE IF NOT EXISTS shop_order_item
(
    id               BIGINT       GENERATED BY DEFAULT AS IDENTITY,
    order_id         BIGINT       NOT NULL,
    product_id       BIGINT       NOT NULL,
    sku              VARCHAR(64)  NOT NULL,
    product_name     VARCHAR(200) NOT NULL,
    unit_price_cents BIGINT       NOT NULL,
    quantity         INT          NOT NULL,
    line_cents       BIGINT       NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_shop_order_item PRIMARY KEY (id),
    CONSTRAINT uk_shop_order_item_order UNIQUE (order_id),
    CONSTRAINT fk_shop_order_item_order FOREIGN KEY (order_id) REFERENCES shop_order (id),
    CONSTRAINT ck_shop_order_item_qty CHECK (quantity > 0),
    CONSTRAINT ck_shop_order_item_price CHECK (unit_price_cents >= 0 AND line_cents >= 0)
);

CREATE INDEX IF NOT EXISTS idx_shop_order_item_product
    ON shop_order_item (product_id);

-- 库存余额（单仓，按 SKU 一行）。
--
-- ck_shop_inventory_available 是**最后一道**防线：
-- 即使应用层的条件更新写错，数据库也不允许出现负库存。
-- 它不替代"发货前校验 + 原子条件更新"——那两件事决定的是**用户体验**
-- （给"库存不足"而不是给"约束冲突"），约束只负责"绝不为负"。
CREATE TABLE IF NOT EXISTS shop_inventory_balance
(
    sku        VARCHAR(64) NOT NULL,
    product_id BIGINT      NOT NULL,
    available  INT         NOT NULL,
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_shop_inventory_balance PRIMARY KEY (sku),
    CONSTRAINT ck_shop_inventory_available CHECK (available >= 0)
);

-- 库存流水（只增不改）。
--
-- order_id / order_item_id / receipt_id 是可追溯的来源：
-- 出库能回到订单行，入库能回到**具体那一条收货记录**。
--
-- dedupe_key 是"一次业务动作只写一条流水"的数据库兜底：
-- <pre>
--   出库 OUT:{order_item_id}   —— 同一订单行只可能发一次货
--   入库 IN:{receipt_id}       —— 同一张收货记录只可能入一次库
-- </pre>
-- 为什么不再用 (order_item_id, movement_type) 做唯一键：
-- B2 起同一订单行可以有多笔退货入库（一张退货单分多次收货），
-- 那条索引会把合法的第二批挡在门外——它不是"防重"，而是"防业务"。
-- 正常路径仍靠订单行锁 + 状态/累计数量判断，dedupe_key 只是最后一道保险。
CREATE TABLE IF NOT EXISTS shop_inventory_movement
(
    id             BIGINT      GENERATED BY DEFAULT AS IDENTITY,
    sku            VARCHAR(64) NOT NULL,
    quantity_delta INT         NOT NULL,
    movement_type  VARCHAR(24) NOT NULL,
    order_id       BIGINT,
    order_item_id  BIGINT,
    receipt_id     BIGINT,
    dedupe_key     VARCHAR(96) NOT NULL,
    operator_id    VARCHAR(64),
    note           VARCHAR(200),
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_shop_inventory_movement PRIMARY KEY (id),
    CONSTRAINT ck_shop_inventory_movement_type
        CHECK (movement_type IN ('OUTBOUND_SHIPMENT', 'RETURN_INBOUND')),
    CONSTRAINT ck_shop_inventory_movement_delta CHECK (quantity_delta <> 0)
);

-- 存量库增量升级（幂等，可重复执行）。
--
-- 三件事：补 dedupe_key 并回填老出库流水；丢掉"同订单行同类型只能一条"的旧索引；
-- 放开 movement_type 的取值集合。
-- 老数据不需要清库：出库流水的 dedupe_key 由 order_item_id 推导出来，
-- 因此升级后 A0 的"重复发货不重复写流水"仍然成立。
ALTER TABLE shop_inventory_movement
    ADD COLUMN IF NOT EXISTS dedupe_key VARCHAR(96);

ALTER TABLE shop_inventory_movement
    ADD COLUMN IF NOT EXISTS receipt_id BIGINT;

-- 老库里的流水全部是出库，按订单行推导去重键即可。
-- 只回填出库类型：万一库里已经有别的类型（升级中断、手工修数），
-- 用 'OUT:' 前缀会让两条不同的动作算出同一个键，唯一索引当场建不起来。
UPDATE shop_inventory_movement
   SET dedupe_key = 'OUT:' || order_item_id
 WHERE dedupe_key IS NULL
   AND movement_type = 'OUTBOUND_SHIPMENT'
   AND order_item_id IS NOT NULL;

-- 兜底：其它历史行（没有订单行、或非出库类型）用主键给一个稳定键。
UPDATE shop_inventory_movement
   SET dedupe_key = 'LEGACY:' || id
 WHERE dedupe_key IS NULL;

ALTER TABLE shop_inventory_movement
    ALTER COLUMN dedupe_key SET NOT NULL;

DROP INDEX IF EXISTS uk_shop_inventory_movement_source;

CREATE UNIQUE INDEX IF NOT EXISTS uk_shop_inventory_movement_dedupe
    ON shop_inventory_movement (dedupe_key);

CREATE INDEX IF NOT EXISTS idx_shop_inventory_movement_receipt
    ON shop_inventory_movement (receipt_id);

ALTER TABLE shop_inventory_movement
    DROP CONSTRAINT IF EXISTS ck_shop_inventory_movement_type;

ALTER TABLE shop_inventory_movement
    ADD CONSTRAINT ck_shop_inventory_movement_type
        CHECK (movement_type IN ('OUTBOUND_SHIPMENT', 'RETURN_INBOUND'));

ALTER TABLE shop_inventory_movement
    DROP CONSTRAINT IF EXISTS ck_shop_inventory_movement_dedupe;

ALTER TABLE shop_inventory_movement
    ADD CONSTRAINT ck_shop_inventory_movement_dedupe
        CHECK (char_length(dedupe_key) > 0);

CREATE INDEX IF NOT EXISTS idx_shop_inventory_movement_sku
    ON shop_inventory_movement (sku, created_at);
CREATE INDEX IF NOT EXISTS idx_shop_inventory_movement_order
    ON shop_inventory_movement (order_id);

-- 商城用户角色。与 music_user **完全独立**：
-- 同一个 userId 在两张表里可以有不同角色，互不推导。
--
-- 角色是商城权限的唯一可信来源（服务端查询），
-- 不接受请求头、请求参数或提示词里声明的角色。
CREATE TABLE IF NOT EXISTS shop_user
(
    user_id    VARCHAR(64) NOT NULL,
    role       VARCHAR(16) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_shop_user PRIMARY KEY (user_id),
    CONSTRAINT ck_shop_user_role CHECK (role IN ('BUYER', 'ADMIN'))
);

COMMENT ON TABLE shop_product IS '商城商品（A0：一行一个可售 SKU，单仓单规格）';
COMMENT ON COLUMN shop_product.price_cents IS '售价（分）；金额一律用整数，不用浮点';
COMMENT ON COLUMN shop_product.status IS 'ACTIVE / INACTIVE（下架商品不可下单）';
COMMENT ON TABLE shop_order IS '销售订单（A0：演示付款，无真实资金交易）';
COMMENT ON COLUMN shop_order.order_no IS '对外订单号（页面与接口用它定位订单，不暴露自增 id）';
COMMENT ON COLUMN shop_order.status IS 'PENDING_SHIPMENT → SHIPPED → SIGNED，由 CHECK 约束限定';
COMMENT ON COLUMN shop_order.payment_mode IS '本轮固定 DEMO：演示付款，未发生真实支付';
COMMENT ON COLUMN shop_order.shipped_by IS '执行发货的后台演示人员';
COMMENT ON COLUMN shop_order.signed_by IS '执行签收标记的后台演示人员';
COMMENT ON TABLE shop_order_item IS '订单明细（含成交快照；A0 每单一条）';
COMMENT ON COLUMN shop_order_item.unit_price_cents IS '成交单价快照（分），不随商品调价变化';
COMMENT ON TABLE shop_inventory_balance IS '库存余额（单仓，按 SKU 一行；available 永不为负）';
COMMENT ON TABLE shop_inventory_movement IS '库存流水（只增不改；一次业务动作一条，见 dedupe_key）';
COMMENT ON COLUMN shop_inventory_movement.quantity_delta IS '变动数量：出库为负，退货入库为正';
COMMENT ON COLUMN shop_inventory_movement.order_item_id IS '来源订单行（出库与退货入库都指向它）';
COMMENT ON TABLE shop_user IS '商城角色表：商城权限的唯一可信来源（与 music_user 独立）';
COMMENT ON COLUMN shop_user.role IS '角色：BUYER / ADMIN';

-- =============================================================
-- 商城售后（A1：退货申请已提交、可查询）
--
-- 与 A0 的 shop_* 同命名空间，但**不复用**订单表之外的任何结构。
-- 全部语句幂等（IF NOT EXISTS），PostgreSQL 与 H2（PostgreSQL 兼容模式）通用。
--
-- 这一批只建四张表，且**不提前**建客服审批 / 仓库验收 / 退款表：
-- B 段的状态机（已批准 / 已驳回 / 已收货 / 已验货）现在还没定，
-- 提前建表只会把没想清楚的状态固化成约束。
--
-- A1 不动库存：这里没有、也不应该有 inventory 相关列。
-- =============================================================

-- 退货申请（退货单）。
--
-- 状态机（B1 起完整）：
--   SUBMITTED ──批准──→ APPROVED（保留原额度，不释放）
--   SUBMITTED ──驳回──→ REJECTED（释放本单申请数量）
-- 终态不可回退，也不允许"再次决策"：唯一性由
-- shop_aftersale_decision 的 uk(return_id) 与条件更新共同保证。
--
-- 金额字段同样是**成交快照**：退款上限按订单行单价 × 数量算，
-- 商品后来调价不影响已提交的申请。
CREATE TABLE IF NOT EXISTS shop_aftersale_return
(
    id               BIGINT       GENERATED BY DEFAULT AS IDENTITY,
    return_no        VARCHAR(32)  NOT NULL,
    order_id         BIGINT       NOT NULL,
    order_item_id    BIGINT       NOT NULL,
    buyer_id         VARCHAR(64)  NOT NULL,
    sku              VARCHAR(64)  NOT NULL,
    product_name     VARCHAR(200) NOT NULL,
    unit_price_cents BIGINT       NOT NULL,
    quantity         INT          NOT NULL,
    refund_cents     BIGINT       NOT NULL,
    reason           VARCHAR(200) NOT NULL,
    status           VARCHAR(24)  NOT NULL,
    source           VARCHAR(16)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_shop_aftersale_return PRIMARY KEY (id),
    CONSTRAINT uk_shop_aftersale_return_no UNIQUE (return_no),
    CONSTRAINT fk_shop_aftersale_return_order
        FOREIGN KEY (order_id) REFERENCES shop_order (id),
    CONSTRAINT fk_shop_aftersale_return_item
        FOREIGN KEY (order_item_id) REFERENCES shop_order_item (id),
    CONSTRAINT ck_shop_aftersale_return_qty CHECK (quantity > 0),
    CONSTRAINT ck_shop_aftersale_return_status
        CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_shop_aftersale_return_source CHECK (source IN ('FORM', 'AGENT')),
    CONSTRAINT ck_shop_aftersale_return_reason CHECK (char_length(reason) > 0),
    CONSTRAINT ck_shop_aftersale_return_amount
        CHECK (unit_price_cents >= 0 AND refund_cents >= 0)
);

-- 存量库增量升级：状态机的取值集合（幂等，可重复执行）。
--
-- 为什么必须显式 DROP + ADD 而不是只改上面的 CREATE TABLE：
-- CREATE TABLE IF NOT EXISTS 对已存在的表**什么都不做**，
-- 旧库里那条 CHECK 仍然只允许 'SUBMITTED'，B1 的批准/驳回会在写入时失败。
-- DROP IF EXISTS + ADD 这一对可以重复执行：第二次 DROP 是空操作，
-- ADD 用的还是同一个约束名与同一份定义。
--
-- 先 DROP 再 ADD 的顺序也保证了"旧数据合法"这件事被顺带校验一次：
-- 如果旧库里已经有 APPROVED 这种当时不可能存在的值，ADD 会直接失败，
-- 而不是让一个没人认识的状态继续躺在表里。
ALTER TABLE shop_aftersale_return
    DROP CONSTRAINT IF EXISTS ck_shop_aftersale_return_status;

ALTER TABLE shop_aftersale_return
    ADD CONSTRAINT ck_shop_aftersale_return_status
        CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED'));

CREATE INDEX IF NOT EXISTS idx_shop_aftersale_return_buyer
    ON shop_aftersale_return (buyer_id, created_at);
CREATE INDEX IF NOT EXISTS idx_shop_aftersale_return_item
    ON shop_aftersale_return (order_item_id);

-- 订单行的可退额度（一行一条）。
--
-- 三个数必须能分开回答：已购多少（quantity_total）、已占用多少（reserved_qty）、
-- 还能退多少（quantity_total - reserved_qty）。把它做成一张表而不是
-- "每次 SUM 一遍退货单"，是为了让"已占用"成为一个**可被约束检查**的事实：
-- ck_shop_aftersale_quota_reserved 是"绝不超退"的最后一道防线，
-- 应用层写错会变成约束冲突，而不是悄悄多退。
--
-- 谁占额度：status = 'SUBMITTED' 与 'APPROVED' 都占额度；
-- 只有 'REJECTED' 不占（驳回时在同一事务里减 reserved_qty，不是删行）。
-- 这样"已批准"的申请仍然锁住它那部分数量，避免同一件商品被反复申请。
--
-- B2（收货）**不改变 reserved_qty**：收进来的件数已经把这份退货资格用掉了，
-- 再把它退回"可退"会让同一件商品被退第二次。额度只在驳回时释放；
-- 退款阶段的额度回收不在本轮。
CREATE TABLE IF NOT EXISTS shop_aftersale_quota
(
    order_item_id  BIGINT    NOT NULL,
    quantity_total INT       NOT NULL,
    reserved_qty   INT       NOT NULL DEFAULT 0,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_shop_aftersale_quota PRIMARY KEY (order_item_id),
    CONSTRAINT fk_shop_aftersale_quota_item
        FOREIGN KEY (order_item_id) REFERENCES shop_order_item (id),
    CONSTRAINT ck_shop_aftersale_quota_total CHECK (quantity_total > 0),
    CONSTRAINT ck_shop_aftersale_quota_reserved
        CHECK (reserved_qty >= 0 AND reserved_qty <= quantity_total)
);

-- 售后事件（只增不改）。
--
-- 为什么 A1 就要有它：一次提交至少要能回答"谁在什么时候提交了什么"，
-- 而 B 段的审批 / 验收会往同一个序列里追加事件。
-- 如果等到 B 段才建，A1 期间提交的申请就没有可追溯的起点。
--
-- B1 起的事件类型与状态机一一对应：
--   SUBMITTED —— 买家提交；APPROVED / REJECTED —— 客服决策。
-- **一个动作一个事件**：驳回时的额度释放在同一事务里完成，
-- 不额外写一条 RELEASED——那会让"事件条数"与"状态转换次数"对不上，
-- 排查时得先猜哪两条属于同一个动作。
CREATE TABLE IF NOT EXISTS shop_aftersale_event
(
    id         BIGINT       GENERATED BY DEFAULT AS IDENTITY,
    return_id  BIGINT       NOT NULL,
    event_type VARCHAR(24)  NOT NULL,
    actor_id   VARCHAR(64)  NOT NULL,
    note       VARCHAR(200),
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_shop_aftersale_event PRIMARY KEY (id),
    CONSTRAINT fk_shop_aftersale_event_return
        FOREIGN KEY (return_id) REFERENCES shop_aftersale_return (id),
    CONSTRAINT ck_shop_aftersale_event_type
        CHECK (event_type IN ('SUBMITTED', 'APPROVED', 'REJECTED'))
);

-- 存量库增量升级：事件类型集合（幂等，理由同 ck_shop_aftersale_return_status）。
ALTER TABLE shop_aftersale_event
    DROP CONSTRAINT IF EXISTS ck_shop_aftersale_event_type;

ALTER TABLE shop_aftersale_event
    ADD CONSTRAINT ck_shop_aftersale_event_type
        CHECK (event_type IN ('SUBMITTED', 'APPROVED', 'REJECTED'));

CREATE UNIQUE INDEX IF NOT EXISTS uk_shop_aftersale_event_once
    ON shop_aftersale_event (return_id, event_type);

-- 客服决策记录（B1：一单一决）。
--
-- 为什么单独一张表，而不是只在退货单上加 decided_by / decided_at：
-- 决策需要保留**原因**与**不可变性**。放在退货单上时，任何一次
-- UPDATE 都能顺手改掉"当时是谁批的、为什么驳回"，审计价值当场归零；
-- 单独一张只增不改的表，让"这一单被谁在什么时候怎么处理"成为事实记录。
--
-- uk(return_id) 是"一单一决"的数据库兜底：即使应用层漏了条件判断，
-- 第二次决策也插不进来。但**不依赖**它来处理重试——重试由
-- "条件状态转换 UPDATE ... WHERE status = 'SUBMITTED'" 挡住，
-- 不会先释放一次额度再靠撞唯一键回滚。
CREATE TABLE IF NOT EXISTS shop_aftersale_decision
(
    id         BIGINT       GENERATED BY DEFAULT AS IDENTITY,
    return_id  BIGINT       NOT NULL,
    decision   VARCHAR(16)  NOT NULL,
    reason     VARCHAR(200),
    decided_by VARCHAR(64)  NOT NULL,
    decided_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_shop_aftersale_decision PRIMARY KEY (id),
    CONSTRAINT fk_shop_aftersale_decision_return
        FOREIGN KEY (return_id) REFERENCES shop_aftersale_return (id),
    CONSTRAINT ck_shop_aftersale_decision_kind
        CHECK (decision IN ('APPROVED', 'REJECTED')),
    -- 批准不需要原因；驳回必须写原因。用一条 CHECK 钉死，
    -- 而不是靠"前端已经校验过了"。
    CONSTRAINT ck_shop_aftersale_decision_reason
        CHECK (
            (decision = 'APPROVED' AND reason IS NULL)
            OR (decision = 'REJECTED' AND reason IS NOT NULL AND char_length(reason) > 0)
        )
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_shop_aftersale_decision_return
    ON shop_aftersale_decision (return_id);

CREATE INDEX IF NOT EXISTS idx_shop_aftersale_return_status
    ON shop_aftersale_return (status, created_at);

-- 仓库收货 / 验货记录（B2）。
--
-- 一张**已批准**的退货单可以分多次收货：每次一行，记录"这一批实收几件、
-- 其中几件合格、几件损坏"。只有合格数量增加可售库存；
-- 损坏数量留在本表，不建残次库存模块（本轮不做逆向仓）。
--
-- 为什么不把 received_qty 放在退货单上：
-- 分批收货要保留每一批的时间、操作人与验货结果，
-- 只留一个累计数会把"谁在哪一批验出 1 件损坏"永久丢掉。
-- 进度（未收货 / 部分收货 / 全部收货）由 SUM(received_qty) 与批准数量**算出来**，
-- 不给退货单加第四个状态——status 仍然只表达客服结论。
--
-- request_key 是"这一次收货动作"的身份：同键同参返回原记录，同键不同参 409。
-- 它不参与业务判断，所以不同的合法批次各用一个新键即可分别入库。
CREATE TABLE IF NOT EXISTS shop_aftersale_receipt
(
    id            BIGINT       GENERATED BY DEFAULT AS IDENTITY,
    receipt_no    VARCHAR(32)  NOT NULL,
    return_id     BIGINT       NOT NULL,
    order_item_id BIGINT       NOT NULL,
    received_qty  INT          NOT NULL,
    qualified_qty INT          NOT NULL,
    damaged_qty   INT          NOT NULL,
    note          VARCHAR(200),
    request_key   VARCHAR(64)  NOT NULL,
    request_hash  VARCHAR(64)  NOT NULL,
    operator_id   VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_shop_aftersale_receipt PRIMARY KEY (id),
    CONSTRAINT uk_shop_aftersale_receipt_no UNIQUE (receipt_no),
    CONSTRAINT uk_shop_aftersale_receipt_request UNIQUE (request_key),
    CONSTRAINT fk_shop_aftersale_receipt_return
        FOREIGN KEY (return_id) REFERENCES shop_aftersale_return (id),
    CONSTRAINT fk_shop_aftersale_receipt_item
        FOREIGN KEY (order_item_id) REFERENCES shop_order_item (id),
    -- 验货结果必须自洽：合格 + 损坏 = 实收。写在库里，而不是只靠前端校验。
    CONSTRAINT ck_shop_aftersale_receipt_qty
        CHECK (received_qty > 0
            AND qualified_qty >= 0
            AND damaged_qty >= 0
            AND qualified_qty + damaged_qty = received_qty),
    CONSTRAINT ck_shop_aftersale_receipt_note
        CHECK (note IS NULL OR char_length(note) > 0)
);

CREATE INDEX IF NOT EXISTS idx_shop_aftersale_receipt_return
    ON shop_aftersale_receipt (return_id, created_at);

-- 模拟退款（B3）。
--
-- **一张退货单最多一笔**：uk(return_id) 就是这条业务规则，不是"顺手加的唯一索引"。
-- 重复点击、并发点击都只可能落一条，第二次由数据库直接拒绝。
--
-- 为什么单独一张表，而不是给退货单加 refund_no / refunded_at 三列：
-- 退款是一次**独立动作**（谁在什么时候、按什么金额、由谁操作），
-- 和"客服批准"不是同一件事；而且加了列之后，
-- "这单退过款吗"和"这单批准过吗"就混在同一行里，只能靠多列组合去猜。
--
-- amount_cents 是**整数分**，由服务端算：原订单成交单价 × 累计实收数量。
-- 不用合格件数：退款是"买家退回来的件数"，损坏件同样要退钱；
-- 只有入库这件事才关心合格与否。
--
-- 本轮不接支付平台：这张表只记录"模拟退款"，没有资金流水、没有对账、没有回调。
CREATE TABLE IF NOT EXISTS shop_aftersale_refund
(
    id           BIGINT      GENERATED BY DEFAULT AS IDENTITY,
    refund_no    VARCHAR(32) NOT NULL,
    return_id    BIGINT      NOT NULL,
    amount_cents BIGINT      NOT NULL,
    operator_id  VARCHAR(64) NOT NULL,
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_shop_aftersale_refund PRIMARY KEY (id),
    CONSTRAINT uk_shop_aftersale_refund_no UNIQUE (refund_no),
    -- 一张退货单最多一笔模拟退款
    CONSTRAINT uk_shop_aftersale_refund_return UNIQUE (return_id),
    CONSTRAINT fk_shop_aftersale_refund_return
        FOREIGN KEY (return_id) REFERENCES shop_aftersale_return (id),
    -- 金额必须是正数：0 元退款没有意义，负数是算错了
    CONSTRAINT ck_shop_aftersale_refund_amount CHECK (amount_cents > 0)
);

-- 库存流水 → 收货记录的外键。
--
-- 只能后置添加：收货表在本文件里定义在流水表之后。
-- 有了它，"这条 +1 流水是哪一批验货入的库"是数据库层面的强引用，
-- 而不是一句注释。
ALTER TABLE shop_inventory_movement
    DROP CONSTRAINT IF EXISTS fk_shop_inventory_movement_receipt;

ALTER TABLE shop_inventory_movement
    ADD CONSTRAINT fk_shop_inventory_movement_receipt
        FOREIGN KEY (receipt_id) REFERENCES shop_aftersale_receipt (id);

-- 普通表单的幂等记录。
--
-- 只服务"表单提交"这一条路径：Agent 写工具走的是框架既有的
-- 写工具幂等机制（键由 runId + step + toolCallId 派生），
-- 两条路径的身份来源不同，因此**不共用**同一张记录表。
--
-- 为什么 key 是主键：并发同键时由数据库裁决，只有一个能插入成功；
-- 输的那个整体回滚，再读赢家的 return_no 原样返回。
CREATE TABLE IF NOT EXISTS shop_aftersale_idempotency
(
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash    VARCHAR(64) NOT NULL,
    return_no       VARCHAR(32) NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_shop_aftersale_idempotency PRIMARY KEY (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_shop_aftersale_idempotency_return
    ON shop_aftersale_idempotency (return_no);

COMMENT ON TABLE shop_aftersale_return IS '退货申请（B1：已提交 / 已批准 / 已驳回；收货见 shop_aftersale_receipt，模拟退款见 shop_aftersale_refund）';
COMMENT ON COLUMN shop_aftersale_return.return_no IS '对外退货单号（页面与接口用它定位，不暴露自增 id）';
COMMENT ON COLUMN shop_aftersale_return.status IS 'SUBMITTED → APPROVED（保留额度）/ REJECTED（释放额度），终态不可回退';
COMMENT ON COLUMN shop_aftersale_return.source IS 'FORM（普通表单）/ AGENT（售后 Agent 确认后提交）';
COMMENT ON COLUMN shop_aftersale_return.refund_cents IS '按订单行单价快照 × 本次退货数量估算的退款金额（分）';
COMMENT ON TABLE shop_aftersale_quota IS '订单行可退额度：已购 / 已占用 / 剩余（reserved_qty 绝不超 quantity_total）';
COMMENT ON TABLE shop_aftersale_event IS '售后事件（只增不改；同一退货单同类型事件唯一）';
COMMENT ON TABLE shop_aftersale_decision IS '客服决策记录（一单一决；只增不改，保留原因与操作人）';
COMMENT ON TABLE shop_aftersale_receipt IS '仓库收货 / 验货记录（B2：一张退货单可分批收货；合格数量才入库）';
COMMENT ON COLUMN shop_aftersale_receipt.received_qty IS '本批实收数量（> 0；累计不得超过批准数量）';
COMMENT ON COLUMN shop_aftersale_receipt.qualified_qty IS '本批合格数量（增加可售库存）';
COMMENT ON COLUMN shop_aftersale_receipt.damaged_qty IS '本批损坏 / 不可售数量（只留记录，不入库）';
COMMENT ON COLUMN shop_aftersale_receipt.request_key IS '收货动作的幂等键（同键同参返回原记录，同键不同参 409）';
COMMENT ON COLUMN shop_inventory_movement.dedupe_key IS '一次业务动作一条流水：出库 OUT:{订单行id}，入库 IN:{收货记录id}';
COMMENT ON COLUMN shop_inventory_movement.receipt_id IS '退货入库对应的收货记录（出库为 NULL）';
COMMENT ON TABLE shop_aftersale_idempotency IS '普通表单幂等记录（Agent 写工具使用框架既有幂等机制）';
COMMENT ON TABLE shop_aftersale_refund IS '模拟退款记录（B3：一张退货单最多一笔；不接支付平台，不产生真实资金流）';
COMMENT ON COLUMN shop_aftersale_refund.refund_no IS '模拟退款单号（对外展示，不暴露自增 id）';
COMMENT ON COLUMN shop_aftersale_refund.amount_cents IS '退款金额（分）= 原订单成交单价 × 累计实收数量；整数分，服务端算';
COMMENT ON COLUMN shop_aftersale_refund.operator_id IS '执行模拟退款的后台人员（服务端身份，不来自请求体）';

-- =============================================================
-- 电商导购 / 共享购物车 / 结算确认幂等（一期扩展）
--
-- 增量升级 shop_product 结构化规格列（幂等，存量库直接平滑升级）；
-- 新增共享购物车 shop_cart_item 与结算确认幂等表 shop_order_idempotency。
-- 维持 A0 订单表（shop_order / shop_order_item 单 SKU）与发货才扣库存口径不变。
-- =============================================================
ALTER TABLE shop_product ADD COLUMN IF NOT EXISTS category VARCHAR(32);
ALTER TABLE shop_product ADD COLUMN IF NOT EXISTS connectivity VARCHAR(32);
ALTER TABLE shop_product ADD COLUMN IF NOT EXISTS mac_compatible BOOLEAN;
ALTER TABLE shop_product ADD COLUMN IF NOT EXISTS silent_feature BOOLEAN;
ALTER TABLE shop_product ADD COLUMN IF NOT EXISTS switch_type VARCHAR(64);
ALTER TABLE shop_product ADD COLUMN IF NOT EXISTS battery_info VARCHAR(128);
ALTER TABLE shop_product ADD COLUMN IF NOT EXISTS weight_grams INT;

-- 共享购物车：普通页面与导购 Agent 共用同一服务与同一张表。
-- 加购不等于下单，也不锁定或扣减库存。
CREATE TABLE IF NOT EXISTS shop_cart_item
(
    id         BIGINT      GENERATED BY DEFAULT AS IDENTITY,
    buyer_id   VARCHAR(64) NOT NULL,
    product_id BIGINT      NOT NULL,
    sku        VARCHAR(64) NOT NULL,
    quantity   INT         NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_shop_cart_item PRIMARY KEY (id),
    CONSTRAINT uk_shop_cart_buyer_product UNIQUE (buyer_id, product_id),
    CONSTRAINT fk_shop_cart_product FOREIGN KEY (product_id) REFERENCES shop_product (id),
    CONSTRAINT ck_shop_cart_qty CHECK (quantity > 0 AND quantity <= 99)
);

CREATE INDEX IF NOT EXISTS idx_shop_cart_buyer
    ON shop_cart_item (buyer_id, updated_at);

-- 结算确认幂等表：同键同参重复确认返回原订单号，绝不产生第二张订单。
CREATE TABLE IF NOT EXISTS shop_order_idempotency
(
    idempotency_key VARCHAR(96) NOT NULL,
    buyer_id        VARCHAR(64) NOT NULL,
    request_hash    VARCHAR(64) NOT NULL,
    order_no        VARCHAR(32) NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_shop_order_idempotency PRIMARY KEY (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_shop_order_idempotency_order
    ON shop_order_idempotency (order_no);

COMMENT ON TABLE shop_cart_item IS '共享购物车（普通页面与导购 Agent 共用；加购不锁库存）';
COMMENT ON TABLE shop_order_idempotency IS '结算确认幂等记录（重复确认只产生一张订单）';
