package com.example.mkagent.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.mkagent.controller.AgentRunController;
import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.entity.AgentRunEntity;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.model.vo.AgentRunVO;
import com.example.mkagent.model.vo.PageResult;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import com.example.mkagent.service.AgentRunService;
import com.example.mkagent.support.FakeChatModel;
import com.example.mkagent.tools.DemoInventoryTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentRun 任务持久化集成测试。
 *
 * 与 MkManusAsyncSseIntegrationTest 相同的隔离策略：
 * 排除 DashScope / MCP 自动配置、FakeChatModel 替换真实模型、
 * DemoInventoryTool 覆盖工具白名单，全程不调用真实模型与外部工具。
 *
 * 数据库使用 H2 内存库（PostgreSQL 兼容模式）+ db/schema.sql 建表，
 * 不依赖外部数据库。
 *
 * 覆盖用户要求的验证点：
 * 1. 任务启动后持久化 RUNNING 记录；
 * 2. 正常完成后更新 SUCCEEDED（final_answer / finished_at / total_cost_millis）；
 * 3. 工具调用后 tool_call_count 正确更新；
 * 4. 失败、取消时状态正确更新；
 * 5. 用户 A 不能查询用户 B 的任务；
 * 6. 分页只返回自己的任务且支持筛选；
 * 7. 模型 Token Usage（model / prompt / completion / total tokens）
 *    随终态持久化到 agent_run。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration,"
                        // 排除 MCP 客户端自动配置：启动时会真实连接外部 MCP 服务，
                        // 测试环境不依赖 MCP，chatApp 需要的 ToolCallbackProvider 由测试桩提供。
                        + "org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.McpToolCallbackAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.StdioTransportAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.SseHttpClientTransportAutoConfiguration",
                // 测试配置需要覆盖用户配置类的同名 Bean（如 chatAppDocumentLoader）
                "spring.main.allow-bean-definition-overriding=true",
                // AgentRun 持久化使用 H2 内存库（PostgreSQL 兼容模式）。
                "spring.datasource.url=jdbc:h2:mem:mk_agent_persist;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/schema.sql",
                // 禁用 pgvector 向量库：PgVectorStore 初始化会执行 PostgreSQL 专用的
                // CREATE EXTENSION IF NOT EXISTS vector，H2 不支持该语法。
                "mkagent.rag.pgvector.enabled=false"
        }
)
@ActiveProfiles("local")
@Timeout(90)
class AgentRunPersistenceIntegrationTest {

    /** 测试要求的固定提示词。 */
    private static final String PROMPT = """
            请查询 SKU MK-2026-001 的库存。
            必须根据库存工具返回的结果回答；
            不要凭空猜测库存数量。
            """;

    /**
     * 与其他隔离集成测试相同的桩配置：
     * FakeChatModel 两轮可控行为 + DemoInventoryTool 覆盖工具白名单。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModelConfig {

        private final FakeChatModel behavior = new FakeChatModel();

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

        /** 测试专用库存工具，仅存活于测试上下文。 */
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
        FakeChatModel fakeChatModel() {
            return behavior;
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private AgentRunService agentRunService;

    @Autowired
    private AgentRunController agentRunController;

    /**
     * 替换 RAG 内存向量库，避免测试触网调用 Embedding API。
     */
    @MockitoBean
    private VectorStore chatAppVectorStore;

    @BeforeEach
    void resetControls() {
        fakeChatModel.resetControls();
    }

    @AfterEach
    void clearUserContext() {
        UserContextHolder.clear();
    }

    /**
     * 验证点 1/2/3：
     * 启动 → RUNNING 落库；工具调用后 tool_call_count 更新；
     * 正常完成 → SUCCEEDED + final_answer + finished_at + total_cost_millis。
     */
    @Test
    void runPersistsRunningProgressAndSucceeded() throws Exception {
        String userId = "user-persist-success";

        // 闸门：工具执行完后、最终回答前暂停，拿到稳定的"运行中"窗口。
        CountDownLatch holdFinalAnswer = new CountDownLatch(1);
        fakeChatModel.setPauseBeforeFinalAnswer(holdFinalAnswer);

        CompletableFuture<HttpResponse<String>> sseFuture =
                requestAgentSseAsync(userId, PROMPT);

        // ===== 1. 任务启动后持久化 RUNNING 记录 =====
        AgentRunEntity running = awaitRunInState(userId, "RUNNING");
        assertThat(running).as("任务启动后应持久化 RUNNING 记录").isNotNull();

        String runId = running.getRunId();
        assertThat(running.getUserId()).isEqualTo(userId);
        assertThat(running.getAgentType()).isEqualTo("MANUS");
        assertThat(running.getUserPrompt()).contains("MK-2026-001");
        assertThat(running.getStartedAt()).isNotNull();
        assertThat(running.getFinishedAt()).as("运行中任务不应有结束时间").isNull();

        // ===== 2. 工具调用后 tool_call_count 正确更新 =====
        awaitUntil(() -> {
            AgentRunEntity current = findRun(runId);
            return current != null && current.getToolCallCount() >= 1;
        });
        AgentRunEntity progressed = findRun(runId);
        assertThat(progressed.getToolCallCount()).isGreaterThanOrEqualTo(1);
        assertThat(progressed.getCurrentStep()).isGreaterThanOrEqualTo(1);

        // ===== 3. 放行闸门，任务正常完成 =====
        holdFinalAnswer.countDown();
        HttpResponse<String> sseResponse = sseFuture.get(20, TimeUnit.SECONDS);
        assertThat(sseResponse.body()).contains("event:final_answer");

        // ===== 4. 终态更新 SUCCEEDED =====
        awaitUntil(() -> "SUCCEEDED".equals(stateOf(runId)));
        AgentRunEntity finished = findRun(runId);
        assertThat(finished.getFinalAnswer()).contains("17");
        assertThat(finished.getFinishedAt()).isNotNull();
        assertThat(finished.getTotalCostMillis()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(finished.getErrorMessage()).isNull();
    }

    /**
     * 验证点 4（取消）：
     * 新取消接口（/ai/runs/{runId}/cancel）复用注册表取消逻辑，
     * 任务线程退出后数据库更新 CANCELLED。
     */
    @Test
    void cancelThroughRunsApiPersistsCancelled() throws Exception {
        String userId = "user-persist-cancel";

        CountDownLatch holdFinalAnswer = new CountDownLatch(1);
        fakeChatModel.setPauseBeforeFinalAnswer(holdFinalAnswer);

        CompletableFuture<HttpResponse<String>> sseFuture =
                requestAgentSseAsync(userId, PROMPT);

        AgentRunEntity running = awaitRunInState(userId, "RUNNING");
        assertThat(running).isNotNull();
        String runId = running.getRunId();

        // 走新取消接口的完整 HTTP 链路：拦截器 + 归属校验 + 注册表取消。
        HttpResponse<String> cancelResponse = postCancel(runId, userId);
        assertEquals(200, cancelResponse.statusCode(),
                "取消接口应返回 200，响应体：" + cancelResponse.body());
        assertThat(cancelResponse.body()).contains("CANCELLED");

        // SSE 收到 cancelled 事件并关闭。
        HttpResponse<String> sseResponse = sseFuture.get(20, TimeUnit.SECONDS);
        assertThat(sseResponse.body()).contains("event:cancelled");

        // 任务线程退出后数据库落盘 CANCELLED。
        awaitUntil(() -> "CANCELLED".equals(stateOf(runId)));
        AgentRunEntity finished = findRun(runId);
        assertThat(finished.getFinishedAt()).isNotNull();
        assertThat(finished.getTotalCostMillis()).isNotNull();
    }

    /**
     * 验证点 4（失败）：
     * 模型调用抛异常 → FAILED + 脱敏 errorMessage（无堆栈）。
     */
    @Test
    void failedTaskPersistsFailedWithMaskedErrorMessage() throws Exception {
        String userId = "user-persist-fail";
        fakeChatModel.setFailMode(true);

        HttpResponse<String> sseResponse = requestAgentSseSync(userId, PROMPT);
        assertThat(sseResponse.body()).contains("event:error");

        awaitUntil(() -> {
            AgentRunEntity latest = latestRunOf(userId);
            return latest != null && "FAILED".equals(latest.getState());
        });

        AgentRunEntity failed = latestRunOf(userId);
        assertThat(failed.getErrorMessage()).contains("IllegalStateException");
        // 脱敏要求：不包含堆栈帧。
        assertThat(failed.getErrorMessage()).doesNotContain("\tat ");
        assertThat(failed.getFinishedAt()).isNotNull();
        assertThat(failed.getTotalCostMillis()).isNotNull();
        assertThat(failed.getFinalAnswer()).isNull();
    }

    /**
     * 验证点 7（模型 Usage）：
     * 模型响应中的 Token 用量随终态持久化。
     *
     * FakeChatModel 每轮固定返回 model=fake-model、
     * promptTokens=10 / completionTokens=5，任务共 2 轮，
     * 因此累计应为 prompt=20 / completion=10 / total=30。
     */
    @Test
    void runPersistsModelUsage() throws Exception {
        String userId = "user-persist-usage";

        requestAgentSseSync(userId, PROMPT);

        awaitUntil(() -> {
            AgentRunEntity latest = latestRunOf(userId);
            return latest != null && "SUCCEEDED".equals(latest.getState());
        });

        AgentRunEntity finished = latestRunOf(userId);

        assertThat(finished.getModel())
                .as("应记录响应元数据中的模型名")
                .isEqualTo("fake-model");
        assertThat(finished.getPromptTokens())
                .as("两轮各 10 prompt tokens，应累计为 20")
                .isEqualTo(20L);
        assertThat(finished.getCompletionTokens())
                .as("两轮各 5 completion tokens，应累计为 10")
                .isEqualTo(10L);
        assertThat(finished.getTotalTokens())
                .as("total = prompt + completion")
                .isEqualTo(30L);
    }

    /**
     * 验证点 5：用户 A 不能查询 / 取消用户 B 的任务。
     */
    @Test
    void userCannotQueryOrCancelAnotherUsersRun() throws Exception {
        String ownerId = "user-owner";
        requestAgentSseSync(ownerId, PROMPT);

        AgentRunEntity entity = awaitTerminalRunOf(ownerId);
        assertThat(entity).isNotNull();
        String runId = entity.getRunId();

        // 本人可查（直接调用 Controller，覆盖接口层逻辑）。
        UserContextHolder.set(ownerId);
        try {
            AgentRunVO ownView = agentRunController.getRun(runId);
            assertThat(ownView.getRunId()).isEqualTo(runId);
            assertThat(ownView.getUserId()).isEqualTo(ownerId);
        } finally {
            UserContextHolder.clear();
        }

        // 他人 HTTP 查询 → 404（不泄露任务是否存在）。
        HttpResponse<String> otherDetail = getRunHttp(runId, "user-other");
        assertEquals(404, otherDetail.statusCode());

        // 他人直接调用查询 / 取消 → BusinessException 404。
        UserContextHolder.set("user-other");
        try {
            BusinessException queryException = assertThrows(
                    BusinessException.class,
                    () -> agentRunController.getRun(runId)
            );
            assertEquals(404, queryException.getHttpStatus());

            BusinessException cancelException = assertThrows(
                    BusinessException.class,
                    () -> agentRunController.cancelRun(runId)
            );
            assertEquals(404, cancelException.getHttpStatus());
        } finally {
            UserContextHolder.clear();
        }
    }

    /**
     * 验证点 6：分页只返回自己的任务，支持 state / agentType / 时间筛选。
     */
    @Test
    void pageReturnsOnlyOwnTasksAndSupportsFilters() throws Exception {
        requestAgentSseSync("user-page-a", PROMPT);
        requestAgentSseSync("user-page-a", PROMPT);
        requestAgentSseSync("user-page-b", PROMPT);

        awaitTerminalCount("user-page-a", 2);
        awaitTerminalCount("user-page-b", 1);

        UserContextHolder.set("user-page-a");
        try {
            // 不筛选：只有自己的 2 条。
            PageResult<AgentRunVO> page =
                    agentRunController.pageRuns(1, 10, null, null, null, null);
            assertThat(page.getTotal()).isEqualTo(2);
            assertThat(page.getRecords()).allSatisfy(vo ->
                    assertThat(vo.getUserId()).isEqualTo("user-page-a"));

            // agentType 筛选：MANUS。
            PageResult<AgentRunVO> manusOnly =
                    agentRunController.pageRuns(1, 10, null, "MANUS", null, null);
            assertThat(manusOnly.getTotal()).isEqualTo(2);
            assertThat(manusOnly.getRecords()).allSatisfy(vo ->
                    assertThat(vo.getAgentType()).isEqualTo("MANUS"));

            // 分页参数：每页 1 条。
            PageResult<AgentRunVO> onePerPage =
                    agentRunController.pageRuns(1, 1, null, null, null, null);
            assertThat(onePerPage.getRecords()).hasSize(1);
            assertThat(onePerPage.getTotal()).isEqualTo(2);
            assertThat(onePerPage.getPages()).isEqualTo(2);

            // state 筛选：无 RUNNING 记录。
            PageResult<AgentRunVO> runningOnly =
                    agentRunController.pageRuns(1, 10, "RUNNING", null, null, null);
            assertThat(runningOnly.getTotal()).isZero();

            // 创建时间筛选：过去的区间查不到任何记录。
            PageResult<AgentRunVO> pastOnly = agentRunController.pageRuns(
                    1, 10, null, null,
                    LocalDateTime.of(2020, 1, 1, 0, 0),
                    LocalDateTime.of(2020, 12, 31, 23, 59)
            );
            assertThat(pastOnly.getTotal()).isZero();
        } finally {
            UserContextHolder.clear();
        }

        // 另一个用户只能看到自己的 1 条。
        UserContextHolder.set("user-page-b");
        try {
            PageResult<AgentRunVO> otherPage =
                    agentRunController.pageRuns(1, 10, null, null, null, null);
            assertThat(otherPage.getTotal()).isEqualTo(1);
            assertThat(otherPage.getRecords()).allSatisfy(vo ->
                    assertThat(vo.getUserId()).isEqualTo("user-page-b"));
        } finally {
            UserContextHolder.clear();
        }
    }

    // ===== HTTP 辅助方法 =====

    private CompletableFuture<HttpResponse<String>> requestAgentSseAsync(
            String userId,
            String message
    ) throws Exception {
        return httpClient().sendAsync(
                buildSseRequest(userId, message),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> requestAgentSseSync(
            String userId,
            String message
    ) throws Exception {
        HttpResponse<String> response = httpClient().send(
                buildSseRequest(userId, message),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode(), "SSE 请求应返回 200");
        return response;
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

    private HttpResponse<String> postCancel(String runId, String userId)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/ai/runs/" + runId + "/cancel"))
                .timeout(Duration.ofSeconds(10))
                .header("X-User-Id", userId)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getRunHttp(String runId, String userId)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/ai/runs/" + runId))
                .timeout(Duration.ofSeconds(10))
                .header("X-User-Id", userId)
                .GET()
                .build();
        return httpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ===== 数据库轮询辅助方法 =====

    private AgentRunEntity findRun(String runId) {
        return agentRunService.lambdaQuery()
                .eq(AgentRunEntity::getRunId, runId)
                .one();
    }

    private String stateOf(String runId) {
        AgentRunEntity entity = findRun(runId);
        return entity == null ? null : entity.getState();
    }

    private AgentRunEntity latestRunOf(String userId) {
        List<AgentRunEntity> records = agentRunService.lambdaQuery()
                .eq(AgentRunEntity::getUserId, userId)
                .orderByDesc(AgentRunEntity::getCreatedAt)
                .page(new Page<>(1, 1))
                .getRecords();
        return records.isEmpty() ? null : records.get(0);
    }

    private AgentRunEntity awaitRunInState(String userId, String state)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            List<AgentRunEntity> records = agentRunService.lambdaQuery()
                    .eq(AgentRunEntity::getUserId, userId)
                    .eq(AgentRunEntity::getState, state)
                    .page(new Page<>(1, 1))
                    .getRecords();
            if (!records.isEmpty()) {
                return records.get(0);
            }
            Thread.sleep(50);
        }
        return null;
    }

    private AgentRunEntity awaitTerminalRunOf(String userId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            AgentRunEntity latest = latestRunOf(userId);
            if (latest != null && !"RUNNING".equals(latest.getState())
                    && !"IDLE".equals(latest.getState())) {
                return latest;
            }
            Thread.sleep(50);
        }
        return null;
    }

    private void awaitTerminalCount(String userId, int expectedCount)
            throws InterruptedException {
        awaitUntil(() -> agentRunService.lambdaQuery()
                .eq(AgentRunEntity::getUserId, userId)
                .notIn(AgentRunEntity::getState, "RUNNING", "IDLE")
                .count() >= expectedCount);
    }

    private void awaitUntil(BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "等待条件超时未成立");
    }
}
