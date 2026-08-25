package com.example.mkagent.resilience;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.BaseAgent;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.RunningAgentTask;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import com.example.mkagent.tools.DemoInventoryTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局 Agent 并发控制集成测试。
 *
 * 与其他隔离集成测试相同的策略：排除 DashScope / MCP 自动配置、
 * H2 内存库、测试模型替换真实模型，全程不调用真实大模型。
 *
 * 本测试将并发上限调低为 2（mkagent.agent.max-concurrency=2），
 * 用 HoldingChatModel 按提示词标记把指定任务"挂起"在模型调用中，
 * 从而稳定复现"并发已满"场景。
 *
 * 覆盖用户要求的验证点：
 * 1. 并发上限生效：2 个任务挂起时，第 3 个请求被 429 拒绝，
 *    且返回明确业务文案"当前智能体任务较多，请稍后重试。"；
 * 2. 许可不泄漏：顺序执行多个任务后许可数恢复到上限；
 * 3. 失败场景释放：模型抛异常后许可释放；
 * 4. 取消场景释放：主动取消挂起任务后许可释放。
 *
 * 注意：不使用 FakeChatModel —— 它的会话计数逻辑非并发安全，
 * 并发任务会触发"被意外调用超过 2 轮"异常，因此这里用专用的
 * HoldingChatModel（无共享计数，天然并发安全）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.McpToolCallbackAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.StdioTransportAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.SseHttpClientTransportAutoConfiguration",
                "spring.main.allow-bean-definition-overriding=true",
                "spring.datasource.url=jdbc:h2:mem:mk_agent_concurrency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/schema.sql",
                "mkagent.rag.pgvector.enabled=false",
                // 并发上限调低为 2，方便用小流量复现"并发已满"。
                "mkagent.agent.max-concurrency=2"
        }
)
@ActiveProfiles("local")
@Timeout(60)
class AgentConcurrencyLimitIntegrationTest {

    /**
     * 并发安全可控模型：
     * 1. 提示词中包含某个挂起标记时，阻塞在该标记对应的闸门上，
     *    模拟"长时间占用许可的运行中任务"；
     * 2. 被中断（取消）时按取消语义抛出带 InterruptedException 的异常；
     * 3. 无标记时立即返回纯文本最终回答（一轮结束，无工具调用）。
     */
    static class HoldingChatModel implements ChatModel {

        /** 挂起标记 → 闸门。按提示词内容匹配，天然支持并发任务各自独立挂起。 */
        private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();

        /** 失败模式开关：任意模型调用直接抛异常。 */
        private volatile boolean failMode;

        void hold(String mark) {
            gates.put(mark, new CountDownLatch(1));
        }

        void releaseGate(String mark) {
            CountDownLatch latch = gates.remove(mark);
            if (latch != null) {
                latch.countDown();
            }
        }

        void setFailMode(boolean failMode) {
            this.failMode = failMode;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (failMode) {
                throw new IllegalStateException("HoldingChatModel 模拟模型调用失败");
            }

            String userText = prompt.getInstructions().stream()
                    .filter(message -> message instanceof UserMessage)
                    .map(Message::getText)
                    .collect(Collectors.joining(" "));

            for (Map.Entry<String, CountDownLatch> entry : gates.entrySet()) {
                if (userText.contains(entry.getKey())) {
                    try {
                        entry.getValue().await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                new InterruptedException("模型调用被取消中断")
                        );
                    }
                }
            }

            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage("并发控制测试的最终回答")))
            );
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HoldingModelConfig {

        private final HoldingChatModel behavior = new HoldingChatModel();

        /**
         * MyKeywordEnricher 按具体类型 DashScopeChatModel 注入，
         * 因此用 Mockito mock 作为类型壳，行为委托给 HoldingChatModel。
         */
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

        /** RAG 文档加载替换为 mock，避免启动时富集调用真实模型。 */
        @Bean("chatAppDocumentLoader")
        ChatAppDocumentLoader chatAppDocumentLoader() {
            ChatAppDocumentLoader mock =
                    Mockito.mock(ChatAppDocumentLoader.class);
            Mockito.when(mock.loadMarkdowns()).thenReturn(List.of());
            return mock;
        }

        /** 测试专用库存工具（模型不会调用，仅保证工具链装配完整）。 */
        @Bean
        DemoInventoryTool demoInventoryTool() {
            return new DemoInventoryTool();
        }

        /** 覆盖生产的 mkToolCallbacks：只暴露测试工具。 */
        @Bean("mkToolCallbacks")
        @Primary
        ToolCallback[] mkToolCallbacks(DemoInventoryTool demoInventoryTool) {
            return ToolCallbacks.from(demoInventoryTool);
        }

        /** MCP ToolCallbackProvider 桩。 */
        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }

        @Bean
        HoldingChatModel holdingChatModel() {
            return behavior;
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private AgentConcurrencyGuard concurrencyGuard;

    @Autowired
    private AgentTaskRegistry agentTaskRegistry;

    @Autowired
    private HoldingChatModel holdingChatModel;

    /**
     * 替换 RAG 内存向量库，避免测试触网调用 Embedding API。
     */
    @MockitoBean
    private VectorStore chatAppVectorStore;

    @BeforeEach
    void resetAndAwaitPermits() throws InterruptedException {
        holdingChatModel.setFailMode(false);
        // 等待上一个测试的任务全部终态并释放许可，保证断言基线干净。
        awaitUntil(() -> concurrencyGuard.availablePermits()
                == concurrencyGuard.getMaxConcurrency());
    }

    @AfterEach
    void releaseAllGates() {
        holdingChatModel.releaseGate("HOLD-A");
        holdingChatModel.releaseGate("HOLD-B");
        holdingChatModel.setFailMode(false);
    }

    /**
     * 验证点 1：并发上限生效。
     * 并发上限 = 2，两个任务被挂起占满许可后，
     * 第 3 个请求必须被 429 拒绝并返回明确业务文案。
     */
    @Test
    void thirdRequestRejectedWhenConcurrencyFull() throws Exception {
        assertEquals(2, concurrencyGuard.getMaxConcurrency(),
                "测试配置应把并发上限降为 2");

        holdingChatModel.hold("HOLD-A");
        holdingChatModel.hold("HOLD-B");

        // 两个任务分别挂起在各自的模型调用闸门上，占满全部许可。
        CompletableFuture<HttpResponse<String>> first =
                requestAgentSseAsync("user-cc-full-1", "请执行任务 HOLD-A");
        CompletableFuture<HttpResponse<String>> second =
                requestAgentSseAsync("user-cc-full-2", "请执行任务 HOLD-B");

        awaitUntil(() -> concurrencyGuard.availablePermits() == 0);

        // 并发已满：第 3 个请求在获取许可时被拒绝。
        HttpResponse<String> third =
                requestAgentSse("user-cc-full-3", "请执行普通任务");

        assertEquals(429, third.statusCode(),
                "并发已满时应返回 429，响应体：" + third.body());
        assertTrue(third.body().contains(BaseAgent.CONCURRENCY_LIMIT_MESSAGE),
                "应返回明确业务文案。响应体：" + third.body());

        // 放行两个挂起任务，验证它们正常完成且许可全部归还。
        holdingChatModel.releaseGate("HOLD-A");
        holdingChatModel.releaseGate("HOLD-B");

        HttpResponse<String> firstResponse = first.get(20, TimeUnit.SECONDS);
        HttpResponse<String> secondResponse = second.get(20, TimeUnit.SECONDS);
        assertTrue(firstResponse.body().contains("event:done"),
                "放行后第一个任务应正常完成");
        assertTrue(secondResponse.body().contains("event:done"),
                "放行后第二个任务应正常完成");

        awaitUntil(() -> concurrencyGuard.availablePermits()
                == concurrencyGuard.getMaxConcurrency());
    }

    /**
     * 验证点 2：许可不泄漏。
     * 顺序执行 3 个正常完成的任务后，许可数必须恢复到上限。
     */
    @Test
    void permitsNotLeakedAfterSequentialRuns() throws Exception {
        for (int i = 1; i <= 3; i++) {
            HttpResponse<String> response =
                    requestAgentSse("user-cc-leak-" + i, "请执行普通任务");
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("event:done"),
                    "第 " + i + " 个任务应正常完成");
        }

        awaitUntil(() -> concurrencyGuard.availablePermits()
                == concurrencyGuard.getMaxConcurrency());
        assertEquals(2, concurrencyGuard.availablePermits(),
                "顺序任务全部完成后许可必须全部归还，不能泄漏");
    }

    /**
     * 验证点 3：失败场景释放许可。
     * 模型调用抛异常 → 任务 FAILED → 许可仍必须释放。
     */
    @Test
    void permitReleasedAfterFailure() throws Exception {
        holdingChatModel.setFailMode(true);

        HttpResponse<String> response =
                requestAgentSse("user-cc-fail", "请执行一个会失败的任务");

        assertEquals(200, response.statusCode(),
                "任务失败不影响 SSE 连接本身返回 200");
        assertTrue(response.body().contains("event:error"),
                "模型失败应推送 error 事件。原始 SSE 文本：\n" + response.body());

        awaitUntil(() -> concurrencyGuard.availablePermits()
                == concurrencyGuard.getMaxConcurrency());
        assertEquals(2, concurrencyGuard.availablePermits(),
                "失败任务结束后许可必须释放");
    }

    /**
     * 验证点 4：取消场景释放许可。
     * 挂起任务被主动取消 → 任务线程退出 → 许可释放。
     */
    @Test
    void permitReleasedAfterCancel() throws Exception {
        holdingChatModel.hold("HOLD-A");

        CompletableFuture<HttpResponse<String>> sseFuture =
                requestAgentSseAsync("user-cc-cancel", "请执行任务 HOLD-A");

        awaitUntil(() -> concurrencyGuard.availablePermits() == 1);

        // 从注册表找到运行中任务并通过取消接口取消。
        RunningAgentTask runningTask = awaitRunningTask();
        assertNotNull(runningTask, "挂起任务应已注册到注册表");
        String runId = runningTask.context().getRunId().toString();

        HttpResponse<String> cancelResponse = postCancel(runId);
        assertEquals(200, cancelResponse.statusCode(),
                "取消接口应返回 200，响应体：" + cancelResponse.body());
        assertTrue(cancelResponse.body().contains("CANCELLED"));

        HttpResponse<String> sseResponse = sseFuture.get(20, TimeUnit.SECONDS);
        assertTrue(sseResponse.body().contains("event:cancelled"),
                "取消后 SSE 应收到 cancelled 事件");
        assertFalse(sseResponse.body().contains("event:done"),
                "被取消的任务不应再发送 done 事件");

        awaitUntil(() -> concurrencyGuard.availablePermits()
                == concurrencyGuard.getMaxConcurrency());
        assertEquals(2, concurrencyGuard.availablePermits(),
                "取消任务结束后许可必须释放");
    }

    // ===== HTTP 辅助方法 =====

    private CompletableFuture<HttpResponse<String>> requestAgentSseAsync(
            String userId,
            String message
    ) {
        return httpClient().sendAsync(
                buildSseRequest(userId, message),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> requestAgentSse(String userId, String message)
            throws Exception {
        return httpClient().send(
                buildSseRequest(userId, message),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpRequest buildSseRequest(String userId, String message) {
        String url = "http://localhost:" + port + "/api/ai/manus/chat?message="
                + URLEncoder.encode(message, StandardCharsets.UTF_8);
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("X-User-Id", userId)
                .GET()
                .build();
    }

    private HttpResponse<String> postCancel(String runId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/ai/manus/" + runId + "/cancel"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ===== 轮询辅助方法 =====

    /**
     * 轮询注册表，直到出现一个 RUNNING 状态的任务（最多等 10 秒）。
     */
    private RunningAgentTask awaitRunningTask() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            RunningAgentTask task = agentTaskRegistry.snapshot().stream()
                    .filter(t -> t.context().getState() == AgentState.RUNNING)
                    .findFirst()
                    .orElse(null);
            if (task != null) {
                return task;
            }
            Thread.sleep(50);
        }
        return null;
    }

    /**
     * 轮询等待条件成立（最多等 15 秒）。
     */
    private void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "等待条件超时未成立");
    }
}
