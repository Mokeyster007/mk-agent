package com.example.mkagent.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.tools.DemoInventoryTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 缓冲修复的真实验证（真实容器 + 真实异步超时，不联网）。
 *
 * 要证明的事：
 * **Agent 超时后，TIMED_OUT 状态事件与 DONE 仍然能送到客户端。**
 *
 * 场景设计（必须让 Agent 在 timeout 之后仍在跑，才能触发 TIMED_OUT）：
 * 1. agent timeout = 500ms，sse-buffer = 5s → emitter 超时 5.5s；
 * 2. 桩模型每次调用都睡 1.2s 并请求工具 → 状态一直是 RUNNING；
 * 3. 第一步结束后已耗时 > 500ms → 循环退出 → TIMED_OUT。
 *
 * 修复前（emitter 超时 = 500ms）：容器异步超时先到，
 * 连接在 500ms 就被掐断，"任务运行超时，已强制停止。"这条事件发不出去，
 * 本测试的断言会失败。修复后：连接活到 5.5s，事件正常送达。
 */
@HermeticSpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Agent 500ms 就超时；缓冲 5s 让连接活到 5.5s。
                "mkagent.agent.manus.timeout=500ms",
                "mkagent.agent.manus.sse-buffer=5s"
        }
)
@Timeout(40)
class SseTimeoutEventDeliveryTest {

    /**
     * 测试专用模型与工具：替换真实 DashScope 模型，只暴露一个本地工具。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SlowToolCallingModelConfig {

        /** 每次调用都请求同一个工具，让 Agent 永远拿不到"最终回答"。 */
        private final SlowToolCallingModel behavior = new SlowToolCallingModel();

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
        DemoInventoryTool demoInventoryTool() {
            return new DemoInventoryTool();
        }

        @Bean("mkToolCallbacks")
        @Primary
        ToolCallback[] mkToolCallbacks(DemoInventoryTool demoInventoryTool) {
            return ToolCallbacks.from(demoInventoryTool);
        }

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }

        @Bean
        SlowToolCallingModel slowToolCallingModel() {
            return behavior;
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private SlowToolCallingModel slowModel;

    @Autowired
    private MkManus mkManus;

    @MockitoBean
    private VectorStore chatAppVectorStore;

    @Test
    @DisplayName("Agent 超时后，TIMED_OUT 状态与 DONE 仍能送达（emitter 超时 = timeout + buffer）")
    void timedOutEventStillReachesClient() throws Exception {
        // 先确认配置真的灌进了 MkManus，而不是断言了一个没生效的值。
        assertEquals(500L, mkManus.getTimeout().toMillis(),
                "mkagent.agent.manus.timeout 应被注入");
        assertEquals(5_000L, mkManus.getSseBuffer().toMillis(),
                "mkagent.agent.manus.sse-buffer 应被注入");

        String body = requestAgentSse();

        // 1. Agent 确实走了超时分支（而不是模型正常收尾）。
        assertTrue(body.contains("event:status"),
                "缺少 status 事件。原始 SSE 文本：\n" + body);
        assertTrue(body.contains("任务运行超时，已强制停止。"),
                "超时状态事件必须在连接关闭前送达；"
                        + "缺失说明 emitter 超时早于 Agent 收尾。原始 SSE 文本：\n" + body);

        // 2. 连接正常收尾，而不是被容器掐断。
        assertTrue(body.contains("event:done"),
                "缺少 done 事件，说明连接被提前关闭。原始 SSE 文本：\n" + body);
        assertTrue(body.contains("[DONE]"),
                "done 事件应携带 [DONE]。原始 SSE 文本尾部：\n"
                        + tail(body));

        // 3. 模型确实被调用过（证明超时发生在"正在跑"而不是"没开始"）。
        assertTrue(slowModel.getCallCount() >= 1,
                "模型应至少被调用一次，实际：" + slowModel.getCallCount());

        // 4. 超时任务不应把最终回答推给用户（没有可信答案）。
        assertFalse(body.contains("event:final_answer"),
                "TIMED_OUT 不应推送 final_answer。原始 SSE 文本：\n" + body);
    }

    private String requestAgentSse() throws Exception {
        String url = "http://localhost:" + port + "/api/ai/manus/chat?message="
                + URLEncoder.encode("请反复查询库存直到我喊停", StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("X-User-Id", "user-sse-timeout-test")
                .GET()
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "HTTP 状态码应为 200");
        return response.body();
    }

    private String tail(String body) {
        return body.substring(Math.max(0, body.length() - 300));
    }

    /**
     * 慢速"只会调工具"的桩模型：
     * 每次调用先睡 1.2 秒（远超 500ms 的 agent 超时），再请求工具。
     *
     * 这样 Agent 永远拿不到最终回答，第一步结束后就因超时退出循环，
     * 正好覆盖"超时收尾"这条路径。
     */
    static final class SlowToolCallingModel implements ChatModel {

        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            int round = callCount.incrementAndGet();

            try {
                Thread.sleep(1_200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(new InterruptedException(
                        "桩模型被中断（超时/取消）"));
            }

            AssistantMessage assistantMessage = new AssistantMessage(
                    "",
                    Map.of(),
                    List.of(new AssistantMessage.ToolCall(
                            "call_" + round,
                            "function",
                            "demo_inventory_check",
                            "{\"sku\":\"MK-2026-001\"}"
                    ))
            );

            return new ChatResponse(
                    List.of(new Generation(assistantMessage)),
                    ChatResponseMetadata.builder().model("slow-stub-model").build()
            );
        }

        int getCallCount() {
            return callCount.get();
        }
    }
}
