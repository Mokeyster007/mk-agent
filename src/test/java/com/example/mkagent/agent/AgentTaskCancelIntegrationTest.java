package com.example.mkagent.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.RunningAgentTask;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主动取消接口 + 运行中任务注册表的集成测试。
 *
 * 与 MkManusAsyncSseIntegrationTest 相同的隔离策略：
 * 1. 排除 DashScope 自动配置，不创建真实模型；
 * 2. 用 SlowFakeChatModel 替换真实模型：
 *    第一轮模型调用长时间 sleep，模拟"正在运行的长任务"，
 *    给测试留出稳定的取消窗口；被中断时记录 interrupted 标记。
 *
 * 覆盖用户要求的测试点：
 * 1. 运行中的任务能被注册表查询到；
 * 2. 主动取消后状态为 CANCELLED；
 * 3. 取消后不会进入新的 Agent Step（模型只被调用 1 次）；
 * 4. 执行线程被显式中断（CompletableFuture.cancel(true) 不会中断线程）；
 * 5. 任务结束后从注册表移除；
 * 6. SSE 收到 cancelled 事件；
 * 7. 取消不存在 / 已结束的任务返回明确业务错误。
 */
@HermeticSpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Timeout(60)
class AgentTaskCancelIntegrationTest {

    /**
     * 慢速假模型：第一次调用长时间 sleep，模拟长任务。
     * 被中断时设置 interrupted 标记并抛出带 InterruptedException 的异常。
     */
    static class SlowFakeChatModel implements ChatModel {

        static final long SLEEP_MILLIS = 10_000;

        final AtomicInteger callCount = new AtomicInteger();

        final AtomicBoolean interrupted = new AtomicBoolean();

        @Override
        public ChatResponse call(Prompt prompt) {
            callCount.incrementAndGet();
            try {
                Thread.sleep(SLEEP_MILLIS);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        new InterruptedException("模型调用被取消中断")
                );
            }
            // 正常情况下不会执行到这里：测试总会在 sleep 期间取消任务。
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage("不会到达的回答")))
            );
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SlowModelConfig {

        private final SlowFakeChatModel behavior = new SlowFakeChatModel();

        /**
         * MyKeywordEnricher 按具体类型 DashScopeChatModel 注入，
         * 因此用 Mockito mock 作为类型壳，并把行为委托给 SlowFakeChatModel。
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

        /**
         * 排除 MCP 自动配置后，chatApp 注入的 ToolCallbackProvider 由此桩提供，
         * 返回空工具列表，测试完全不依赖外部 MCP 服务。
         */
        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }

        @Bean
        SlowFakeChatModel slowFakeChatModel() {
            return behavior;
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private AgentTaskRegistry agentTaskRegistry;

    @Autowired
    private SlowFakeChatModel slowFakeChatModel;

    /**
     * 替换 RAG 内存向量库，避免测试触网调用 Embedding API。
     */
    @MockitoBean
    private VectorStore chatAppVectorStore;

    /**
     * 桩模型是单例 Bean，多个用例共享同一个计数器。
     *
     * 每个用例开始前清零，否则"模型只被调用 1 次"这类**绝对次数**断言
     * 会被上一个用例的调用污染（表现为偶发失败，且与执行顺序相关）。
     */
    @BeforeEach
    void resetModelCounters() {
        slowFakeChatModel.callCount.set(0);
        slowFakeChatModel.interrupted.set(false);
    }

    @Test
    void cancelRunningTaskThroughHttpApi() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        // ===== 1. 异步发起 SSE 请求（不阻塞测试线程） =====
        String url = "http://localhost:" + port + "/api/ai/manus/chat?message="
                + URLEncoder.encode("请执行一个需要很长时间的库存盘点任务",
                StandardCharsets.UTF_8);

        HttpRequest sseRequest = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                // Agent 任务归属需要用户身份（请求头占位方案）。
                .header("X-User-Id", "user-cancel-test")
                .GET()
                .build();

        CompletableFuture<HttpResponse<String>> sseFuture =
                client.sendAsync(sseRequest, HttpResponse.BodyHandlers.ofString());

        // ===== 2. 轮询注册表，等待任务注册并处于 RUNNING =====
        RunningAgentTask runningTask = awaitRunningTask();
        assertThat(runningTask).as("运行中的任务应已注册到注册表").isNotNull();

        AgentRunContext ctx = runningTask.context();
        String runId = ctx.getRunId().toString();

        assertThat(agentTaskRegistry.size()).isGreaterThanOrEqualTo(1);
        assertThat(ctx.getState()).isEqualTo(AgentState.RUNNING);

        // ===== 3. 调用取消接口（必须带上任务所属用户） =====
        HttpRequest cancelRequest = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/ai/manus/" + runId + "/cancel"))
                .header("X-User-Id", "user-cancel-test")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> cancelResponse =
                client.send(cancelRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(cancelResponse.statusCode()).isEqualTo(200);
        assertThat(cancelResponse.body()).contains("CANCELLED");
        assertThat(ctx.getState()).isEqualTo(AgentState.CANCELLED);

        // ===== 4. 等待后台任务真正退出 =====
        try {
            runningTask.future().join();
        } catch (Exception ignored) {
            // 被取消的任务 join 会抛 CancellationException / CompletionException
        }

        // ===== 5. 取消后不会进入新的 Agent Step：模型只被调用 1 次 =====
        assertThat(slowFakeChatModel.callCount.get())
                .as("取消后不应再发起新的模型调用（新一轮 step）")
                .isEqualTo(1);
        assertThat(slowFakeChatModel.interrupted.get())
                .as("执行线程应被显式中断（CompletableFuture.cancel 不会中断线程）")
                .isTrue();

        // ===== 6. 任务结束后从注册表移除 =====
        awaitUntil(() -> !agentTaskRegistry.contains(runId));
        assertThat(agentTaskRegistry.contains(runId)).isFalse();

        // ===== 7. SSE 连接被关闭且包含 cancelled 事件 =====
        HttpResponse<String> sseResponse = sseFuture.get(10, TimeUnit.SECONDS);
        assertThat(sseResponse.body()).contains("event:cancelled");

        // ===== 8. 再次取消同一任务：已移除 → 404 业务错误 =====
        HttpResponse<String> secondCancel =
                client.send(cancelRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(secondCancel.statusCode()).isEqualTo(404);
        assertThat(secondCancel.body()).contains("任务不存在或已结束");
    }

    @Test
    void cancelUnknownRunIdReturns404() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/ai/manus/no-such-run-id/cancel"))
                .header("X-User-Id", "user-cancel-test")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("任务不存在或已结束");
    }

    /**
     * 缺失 X-User-Id：401，且**先于** runId 查询发生。
     *
     * 顺序很重要：先鉴权再查注册表，
     * 否则"有没有身份"和"runId 是否存在"的响应差异本身就成了探测通道。
     */
    @Test
    void cancelWithoutUserHeaderReturns401() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/ai/manus/no-such-run-id/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("X-User-Id");
    }

    /**
     * 跨用户取消：404，且任务**没有被取消**。
     *
     * 断言要点：
     * 1. 别人的任务取消请求拿不到任何"存在性"信号——404 与"任务不存在"
     *    共用同一句文案；
     * 2. 状态仍是 RUNNING、注册表里仍在，说明拒绝发生在取消之前，
     *    不是"先取消再报错"；
     * 3. 跨用户 404 的响应体与"任务结束后再取消"的响应体**逐字相同**
     *    （同一个 runId，只差任务是否还活着），这是最难被误改的一条。
     */
    @Test
    void crossUserCancelIsRejectedAndTaskKeepsRunning() throws Exception {
        String owner = "user-cancel-owner";
        String other = "user-cancel-other";

        HttpClient client = HttpClient.newHttpClient();
        String url = "http://localhost:" + port + "/api/ai/manus/chat?message="
                + URLEncoder.encode("请执行一个需要很长时间的库存盘点任务",
                StandardCharsets.UTF_8);

        CompletableFuture<HttpResponse<String>> sseFuture = client.sendAsync(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("X-User-Id", owner)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        RunningAgentTask runningTask = awaitRunningTask(owner);
        assertThat(runningTask).as("任务应已注册到注册表").isNotNull();

        AgentRunContext ctx = runningTask.context();
        String runId = ctx.getRunId().toString();
        assertThat(ctx.getUserId()).isEqualTo(owner);

        // ===== 他人取消：404，任务不受影响 =====
        HttpResponse<String> rejected = postCancel(client, other, runId);

        assertThat(rejected.statusCode()).isEqualTo(404);
        assertThat(rejected.body()).contains("任务不存在或已结束");
        assertThat(ctx.getState())
                .as("跨用户取消不得改变任务状态")
                .isEqualTo(AgentState.RUNNING);
        assertThat(agentTaskRegistry.contains(runId))
                .as("跨用户取消不得把任务从注册表移除")
                .isTrue();

        // ===== 本人取消：成功 =====
        HttpResponse<String> cancelled = postCancel(client, owner, runId);

        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(cancelled.body()).contains("CANCELLED");

        try {
            runningTask.future().join();
        } catch (Exception ignored) {
            // 被取消的任务 join 会抛 CancellationException / CompletionException
        }
        awaitUntil(() -> !agentTaskRegistry.contains(runId));

        // ===== 任务结束后再取消：与跨用户 404 逐字相同 =====
        HttpResponse<String> gone = postCancel(client, owner, runId);

        assertThat(gone.statusCode()).isEqualTo(404);
        assertThat(gone.body())
                .as("跨用户与任务已结束必须返回完全相同的响应体")
                .isEqualTo(rejected.body());

        // 收尾：SSE 连接应已结束
        sseFuture.get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<String> postCancel(HttpClient client, String userId,
                                            String runId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/ai/manus/" + runId + "/cancel"))
                .timeout(Duration.ofSeconds(10))
                .header("X-User-Id", userId)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 轮询注册表，直到出现指定用户的一个 RUNNING 任务（最多等 10 秒）。
     *
     * 按用户筛选：注册表是进程内共享的，
     * 其他用例残留的任务不能被误当成"本次启动的任务"。
     */
    private RunningAgentTask awaitRunningTask(String userId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            RunningAgentTask task = agentTaskRegistry.snapshot().stream()
                    .filter(t -> t.context().getState() == AgentState.RUNNING)
                    .filter(t -> userId.equals(t.context().getUserId()))
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
     * 轮询等待条件成立（最多等 10 秒）。
     */
    private void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            Thread.sleep(50);
        }
    }
}
