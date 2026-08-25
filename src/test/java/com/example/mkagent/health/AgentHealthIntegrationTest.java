package com.example.mkagent.health;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Actuator 健康检查集成测试（真实 HTTP 端点）。
 *
 * 与其他隔离集成测试相同的策略：排除 DashScope / MCP 自动配置、
 * H2 内存库、mock ChatModel，全程不调用真实大模型。
 *
 * 覆盖用户要求的验证点：
 * 1. health 端点正确反映依赖状态：
 *    - 整体 UP；
 *    - db 组件（H2 数据源）UP；
 *    - 自定义 aiModel 组件 UP（只检查配置，绝不发起真实模型请求）；
 * 2. API Key 在健康响应中脱敏（前 6 位 + ***）；
 * 3. 只暴露 health，env / heapdump 等敏感端点不可访问（404）。
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
                "spring.datasource.url=jdbc:h2:mem:mk_agent_health;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/schema.sql",
                "mkagent.rag.pgvector.enabled=false"
        }
)
@ActiveProfiles("local")
@Timeout(60)
class AgentHealthIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TestConfiguration(proxyBeanMethods = false)
    static class HealthTestConfig {

        /**
         * ChatModel 类型壳（不做任何行为桩）：
         * 健康检查只验证 Bean 是否装配成功，绝不发起模型调用。
         */
        @Bean("dashscopeChatModel")
        @Primary
        ChatModel dashscopeChatModel() {
            return Mockito.mock(DashScopeChatModel.class);
        }

        /** RAG 文档加载替换为 mock，避免启动时富集调用真实模型。 */
        @Bean("chatAppDocumentLoader")
        ChatAppDocumentLoader chatAppDocumentLoader() {
            ChatAppDocumentLoader mock =
                    Mockito.mock(ChatAppDocumentLoader.class);
            Mockito.when(mock.loadMarkdowns()).thenReturn(List.of());
            return mock;
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
     * health 端点正确反映依赖状态：
     * 整体 UP，且 db / aiModel 组件各自 UP。
     */
    @Test
    void healthEndpointReflectsDependencyStatus() throws Exception {
        HttpResponse<String> response =
                get("/api/actuator/health");

        assertEquals(200, response.statusCode(),
                "health 端点应返回 200，响应体：" + response.body());

        JsonNode root = objectMapper.readTree(response.body());

        assertEquals("UP", root.path("status").asText(),
                "所有依赖健康时整体状态应为 UP。响应体：" + response.body());
        assertEquals("UP",
                root.path("components").path("db").path("status").asText(),
                "数据库组件应 UP。响应体：" + response.body());
        assertEquals("UP",
                root.path("components").path("aiModel").path("status").asText(),
                "模型配置轻量级检查应 UP（不发起真实模型请求）。响应体："
                        + response.body());

        // API Key 脱敏：只回显前 6 位 + ***。
        String maskedKey = root.path("components").path("aiModel")
                .path("details").path("apiKey").asText();
        assertTrue(maskedKey.endsWith("***"),
                "健康端点中的 API Key 必须脱敏，实际：" + maskedKey);
    }

    /**
     * 只暴露 health 端点：
     * env / heapdump 等敏感端点未暴露，访问返回 404。
     */
    @Test
    void sensitiveActuatorEndpointsAreNotExposed() throws Exception {
        assertEquals(404, get("/api/actuator/env").statusCode(),
                "env 端点不应暴露（包含配置与密钥信息）");
        assertEquals(404, get("/api/actuator/heapdump").statusCode(),
                "heapdump 端点不应暴露（可导出内存中的敏感数据）");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }
}
