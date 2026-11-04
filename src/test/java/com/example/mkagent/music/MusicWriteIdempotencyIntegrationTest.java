package com.example.mkagent.music;

import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.BaseAgent;
import com.example.mkagent.agent.MusicAgent;
import com.example.mkagent.approval.ApprovalClaimService;
import com.example.mkagent.approval.ApprovalDecisionService;
import com.example.mkagent.approval.ApprovalExecutionCompletionService;
import com.example.mkagent.approval.ApprovalPauseGateway;
import com.example.mkagent.approval.ApprovalProperties;
import com.example.mkagent.approval.ApprovalResumeService;
import com.example.mkagent.approval.ApprovalSummaryBuilder;
import com.example.mkagent.approval.ApprovalToolPolicy;
import com.example.mkagent.approval.RunHandoffLocks;
import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import com.example.mkagent.service.AgentRunRecorder;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.tools.ToolCallPlan;
import com.example.mkagent.tools.ToolCallPlanHolder;
import com.example.mkagent.tools.ToolEventMessageProvider;
import com.example.mkagent.tools.ToolResponseEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写工具幂等在真实 Agent 链路里的表现（M3 批次 3）。
 *
 * 覆盖两条执行路径：
 * 1. **普通 act**：模型直接请求写工具（本类关掉审批策略，只测幂等本身）；
 * 2. **批准后补做暂停轮**：暂停 → 批准 → {@code executeApprovedRound}。
 *
 * 两条路径都必须做到：一次逻辑调用只写一次业务、只留一条幂等记录；
 * 身份不可判定时一个字节都不写。
 */
@HermeticSpringBootTest
@Timeout(120)
class MusicWriteIdempotencyIntegrationTest {

    private static final String USER = MusicSeedInitializer.USER_ALICE;

    private static final String NAME_PREFIX = "幂等集成-";

    private static final String TOOL = "create_playlist";

    @Autowired
    @Qualifier("musicWriteToolCallbacks")
    private ToolCallback[] writeCallbacks;

    @Autowired
    private ApprovalToolPolicy approvalToolPolicy;

    @Autowired
    private ApprovalPauseGateway approvalPauseGateway;

    @Autowired
    private ApprovalSummaryBuilder approvalSummaryBuilder;

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
    private ApprovalClaimService claimService;

    @Autowired
    private ApprovalExecutionCompletionService approvalCompletionService;

    @Autowired
    private ApprovalDecisionService decisionService;

    @Autowired
    private ApprovalProperties approvalProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private final List<String> createdRuns = new ArrayList<>();

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        ToolCallPlanHolder.clear();

        for (String runId : createdRuns) {
            jdbcTemplate.update("DELETE FROM music_tool_idempotency WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_approval WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_checkpoint WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_run WHERE run_id = ?", runId);
        }
        createdRuns.clear();

        jdbcTemplate.update(
                "DELETE FROM music_playlist_song WHERE playlist_id IN "
                        + "(SELECT id FROM music_playlist WHERE name LIKE ?)",
                NAME_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM music_playlist WHERE name LIKE ?", NAME_PREFIX + "%");
    }

    // ==================== 1. 普通 act ====================

    @Test
    @DisplayName("普通 act：一次调用只写一次业务，只留一条幂等记录")
    void plainActExecutesOnceAndRecords() {
        String name = uniqueName();
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-1", TOOL, args(name))
                .answer("建好了");

        MusicAgent agent = newAgent(model, Runnable::run, 4, 10, false);
        AgentRunContext ctx = run(agent, "帮我建歌单");

        assertEquals(AgentState.SUCCEEDED, ctx.getState());
        assertEquals(1, playlistCount(name), "业务只允许写一次");
        assertEquals(1, recordCount(ctx.getRunId().toString()), "必须留下一条幂等记录");
        assertEquals(1, runToolCalls(ctx), "工具计数保持 1 次");

        Map<String, Object> record = jdbcTemplate.queryForMap(
                "SELECT tool_call_id, step FROM music_tool_idempotency WHERE run_id = ?",
                ctx.getRunId().toString());
        assertEquals("call-1", record.get("tool_call_id"));
        assertEquals(1, ((Number) record.get("step")).intValue());

        assertNull(ToolCallPlanHolder.current(), "执行完必须解绑，不能污染线程池");
    }

    @Test
    @DisplayName("同一身份再次到达（模拟重跑）：回放结果，不再写业务")
    void replayThroughCallbackDoesNotWriteAgain() {
        String name = uniqueName();
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-1", TOOL, args(name))
                .answer("建好了");

        MusicAgent agent = newAgent(model, Runnable::run, 4, 10, false);
        AgentRunContext ctx = run(agent, "帮我建歌单");
        assertEquals(1, playlistCount(name));

        /*
         * 用同一个 runId + 同一个 step 重建上下文再送达一次，
         * 这正是"同一次调用被重复执行"的样子。必须回放，而不是再写一次。
         *
         * 重复到达的真实场景发生在**任务仍在执行时**（例如恢复后重跑暂停轮），
         * 所以这里先把 run 状态摆回 RUNNING：
         * 上面那次 run 已经跑完，直接重投会被"非执行状态拒绝写入"拦下——
         * 那是另一条（正确的）保护，不是这里要验证的回放路径。
         */
        jdbcTemplate.update(
                "UPDATE agent_run SET state = 'RUNNING' WHERE run_id = ?",
                ctx.getRunId().toString());

        String replayed = invokeCallback(ctx, 1, "call-1", TOOL, args(name));

        assertTrue(ToolResponseEnvelope.isSuccess(replayed), replayed);
        assertEquals(1, playlistCount(name), "重跑不得二次写入");
        assertEquals(1, recordCount(ctx.getRunId().toString()));
    }

    @Test
    @DisplayName("任务已结束后迟到的同一调用：拒绝写入，也不回放旧结果")
    void lateCallbackAfterRunFinishedIsRejected() {
        String name = uniqueName();
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-1", TOOL, args(name))
                .answer("建好了");

        MusicAgent agent = newAgent(model, Runnable::run, 4, 10, false);
        AgentRunContext ctx = run(agent, "帮我建歌单");
        assertEquals(1, playlistCount(name));

        // run 已经 SUCCEEDED：此时再来的回调既不该写，也不该把旧结果当成新执行的结果
        String late = invokeCallback(ctx, 1, "call-1", TOOL, args(name));

        assertFalse(ToolResponseEnvelope.isSuccess(late), late);
        assertTrue(late.contains("执行状态"), late);
        assertEquals(1, playlistCount(name), "迟到回调不得二次写入");
        assertEquals(1, recordCount(ctx.getRunId().toString()), "也不得新增记录");
    }

    // ==================== 2. 身份不可判定 ====================

    @Test
    @DisplayName("缺 id：整轮写工具零副作用，模型收到稳定错误")
    void missingIdProducesNoSideEffect() {
        String name = uniqueName();
        ScriptedModel model = new ScriptedModel()
                .toolCall("", TOOL, args(name))
                .answer("这次没做成");

        MusicAgent agent = newAgent(model, Runnable::run, 4, 10, false);
        AgentRunContext ctx = run(agent, "帮我建歌单");

        assertEquals(0, playlistCount(name), "身份不可判定时一个字节都不能写");
        assertEquals(0, recordCount(ctx.getRunId().toString()));

        String response = lastToolResponseText(model.lastPrompt());
        assertTrue(response.contains("工具调用标识缺失"), response);
    }

    @Test
    @DisplayName("同轮重复 id：整轮写工具零副作用")
    void duplicatedIdProducesNoSideEffect() {
        String first = uniqueName();
        String second = uniqueName();
        ScriptedModel model = new ScriptedModel()
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("dup", "function", TOOL, args(first)),
                        new AssistantMessage.ToolCall("dup", "function", TOOL, args(second))))
                .answer("这次没做成");

        MusicAgent agent = newAgent(model, Runnable::run, 4, 10, false);
        AgentRunContext ctx = run(agent, "帮我建两个歌单");

        assertEquals(0, playlistCount(first), "重复 id 时一个歌单都不能建");
        assertEquals(0, playlistCount(second));
        assertEquals(0, recordCount(ctx.getRunId().toString()));

        String response = lastToolResponseText(model.lastPrompt());
        assertTrue(response.contains("工具调用标识重复"), response);
    }

    @Test
    @DisplayName("同名同参、不同 id：两次调用不被合并，各自独立裁决")
    void sameNameDifferentIdsAreNotMerged() {
        String name = uniqueName();
        ScriptedModel model = new ScriptedModel()
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", TOOL, args(name)),
                        new AssistantMessage.ToolCall("c2", "function", TOOL, args(name))))
                .answer("第一个建好了，第二个重名");

        MusicAgent agent = newAgent(model, Runnable::run, 4, 10, false);
        AgentRunContext ctx = run(agent, "帮我建歌单");

        /*
         * 如果两条调用被合并成一次幂等，第二条会回放成功；
         * 实际第二条必须走真实业务并被同名唯一键挡下——这正好证明没有合并。
         */
        assertEquals(1, playlistCount(name), "业务唯一键决定只能有一行");
        assertEquals(1, recordCount(ctx.getRunId().toString()),
                "失败那条的占位记录必须回滚，只剩成功的一条");

        String response = lastToolResponseText(model.lastPrompt());
        assertTrue(response.contains("同名歌单"), "第二条必须是真实业务错误：" + response);
    }

    // ==================== 3. 批准后补做暂停轮 ====================

    @Test
    @DisplayName("批准后补做暂停轮：只执行一次，只留一条记录")
    void approvedRoundExecutesOnceAndRecords() {
        String name = uniqueName();
        ScriptedModel model = new ScriptedModel()
                .toolCall("call-approved", TOOL, args(name))
                .answer("建好了");

        MusicAgent agent = newAgent(model, Runnable::run, 4, 10, true);

        AgentRunContext ctx = new AgentRunContext();
        UserContextHolder.set(USER);
        try {
            agent.run("帮我建歌单", ctx);
        } finally {
            UserContextHolder.clear();
        }

        assertEquals(AgentState.WAITING_APPROVAL, ctx.getState(), "前置条件：必须暂停");
        assertEquals(0, playlistCount(name), "暂停时不得写业务");

        String runId = ctx.getRunId().toString();
        createdRuns.add(runId);

        String approvalId = jdbcTemplate.queryForObject(
                "SELECT approval_id FROM agent_approval WHERE run_id = ?",
                String.class, runId);

        assertEquals(200, decisionService.decide(approvalId, USER, true).httpStatus());

        new ApprovalResumeService(claimService, approvalProperties, List.of(agent))
                .resume(approvalId, USER);

        assertEquals(1, playlistCount(name), "补做暂停轮只允许执行一次");
        assertEquals(1, recordCount(runId));

        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT state, tool_call_count FROM agent_run WHERE run_id = ?", runId);
        assertEquals("SUCCEEDED", run.get("state"), "实际：" + run);
        assertEquals(1, ((Number) run.get("tool_call_count")).intValue());

        assertNull(ToolCallPlanHolder.current(), "恢复执行后也要解绑计划");
    }

    // ==================== 4. 事务前提 ====================

    @Test
    @DisplayName("调用方已在事务里：直接拒绝，不冒'读不到赢家提交'的风险")
    void refusesToRunInsideCallerTransaction() {
        AgentRunContext ctx = new AgentRunContext();
        ctx.setUserId(USER);

        String name = uniqueName();
        assertThrows(IllegalStateException.class, () ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    invokeCallback(ctx, 1, "call-tx", TOOL, args(name));
                    return null;
                }));

        assertEquals(0, playlistCount(name), "被拒绝时不得有任何写入");
    }

    // ==================== 辅助 ====================

    private AgentRunContext run(MusicAgent agent, String prompt) {
        AgentRunContext ctx = new AgentRunContext();
        UserContextHolder.set(USER);
        try {
            agent.run(prompt, ctx);
        } finally {
            UserContextHolder.clear();
        }
        createdRuns.add(ctx.getRunId().toString());
        return ctx;
    }

    /**
     * 直接通过装饰后的回调再执行一次，模拟"同一调用被重复送达"。
     */
    private String invokeCallback(
            AgentRunContext origin,
            int step,
            String callId,
            String toolName,
            String argsJson
    ) {
        ToolCallPlan plan = ToolCallPlan.build(
                List.of(new AssistantMessage.ToolCall(callId, "function", toolName, argsJson)),
                Set.of(toolName));

        AgentRunContext replayCtx = new AgentRunContext(origin.getRunId());
        replayCtx.setUserId(USER);
        while (replayCtx.getCurrentStep() < step) {
            replayCtx.nextStep();
        }

        AgentRunContext previous = AgentRunContextHolder.bind(replayCtx);
        ToolCallPlan previousPlan = ToolCallPlanHolder.bind(plan);
        try {
            for (ToolCallback callback : writeCallbacks) {
                if (toolName.equals(callback.getToolDefinition().name())) {
                    return callback.call(argsJson);
                }
            }
            throw new IllegalStateException("找不到写工具：" + toolName);
        } finally {
            ToolCallPlanHolder.restore(previousPlan);
            AgentRunContextHolder.restore(previous);
        }
    }

    private String lastToolResponseText(Prompt prompt) {
        assertNotNull(prompt, "必须捕获到模型收到的 Prompt");
        List<Message> messages = prompt.getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolResponseMessage response) {
                StringBuilder text = new StringBuilder();
                response.getResponses().forEach(item -> text.append(item.responseData()));
                return text.toString();
            }
        }
        throw new AssertionError("对话历史里没有 ToolResponseMessage");
    }

    private int runToolCalls(AgentRunContext ctx) {
        Number count = jdbcTemplate.queryForObject(
                "SELECT tool_call_count FROM agent_run WHERE run_id = ?",
                Number.class, ctx.getRunId().toString());
        return count == null ? 0 : count.intValue();
    }

    private MusicAgent newAgent(
            ChatModel model, Executor executor, int maxSteps, int maxToolCalls, boolean approval) {
        MusicAgent agent = new MusicAgent(
                model,
                writeCallbacks,
                executor,
                toolEventMessageProvider,
                AgentType.MUSIC_USER,
                "idempotency-test",
                MusicAgent.USER_SYSTEM_PROMPT
        );

        agent.setTaskRegistry(taskRegistry);
        agent.setRunRecorder(runRecorder);
        agent.setConcurrencyGuard(concurrencyGuard);

        if (approval) {
            agent.setApprovalToolPolicy(approvalToolPolicy);
            agent.setApprovalPauseGateway(approvalPauseGateway);
            agent.setApprovalSummaryBuilder(approvalSummaryBuilder);
            agent.setHandoffLocks(runHandoffLocks);
            agent.setApprovalClaimService(claimService);
            agent.setApprovalCompletionService(approvalCompletionService);
        }

        agent.setMaxSteps(maxSteps);
        agent.setMaxToolCalls(maxToolCalls);
        agent.setTimeout(Duration.ofSeconds(30));

        return agent;
    }

    private String args(String name) {
        return "{\"name\":\"" + name + "\",\"songIds\":[1001]}";
    }

    private String uniqueName() {
        return NAME_PREFIX + UUID.randomUUID().toString().substring(0, 8);
    }

    private int playlistCount(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_playlist WHERE user_id = ? AND name = ?",
                Integer.class, USER, name);
        return count == null ? 0 : count;
    }

    private int recordCount(String runId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_tool_idempotency WHERE run_id = ?",
                Integer.class, runId);
        return count == null ? 0 : count;
    }

    // ==================== 脚本模型 ====================

    static class ScriptedModel implements ChatModel {

        private final Deque<Supplier<ChatResponse>> script = new ArrayDeque<>();

        private final AtomicInteger calls = new AtomicInteger();

        private volatile Prompt lastPrompt;

        ScriptedModel toolCall(String id, String name, String args) {
            script.add(() -> toolResponse(List.of(
                    new AssistantMessage.ToolCall(id, "function", name, args))));
            return this;
        }

        ScriptedModel toolCalls(List<AssistantMessage.ToolCall> calls) {
            script.add(() -> toolResponse(calls));
            return this;
        }

        ScriptedModel answer(String text) {
            script.add(() -> textResponse(text));
            return this;
        }

        Prompt lastPrompt() {
            return lastPrompt;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            lastPrompt = prompt;

            Supplier<ChatResponse> next = script.poll();
            return next == null ? textResponse("已完成") : next.get();
        }
    }

    private static ChatResponse toolResponse(List<AssistantMessage.ToolCall> calls) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage("", Map.of(), calls))),
                ChatResponseMetadata.builder()
                        .model("stub-model")
                        .usage(new StubUsage())
                        .build()
        );
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder()
                        .model("stub-model")
                        .usage(new StubUsage())
                        .build()
        );
    }

    record StubUsage() implements Usage {

        @Override
        public Integer getPromptTokens() {
            return 10;
        }

        @Override
        public Integer getCompletionTokens() {
            return 5;
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
