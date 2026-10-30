package com.example.mkagent.agent;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 模型名兜底（全部桩测试，不调用真实模型）。
 *
 * 真实运行（model=qwen-plus）暴露的问题：
 * 日志里 `model=` 与落库的 agent_run.model 一直是空的。
 * 原因是 ChatResponseMetadata#getModel() 在部分场景返回 null/空串，
 * recordUsage 直接透传。
 *
 * 修复后的三级兜底：
 * 1. 响应 metadata 的 model（最准确）；
 * 2. ChatModel Bean 上配置的模型名（getDefaultOptions().getModel()）；
 * 3. "unknown"。
 */
class ToolCallAgentModelNameFallbackTest {

    private static final String CONFIGURED_MODEL = "qwen-plus";

    @Test
    @DisplayName("metadata 的 model 为空时回退到配置值")
    void fallsBackToConfiguredModelWhenMetadataIsEmpty() {
        ModelWithDefaultOptions model =
                new ModelWithDefaultOptions(CONFIGURED_MODEL);

        ToolCallAgent agent = newAgent(model);

        assertEquals(
                CONFIGURED_MODEL,
                agent.resolveModelName(null),
                "metadata 为 null 时应回退到配置值"
        );
        assertEquals(
                CONFIGURED_MODEL,
                agent.resolveModelName(""),
                "metadata 为空串时应回退到配置值"
        );
        assertEquals(
                CONFIGURED_MODEL,
                agent.resolveModelName("   "),
                "metadata 为空白串时应回退到配置值"
        );
    }

    @Test
    @DisplayName("metadata 的 model 非空时优先使用 metadata")
    void prefersMetadataModelWhenPresent() {
        ModelWithDefaultOptions model =
                new ModelWithDefaultOptions(CONFIGURED_MODEL);

        ToolCallAgent agent = newAgent(model);

        // 真实服务端可能返回带日期后缀的精确模型名，必须优先采用。
        assertEquals(
                "qwen-plus-2025-04-28",
                agent.resolveModelName("qwen-plus-2025-04-28")
        );
    }

    @Test
    @DisplayName("连配置都读不到时退到 unknown，绝不留空")
    void fallsBackToUnknownWhenNothingResolvable() {
        // 没有 getDefaultOptions() 的模型（多数测试桩都是这样）
        ChatModel plainModel = prompt -> textResponse(null);

        ToolCallAgent agent = newAgent(plainModel);

        assertEquals(
                ToolCallAgent.UNKNOWN_MODEL_NAME,
                agent.resolveModelName(null),
                "解析不到时必须给 unknown 而不是 null/空串"
        );
    }

    @Test
    @DisplayName("配置值本身为空时也退到 unknown")
    void blankConfiguredModelFallsBackToUnknown() {
        ModelWithDefaultOptions model = new ModelWithDefaultOptions("  ");

        ToolCallAgent agent = newAgent(model);

        assertEquals(ToolCallAgent.UNKNOWN_MODEL_NAME, agent.resolveModelName(null));
    }

    /**
     * 端到端：跑完一轮后 AgentRunContext#getModel() 必须非空。
     *
     * 这是"日志与 agent_run.model 不再为空"的真正落点——
     * AgentRunContext.addModelUsage 会忽略空值，
     * 所以兜底必须发生在传参之前。
     */
    @Test
    @DisplayName("Agent Loop 结束后 ctx.getModel() 不再是空")
    void agentRunContextModelIsPopulatedAfterRun() {
        ModelWithDefaultOptions model =
                new ModelWithDefaultOptions(CONFIGURED_MODEL);

        ToolCallAgent agent = newAgent(model);
        AgentRunContext ctx = new AgentRunContext();

        agent.run("你好", ctx);

        assertEquals(AgentState.SUCCEEDED, ctx.getState());
        assertEquals(
                CONFIGURED_MODEL,
                ctx.getModel(),
                "响应未带模型名时，agent_run.model 应落配置值"
        );
    }

    // ===== 测试辅助 =====

    private ToolCallAgent newAgent(ChatModel chatModel) {
        ToolCallAgent agent = new ToolCallAgent(
                chatModel,
                new ToolCallback[0],
                Runnable::run,
                new DefaultToolEventMessageProvider()
        );
        agent.setName("test-agent");
        agent.setSystemPrompt("你是一个测试用的智能体。");
        agent.setMaxSteps(2);
        agent.setMaxToolCalls(2);
        agent.setTimeout(Duration.ofSeconds(10));
        return agent;
    }

    private static ChatResponse textResponse(String modelName) {
        ChatResponseMetadata metadata = modelName == null
                ? ChatResponseMetadata.builder().build()
                : ChatResponseMetadata.builder().model(modelName).build();

        return new ChatResponse(
                List.of(new Generation(new AssistantMessage("好的，已完成。"))),
                metadata
        );
    }

    /**
     * 模拟真实实现：公开 getDefaultOptions()，
     * 通过反射可读出配置的模型名（与 DashScopeChatModel /
     * OpenAiChatModel 的方法签名一致）。
     */
    private static final class ModelWithDefaultOptions implements ChatModel {

        private final ChatOptions defaultOptions;

        private ModelWithDefaultOptions(String model) {
            this.defaultOptions = ChatOptions.builder().model(model).build();
        }

        public ChatOptions getDefaultOptions() {
            return defaultOptions;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            // 刻意不设置 model：模拟 metadata 缺失模型名的真实情况。
            return textResponse(null);
        }
    }
}
