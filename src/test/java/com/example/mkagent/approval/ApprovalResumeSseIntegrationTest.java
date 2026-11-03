package com.example.mkagent.approval;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.music.MusicSeedInitializer;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
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

import java.io.InputStream;
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
 * 审批接口的端到端回归（真实 HTTP + 真实 H2 + 桩模型，批次 2）。
 *
 * 与 {@link ApprovalResumeIntegrationTest} 的分工：
 * - 那边直接调用服务，断言状态机与事务；
 * - 这里走真实 HTTP，证明 **Controller / 归属校验 / Spring 装配** 这一层是通的：
 *   暂停由 {@code /ai/music/chat} 产生，批准与恢复由 {@code /ai/approvals/**} 触发。
 *
 * 仍然不开放生产写工具：用策略替身把只读工具 {@code search_songs}
 * 临时标成"需要确认"，写工具白名单保持关闭。
 */
@HermeticSpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mkagent.approval.ttl=10m"
        }
)
@Timeout(120)
class ApprovalResumeSseIntegrationTest {

    private static final String USER = MusicSeedInitializer.USER_ALICE;

    private static final String OTHER = MusicSeedInitializer.USER_BOB;

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

    private final java.util.List<String> createdRuns = new java.util.ArrayList<>();

    @BeforeEach
    void reset() {
        scriptedChatModel.reset();

        Mockito.when(approvalToolPolicy.isEnabled()).thenReturn(true);
        Mockito.when(approvalToolPolicy.requiresApproval("search_songs")).thenReturn(true);
        Mockito.when(approvalToolPolicy.anyRequiresApproval(Mockito.anyList()))
                .thenReturn(true);
        Mockito.when(approvalToolPolicy.requiredToolNames())
                .thenReturn(Set.of("search_songs"));
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        /*
         * 共享内存库：本类会产生 agent_run / agent_approval / agent_checkpoint 行，
         * 结束时删掉，避免影响其它用例对任务表的全局断言。
         */
        for (String runId : createdRuns) {
            jdbcTemplate.update("DELETE FROM agent_approval WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_checkpoint WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_run WHERE run_id = ?", runId);
        }
        createdRuns.clear();
    }

    @Test
    @DisplayName("批准一次调用即完成决定与执行：续流给出 final_answer，任务 SUCCEEDED，审批 FINISHED")
    void approveRunsDecisionAndResumeInOneCall() throws Exception {
        String runId = pauseViaHttp();

        String approvalId = pendingApprovalId(runId);

        // 契约：approve 直接返回续流，不再需要第二次 /resume 调用
        HttpResponse<String> approved =
                post("/ai/approvals/" + approvalId + "/approve", USER);

        assertEquals(200, approved.statusCode(), "批准失败，body=" + approved.body());

        String body = approved.body();
        assertTrue(body.contains("event:final_answer"),
                "批准后应直接给出最终回答。原始 SSE：\n" + body);
        assertTrue(body.contains("event:done"), "续流必须正常结束。原始 SSE：\n" + body);
        assertFalse(body.contains("event:approval_required"),
                "本轮没有新的写操作，不应再次暂停。原始 SSE：\n" + body);

        Map<String, Object> run = awaitFinishedRun(runId);
        assertEquals("SUCCEEDED", run.get("state"), "实际：" + run);
        assertEquals(1, ((Number) run.get("tool_call_count")).intValue(),
                "批准后应执行一次被冻结的工具，实际：" + run);
        assertNotNull(run.get("finished_at"));

        Map<String, Object> approval = approvalRow(approvalId);
        assertEquals("APPROVED", approval.get("status"));
        assertEquals("FINISHED", approval.get("resume_state"));

        assertEquals(2, scriptedChatModel.calls.get(),
                "暂停 1 次 + 恢复后 1 次，不得多调");

        assertEquals(0, taskRegistry.size(), "结束后注册表应清空");
        assertEquals(concurrencyGuard.getMaxConcurrency(),
                concurrencyGuard.availablePermits(), "许可必须全部归还");
    }

    @Test
    @DisplayName("拒绝 → 恢复：不执行工具，续流正常结束")
    void rejectAndResumeOverHttp() throws Exception {
        String runId = pauseViaHttp();
        String approvalId = pendingApprovalId(runId);

        HttpResponse<String> rejected =
                post("/ai/approvals/" + approvalId + "/reject", USER);

        assertEquals(200, rejected.statusCode(), "拒绝失败，body=" + rejected.body());
        assertTrue(rejected.body().contains("event:done"),
                "拒绝后应直接结束续流。原始 SSE：\n" + rejected.body());

        Map<String, Object> run = awaitFinishedRun(runId);
        assertEquals("SUCCEEDED", run.get("state"), "实际：" + run);
        assertEquals(0, ((Number) run.get("tool_call_count")).intValue(),
                "拒绝不得执行工具，实际：" + run);

        assertEquals("REJECTED", approvalRow(approvalId).get("status"));
        assertEquals("FINISHED", approvalRow(approvalId).get("resume_state"));
    }

    @Test
    @DisplayName("决定已保存但启动失败：返回明确错误，决定不撤销，可用 /resume 重试")
    void approveKeepsDecisionWhenResumeFails() throws Exception {
        String runId = pauseViaHttp();
        String approvalId = pendingApprovalId(runId);

        int permits = concurrencyGuard.getMaxConcurrency();
        for (int i = 0; i < permits; i++) {
            assertTrue(concurrencyGuard.tryAcquire(), "应当能占满许可");
        }

        try {
            HttpResponse<String> approved =
                    post("/ai/approvals/" + approvalId + "/approve", USER);

            assertEquals(429, approved.statusCode(),
                    "无并发许可应明确失败，body=" + approved.body());
            assertTrue(approved.body().contains("RESUME_CONCURRENCY_LIMIT"),
                    "应返回稳定原因码，body=" + approved.body());
            assertTrue(approved.body().contains("决定已保存"),
                    "错误信息必须说明决定已保存，body=" + approved.body());

            assertEquals("APPROVED", approvalRow(approvalId).get("status"),
                    "启动失败不得撤销用户已经做出的决定");
            assertEquals("PENDING_RESUME", approvalRow(approvalId).get("resume_state"));
            assertEquals("WAITING_APPROVAL", runRow(runId).get("state"));
            assertEquals(1, scriptedChatModel.calls.get(), "启动失败不得调用模型");
        } finally {
            for (int i = 0; i < permits; i++) {
                concurrencyGuard.release();
            }
        }

        // 重试入口：决定已经存在，/resume 直接执行
        HttpResponse<String> resumed =
                post("/ai/approvals/" + approvalId + "/resume", USER);

        assertEquals(200, resumed.statusCode(), "重试失败，body=" + resumed.body());
        assertTrue(resumed.body().contains("event:done"));

        awaitRunState(runId, "SUCCEEDED");
        assertEquals("FINISHED", approvalRow(approvalId).get("resume_state"));
    }

    @Test
    @DisplayName("他人批准 / 恢复 / 查询：404 且记录不变")
    void crossUserIs404() throws Exception {
        String runId = pauseViaHttp();
        String approvalId = pendingApprovalId(runId);

        assertEquals(404, post("/ai/approvals/" + approvalId + "/approve", OTHER).statusCode());
        assertEquals(404, post("/ai/approvals/" + approvalId + "/resume", OTHER).statusCode());
        assertEquals(404, get("/ai/approvals/" + approvalId, OTHER).statusCode());

        Map<String, Object> approval = approvalRow(approvalId);
        assertEquals("PENDING", approval.get("status"), "跨用户请求不得改动审批");
        assertEquals("PENDING_RESUME", approval.get("resume_state"));
        assertEquals("WAITING_APPROVAL", runRow(runId).get("state"));
        assertEquals(1, scriptedChatModel.calls.get(), "不得因为他人请求而调用模型");
    }

    @Test
    @DisplayName("查询接口只返回摘要与状态，不返回检查点原文")
    void queryReturnsSummaryOnly() throws Exception {
        String runId = pauseViaHttp();
        String approvalId = pendingApprovalId(runId);

        HttpResponse<String> response = get("/ai/approvals/" + approvalId, USER);
        assertEquals(200, response.statusCode(), "body=" + response.body());

        String body = response.body();
        assertTrue(body.contains(approvalId), "应返回 approvalId，实际：" + body);
        assertTrue(body.contains("PENDING"), "应返回状态，实际：" + body);
        assertTrue(body.contains("summary"), "应返回摘要，实际：" + body);
        assertFalse(body.contains("payload_json"),
                "不得返回检查点原文，实际：" + body);
        assertFalse(body.contains("pendingToolCalls"),
                "不得返回待执行调用明细，实际：" + body);
    }

    @Test
    @DisplayName("续流断开：任务在后台继续并成功，不会被当成取消")
    void streamDropKeepsRunningInBackground() throws Exception {
        String runId = pauseViaHttp();
        String approvalId = pendingApprovalId(runId);

        /*
         * 让第二次模型调用慢下来，保证断开发生在执行中途。
         * 第一次调用（暂停那一轮）不受影响：延迟只作用于 n>1。
         */
        scriptedChatModel.answerDelayMillis = 1200;

        String url = "http://localhost:" + port + "/api/ai/approvals/" + approvalId + "/approve";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("X-User-Id", USER)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<InputStream> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());

        assertEquals(200, response.statusCode());

        // 读到一点内容后立刻断开，模拟客户端掉线
        try (InputStream stream = response.body()) {
            stream.read();
        }

        awaitRunState(runId, "SUCCEEDED");

        Map<String, Object> run = runRow(runId);
        assertEquals("SUCCEEDED", run.get("state"),
                "恢复流断开不得把任务判成取消，实际：" + run);
        assertNotNull(run.get("final_answer"), "后台跑完必须留下结果，实际：" + run);
        assertEquals("FINISHED", approvalRow(approvalId).get("resume_state"));
    }

    // ==================== 辅助 ====================

    /**
     * 通过真实入口制造一次暂停，返回 runId。
     */
    private String pauseViaHttp() throws Exception {
        String url = "http://localhost:" + port + "/api/ai/music/chat"
                + "?message=" + URLEncoder.encode("帮我找几首和夜有关的歌",
                StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("X-User-Id", USER)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode(), "暂停请求失败，body=" + response.body());
        assertTrue(response.body().contains("event:approval_required"),
                "前置条件：必须产生审批。原始 SSE：\n" + response.body());

        Map<String, Object> run = awaitLatestRun(USER, "WAITING_APPROVAL");
        assertNotNull(run, "应有一行暂停中的任务");

        String runId = run.get("run_id").toString();
        createdRuns.add(runId);
        return runId;
    }

    private String pendingApprovalId(String runId) {
        return jdbcTemplate.queryForObject(
                "SELECT approval_id FROM agent_approval WHERE run_id = ? "
                        + "AND status = 'PENDING'", String.class, runId);
    }

    private HttpResponse<String> post(String path, String userId) throws Exception {
        /*
         * 不设 Accept：approve/reject/resume 都返回 SSE，
         * 但失败时是 JSON 错误体；不限定 Accept 才能两种都读。
         */
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api" + path))
                .header("X-User-Id", userId)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path, String userId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api" + path))
                .header("X-User-Id", userId)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private Map<String, Object> runRow(String runId) {
        return jdbcTemplate.queryForMap(
                "SELECT state, current_step, tool_call_count, approval_count, "
                        + "finished_at, error_code, final_answer "
                        + "FROM agent_run WHERE run_id = ?", runId);
    }

    private Map<String, Object> approvalRow(String approvalId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, resume_state FROM agent_approval WHERE approval_id = ?",
                approvalId);
    }

    private Map<String, Object> awaitLatestRun(String userId, String expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        Map<String, Object> row = null;

        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT run_id, state, final_answer FROM agent_run "
                            + "WHERE user_id = ? ORDER BY id DESC LIMIT 1", userId);
            row = rows.isEmpty() ? null : rows.get(0);
            if (row != null && expected.equals(row.get("state"))) {
                return row;
            }
            Thread.sleep(25);
        }
        return row;
    }

    /**
     * 等到终态真正落库（finished_at 非空）。
     *
     * 只等 state 是不够的：终态写入与连接关闭之间有一个极短窗口，
     * 断言 finished_at 必须等这一行完整写下来。
     */
    private Map<String, Object> awaitFinishedRun(String runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        Map<String, Object> row = null;

        while (System.currentTimeMillis() < deadline) {
            row = runRow(runId);
            if (row.get("finished_at") != null) {
                return row;
            }
            Thread.sleep(25);
        }
        return row;
    }

    private void awaitRunState(String runId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        Object state = null;

        while (System.currentTimeMillis() < deadline) {
            state = runRow(runId).get("state");
            if (expected.equals(state)) {
                return;
            }
            Thread.sleep(25);
        }
        assertEquals(expected, state, "等待任务状态超时");
    }

    /**
     * 桩模型：第一次请求工具，之后直接给最终回答。
     *
     * 这样一次暂停 + 一次恢复刚好走完整个闭环；
     * {@code answerDelayMillis} 用于让"断流"发生在执行中途。
     */
    static class ScriptedChatModel implements ChatModel {

        final AtomicInteger calls = new AtomicInteger();

        volatile long answerDelayMillis = 0;

        void reset() {
            calls.set(0);
            answerDelayMillis = 0;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            int n = calls.incrementAndGet();

            if (n == 1) {
                return new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "",
                                Map.of(),
                                List.of(new AssistantMessage.ToolCall(
                                        "call-1", "function", "search_songs",
                                        "{\"q\":\"夜\"}"))))),
                        ChatResponseMetadata.builder()
                                .model("stub-model")
                                .usage(new StubUsage(10, 5))
                                .build()
                );
            }

            if (answerDelayMillis > 0) {
                try {
                    Thread.sleep(answerDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(
                            "找到几首和夜有关的歌"))),
                    ChatResponseMetadata.builder()
                            .model("stub-model")
                            .usage(new StubUsage(10, 5))
                            .build()
            );
        }
    }

    private record StubUsage(int promptTokens, int completionTokens) implements Usage {

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
