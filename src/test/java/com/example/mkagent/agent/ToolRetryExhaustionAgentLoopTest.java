package com.example.mkagent.agent;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.resilience.ToolRetryWrapper;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具重试耗尽后的 Agent Loop 行为（全部桩测试，不联网、不启动 Spring）。
 *
 * 真实运行暴露的问题：web_scrape 抓 GitHub 超时，重试耗尽后**抛异常**，
 * 异常一路冒到 BaseAgent，整个任务被判成 FAILED——
 * 一次网页抓取超时不该让任务终止。
 *
 * 修复后的约定（on-exhausted=return，默认）：
 * 1. 耗尽后返回一条脱敏失败文案作为工具结果，Agent 继续运行；
 * 2. 下一轮模型能在上下文里看到这条文案，自己决定换方式或收尾；
 * 3. 失败的那次调用仍然计入 toolCallCount / maxToolCalls 预算；
 * 4. on-exhausted=throw 时保持旧行为（任务 FAILED），可回滚。
 */
class ToolRetryExhaustionAgentLoopTest {

    private static final String TOOL_NAME = "web_scrape";

    @Test
    @DisplayName("重试耗尽后任务不 FAILED，下一轮模型能看到失败文案并继续")
    void exhaustedRetryKeepsTaskAliveAndModelSeesFailureText() {
        StubScrapeTool scrapeTool = new StubScrapeTool();
        ScriptedChatModel model = new ScriptedChatModel(false);

        ToolCallAgent agent = newAgent(model, wrap(scrapeTool, "return"));
        AgentRunContext ctx = new AgentRunContext();

        agent.run("抓取 https://github.com 的正文", ctx);

        // 1. 关键断言：任务正常收尾，没有因为工具重试耗尽而 FAILED。
        assertEquals(
                AgentState.SUCCEEDED,
                ctx.getState(),
                "工具重试耗尽不得让整个任务失败，实际终态：" + ctx.getState()
        );

        // 2. 工具确实被尝试了 maxAttempts 次（2 次），没有无限重试。
        assertEquals(2, scrapeTool.callCount.get(),
                "maxAttempts=2 后必须停止");

        // 3. 失败调用仍计入预算：1 次工具调用。
        assertEquals(1, ctx.getToolCallCount(),
                "失败的工具调用同样占用 toolCallCount 预算");

        // 4. 下一轮模型看到了失败文案（这是"让模型自行决策"的前提）。
        assertNotNull(model.secondRoundToolResult,
                "第二轮模型应已收到 ToolResponseMessage");
        assertTrue(
                model.secondRoundToolResult.contains("多次尝试仍失败"),
                "模型必须看到重试耗尽文案，实际：" + model.secondRoundToolResult
        );
        assertTrue(
                model.secondRoundToolResult.contains("Read timed out"),
                "失败文案应带简短原因，实际：" + model.secondRoundToolResult
        );
        assertTrue(
                model.secondRoundToolResult.contains("terminate_task"),
                "失败文案应给出下一步选项，实际：" + model.secondRoundToolResult
        );

        // 5. 模型据此给出了最终回答。
        assertTrue(
                ctx.getFinalAnswer() != null && !ctx.getFinalAnswer().isBlank(),
                "模型应基于失败文案给出最终回答"
        );
    }

    @Test
    @DisplayName("工具预算耗尽时安全停止，不抛异常、不 FAILED")
    void toolCallBudgetExhaustionStopsSafely() {
        StubScrapeTool scrapeTool = new StubScrapeTool();
        // 模型每轮都要求调用工具，永远不给最终回答。
        ScriptedChatModel model = new ScriptedChatModel(true);

        ToolCallAgent agent = newAgent(model, wrap(scrapeTool, "return"));
        // 预算只够一次工具调用。
        agent.setMaxToolCalls(1);

        AgentRunContext ctx = new AgentRunContext();

        agent.run("反复抓取同一个页面", ctx);

        // 安全停止：不是 FAILED，而是"达到上限"类终态。
        assertEquals(
                AgentState.MAX_STEPS_REACHED,
                ctx.getState(),
                "预算耗尽应安全停止，实际终态：" + ctx.getState()
        );
        assertEquals(1, ctx.getToolCallCount(),
                "工具调用次数不得超过预算");
        assertEquals(2, scrapeTool.callCount.get(),
                "预算耗尽前这一次工具调用仍完整执行了 2 次尝试");
    }

    @Test
    @DisplayName("on-exhausted=throw 保持旧行为：任务直接 FAILED")
    void throwModePreservesLegacyFailureBehaviour() {
        StubScrapeTool scrapeTool = new StubScrapeTool();
        ScriptedChatModel model = new ScriptedChatModel(false);

        ToolCallAgent agent = newAgent(model, wrap(scrapeTool, "throw"));
        AgentRunContext ctx = new AgentRunContext();

        agent.run("抓取 https://github.com 的正文", ctx);

        assertEquals(
                AgentState.FAILED,
                ctx.getState(),
                "throw 模式必须保持旧行为（任务 FAILED）"
        );
        assertEquals(2, scrapeTool.callCount.get(),
                "抛异常前仍应完成 maxAttempts 次尝试");
    }

    // ===== 测试辅助 =====

    private ToolCallAgent newAgent(
            ChatModel chatModel,
            ToolCallback[] tools
    ) {
        ToolCallAgent agent = new ToolCallAgent(
                chatModel,
                tools,
                Runnable::run,
                new DefaultToolEventMessageProvider()
        );
        agent.setName("test-agent");
        agent.setSystemPrompt("你是一个测试用的智能体。");
        agent.setMaxSteps(5);
        agent.setMaxToolCalls(5);
        agent.setTimeout(Duration.ofSeconds(30));
        return agent;
    }

    /**
     * 用生产同款的重试配置包装工具（maxAttempts=2，退避 1ms 让测试快）。
     */
    private ToolCallback[] wrap(StubScrapeTool tool, String onExhausted) {
        ToolRetryWrapper wrapper = new ToolRetryWrapper(
                true,
                2,
                1,
                1.0,
                TOOL_NAME,
                "web_scrape:网页抓取失败",
                onExhausted
        );
        return wrapper.wrap(ToolCallbacks.from(tool));
    }

    /**
     * 模拟生产 WebScrapingTool：内部吞掉异常，返回可重试失败文案。
     *
     * 刻意用 @Tool 注解 → 经 MethodToolCallback 序列化成带引号的
     * JSON 字符串字面量，顺带验证"解码后再识别失败"这条链路。
     */
    public static final class StubScrapeTool {

        private final AtomicInteger callCount = new AtomicInteger();

        @Tool(
                name = TOOL_NAME,
                description = "测试用网页抓取工具（始终失败）"
        )
        public String scrape(
                @ToolParam(description = "网页地址") String url
        ) {
            callCount.incrementAndGet();
            return "网页抓取失败：Read timed out";
        }
    }

    /**
     * 脚本化 ChatModel：
     * 第一轮要求调用 web_scrape；
     * 后续轮次记录模型实际看到的工具结果，然后给出最终回答
     * （alwaysToolCall=true 时持续要求调用工具，用于预算耗尽场景）。
     */
    private static final class ScriptedChatModel implements ChatModel {

        private final boolean alwaysToolCall;

        private volatile String secondRoundToolResult;

        private ScriptedChatModel(boolean alwaysToolCall) {
            this.alwaysToolCall = alwaysToolCall;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();

            boolean hasToolResult = messages.stream()
                    .anyMatch(message -> message instanceof ToolResponseMessage);

            if (!hasToolResult) {
                return toolCallResponse();
            }

            secondRoundToolResult = messages.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .collect(Collectors.joining("\n"));

            if (alwaysToolCall) {
                return toolCallResponse();
            }

            return textResponse("该页面无法抓取，改用已知信息作答。");
        }

        private ChatResponse toolCallResponse() {
            AssistantMessage assistantMessage = new AssistantMessage(
                    "",
                    Map.of(),
                    List.of(new AssistantMessage.ToolCall(
                            "call_1",
                            "function",
                            TOOL_NAME,
                            "{\"url\":\"https://github.com\"}"
                    ))
            );
            return new ChatResponse(
                    List.of(new Generation(assistantMessage)),
                    ChatResponseMetadata.builder().model("stub-model").build()
            );
        }

        private ChatResponse textResponse(String text) {
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(text))),
                    ChatResponseMetadata.builder().model("stub-model").build()
            );
        }
    }
}
