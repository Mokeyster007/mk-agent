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
COMMENT ON COLUMN agent_run.agent_type IS 'Agent 类型：CHAT / MANUS / FILE';
COMMENT ON COLUMN agent_run.user_prompt IS '用户输入的任务（不保存系统提示词）';
COMMENT ON COLUMN agent_run.state IS '任务状态：RUNNING / SUCCEEDED / FAILED / CANCELLED / TIMED_OUT / MAX_STEPS_REACHED';
COMMENT ON COLUMN agent_run.current_step IS '已执行的 Agent Loop 轮数';
COMMENT ON COLUMN agent_run.tool_call_count IS '累计工具调用次数';
COMMENT ON COLUMN agent_run.final_answer IS '模型最终回答';
COMMENT ON COLUMN agent_run.error_message IS '脱敏后的失败原因摘要（不含堆栈）';
COMMENT ON COLUMN agent_run.model IS '本次任务实际使用的模型名（来自模型响应 metadata）';
COMMENT ON COLUMN agent_run.prompt_tokens IS '累计输入 Token 数（模型未返回 usage 时为 NULL）';
COMMENT ON COLUMN agent_run.completion_tokens IS '累计输出 Token 数';
COMMENT ON COLUMN agent_run.total_tokens IS '累计总 Token 数（成本统计主键）';
COMMENT ON COLUMN agent_run.total_cost_millis IS '任务总耗时（毫秒）';
