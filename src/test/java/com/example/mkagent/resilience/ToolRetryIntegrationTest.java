package com.example.mkagent.resilience;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import com.example.mkagent.support.FakeChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具重试集成测试（真实 HTTP + 真实 Agent 执行链路）。
 *
 * 与其他隔离集成测试相同的策略：排除 DashScope / MCP 自动配置、
 * H2 内存库、FakeChatModel 替换真实模型，全程不调用真实大模型。
 *
 * 与生产一致的装配路径：
 * mkToolCallbacks 中的工具经过 ToolRetryWrapper.wrap 包装，
 * 白名单内的 web_search 被包装为 RetryableToolCallback。
 *
 * FlakySearchTool 模拟生产 WebSearchTool 的真实失败模式：
 * 内部吞掉异常并返回失败文案（"搜索工具执行失败：..."），
 * 第 1 次调用必失败，第 2 次成功。
 *
 * 覆盖用户要求的验证点：
 * 可重试工具在临时失败后被自动重试并成功，
 * 任务最终正常产出 final_answer，模型无感知（不需要重新决策）。
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
                "spring.datasource.url=jdbc:h2:mem:mk_agent_retry;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/schema.sql",
                "mkagent.rag.pgvector.enabled=false",
                // 重试退避调为 1ms，测试不被等待拖慢。
                "mkagent.tool-retry.initial-backoff-millis=1"
        }
)
@ActiveProfiles("local")
@Timeout(60)
class ToolRetryIntegrationTest {

    /** 测试要求的固定提示词（与 FakeChatModel 两轮行为配套）。 */
    private static final String PROMPT = """
            请查询 SKU MK-2026-001 的库存。
            必须根据搜索工具返回的结果回答；
            不要凭空猜测库存数量。
            """;

    /**
     * 模拟生产 WebSearchTool 的失败模式：
     * 内部吞异常返回失败文案（触发 failure-markers 识别），
     * 第 1 次调用失败，第 2 次成功。
     */
    public static class FlakySearchTool {

        private final AtomicInteger callCount = new AtomicInteger(0);

        @Tool(
                name = "web_search",
                description = "联网搜索工具（测试桩：第 1 次调用临时失败）"
        )
        public String search(
                @ToolParam(description = "搜索关键词") String query
        ) {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                return "搜索工具执行失败：模拟网络超时";
            }
            return "搜索结果：" + query + " 库存数量：17，状态：可发货";
        }

        public int getCallCount() {
            return callCount.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RetryModelConfig {

        /**
         * 两轮可控行为：第一轮请求 web_search 工具，
         * 第二轮基于工具结果回答（要求结果包含"库存数量：17 / 可发货"）。
         */
        private final FakeChatModel behavior =
                new FakeChatModel("web_search", "MK-2026-001");

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

        @Bean
        FlakySearchTool flakySearchTool() {
            return new FlakySearchTool();
        }

        /**
         * 覆盖生产的 mkToolCallbacks：
         * 与生产相同的包装路径——工具经过 ToolRetryWrapper.wrap，
         * 白名单内的 web_search 获得自动重试能力。
         */
        @Bean("mkToolCallbacks")
        @Primary
        ToolCallback[] mkToolCallbacks(
                FlakySearchTool flakySearchTool,
                ToolRetryWrapper toolRetryWrapper
        ) {
            return toolRetryWrapper.wrap(ToolCallbacks.from(flakySearchTool));
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
    private FlakySearchTool flakySearchTool;

    /**
     * 替换 RAG 内存向量库，避免测试触网调用 Embedding API。
     */
    @MockitoBean
    private VectorStore chatAppVectorStore;

    @BeforeEach
    void resetGlobalRetryCount() {
        RetryableToolCallback.resetGlobalRetryCount();
    }

    /**
     * 可重试工具在临时失败后自动重试并成功：
     * 1. 第 1 次调用返回失败文案 → 识别为失败并重试；
     * 2. 第 2 次调用成功 → 工具结果写回上下文；
     * 3. 模型基于真实工具结果回答，任务正常完成。
     */
    @Test
    void retryableToolSucceedsAfterTransientFailure() throws Exception {
        String url = "http://localhost:" + port + "/api/ai/manus/chat?message="
                + URLEncoder.encode(PROMPT, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("X-User-Id", "user-retry-test")
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        String body = response.body();

        assertTrue(body.contains("event:final_answer"),
                "重试成功后任务应正常产出最终回答。原始 SSE 文本：\n" + body);
        assertFalse(body.contains("event:error"),
                "临时失败被重试兜住，不应出现 error 事件。原始 SSE 文本：\n" + body);
        assertTrue(body.contains("17"),
                "最终回答应包含重试后拿到的真实库存数量。原始 SSE 文本：\n" + body);

        assertEquals(2, flakySearchTool.getCallCount(),
                "第 1 次失败 + 第 2 次成功，工具应恰好被调用 2 次");
        assertTrue(RetryableToolCallback.getGlobalRetryCount() >= 1,
                "应记录到至少 1 次工具重试");
    }
}
