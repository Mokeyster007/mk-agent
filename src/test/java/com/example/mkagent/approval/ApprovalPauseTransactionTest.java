package com.example.mkagent.approval;

import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.MusicAgent;
import com.example.mkagent.model.AgentEvent;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import com.example.mkagent.service.AgentRunRecorder;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.tools.PlaylistWriteTool;
import com.example.mkagent.tools.ToolEventMessageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.support.AopUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 暂停事务的故障注入（批次 1 补充）。
 *
 * 注入方式不用 Mock：先插一条与本次暂停同 (run_id, step) 的审批记录，
 * 让"写审批记录"这一步撞上唯一索引 {@code uk_agent_approval_run_step}。
 * 这样失败发生在**事务内部、检查点写入之后**，且是真实数据库异常，
 * 比 mock 抛异常更能证明回滚语义。
 *
 * 三个层次：
 * 1. 服务层：事务代理调用 → 异常穿过边界 → 三张表全部回滚；
 * 2. Agent 层（同步）：内存 ctx 不残留假暂停，落库为 FAILED；
 * 3. Agent 层（SSE）：不发送 approval_required，客户端拿不到不存在的 approvalId。
 */
@HermeticSpringBootTest
@Timeout(60)
class ApprovalPauseTransactionTest {

    private static final String USER = "u-tx-it";

    /**
     * 预埋记录的 approval_id 按 runId 派生：同一个测试类里多次埋雷不会撞主键。
     */
    private static final String SEED_APPROVAL_PREFIX = "ap-seed-";

    @Autowired
    private PlaylistWriteTool playlistWriteTool;

    @Autowired
    private ApprovalPauseGateway approvalPauseGateway;

    @Autowired
    private ApprovalSummaryBuilder approvalSummaryBuilder;

    @Autowired
    private ApprovalToolPolicy approvalToolPolicy;

    @Autowired
    private RunHandoffLocks runHandoffLocks;

    @Autowired
    private AgentTaskRegistry taskRegistry;

    @Autowired
    private AgentRunRecorder runRecorder;

    @Autowired
    private AgentConcurrencyGuard concurrencyGuard;

    @Autowired
    private ToolEventMessageProvider toolEventMessageProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("服务层：审批写入失败 → 运行状态与检查点一起回滚，不留孤儿暂停")
    void persistRollsBackEverythingWhenApprovalInsertFails() {
        /*
         * 事务代理证据：注入的是接口，实际对象是 @Transactional 代理。
         * 没有代理就没有事务边界，回滚断言会全部失效。
         */
        assertTrue(AopUtils.isAopProxy(approvalPauseGateway),
                "ApprovalPauseGateway 必须由事务代理实现，实际："
                        + approvalPauseGateway.getClass());

        AgentRunContext ctx = new AgentRunContext();
        String runId = ctx.getRunId().toString();
        ctx.setUserId(USER);
        ctx.setAgentType(AgentType.MUSIC_USER);
        ctx.transitionTo(AgentState.RUNNING);
        runRecorder.recordStart(ctx, "帮我建个歌单");
        ctx.nextStep();

        // 埋雷：同 (run_id, step) 的审批记录已存在 → 本次插入必然撞唯一索引
        seedConflictingApproval(runId, ctx.getCurrentStep());

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> approvalPauseGateway.persist(ctx, checkpoint(runId), summary()));

        assertNotNull(error, "异常必须穿过事务边界抛到调用方");

        // 1. agent_run 的暂停 CAS 被回滚：仍是 RUNNING，没有 finished_at
        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT state, approval_count, finished_at, active_elapsed_millis "
                        + "FROM agent_run WHERE run_id = ?", runId);

        assertEquals("RUNNING", run.get("state"), "暂停写入失败后不得留下 WAITING_APPROVAL");
        assertEquals(0, ((Number) run.get("approval_count")).intValue(),
                "approval_count 自增必须一起回滚");
        assertNull(run.get("finished_at"));

        // 2. 检查点被回滚：不能出现"有检查点但没有审批"的半截状态
        assertEquals(0, count(
                        "SELECT COUNT(*) FROM agent_checkpoint WHERE run_id = ?", runId),
                "检查点必须随事务回滚");

        // 3. 审批表只剩埋的那一条，没有新增
        assertEquals(1, count(
                        "SELECT COUNT(*) FROM agent_approval WHERE run_id = ?", runId),
                "只应剩下预埋的那条，本次插入必须回滚");
    }

    @Test
    @DisplayName("Agent 同步路径：暂停落库失败 → 任务 FAILED，ctx 不残留 WAITING_APPROVAL")
    void agentDoesNotStayPausedWhenPersistFails() {
        ScriptedModel model = new ScriptedModel(
                "call-tx", "create_playlist", "{\"name\":\"夜曲\",\"songIds\":[1]}");

        MusicAgent agent = newAgent(model, approvalPauseGateway);

        AgentRunContext ctx = new AgentRunContext();
        String runId = ctx.getRunId().toString();

        // 埋雷必须在 run 之前：暂停发生在第 1 步
        seedConflictingApproval(runId, 1);

        String answer = agent.run("帮我建一个歌单", ctx);

        assertTrue(answer.startsWith("执行错误"), "应返回脱敏后的错误摘要，实际：" + answer);

        // 1. 内存状态：不能停在"假暂停"
        assertEquals(AgentState.FAILED, ctx.getState(), "落库失败必须转 FAILED");
        assertNull(ctx.getPendingApprovalJson(), "失败时不能准备 approval_required 事件");

        // 2. 落库：FAILED 且有 finished_at，稳定原因码可查
        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT state, finished_at, error_code FROM agent_run WHERE run_id = ?",
                runId);

        assertEquals("FAILED", run.get("state"));
        assertNotNull(run.get("finished_at"), "失败是终态，必须写 finished_at");
        assertEquals("APPROVAL_PERSIST_FAILED", run.get("error_code"));

        // 3. 没有孤儿：检查点为空，审批表只剩埋的那条
        assertEquals(0, count(
                "SELECT COUNT(*) FROM agent_checkpoint WHERE run_id = ?", runId));
        assertEquals(1, count(
                "SELECT COUNT(*) FROM agent_approval WHERE run_id = ?", runId));

        // 4. 资源清理：注册表与许可恰好回收一次
        assertEquals(0, taskRegistry.size());
        assertEquals(concurrencyGuard.getMaxConcurrency(),
                concurrencyGuard.availablePermits());
        assertFalse(runHandoffLocks.exists(runId), "交接锁必须释放");
    }

    @Test
    @DisplayName("SSE 路径：暂停落库失败 → 不发 approval_required，客户端拿不到假 approvalId")
    void sseNeverEmitsApprovalIdWhenPersistFails() {
        ScriptedModel model = new ScriptedModel(
                "call-sse", "create_playlist", "{\"name\":\"夜曲\",\"songIds\":[1]}");

        ApprovalPauseGateway failingGateway = new ApprovalPauseGateway() {
            @Override
            public PauseResult persist(AgentRunContext ctx,
                                       ApprovalCheckpoint checkpoint,
                                       ApprovalSummary summary) {
                throw new IllegalStateException("模拟审批写入失败");
            }

            @Override
            public void abandon(String approvalId, String reason) {
            }
        };

        CapturingMusicAgent agent = newAgent(model, failingGateway);

        agent.runStream("帮我建一个歌单");

        List<String> types = agent.events.stream().map(AgentEvent::getType).toList();

        assertFalse(types.contains("approval_required"),
                "落库失败绝不能推送 approval_required（客户端会拿到不存在的 id），实际：" + types);
        assertTrue(types.contains("error"), "应推送脱敏错误事件，实际：" + types);
        assertFalse(types.contains("final_answer"), "失败时不应有最终回答，实际：" + types);

        // 库里也不能有这条审批
        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT run_id, state FROM agent_run ORDER BY id DESC LIMIT 1");

        assertEquals("FAILED", run.get("state"));
        assertEquals(0, count(
                        "SELECT COUNT(*) FROM agent_approval WHERE run_id = ?", run.get("run_id")),
                "失败路径不得留下审批记录");
        assertNotEquals("WAITING_APPROVAL", run.get("state"));
    }

    // ==================== 辅助 ====================

    private void seedConflictingApproval(String runId, int step) {
        jdbcTemplate.update(
                "INSERT INTO agent_approval "
                        + "(approval_id, run_id, user_id, agent_type, step, primary_tool, "
                        + " tool_count, args_hash, summary_text, summary_json, "
                        + " status, resume_state, resume_attempts, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + " 'PENDING', 'PENDING_RESUME', 0, ?)",
                SEED_APPROVAL_PREFIX + runId, runId, USER, "MUSIC_USER", step,
                "create_playlist",
                1, "seed-hash", "预埋冲突记录", null,
                LocalDateTime.now().plusMinutes(10));
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private ApprovalCheckpoint checkpoint(String runId) {
        String arguments = "{\"name\":\"夜曲\",\"songIds\":[1]}";

        return new ApprovalCheckpoint(
                ApprovalCheckpoint.CURRENT_SCHEMA_VERSION,
                "cfg-test",
                runId,
                USER,
                AgentType.MUSIC_USER.name(),
                1,
                0,
                List.of(),
                new ApprovalCheckpoint.CheckpointMessage(
                        "assistant", "",
                        List.of(new ApprovalCheckpoint.PendingToolCall(
                                "call-tx", "function", "create_playlist",
                                arguments, ApprovalHashes.argsHash(arguments))),
                        null),
                List.of(new ApprovalCheckpoint.PendingToolCall(
                        "call-tx", "function", "create_playlist",
                        arguments, ApprovalHashes.argsHash(arguments))),
                new ApprovalCheckpoint.BudgetSnapshot(4, 10, 30),
                new ApprovalCheckpoint.UsageSnapshot("stub", 1, 1, 2, 1, 5),
                new ApprovalCheckpoint.TimingSnapshot(
                        LocalDateTime.now().atZone(ZoneId.systemDefault()).toString(), 0),
                "whitelist-test"
        );
    }

    private ApprovalSummary summary() {
        return new ApprovalSummary(
                "create_playlist", "创建歌单", "夜曲", 1,
                List.of("城市霓虹"), 1, "创建歌单《夜曲》，共 1 首。");
    }

    private CapturingMusicAgent newAgent(ChatModel model, ApprovalPauseGateway gateway) {
        ToolCallback[] callbacks = ToolCallbacks.from(playlistWriteTool);

        CapturingMusicAgent agent = new CapturingMusicAgent(
                model, callbacks, toolEventMessageProvider);

        agent.setTaskRegistry(taskRegistry);
        agent.setRunRecorder(runRecorder);
        agent.setConcurrencyGuard(concurrencyGuard);

        agent.setApprovalToolPolicy(approvalToolPolicy);
        agent.setApprovalPauseGateway(gateway);
        agent.setApprovalSummaryBuilder(approvalSummaryBuilder);
        agent.setHandoffLocks(runHandoffLocks);

        agent.setMaxSteps(4);
        agent.setMaxToolCalls(10);
        agent.setTimeout(Duration.ofSeconds(30));

        return agent;
    }

    /**
     * 捕获 SSE 事件，不真的写连接（测试没有 HTTP 客户端）。
     */
    static class CapturingMusicAgent extends MusicAgent {

        private final List<AgentEvent> events =
                Collections.synchronizedList(new ArrayList<>());

        CapturingMusicAgent(
                ChatModel model,
                ToolCallback[] tools,
                ToolEventMessageProvider provider
        ) {
            super(model, tools, Runnable::run, provider,
                    AgentType.MUSIC_USER, "approval-tx-test",
                    MusicAgent.USER_SYSTEM_PROMPT);
        }

        @Override
        public void sendEvent(SseEmitter emitter, AgentEvent event) {
            events.add(event);
        }
    }

    /**
     * 桩模型：永远请求一次 create_playlist。
     */
    static class ScriptedModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();

        private final String callId;

        private final String toolName;

        private final String arguments;

        ScriptedModel(String callId, String toolName, String arguments) {
            this.callId = callId;
            this.toolName = toolName;
            this.arguments = arguments;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(
                            "",
                            Map.of(),
                            List.of(new AssistantMessage.ToolCall(
                                    callId, "function", toolName, arguments))))),
                    ChatResponseMetadata.builder()
                            .model("stub-model")
                            .usage(new StubUsage(10, 5))
                            .build()
            );
        }
    }

    record StubUsage(int promptTokens, int completionTokens) implements Usage {

        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfig {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }
}
