package com.example.mkagent.approval;

import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.MusicAgent;
import com.example.mkagent.config.AgentToolProvider;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import com.example.mkagent.service.AgentRunRecorder;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.tools.PlaylistWriteTool;
import com.example.mkagent.tools.ToolEventMessageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实扫描链路 → 真实写工具 → 暂停（批次 1 补充）。
 *
 * 与 SSE 测试的分工：
 * - SSE 测试用 {@link ApprovalToolPolicy} 替身，证明"暂停时流怎么收尾"；
 * - 这里用**真实** {@link ApprovalToolPolicy}（容器扫描）与**真实**
 *   {@link PlaylistWriteTool}（真实注解），证明"注解能被识别、写工具会被拦住"。
 *
 * 写工具仍然**不进入**生产白名单：本测试自己装配一个只含写工具的 Agent，
 * 不改 {@link AgentToolProvider} 的任何配置。
 */
@HermeticSpringBootTest
@Timeout(60)
class ApprovalWriteToolPauseIntegrationTest {

    private static final String USER = "u-pause-it";

    @Autowired
    private PlaylistWriteTool playlistWriteTool;

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
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        taskRegistry.snapshot().forEach(t ->
                taskRegistry.remove(t.context().getRunId().toString(), t.epoch()));
    }

    @Test
    @DisplayName("真实写工具命中审批注解：暂停且业务表相对快照无变更")
    void realWriteToolPausesWithoutBusinessChange() {
        long playlistsBefore = count("music_playlist");
        long playlistSongsBefore = count("music_playlist_song");

        ScriptedModel model = new ScriptedModel(
                "call-w1", "create_playlist",
                "{\"name\":\"夜曲\",\"songIds\":[1,2]}");

        MusicAgent agent = newAgent(model);
        AgentRunContext ctx = new AgentRunContext();
        String runId = ctx.getRunId().toString();

        String answer = agent.run("帮我建一个叫夜曲的歌单，放前两首歌", ctx);

        assertEquals("任务已暂停，等待人工确认。", answer);

        // 1. 状态：暂停不是终态
        assertEquals(AgentState.WAITING_APPROVAL, ctx.getState());
        assertEquals(0, ctx.getToolCallCount(), "暂停轮不得执行任何工具");
        assertEquals(1, ctx.getCurrentStep(), "暂停发生在第 1 步");
        assertEquals(1, model.calls.get(), "暂停后不得再调用模型");

        // 2. 业务数据：相对快照零变更
        assertEquals(playlistsBefore, count("music_playlist"), "不得创建歌单");
        assertEquals(playlistSongsBefore, count("music_playlist_song"), "不得写入歌单歌曲");

        // 3. 落库：WAITING_APPROVAL、finished_at 为空、审批计数 1
        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT state, current_step, tool_call_count, approval_count, "
                        + "finished_at, config_version, active_elapsed_millis "
                        + "FROM agent_run WHERE run_id = ?", runId);

        assertEquals("WAITING_APPROVAL", run.get("state"));
        assertNull(run.get("finished_at"), "暂停不是终态，finished_at 必须为空");
        assertEquals(0, ((Number) run.get("tool_call_count")).intValue());
        assertEquals(1, ((Number) run.get("approval_count")).intValue());
        assertNotNull(run.get("config_version"), "必须记录配置指纹");

        // 4. 审批记录与检查点
        Map<String, Object> approval = jdbcTemplate.queryForMap(
                "SELECT approval_id, primary_tool, tool_count, status, resume_state, "
                        + "summary_text, args_hash FROM agent_approval WHERE run_id = ?",
                runId);

        assertEquals("create_playlist", approval.get("primary_tool"));
        assertEquals(1, ((Number) approval.get("tool_count")).intValue());
        assertEquals("PENDING", approval.get("status"));
        assertEquals("PENDING_RESUME", approval.get("resume_state"));
        assertFalse(approval.get("args_hash").toString().isBlank());

        String summary = approval.get("summary_text").toString();
        assertTrue(summary.contains("夜曲"), "摘要应包含歌单名，实际：" + summary);
        assertTrue(summary.contains("2"), "摘要应包含歌曲数量，实际：" + summary);
        assertFalse(summary.contains("songIds"),
                "摘要不得包含参数原文，实际：" + summary);

        String payload = jdbcTemplate.queryForObject(
                "SELECT payload_json FROM agent_checkpoint WHERE run_id = ?",
                String.class, runId);
        ApprovalCheckpoint checkpoint = CheckpointCodec.decode(payload);

        assertEquals(1, checkpoint.pendingToolCalls().size());
        assertEquals("create_playlist", checkpoint.pendingToolCalls().get(0).name());
        assertEquals("call-w1", checkpoint.pendingToolCalls().get(0).id(),
                "toolCallId 必须与模型返回一致");
        assertEquals(2, checkpoint.messages().size(), "history 只含 system + user");
        assertNotNull(checkpoint.pendingAssistant());

        // 5. 资源：注册表清空、许可归还
        assertEquals(0, taskRegistry.size(), "暂停后注册表应清空");
        assertEquals(concurrencyGuard.getMaxConcurrency(),
                concurrencyGuard.availablePermits());
        assertFalse(runHandoffLocks.exists(runId), "交接锁必须释放");
    }

    // ==================== 辅助 ====================

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }

    private MusicAgent newAgent(ChatModel model) {
        ToolCallback[] callbacks = ToolCallbacks.from(playlistWriteTool);

        MusicAgent agent = new MusicAgent(
                model,
                callbacks,
                Runnable::run,
                toolEventMessageProvider,
                AgentType.MUSIC_USER,
                "approval-write-tool-test",
                MusicAgent.USER_SYSTEM_PROMPT
        );

        agent.setTaskRegistry(taskRegistry);
        agent.setRunRecorder(runRecorder);
        agent.setConcurrencyGuard(concurrencyGuard);

        // 真实策略：名单来自容器扫描，不是手工喂进去的
        agent.setApprovalToolPolicy(approvalToolPolicy);
        agent.setApprovalPauseGateway(approvalPauseGateway);
        agent.setApprovalSummaryBuilder(approvalSummaryBuilder);
        agent.setHandoffLocks(runHandoffLocks);

        agent.setMaxSteps(4);
        agent.setMaxToolCalls(10);
        agent.setTimeout(Duration.ofSeconds(30));

        return agent;
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
