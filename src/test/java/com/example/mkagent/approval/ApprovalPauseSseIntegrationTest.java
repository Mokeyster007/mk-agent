package com.example.mkagent.approval;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import com.example.mkagent.shop.ShopSeedInitializer;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批暂停的端到端回归（真实 HTTP + 真实 H2 + 桩模型，批次 1）。
 *
 * 不把写工具加进生产白名单：这里用 {@link ApprovalToolPolicy} 的测试替身
 * 把只读工具 {@code search_songs} 临时标成"需要确认"，
 * 从而在不改变线上能力的前提下验证暂停链路。
 *
 * 必须同时满足：
 * 1. SSE 顺序 status → approval_required → done，没有 error / final_answer；
 * 2. agent_run 停在 WAITING_APPROVAL，finished_at 为 NULL，step=1、tool_count=0；
 * 3. 检查点与审批记录都已落库，且检查点里没有参数原文泄漏到 SSE；
 * 4. 注册表清空、并发许可释放（暂停不占着资源）。
 */
@HermeticSpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mkagent.approval.ttl=10m"
        }
)
@Timeout(90)
class ApprovalPauseSseIntegrationTest {

    private static final String USER = ShopSeedInitializer.BUYER_SHOP_ONLY;

    @LocalServerPort
    private int port;

    @Autowired
    private ScriptedChatModel scriptedChatModel;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentTaskRegistry taskRegistry;

    @Autowired
    private AgentConcurrencyGuard concurrencyGuard;

    @MockitoBean
    private ApprovalToolPolicy approvalToolPolicy;

    @MockitoBean
    private VectorStore chatAppVectorStore;

    @BeforeEach
    void reset() {
        scriptedChatModel.calls.set(0);

        Mockito.when(approvalToolPolicy.isEnabled()).thenReturn(true);
        Mockito.when(approvalToolPolicy.requiresApproval("search_products")).thenReturn(true);
        Mockito.when(approvalToolPolicy.anyRequiresApproval(Mockito.anyList()))
                .thenReturn(true);
        Mockito.when(approvalToolPolicy.requiredToolNames())
                .thenReturn(Set.of("search_products"));
    }

    @Test
    @DisplayName("命中写工具：SSE 以 approval_required + done 结束，任务停在 WAITING_APPROVAL")
    void pauseEndsStreamWithoutTerminalState() throws Exception {
        String body = request("/ai/shop/chat", USER, "帮我找几款 400 元以内的无线键盘");

        int statusAt = body.indexOf("event:status");
        int approvalAt = body.indexOf("event:approval_required");
        int doneAt = body.indexOf("event:done");

        assertTrue(statusAt >= 0, "缺少 status 事件。原始 SSE：\n" + body);
        assertTrue(approvalAt >= 0, "缺少 approval_required 事件。原始 SSE：\n" + body);
        assertTrue(doneAt >= 0, "缺少 done 事件。原始 SSE：\n" + body);
        assertTrue(statusAt < approvalAt, "顺序必须是 status → approval_required");
        assertTrue(approvalAt < doneAt, "顺序必须是 approval_required → done");

        assertFalse(body.contains("event:error"),
                "暂停不是错误，不应出现 error 事件。原始 SSE：\n" + body);
        assertFalse(body.contains("event:final_answer"),
                "暂停时还没有答案，不应出现 final_answer。原始 SSE：\n" + body);

        Map<String, Object> run = awaitRunRow(USER);
        assertNotNull(run, "agent_run 应有一行记录");

        assertEquals("WAITING_APPROVAL", run.get("state"),
                "暂停态必须落库，实际：" + run);
        assertNull(run.get("finished_at"),
                "暂停不是终态，finished_at 必须为 NULL，实际：" + run);
        assertEquals(1, ((Number) run.get("current_step")).intValue(),
                "暂停发生在第 1 步，实际：" + run);
        assertEquals(0, ((Number) run.get("tool_call_count")).intValue(),
                "暂停轮不得执行任何工具，实际：" + run);
        assertEquals(1, ((Number) run.get("approval_count")).intValue(),
                "创建一次审批应计 1 次，实际：" + run);

        Map<String, Object> approval = jdbcTemplate.queryForMap(
                "SELECT approval_id, status, resume_state, tool_count, summary_text "
                        + "FROM agent_approval WHERE run_id = ?",
                run.get("run_id"));

        assertEquals("PENDING", approval.get("status"));
        assertEquals("PENDING_RESUME", approval.get("resume_state"));
        assertEquals(1, ((Number) approval.get("tool_count")).intValue());

        String approvalId = approval.get("approval_id").toString();
        assertTrue(body.contains(approvalId),
                "approval_required 事件应携带 approvalId。原始 SSE：\n" + body);

        String summaryText = approval.get("summary_text").toString();
        assertFalse(summaryText.isBlank(), "摘要不能为空");
        assertFalse(summaryText.contains("\"keyword\""),
                "摘要不得包含参数原文，实际：" + summaryText);

        // 检查点：history 只有 system + user，pending 调用完整
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload_json FROM agent_checkpoint WHERE run_id = ?",
                String.class, run.get("run_id"));

        ApprovalCheckpoint checkpoint = CheckpointCodec.decode(payload);
        assertEquals(1, checkpoint.pendingToolCalls().size());
        assertEquals("search_products", checkpoint.pendingToolCalls().get(0).name());
        assertEquals(2, checkpoint.messages().size(),
                "history 只含 system + user");
        assertNotNull(checkpoint.pendingAssistant(), "pendingAssistant 不能为空");

        // 暂停不占资源
        assertEquals(0, taskRegistry.size(), "暂停后注册表应清空");
        assertEquals(concurrencyGuard.getMaxConcurrency(),
                concurrencyGuard.availablePermits(),
                "暂停后并发许可必须全部归还");

        assertEquals(1, scriptedChatModel.calls.get(),
                "暂停后不得再调用模型（不追加总结）");
    }

    // ==================== 辅助 ====================

    private String request(String path, String userId, String message) throws Exception {
        String url = "http://localhost:" + port + "/api" + path
                + "?message=" + URLEncoder.encode(message, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("X-User-Id", userId)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode(),
                "请求失败，body=" + response.body());
        return response.body();
    }

    private Map<String, Object> awaitRunRow(String userId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        Map<String, Object> row = null;

        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT run_id, state, current_step, tool_call_count, "
                            + "approval_count, finished_at, final_answer "
                            + "FROM agent_run WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                    userId);
            row = rows.isEmpty() ? null : rows.get(0);
            if (row != null && !"RUNNING".equals(row.get("state"))) {
                return row;
            }
            Thread.sleep(50);
        }
        return row;
    }

    /**
     * 桩模型：永远请求一次只读工具（被测试策略标成需确认）。
     */
    static class ScriptedChatModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(
                            "",
                            Map.of(),
                            List.of(new AssistantMessage.ToolCall(
                                    "call-1", "function", "search_products",
                                    "{\"keyword\":\"键盘\"}"))))),
                    ChatResponseMetadata.builder()
                            .model("stub-model")
                            .usage(new StubUsage(10, 5))
                            .build()
            );
        }
    }

    private record StubUsage(int promptTokens, int completionTokens)
            implements Usage {

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
    static class StubModelConfig {

        private final ScriptedChatModel behavior = new ScriptedChatModel();

        @Bean("dashscopeChatModel")
        @Primary
        ChatModel dashscopeChatModel() {
            DashScopeChatModel mock = Mockito.mock(DashScopeChatModel.class);
            Mockito.when(mock.call(Mockito.any(Prompt.class)))
                    .thenAnswer(invocation ->
                            behavior.call(invocation.getArgument(0, Prompt.class)));
            return mock;
        }

        @Bean("chatAppDocumentLoader")
        ChatAppDocumentLoader chatAppDocumentLoader() {
            ChatAppDocumentLoader mock = Mockito.mock(ChatAppDocumentLoader.class);
            Mockito.when(mock.loadMarkdowns()).thenReturn(List.of());
            return mock;
        }

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new org.springframework.ai.tool.ToolCallback[0];
        }

        @Bean
        ScriptedChatModel scriptedChatModel() {
            return behavior;
        }
    }
}
