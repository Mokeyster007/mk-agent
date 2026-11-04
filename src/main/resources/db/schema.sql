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
-- 音乐业务表（M2a：数据模型）
--
-- 约定与 agent_run 一致：
-- 1. 主键由应用侧生成（雪花 / 种子固定 ID），不用数据库自增，
--    便于测试与种子数据幂等；
-- 2. 全部 IF NOT EXISTS，PostgreSQL 与 H2（PostgreSQL 兼容模式）通用；
-- 3. 时间列统一 TIMESTAMP + DEFAULT CURRENT_TIMESTAMP。
--
-- 权限模型：写工具在 M3 之前不进任何白名单，
-- 因此本批只有只读工具会用到这些表。
-- =============================================================

-- 曲库。刻意不建 play_count：
-- 播放次数是"聚合结果"，应该由 music_play_log 聚合得出，
-- 冗余计数会在并发写入时产生偏差，且需要额外的补偿任务。
CREATE TABLE IF NOT EXISTS music_song
(
    id               BIGINT       NOT NULL,
    title            VARCHAR(200) NOT NULL,
    artist           VARCHAR(200) NOT NULL,
    album            VARCHAR(200),
    duration_seconds INT,
    genre            VARCHAR(64),
    release_year     INT,
    CONSTRAINT pk_music_song PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_music_song_title ON music_song (title);
CREATE INDEX IF NOT EXISTS idx_music_song_artist ON music_song (artist);

-- 歌单。is_public 决定"别人的歌单能不能被看到"，
-- 私有歌单只有 owner 本人可读（由服务端按 music_playlist.user_id 校验）。
--
-- id 由数据库生成（identity），不再由应用算 MAX(id)+1：
-- 并发创建时 MAX+1 会算出同一个值，主键冲突让其中一个事务回滚。
-- GENERATED BY DEFAULT 而不是 ALWAYS：种子与测试可以显式插入 id 做固定引用，
-- 而显式插值**不会**推进序列，所以生产写入一律不传 id。
CREATE TABLE IF NOT EXISTS music_playlist
(
    id          BIGINT       GENERATED BY DEFAULT AS IDENTITY,
    user_id     VARCHAR(64)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    is_public   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_music_playlist PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_music_playlist_user_id ON music_playlist (user_id);

-- 同一用户不允许同名歌单；不同用户之间互不影响。
CREATE UNIQUE INDEX IF NOT EXISTS uk_music_playlist_user_name
    ON music_playlist (user_id, name);

-- 歌单-歌曲关联。
-- 主键 (playlist_id, song_id)：同一首歌在一个歌单里只能出现一次；
-- position 只表达展示顺序，不参与唯一性约束。
CREATE TABLE IF NOT EXISTS music_playlist_song
(
    playlist_id BIGINT    NOT NULL,
    song_id     BIGINT    NOT NULL,
    position    INT       NOT NULL,
    added_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_music_playlist_song PRIMARY KEY (playlist_id, song_id)
);

CREATE INDEX IF NOT EXISTS idx_music_playlist_song_playlist
    ON music_playlist_song (playlist_id, position);

-- 播放日志。只记"谁在什么时候听了哪首歌、从哪来"，
-- 不记设备指纹、IP 等个人信息。
-- id 同样由数据库生成：播放日志将来由 log_play 写入，
-- 若沿用 MAX(id)+1，并发写入会撞主键。
CREATE TABLE IF NOT EXISTS music_play_log
(
    id         BIGINT      GENERATED BY DEFAULT AS IDENTITY,
    user_id    VARCHAR(64) NOT NULL,
    song_id    BIGINT      NOT NULL,
    source     VARCHAR(32),
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_music_play_log PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_music_play_log_user_created
    ON music_play_log (user_id, created_at);

-- 用户角色。
-- 管理员身份的唯一可信来源是这张表（服务端查询），
-- 不接受请求参数、请求头或提示词里的"我是管理员"。
CREATE TABLE IF NOT EXISTS music_user
(
    user_id    VARCHAR(64) NOT NULL,
    role       VARCHAR(16) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_music_user PRIMARY KEY (user_id)
);

-- 写工具幂等表（M2 只建表，M3 启用）。
-- 主键 (user_id, idempotency_key)：幂等键由**服务端派生**，不暴露给模型，
-- 派生方案与取舍见 docs/music-idempotency-research.md。
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

-- =============================================================
-- music_tool_idempotency 增量升级（批次 3，幂等）：
--
-- 1. 身份判定只看 idempotency_key，请求一致性只看 request_hash，
--    run_id / step / tool_call_id 只是**排查字段**，不能替代前两者；
-- 2. tool_call_id 由模型给出，长度不可控，且**截断会改变身份**，
--    因此用 TEXT 而不是 VARCHAR(n)；
-- 3. 不新增 status 列：占位行与业务写入、成功结果在同一事务提交，
--    "行存在"即"业务已提交且结果可回放"，没有中间态可表达。
-- =============================================================
ALTER TABLE music_tool_idempotency ADD COLUMN IF NOT EXISTS run_id VARCHAR(64);
ALTER TABLE music_tool_idempotency ADD COLUMN IF NOT EXISTS step INT;
ALTER TABLE music_tool_idempotency ADD COLUMN IF NOT EXISTS tool_call_id TEXT;
ALTER TABLE music_tool_idempotency
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

COMMENT ON TABLE music_song IS '曲库（虚构种子数据，不含真实版权内容）';
COMMENT ON COLUMN music_song.duration_seconds IS '时长（秒）';
COMMENT ON COLUMN music_song.release_year IS '发行年份';
COMMENT ON TABLE music_playlist IS '歌单（公开 / 私有）';
COMMENT ON COLUMN music_playlist.is_public IS '是否公开：TRUE 任何人可读，FALSE 仅 owner 可读';
COMMENT ON TABLE music_playlist_song IS '歌单-歌曲关联（同一歌单内歌曲不重复）';
COMMENT ON COLUMN music_playlist_song.position IS '歌单内展示顺序（从 1 开始，不参与唯一性约束）';
COMMENT ON TABLE music_play_log IS '播放日志（不含设备指纹 / IP 等个人信息）';
COMMENT ON TABLE music_user IS '用户角色表：管理员身份的唯一可信来源';
COMMENT ON COLUMN music_user.role IS '角色：USER / ADMIN';
COMMENT ON TABLE music_tool_idempotency IS '写工具幂等记录（M3 启用；幂等键由服务端派生）';
