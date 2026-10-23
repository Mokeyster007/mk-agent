package com.example.mkagent.agent;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolCallAgent 工具内部执行开关单元测试。
 *
 * 验证目标（全部离线，不调用真实模型、不联网）：
 * 1. think() 传给 ChatModel 的 options 中
 *    internalToolExecutionEnabled 必须为 false；
 * 2. 桩模型返回带 ToolCall 的响应时，response.hasToolCalls() 为 true，
 *    且此时工具“没有被执行”（计数器为 0）——
 *    说明工具执行时机由 act() 独占，不由模型层内部代劳；
 * 3. act() 仍然能通过 ToolCallingManager 正常执行一次工具（行为未回退）。
 *
 * 局限：桩模型只能证明“我们传了什么参数”，
 * 无法证明真实模型实现是否真的尊重该参数。
 * 真实链路的验证步骤见 docs/agent-learning/06-tool-execution-and-network-safety.md。
 */
class ToolCallAgentToolExecutionSwitchTest {

    /**
     * 测试用工具名：刻意不使用 terminate_task，
     * 避免 act() 进入“终止任务”分支，干扰断言。
     */
    private static final String COUNTER_TOOL_NAME = "counter_tool";

    @Test
    void thinkDisablesInternalToolExecutionAndDoesNotRunTools() {
        AtomicInteger counter = new AtomicInteger();

        CapturingChatModel chatModel =
                new CapturingChatModel(toolCallResponse(COUNTER_TOOL_NAME));

        ToolCallAgent agent = newAgent(chatModel, counter);
        AgentRunContext ctx = newContext();

        boolean shouldAct = agent.think(ctx);

        // 1. 模型请求了工具，Agent 需要继续执行 act。
        assertTrue(shouldAct, "模型返回 ToolCall 时 think 应返回 true");

        // 2. options 中必须显式关闭“模型内部自动执行工具”。
        ToolCallingChatOptions options =
                capturedToolCallingOptions(chatModel);
        assertEquals(
                Boolean.FALSE,
                options.getInternalToolExecutionEnabled(),
                "think() 必须显式设置 internalToolExecutionEnabled=false"
        );

        // 3. 工具回调依然随 options 传给模型（否则模型看不到工具定义）。
        assertNotNull(options.getToolCallbacks());
        assertEquals(1, options.getToolCallbacks().size());

        // 4. 关键断言：模型调用阶段工具没有被执行。
        assertEquals(
                0,
                counter.get(),
                "think() 阶段不允许执行任何工具，工具只能由 act() 执行"
        );

        // 5. 响应本身被识别为“含工具调用”。
        assertTrue(ctx.getPendingResponse().hasToolCalls());
    }

    @Test
    void actExecutesToolExactlyOnceThroughToolCallingManager() {
        AtomicInteger counter = new AtomicInteger();

        CapturingChatModel chatModel =
                new CapturingChatModel(toolCallResponse(COUNTER_TOOL_NAME));

        ToolCallAgent agent = newAgent(chatModel, counter);
        AgentRunContext ctx = newContext();

        // 先 think 得到带 ToolCall 的 pendingResponse，再 act 执行。
        agent.think(ctx);
        assertEquals(0, counter.get(), "think 阶段不应执行工具");

        String summary = agent.act(ctx);

        // 工具恰好执行一次：证明关闭内部执行后，
        // 手动 ToolCallingManager 路径仍然正常工作（行为未回退）。
        assertEquals(1, counter.get(), "act() 应恰好执行一次工具");
        assertNotNull(summary);
        assertFalse(summary.isBlank());

        // 工具结果被回填为 ToolResponseMessage。
        List<Message> messages = ctx.getMessages();
        assertInstanceOf(
                ToolResponseMessage.class,
                messages.get(messages.size() - 1),
                "act() 结束后最后一条消息应为 ToolResponseMessage"
        );

        // 只调用了一次模型（act 不重复请求模型）。
        assertEquals(1, chatModel.callCount());
    }

    // ===== 测试辅助 =====

    private static ToolCallAgent newAgent(
            CapturingChatModel chatModel,
            AtomicInteger counter
    ) {
        ToolCallback counterTool = new CountingToolCallback(
                COUNTER_TOOL_NAME,
                counter
        );

        return new ToolCallAgent(
                chatModel,
                new ToolCallback[]{counterTool},
                Runnable::run,
                new DefaultToolEventMessageProvider()
        );
    }

    private static AgentRunContext newContext() {
        AgentRunContext ctx = new AgentRunContext();
        ctx.getMessages().add(new UserMessage("请调用一次计数器工具"));
        return ctx;
    }

    private static ToolCallingChatOptions capturedToolCallingOptions(
            CapturingChatModel chatModel
    ) {
        Prompt prompt = chatModel.capturedPrompt();
        assertNotNull(prompt, "ChatModel 应收到 Prompt");
        assertInstanceOf(
                ToolCallingChatOptions.class,
                prompt.getOptions(),
                "Prompt 的 options 应为 ToolCallingChatOptions"
        );
        return (ToolCallingChatOptions) prompt.getOptions();
    }

    private static ChatResponse toolCallResponse(String toolName) {
        AssistantMessage assistantMessage = new AssistantMessage(
                "",
                Map.of(),
                List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        toolName,
                        "{}"
                ))
        );

        return new ChatResponse(
                List.of(new Generation(assistantMessage)),
                ChatResponseMetadata.builder().model("stub-model").build()
        );
    }

    /**
     * 捕获 Prompt 的桩 ChatModel。
     *
     * 只实现 call(Prompt)，
     * 不会触发任何工具执行 —— 这正是我们要验证的行为。
     */
    private static final class CapturingChatModel implements ChatModel {

        private final ChatResponse response;

        private final AtomicInteger callCount = new AtomicInteger();

        private volatile Prompt capturedPrompt;

        private CapturingChatModel(ChatResponse response) {
            this.response = response;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.capturedPrompt = prompt;
            this.callCount.incrementAndGet();
            return response;
        }

        private Prompt capturedPrompt() {
            return capturedPrompt;
        }

        private int callCount() {
            return callCount.get();
        }
    }

    /**
     * 计数器工具：每被真正调用一次，计数 +1。
     */
    private static final class CountingToolCallback implements ToolCallback {

        private final String name;

        private final AtomicInteger counter;

        private CountingToolCallback(String name, AtomicInteger counter) {
            this.name = name;
            this.counter = counter;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("测试用计数器工具")
                    .inputSchema("""
                            {"type":"object","properties":{}}
                            """)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            counter.incrementAndGet();
            return "计数已增加";
        }
    }
}
