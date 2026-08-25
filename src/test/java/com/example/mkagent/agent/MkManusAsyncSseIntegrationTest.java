package com.example.mkagent.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.model.AgentEvent;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import com.example.mkagent.support.FakeChatModel;
import com.example.mkagent.tools.DemoInventoryTool;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步 SSE Agent 工具调用链集成测试（统一 SSE 事件协议版）。
 *
 * 目标：通过真实 HTTP 接口 + 真实 Agent 执行器（agentExecutor 后台线程），
 * 验证 runStream() 异步场景下完整链路：
 *
 * 创建独立 AgentRunContext
 *   → agentExecutor 后台线程执行
 *   → 模型决定调用 demo_inventory_check 工具
 *   → 工具真实执行（不依赖真实模型，由 FakeChatModel 驱动）
 *   → 工具结果写回上下文
 *   → 下一轮模型调用（FakeChatModel 读取 ToolResponseMessage 后回答）
 *   → 得到最终答案
 *   → 通过 SSE 输出 status / tool_start / tool_result / step / final_answer / done
 *
 * 统一事件协议要点：
 * 1. 所有 SSE data 都是 AgentEvent 的 JSON 序列化结果
 *    （runId / type / message / step / timestamp），
 *    前端不再解析 "Step 1: xxx" 拼接字符串；
 * 2. tool_start / tool_result 是协议内正式事件（始终发送），
 *    消息由 ToolEventMessageProvider 脱敏，不携带完整工具原始结果。
 *
 * 说明：
 * 1. 排除 DashScope Chat 自动配置，避免创建真实模型 Bean；
 * 2. 用 @Bean("dashscopeChatModel") 注册 FakeChatModel，替换真实模型；
 * 3. 测试专用 DemoInventoryTool 位于 src/test 目录，
 *    由 @TestConfiguration 注册并覆盖 mkToolCallbacks，仅暴露一个测试工具；
 * 4. 不使用 Thread.sleep，等待由 HttpClient 超时 + JUnit @Timeout 双保险保证。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration,"
                        // 排除 MCP 客户端自动配置：启动时会真实连接外部 MCP 服务，
                        // 测试只用 mkToolCallbacks 中的工具，ToolCallbackProvider 由测试桩提供。
                        + "org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.McpToolCallbackAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.StdioTransportAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.SseHttpClientTransportAutoConfiguration",
                // 允许测试配置覆盖用户配置类（ToolRegistryConfig）的同名 Bean：
                // 仅对测试上下文生效，不影响生产。
                "spring.main.allow-bean-definition-overriding=true",
                // AgentRun 持久化使用 H2 内存库（PostgreSQL 兼容模式），
                // 测试不依赖外部数据库，也不触碰生产 RDS。
                "spring.datasource.url=jdbc:h2:mem:mk_agent_sse;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
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
@Timeout(30)
class MkManusAsyncSseIntegrationTest {

    /**
     * 测试专用可控模型配置：替换真实 DashScope ChatModel。
     *
     * 说明：
     * 1. MyKeywordEnricher 以具体类型 DashScopeChatModel 注入，
     *    因此这里用 Mockito mock（类型为 DashScopeChatModel 子类）
     *    作为类型壳，并把 call() 行为委托给 FakeChatModel（两轮可控逻辑）。
     * 2. ChatAppDocumentLoader 返回空文档列表，使上下文启动时
     *    KeywordMetadataEnricher 对空列表短路，不会触发真实模型调用。
     * 3. 同名 @Bean 覆盖组件扫描注册的 Bean（Bean 定义覆盖默认开启），
     *    只影响测试上下文，不影响生产。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModelConfig {

        /** 两轮可控行为提供者：第一轮请求工具，第二轮基于工具结果回答。 */
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

        /** RAG 文档加载替换为 mock，避免启动时关键词富集调用真实模型。 */
        @Bean("chatAppDocumentLoader")
        ChatAppDocumentLoader chatAppDocumentLoader() {
            ChatAppDocumentLoader mock =
                    Mockito.mock(ChatAppDocumentLoader.class);
            Mockito.when(mock.loadMarkdowns()).thenReturn(List.of());
            return mock;
        }

        /**
         * 测试专用库存工具（位于 src/test 目录，不参与生产注册）。
         * 由测试配置显式注册，仅存活于测试上下文。
         */
        @Bean
        DemoInventoryTool demoInventoryTool() {
            return new DemoInventoryTool();
        }

        /**
         * 覆盖生产的 mkToolCallbacks（ToolRegistryConfig）：
         * 测试链路只暴露 demo_inventory_check 一个工具，
         * 确保 FakeChatModel 两轮行为完全可控，不会误触其他真实工具。
         */
        @Bean("mkToolCallbacks")
        @Primary
        ToolCallback[] mkToolCallbacks(DemoInventoryTool demoInventoryTool) {
            return ToolCallbacks.from(demoInventoryTool);
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
        FakeChatModel fakeChatModel() {
            return behavior;
        }
    }

    /** 测试要求的固定提示词。 */
    private static final String PROMPT = """
            请查询 SKU MK-2026-001 的库存。
            必须根据库存工具返回的结果回答；
            不要凭空猜测库存数量。
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private FakeChatModel fakeChatModel;

    @Autowired
    private DemoInventoryTool demoInventoryTool;

    /**
     * 替换 RAG 内存向量库 Bean：
     * SimpleVectorStore.add 拒绝空文档列表，且真实 add 会调用 Embedding API；
     * @MockitoBean 通过 bean 定义替换机制覆盖已存在 Bean，测试完全不触网。
     */
    @MockitoBean
    private VectorStore chatAppVectorStore;

    @Test
    void asyncSseAgentToolCallChain() throws Exception {
        // ===== 1. 通过真实 HTTP 接口发起 SSE 请求 =====
        String body = requestAgentSse();

        // ===== 2. SSE 事件协议校验：事件类型齐全 =====
        assertTrue(body.contains("event:status"),
                "缺少 status 事件。原始 SSE 文本：\n" + body);
        assertTrue(body.contains("event:tool_start"),
                "缺少 tool_start 事件。原始 SSE 文本：\n" + body);
        assertTrue(body.contains("event:tool_result"),
                "缺少 tool_result 事件。原始 SSE 文本：\n" + body);
        assertTrue(body.contains("event:step"),
                "缺少 step 事件。原始 SSE 文本：\n" + body);
        assertTrue(body.contains("event:final_answer"),
                "缺少 final_answer 事件。原始 SSE 文本：\n" + body);
        assertTrue(body.contains("event:done"),
                "缺少 done 事件。原始 SSE 文本：\n" + body);
        assertFalse(body.contains("event:error"),
                "不应出现 error 事件。原始 SSE 文本：\n" + body);

        // ===== 3. 工具事件内容校验（脱敏、用户可读） =====
        assertTrue(body.contains("正在查询库存信息"),
                "tool_start 消息应为用户可读文案。原始 SSE 文本：\n" + body);
        assertTrue(body.contains("库存查询完成"),
                "tool_result 消息应为用户可读摘要。原始 SSE 文本：\n" + body);

        // 完整工具原始结果不允许出现在 SSE 输出中（只允许脱敏摘要）。
        assertFalse(body.contains("-> 库存数量：17"),
                "SSE 不应携带完整工具原始结果。原始 SSE 文本：\n" + body);

        // ===== 4. 最终答案内容校验 =====
        assertTrue(body.contains("MK-2026-001"),
                "最终答案应包含 SKU MK-2026-001。原始 SSE 文本：\n" + body);
        assertTrue(body.contains("17"),
                "最终答案应包含库存数量 17。原始 SSE 文本：\n" + body);
        assertTrue(body.contains("可发货"),
                "最终答案应包含状态 可发货。原始 SSE 文本：\n" + body);

        // ===== 5. 证明工具被真实调用（而非模型凭空编造 17） =====
        assertThat(demoInventoryTool.getCallCount())
                .as("库存工具应被真实执行至少 1 次，而非模型凭空编造 17")
                .isGreaterThanOrEqualTo(1);

        // ===== 6. FakeChatModel 行为校验 =====
        assertEquals(
                2,
                fakeChatModel.getCallCount(),
                "模型应恰好被调用 2 轮（工具调用轮 + 最终回答轮）"
        );
        assertTrue(
                fakeChatModel.isSawToolResult(),
                "模型第二轮应读取到 ToolResponseMessage，证明工具结果已写回上下文"
        );

        // ===== 7. 后台线程执行校验（线程名以 agent- 开头） =====
        assertFalse(
                demoInventoryTool.getThreadNames().isEmpty(),
                "工具执行线程名应被记录"
        );
        demoInventoryTool.getThreadNames().forEach(threadName ->
                assertTrue(
                        threadName.startsWith("agent-"),
                        "工具应在 agentExecutor 后台线程执行，实际线程：" + threadName
                )
        );
        fakeChatModel.getThreadNames().forEach(threadName ->
                assertTrue(
                        threadName.startsWith("agent-"),
                        "模型调用应在 agentExecutor 后台线程执行，实际线程：" + threadName
                )
        );

        // ===== 8. 无资源泄漏 =====
        // done 事件由后台任务线程自身发送，随后 emitter.complete() 才关闭连接；
        // 收到 done 即证明后台任务已结束，不存在遗留后台任务。
        assertTrue(
                body.contains("[DONE]"),
                "done 事件应携带 [DONE] 标记。原始 SSE 文本尾部：\n"
                        + body.substring(Math.max(0, body.length() - 300))
        );
        assertTrue(
                Mockito.mockingDetails(chatModel).isMock(),
                "测试上下文中的 ChatModel 应被替换为 mock，避免调用真实模型 API"
        );
    }

    /**
     * SSE 事件顺序校验：
     * status → (step 或 tool_start) → tool_result → final_answer 或 error → done
     */
    @Test
    void sseEventOrderFollowsProtocol() throws Exception {
        String body = requestAgentSse();
        List<Map.Entry<String, String>> events = parseEvents(body);

        assertThat(events).isNotEmpty();

        // 首个事件必须是 status。
        assertEquals("status", events.get(0).getKey(),
                "首个事件应为 status。事件序列：" + eventNames(events));

        // 末尾事件必须是 done。
        assertEquals("done", events.get(events.size() - 1).getKey(),
                "末尾事件应为 done。事件序列：" + eventNames(events));

        int toolStartIdx = firstIndexOf(events, "tool_start");
        int toolResultIdx = firstIndexOf(events, "tool_result");
        int finalAnswerIdx = firstIndexOf(events, "final_answer");

        assertTrue(toolStartIdx >= 1,
                "tool_start 应出现在 status 之后。事件序列：" + eventNames(events));
        assertTrue(toolStartIdx < toolResultIdx,
                "tool_start 应先于 tool_result。事件序列：" + eventNames(events));
        assertTrue(toolResultIdx < finalAnswerIdx,
                "tool_result 应先于 final_answer。事件序列：" + eventNames(events));
        assertTrue(finalAnswerIdx < events.size() - 1,
                "final_answer 应先于 done。事件序列：" + eventNames(events));
    }

    /**
     * 统一 DTO 校验：所有 SSE data 都能反序列化为 AgentEvent，
     * 且携带 runId / type / timestamp；不再是拼接字符串。
     */
    @Test
    void allSseDataDeserializeToAgentEvent() throws Exception {
        String body = requestAgentSse();
        List<Map.Entry<String, String>> events = parseEvents(body);

        assertThat(events).isNotEmpty();

        // 旧的 "Step 1: xxx" 拼接格式不应再出现。
        assertFalse(body.contains("Step 1:"),
                "step 事件不应再使用拼接字符串。原始 SSE 文本：\n" + body);

        String expectedRunId = null;

        for (Map.Entry<String, String> event : events) {
            String data = event.getValue();
            assertTrue(data.startsWith("{"),
                    "data 应为 JSON 对象，event=" + event.getKey() + "，data=" + data);

            AgentEvent agentEvent = objectMapper.readValue(data, AgentEvent.class);

            assertEquals(event.getKey(), agentEvent.getType(),
                    "data.type 应与 SSE event name 一致");
            assertThat(agentEvent.getRunId()).isNotBlank();
            assertThat(agentEvent.getTimestamp()).isGreaterThan(0);

            if (expectedRunId == null) {
                expectedRunId = agentEvent.getRunId();
            }
            assertEquals(expectedRunId, agentEvent.getRunId(),
                    "同一次任务的所有事件应携带相同 runId");
        }

        // step 事件应携带结构化步数字段，而不是拼在文本里。
        events.stream()
                .filter(event -> "step".equals(event.getKey()))
                .forEach(event -> {
                    try {
                        AgentEvent stepEvent = objectMapper.readValue(
                                event.getValue(), AgentEvent.class);
                        assertThat(stepEvent.getStep())
                                .as("step 事件应携带步数字段")
                                .isNotNull()
                                .isGreaterThanOrEqualTo(1);
                    } catch (Exception e) {
                        throw new AssertionError("step 事件反序列化失败", e);
                    }
                });
    }

    /**
     * 通过真实 HTTP 接口发起一次 SSE Agent 请求，返回完整响应体。
     */
    private String requestAgentSse() throws Exception {
        String url = "http://localhost:" + port + "/api/ai/manus/chat?message="
                + URLEncoder.encode(PROMPT, StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                // Agent 任务归属需要用户身份（请求头占位方案）。
                .header("X-User-Id", "user-sse-test")
                .GET()
                .build();

        // send 会阻塞到 SSE 连接结束（done 事件 + emitter.complete() 之后关闭）
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "HTTP 状态码应为 200");

        String contentType = response.headers()
                .firstValue("Content-Type").orElse("");
        assertTrue(
                contentType.contains("text/event-stream"),
                "Content-Type 应包含 text/event-stream，实际：" + contentType
        );

        return response.body();
    }

    /**
     * 解析 SSE 原始文本为（event name, data 文本）有序列表。
     */
    private List<Map.Entry<String, String>> parseEvents(String body) {
        List<Map.Entry<String, String>> events = new ArrayList<>();
        String currentEvent = null;
        StringBuilder data = new StringBuilder();

        for (String line : body.split("\n")) {
            if (line.startsWith("event:")) {
                currentEvent = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).trim());
            } else if (line.isBlank() && currentEvent != null) {
                events.add(Map.entry(currentEvent, data.toString()));
                currentEvent = null;
                data.setLength(0);
            }
        }

        if (currentEvent != null) {
            events.add(Map.entry(currentEvent, data.toString()));
        }

        return events;
    }

    private int firstIndexOf(List<Map.Entry<String, String>> events, String name) {
        for (int i = 0; i < events.size(); i++) {
            if (name.equals(events.get(i).getKey())) {
                return i;
            }
        }
        return -1;
    }

    private List<String> eventNames(List<Map.Entry<String, String>> events) {
        return events.stream().map(Map.Entry::getKey).toList();
    }
}
