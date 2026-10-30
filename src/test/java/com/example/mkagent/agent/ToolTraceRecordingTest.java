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
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具轨迹记录（ctx.toolTrace）的行为，纯桩测试、不联网、不连数据库。
 *
 * 约定：
 * 1. 按**实际执行顺序**记录工具名；
 * 2. **失败的工具调用也要记**——模型看到了失败结果，这同样是轨迹的一部分；
 * 3. 只记工具名，不记参数与结果原文（参数里可能有搜索词、路径、密钥）。
 *
 * 落库与幂等由 AgentRunPersistenceIntegrationTest / SchemaScriptIdempotencyTest 覆盖。
 */
class ToolTraceRecordingTest {

    private static final String OK_TOOL = "ok_tool";

    private static final String FAIL_TOOL = "fail_tool";

    @Test
    @DisplayName("按执行顺序记录工具名，且不包含参数原文")
    void recordsToolNamesInExecutionOrder() {
        ScriptedModel model = new ScriptedModel(
                List.of(OK_TOOL, OK_TOOL, FAIL_TOOL)
        );

        ToolCallAgent agent = newAgent(model, wrap(new RecordingTools()));
        AgentRunContext ctx = new AgentRunContext();

        agent.run("依次调用三个工具", ctx);

        assertEquals(
                List.of(OK_TOOL, OK_TOOL, FAIL_TOOL),
                ctx.getToolTrace(),
                "轨迹必须严格按执行顺序"
        );
        assertEquals(3, ctx.getToolCallCount(), "轨迹长度应与工具调用次数一致");

        String joined = String.join(",", ctx.getToolTrace());
        assertTrue(joined.equals("ok_tool,ok_tool,fail_tool"),
                "拼接后应与落库格式一致，实际：" + joined);
        assertTrue(!joined.contains("secret"),
                "轨迹中绝不能出现参数原文：" + joined);
    }

    @Test
    @DisplayName("失败的工具调用同样记入轨迹")
    void failedToolCallIsStillRecorded() {
        ScriptedModel model = new ScriptedModel(List.of(FAIL_TOOL));

        // 生产同款重试包装：失败两次后耗尽，返回失败文案（任务不失败）。
        ToolRetryWrapper wrapper = new ToolRetryWrapper(
                true,
                2,
                1,
                1.0,
                FAIL_TOOL,
                "fail_tool:工具执行失败",
                "return"
        );
        ToolCallback[] tools = wrapper.wrap(
                ToolCallbacks.from(new RecordingTools())
        );

        ToolCallAgent agent = newAgent(model, tools);
        agent.setMaxToolCalls(1);

        AgentRunContext ctx = new AgentRunContext();
        agent.run("调用一个总是失败的工具", ctx);

        assertEquals(List.of(FAIL_TOOL), ctx.getToolTrace(),
                "失败的工具调用也要出现在轨迹里");
        assertTrue(
                ctx.getToolTrace().size() == 1,
                "一次工具调用只记一条，不按重试次数重复记"
        );
    }

    // ===== 测试辅助 =====

    private ToolCallAgent newAgent(ChatModel chatModel, ToolCallback[] tools) {
        ToolCallAgent agent = new ToolCallAgent(
                chatModel,
                tools,
                Runnable::run,
                new DefaultToolEventMessageProvider()
        );
        agent.setName("trace-test-agent");
        agent.setSystemPrompt("你是一个测试用的智能体。");
        agent.setMaxSteps(10);
        agent.setMaxToolCalls(10);
        agent.setTimeout(Duration.ofSeconds(30));
        // 轨迹测试不关心收尾调用，关掉以减少干扰。
        agent.setForceFinalAnswer(false);
        return agent;
    }

    private ToolCallback[] wrap(RecordingTools tools) {
        return ToolCallbacks.from(tools);
    }

    /**
     * 两个工具：一个成功、一个始终失败（用于验证失败也记账）。
     */
    public static final class RecordingTools {

        private final AtomicInteger calls = new AtomicInteger();

        @Tool(name = OK_TOOL, description = "测试用成功工具")
        public String ok(
                @ToolParam(description = "查询词") String query
        ) {
            calls.incrementAndGet();
            return "成功结果";
        }

        @Tool(name = FAIL_TOOL, description = "测试用失败工具")
        public String fail(
                @ToolParam(description = "网页地址") String url
        ) {
            calls.incrementAndGet();
            return "工具执行失败：模拟故障";
        }
    }

    /**
     * 按顺序请求工具的桩模型；工具请求耗尽后给出最终回答。
     */
    private static final class ScriptedModel implements ChatModel {

        private final List<String> toolSequence;

        private final AtomicInteger index = new AtomicInteger();

        private ScriptedModel(List<String> toolSequence) {
            this.toolSequence = new ArrayList<>(toolSequence);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (!hasTools(prompt) || index.get() >= toolSequence.size()) {
                return textResponse("已完成。");
            }

            int current = index.getAndIncrement();
            return toolCallResponse(toolSequence.get(current), current);
        }

        private boolean hasTools(Prompt prompt) {
            if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
                return false;
            }
            List<ToolCallback> callbacks = options.getToolCallbacks();
            return callbacks != null && !callbacks.isEmpty();
        }

        private ChatResponse toolCallResponse(String toolName, int index) {
            String arguments = OK_TOOL.equals(toolName)
                    ? "{\"query\":\"secret-token\"}"
                    : "{\"url\":\"https://example.com\"}";

            AssistantMessage assistantMessage = new AssistantMessage(
                    "",
                    Map.of(),
                    List.of(new AssistantMessage.ToolCall(
                            "call_" + index,
                            "function",
                            toolName,
                            arguments
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

    /**
     * 防御性断言用：确认没有把 ToolResponseMessage 的内容写进轨迹。
     */
    @SuppressWarnings("unused")
    private static boolean hasToolResponseMessage(List<Message> messages) {
        return messages.stream().anyMatch(ToolResponseMessage.class::isInstance);
    }
}
