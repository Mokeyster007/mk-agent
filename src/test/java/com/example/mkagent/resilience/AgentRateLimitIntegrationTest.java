package com.example.mkagent.resilience;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import com.example.mkagent.tools.DemoInventoryTool;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户请求限流集成测试（真实 HTTP 链路）。
 *
 * 与其他隔离集成测试相同的策略：排除 DashScope / MCP 自动配置、
 * H2 内存库、测试模型替换真实模型，全程不调用真实大模型。
 *
 * 本测试将限流阈值调低为每分钟 3 次
 * （mkagent.rate-limit.max-requests=3），快速复现超限场景。
 * 模型使用立即回答的 ImmediateChatModel（一轮结束、无工具调用），
 * 让每个放行的请求快速完成，避免测试互相阻塞。
 *
 * 覆盖用户要求的验证点：
 * 1. 单用户超过频率时被限流（429 + 友好提示 + 建议等待秒数）；
 * 2. 限流按用户隔离，其他用户不受影响；
 * 3. 限流只作用于发起 Agent 任务的接口，
 *    普通查询类业务接口不受影响。
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
                "spring.datasource.url=jdbc:h2:mem:mk_agent_ratelimit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/schema.sql",
                "mkagent.rag.pgvector.enabled=false",
                // 限流阈值调低为每分钟 3 次，快速复现超限。
                "mkagent.rate-limit.max-requests=3"
        }
)
@ActiveProfiles("local")
@Timeout(90)
class AgentRateLimitIntegrationTest {

    /**
     * 立即回答模型：一轮直接返回最终回答（无工具调用），
     * 保证放行的请求快速完成，测试只聚焦限流本身。
     */
    static class ImmediateChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage("限流测试回答")))
            );
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ImmediateModelConfig {

        private final ImmediateChatModel behavior = new ImmediateChatModel();

        /**
         * MyKeywordEnricher 按具体类型 DashScopeChatModel 注入，
         * 因此用 Mockito mock 作为类型壳，行为委托给 ImmediateChatModel。
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
    }

    @LocalServerPort
    private int port;

    /**
     * 替换 RAG 内存向量库，避免测试触网调用 Embedding API。
     */
    @MockitoBean
    private VectorStore chatAppVectorStore;

    /**
     * 验证点 1：单用户超过频率时被限流。
     * 阈值 = 3/分钟：前 3 次放行，第 4 次返回 429，
     * 且提示中包含建议等待秒数与限流阈值。
     */
    @Test
    void singleUserLimitedAfterMaxRequests() throws Exception {
        String userId = "user-rl-a";

        for (int i = 1; i <= 3; i++) {
            HttpResponse<String> response = requestAgentSse(userId);
            assertEquals(200, response.statusCode(),
                    "限额内的第 " + i + " 次请求应放行");
            assertTrue(response.body().contains("event:done"),
                    "放行的请求应正常执行完成");
        }

        HttpResponse<String> fourth = requestAgentSse(userId);

        assertEquals(429, fourth.statusCode(),
                "超过频率应返回 429，响应体：" + fourth.body());
        assertTrue(fourth.body().contains("请求过于频繁"),
                "应返回友好提示。响应体：" + fourth.body());
        assertTrue(fourth.body().contains("秒后重试"),
                "应给出建议等待时间。响应体：" + fourth.body());
        assertTrue(fourth.body().contains("每分钟最多 3 次"),
                "提示应包含当前限流阈值。响应体：" + fourth.body());
    }

    /**
     * 验证点 2：限流按用户隔离。
     * 用户 A 超限后，用户 B 的请求仍应放行。
     */
    @Test
    void otherUserNotAffectedByOneUsersLimit() throws Exception {
        String limitedUser = "user-rl-limit";
        String otherUser = "user-rl-other";

        // 用户 A 用完配额（3 次放行 + 1 次拒绝）。
        for (int i = 0; i < 4; i++) {
            requestAgentSse(limitedUser);
        }
        assertEquals(429, requestAgentSse(limitedUser).statusCode(),
                "用户 A 应已被限流");

        // 用户 B 拥有独立配额，不受用户 A 影响。
        HttpResponse<String> otherResponse = requestAgentSse(otherUser);
        assertEquals(200, otherResponse.statusCode(),
                "其他用户不应被当前用户的限流影响");
        assertTrue(otherResponse.body().contains("event:done"));
    }

    /**
     * 验证点 3：限流只作用于发起 Agent 任务的接口。
     * 超限用户查询自己的任务历史（普通业务接口）仍应成功。
     */
    @Test
    void nonAgentEndpointNotAffectedByRateLimit() throws Exception {
        String userId = "user-rl-query";

        // 用完 Agent 配额：3 次放行 + 1 次拒绝。
        for (int i = 0; i < 3; i++) {
            assertEquals(200, requestAgentSse(userId).statusCode());
        }
        assertEquals(429, requestAgentSse(userId).statusCode(),
                "Agent 接口应已被限流");

        // 普通查询接口不经过限流：同一用户仍可正常查询。
        HttpRequest pageRequest = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port
                                + "/api/ai/runs/page?pageNum=1&pageSize=10"))
                .timeout(Duration.ofSeconds(10))
                .header("X-User-Id", userId)
                .GET()
                .build();

        HttpResponse<String> pageResponse = httpClient().send(
                pageRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, pageResponse.statusCode(),
                "不涉及 Agent 的普通业务接口不应被限流，响应体："
                        + pageResponse.body());
    }

    // ===== HTTP 辅助方法 =====

    private HttpResponse<String> requestAgentSse(String userId) throws Exception {
        String url = "http://localhost:" + port + "/api/ai/manus/chat?message="
                + URLEncoder.encode("请执行限流测试任务", StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
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
}
