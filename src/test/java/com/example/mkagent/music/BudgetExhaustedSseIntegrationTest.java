package com.example.mkagent.music;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.ToolCallAgent;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算耗尽的端到端回归（真实 HTTP + 真实 H2 + 桩模型）。
 *
 * 复现真实冒烟里的现象：第 1 步调完工具后预算耗尽，收尾调用**没有返回文本**。
 * 旧行为下 SSE 只有 status → done，数据库 final_answer 为 NULL。
 *
 * 现在必须满足三件事同时成立：
 * 1. SSE 依次收到 status → final_answer → done，且 final_answer 是确定性兜底；
 * 2. agent_run.final_answer 与 SSE 的文本**完全一致**；
 * 3. 终态仍是 MAX_STEPS_REACHED，收尾调用的 Token 计入 total_tokens。
 */
@HermeticSpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mkagent.agent.music.max-steps=1",
                "mkagent.agent.music.max-tool-calls=1"
        }
)
@Timeout(90)
class BudgetExhaustedSseIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 工具轮永远请求 search_songs；收尾轮返回空文本（真实冒烟里就是这个形状）。
     */
    static class ScriptedChatModel implements ChatModel {

        /**
         * 收尾轮是否模拟"被取消/超时中断"：
         * 置位后收尾调用会先中断当前线程，再抛带 InterruptedException 的异常。
         */
        volatile boolean interruptOnForcedCall;

        private final AtomicInteger callCount = new AtomicInteger();

        private final AtomicInteger forcedCallCount = new AtomicInteger();

        private final List<Prompt> forcedPrompts = new CopyOnWriteArrayList<>();

        void reset() {
            interruptOnForcedCall = false;
            callCount.set(0);
            forcedCallCount.set(0);
            forcedPrompts.clear();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            callCount.incrementAndGet();

            if (!hasTools(prompt)) {
                forcedCallCount.incrementAndGet();
                forcedPrompts.add(prompt);

                if (interruptOnForcedCall) {
                    /*
                     * 模拟真实中断：取消接口 / SSE 超时会 interrupt 任务线程，
                     * 正在进行的 HTTP 调用以带 InterruptedException 的异常结束。
                     * 异常里故意带上"敏感"字样，用于验证不会外泄。
                     */
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(
                            "interrupt-secret: 收尾调用被中断",
                            new InterruptedException("interrupted")
                    );
                }

                // 空文本：模拟"模型没有给出可用结论"。
                return new ChatResponse(
                        List.of(new Generation(new AssistantMessage(""))),
                        metadata()
                );
            }

            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(
                            "",
                            Map.of(),
                            List.of(new AssistantMessage.ToolCall(
                                    "call_1",
                                    "function",
                                    "search_songs",
                                    "{\"keyword\":\"夜\",\"limit\":3}"
                            ))
                    ))),
                    metadata()
            );
        }

        private static boolean hasTools(Prompt prompt) {
            if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
                return false;
            }
            List<ToolCallback> callbacks = options.getToolCallbacks();
            return callbacks != null && !callbacks.isEmpty();
        }

        private static ChatResponseMetadata metadata() {
            return ChatResponseMetadata.builder()
                    .model("stub-model")
                    .usage(new StubUsage(10, 5))
                    .build();
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
                            behavior.call(invocation.getArgument(0, Prompt.class))
                    );
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
            return () -> new ToolCallback[0];
        }

        @Bean
        ScriptedChatModel scriptedChatModel() {
            return behavior;
        }
    }

    private static final String USER = MusicSeedInitializer.USER_ALICE;

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
    private VectorStore chatAppVectorStore;

    @BeforeEach
    void reset() {
        scriptedChatModel.reset();
    }

    @Test
    @DisplayName("收尾无文本：SSE 发兜底 final_answer，且与落库文本一致")
    void fallbackAnswerIsSentAndPersisted() throws Exception {
        String body = request("/ai/music/chat", USER, "找几首和夜有关的歌");

        String statusAt = eventBlock(body, "status");
        String answerAt = eventBlock(body, "final_answer");
        String doneAt = eventBlock(body, "done");

        assertNotNull(statusAt, "缺少 status 事件。原始 SSE：\n" + body);
        assertNotNull(answerAt,
                "预算耗尽且收尾无文本时也必须发 final_answer。原始 SSE：\n" + body);
        assertNotNull(doneAt, "缺少 done 事件。原始 SSE：\n" + body);

        assertTrue(body.indexOf(statusAt) < body.indexOf(answerAt),
                "顺序必须是 status → final_answer");
        assertTrue(body.indexOf(answerAt) < body.indexOf(doneAt),
                "顺序必须是 final_answer → done（done 之前必须已经给出答案）");

        String sseAnswer = messageOf(answerAt);
        assertTrue(sseAnswer.startsWith(ToolCallAgent.BUDGET_FALLBACK_PREFIX),
                "final_answer 应是确定性兜底，实际：" + sseAnswer);
        assertTrue(sseAnswer.contains("本次已执行 1 步、调用工具 1 次。"),
                "兜底文案应包含步数与工具次数，实际：" + sseAnswer);
        assertTrue(sseAnswer.contains("请缩小任务范围后重试"),
                "兜底文案应包含下一步建议，实际：" + sseAnswer);

        assertFalse(body.contains("event:error"),
                "不应出现 error 事件。原始 SSE：\n" + body);
        // 兜底文案不得泄露工具原文。
        assertFalse(body.contains("\"durationSeconds\""),
                "SSE 不应携带完整工具 JSON。原始 SSE：\n" + body);

        Map<String, Object> run = awaitFinishedRun(USER);
        assertNotNull(run, "agent_run 应有一行记录");
        assertEquals("MAX_STEPS_REACHED", run.get("state"),
                "终态必须保持 MAX_STEPS_REACHED，实际：" + run);

        Object persisted = run.get("final_answer");
        assertNotNull(persisted, "final_answer 不得为 NULL，实际：" + run);
        assertEquals(sseAnswer, persisted.toString(),
                "SSE 的 final_answer 必须与落库文本完全一致");

        long totalTokens = ((Number) run.get("total_tokens")).longValue();
        assertEquals(30L, totalTokens,
                "两次工具轮 + 一次收尾 = 3 次调用 × 10 tokens");
        assertEquals(1, scriptedChatModel.forcedCallCount.get(),
                "收尾调用只允许一次");
        assertEquals(2, scriptedChatModel.callCount.get(),
                "共两次模型调用：工具轮 + 收尾轮");
    }

    @Test
    @DisplayName("收尾期间被中断：终态仍是 MAX_STEPS_REACHED，不写兜底、不泄露异常")
    void interruptionDuringForcedCallKeepsBudgetState() throws Exception {
        scriptedChatModel.interruptOnForcedCall = true;

        String body = request("/ai/music/chat", USER, "找几首和夜有关的歌");

        Map<String, Object> run = awaitRunRow(USER);
        assertNotNull(run, "agent_run 应有一行记录");

        /*
         * 1. 最终状态。
         *
         * 收尾调用发生在 finishIfStillRunning 之后，此时 MAX_STEPS_REACHED
         * 已是终态；异常按中断语义向上抛，但 failOrCancel 里的
         * transitionTo(CANCELLED) 会被终态保护拒绝。
         * 所以数据库与 ctx 都停在 MAX_STEPS_REACHED，不是 CANCELLED。
         */
        assertEquals("MAX_STEPS_REACHED", run.get("state"),
                "终态必须保持 MAX_STEPS_REACHED，实际：" + run);

        // 2. 不继续执行工具，也不追加第二次总结调用。
        assertEquals(1, ((Number) run.get("tool_call_count")).intValue(),
                "中断后不得再执行工具，实际：" + run);
        assertEquals(1, scriptedChatModel.forcedCallCount.get(),
                "收尾调用只尝试一次，不重试");
        assertEquals(2, scriptedChatModel.callCount.get(),
                "不得追加总结调用：工具轮 + 一次收尾");

        // 3. 不写确定性兜底。
        assertNull(run.get("final_answer"),
                "中断不得写入兜底答案，实际：" + run);
        assertNull(eventBlock(body, "final_answer"),
                "SSE 不应发 final_answer。原始 SSE：\n" + body);
        assertFalse(body.contains(ToolCallAgent.BUDGET_FALLBACK_PREFIX),
                "SSE 不得出现兜底文案。原始 SSE：\n" + body);

        // 4. SSE 收尾（连接正常关闭、响应完整）、注册表清理、并发许可释放。
        assertNotNull(eventBlock(body, "status"),
                "预算终态仍应先推 status。原始 SSE：\n" + body);
        assertNull(eventBlock(body, "done"),
                "异常路径不发 done（连接在 error 之后关闭）");
        assertEquals(0, taskRegistry.size(), "任务结束后注册表应清空");
        assertEquals(concurrencyGuard.getMaxConcurrency(),
                concurrencyGuard.availablePermits(),
                "并发许可必须释放");

        // 5. 错误事件与落库信息都不泄露异常细节。
        String errorBlock = eventBlock(body, "error");
        assertNotNull(errorBlock, "中断应推送一条脱敏 error 事件。原始 SSE：\n" + body);
        assertEquals("智能体执行失败，请稍后重试。", messageOf(errorBlock));
        assertFalse(body.contains("interrupt-secret"), "SSE 不得泄露异常文本");
        assertFalse(body.contains("InterruptedException"), "SSE 不得泄露异常类型");

        Object persistedError = run.get("error_message");
        assertNotNull(persistedError, "应记录脱敏错误摘要");
        assertFalse(persistedError.toString().contains("interrupt-secret"),
                "落库错误信息不得含敏感文本，实际：" + persistedError);
    }

    // ==================== 辅助 ====================

    private String request(String path, String userId, String message)
            throws Exception {
        String url = "http://localhost:" + port + "/api" + path
                + "?message=" + URLEncoder.encode(message, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("X-User-Id", userId)
                .GET()
                .build();

        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString())
                .body();
    }

    /**
     * 取某个事件对应的完整 SSE 片段（"event: x" 起、到空行为止）。
     */
    private static String eventBlock(String body, String eventName) {
        String marker = "event:" + eventName;
        int index = body.indexOf(marker);
        if (index < 0) {
            return null;
        }
        int end = body.indexOf("\n\n", index);
        return end < 0 ? body.substring(index) : body.substring(index, end);
    }

    /**
     * 解析事件片段里的 data JSON，取出 message 字段。
     */
    private static String messageOf(String block) throws Exception {
        int dataIndex = block.indexOf("data:");
        assertTrue(dataIndex >= 0, "事件片段里没有 data：\n" + block);
        String json = block.substring(dataIndex + "data:".length()).trim();
        JsonNode node = JSON.readTree(json);
        return node.path("message").asText();
    }

    private Map<String, Object> awaitFinishedRun(String userId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        Map<String, Object> row = null;

        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = new ArrayList<>(jdbcTemplate.queryForList(
                    "SELECT state, final_answer, total_tokens FROM agent_run "
                            + "WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                    userId));
            row = rows.isEmpty() ? null : rows.get(0);
            if (row != null && !"RUNNING".equals(row.get("state"))) {
                return row;
            }
            Thread.sleep(50);
        }

        return row;
    }

    /**
     * 读取最近一次任务的完整终态字段（含终态、工具次数、错误摘要）。
     */
    private Map<String, Object> awaitRunRow(String userId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        Map<String, Object> row = null;

        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = new ArrayList<>(jdbcTemplate.queryForList(
                    "SELECT run_id, state, final_answer, error_message, "
                            + "tool_call_count, total_tokens FROM agent_run "
                            + "WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                    userId));
            row = rows.isEmpty() ? null : rows.get(0);
            if (row != null && !"RUNNING".equals(row.get("state"))) {
                return row;
            }
            Thread.sleep(50);
        }

        return row;
    }

    /**
     * 最小 Usage 实现：每次调用固定 10 / 5 tokens。
     */
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
}
