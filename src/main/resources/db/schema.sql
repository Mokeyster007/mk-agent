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
CREATE TABLE IF NOT EXISTS music_playlist
(
    id          BIGINT       NOT NULL,
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
CREATE TABLE IF NOT EXISTS music_play_log
(
    id         BIGINT      NOT NULL,
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
