package com.example.mkagent.agent;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 步数预算感知与强制收尾（全部桩测试，不联网、不启动 Spring）。
 *
 * 真实运行暴露的问题：预算耗尽时模型还在搜资料，
 * 最后用户拿到的只有"安全停止：达到最大步骤数……"这句系统摘要，
 * 而上下文里其实已经有搜索结果——答案白攒了。
 *
 * 两个改动：
 * 1. 剩余步数 ≤ budget-warning-steps 时，在**发送给模型的副本**里追加一条
 *    "请收尾"的系统提示（不污染 ctx.messages）；
 * 2. 预算耗尽且无最终回答时，做一次**不带工具**的模型调用生成尽力回答，
 *    答案带固定前缀，状态仍为 MAX_STEPS_REACHED，Token 计入 Usage。
 */
class BudgetAwarenessAgentTest {

    private static final String TOOL_NAME = "counter_tool";

    // ===== 1. 剩余步数提示 =====

    @Test
    @DisplayName("只在最后两步追加收尾提示，且不污染 ctx.messages")
    void warningAppearsOnlyInLastTwoStepsAndDoesNotPolluteHistory() {
        ScriptedModel model = new ScriptedModel(false);
        ToolCallAgent agent = newAgent(model, 4);

        AgentRunContext ctx = new AgentRunContext();
        agent.run("反复调用工具直到预算耗尽", ctx);

        // 4 步：第 1、2 步不提示，第 3、4 步（剩余 2、1）提示。
        assertEquals(4, model.agentPrompts().size(),
                "正常轮次应调用 4 次模型");

        for (int i = 0; i < 2; i++) {
            assertFalse(
                    containsBudgetWarning(model.agentPrompts().get(i)),
                    "第 " + (i + 1) + " 步不应出现收尾提示（剩余步数 > 2）"
            );
        }
        for (int i = 2; i < 4; i++) {
            assertTrue(
                    containsBudgetWarning(model.agentPrompts().get(i)),
                    "第 " + (i + 1) + " 步（剩余 ≤ 2）应出现收尾提示"
            );
        }

        // 提示文案本身要对模型可执行：说清楚做什么。
        String warning = ToolCallAgent.BUDGET_WARNING_MESSAGE;
        assertTrue(warning.contains("terminate_task"),
                "提示必须明确要求调用 terminate_task");
        assertTrue(warning.contains("不要再搜索新资料"),
                "提示必须明确禁止继续搜索");

        /*
         * 关键断言：ctx.messages 里绝不能出现这条提示。
         * 它是"本轮策略"，不是任务事实；写进历史会污染后续所有轮次与评测数据。
         */
        assertFalse(
                ctx.getMessages().stream()
                        .filter(SystemMessage.class::isInstance)
                        .map(message -> message.getText())
                        .anyMatch(text -> text.contains("剩余步数不足")),
                "收尾提示只应存在于发送给模型的副本中"
        );
    }

    @Test
    @DisplayName("阈值为 0 时关闭提示")
    void warningCanBeDisabled() {
        ScriptedModel model = new ScriptedModel(false);
        ToolCallAgent agent = newAgent(model, 3);
        agent.setBudgetWarningSteps(0);

        agent.run("任意任务", new AgentRunContext());

        assertTrue(
                model.agentPrompts().stream()
                        .noneMatch(BudgetAwarenessAgentTest::containsBudgetWarning),
                "阈值为 0 时不应追加任何提示"
        );
    }

    // ===== 2. 强制收尾 =====

    @Test
    @DisplayName("预算耗尽后生成尽力回答，状态仍为 MAX_STEPS_REACHED")
    void exhaustedBudgetProducesForcedFinalAnswer() {
        ScriptedModel model = new ScriptedModel(false);
        ToolCallAgent agent = newAgent(model, 2);

        AgentRunContext ctx = new AgentRunContext();
        String result = agent.run("反复调用工具直到预算耗尽", ctx);

        // 1. 确实发起了一次不带工具的收尾调用。
        assertEquals(1, model.forcedCallCount.get(),
                "预算耗尽后应恰好执行一次收尾调用");
        assertTrue(model.lastForcedPromptHadNoTools(),
                "收尾调用不能带任何工具，否则模型会继续调工具");

        // 2. 状态仍是 MAX_STEPS_REACHED——这是成本事实，不能伪装成成功。
        assertEquals(AgentState.MAX_STEPS_REACHED, ctx.getState(),
                "强制收尾不得改变终态");

        // 3. 答案带固定前缀，且包含模型写的内容。
        assertNotNull(ctx.getFinalAnswer());
        assertTrue(
                ctx.getFinalAnswer().startsWith(
                        ToolCallAgent.FORCED_FINAL_ANSWER_PREFIX),
                "答案必须带'(因执行预算耗尽……)'前缀，实际："
                        + ctx.getFinalAnswer()
        );
        assertTrue(ctx.getFinalAnswer().contains("尽力回答"),
                "答案内容应来自收尾调用，实际：" + ctx.getFinalAnswer());

        // 4. run() 返回值与 ctx 一致。
        assertEquals(ctx.getFinalAnswer(), result,
                "同步 run() 应返回强制收尾的答案");

        // 5. 收尾调用的 Token 计入 Usage：2 次普通调用 + 1 次收尾。
        assertEquals(3, ctx.getModelCallCount(),
                "收尾调用也算一次模型调用");
        assertEquals(30L, ctx.getPromptTokens(), "3 次 × 10 prompt tokens");
        assertEquals(15L, ctx.getCompletionTokens(), "3 次 × 5 completion tokens");
        assertEquals(45L, ctx.getTotalTokens(), "3 次 × 15 tokens");
    }

    @Test
    @DisplayName("force-final-answer=false 时保持旧行为：只返回步骤摘要")
    void forceFinalAnswerCanBeDisabled() {
        ScriptedModel model = new ScriptedModel(false);
        ToolCallAgent agent = newAgent(model, 2);
        agent.setForceFinalAnswer(false);

        AgentRunContext ctx = new AgentRunContext();
        String result = agent.run("反复调用工具直到预算耗尽", ctx);

        assertEquals(0, model.forcedCallCount.get(),
                "关闭后不应有额外模型调用");
        assertEquals(AgentState.MAX_STEPS_REACHED, ctx.getState());
        assertNull(ctx.getFinalAnswer(), "关闭后不应伪造最终回答");
        assertTrue(result.contains("安全停止"),
                "应回退到原有的步骤摘要，实际：" + result);
    }

    @Test
    @DisplayName("收尾调用抛异常时写确定性兜底，不抛异常、不改终态")
    void forcedFinalAnswerFailureFallsBackToDeterministicAnswer() {
        ScriptedModel model = new ScriptedModel(true);
        ToolCallAgent agent = newAgent(model, 2);

        AgentRunContext ctx = new AgentRunContext();
        String result = agent.run("反复调用工具直到预算耗尽", ctx);

        assertEquals(1, model.forcedCallCount.get(), "应尝试过一次收尾调用");
        assertEquals(AgentState.MAX_STEPS_REACHED, ctx.getState(),
                "收尾失败不能把任务改成 FAILED");

        /*
         * 行为变更（真实冒烟暴露的问题）：
         * 旧行为是 finalAnswer 留空、run() 返回"安全停止……"，
         * 导致 SSE 没有 final_answer、数据库该列为 NULL。
         * 现在必须给出确定性兜底。
         */
        assertNotNull(ctx.getFinalAnswer(), "失败时必须有兜底答案");
        assertTrue(ctx.getFinalAnswer().startsWith(ToolCallAgent.BUDGET_FALLBACK_PREFIX),
                "兜底答案必须带固定前缀，实际：" + ctx.getFinalAnswer());
        assertEquals(ctx.getFinalAnswer(), result,
                "同步 run() 必须返回同一个兜底答案");
        assertFalse(result.contains("模拟收尾调用失败"),
                "兜底文案不得包含内部异常信息");
    }

    @Test
    @DisplayName("已有最终回答时不触发收尾调用")
    void noForcedCallWhenFinalAnswerExists() {
        ScriptedModel model = new ScriptedModel(false);
        // 模型第一次就给出文本，任务直接 SUCCEEDED。
        model.answerImmediately = true;
        ToolCallAgent agent = newAgent(model, 2);

        AgentRunContext ctx = new AgentRunContext();
        agent.run("直接回答即可", ctx);

        assertEquals(0, model.forcedCallCount.get(),
                "已成功时不该再多花一次模型调用");
        assertEquals(AgentState.SUCCEEDED, ctx.getState());
    }

    // ===== 测试辅助 =====

    private ToolCallAgent newAgent(ScriptedModel model, int maxSteps) {
        ToolCallback[] tools = ToolCallbacks.from(new CounterTool());

        ToolCallAgent agent = new ToolCallAgent(
                model,
                tools,
                Runnable::run,
                new DefaultToolEventMessageProvider()
        );
        agent.setName("budget-test-agent");
        agent.setSystemPrompt("你是一个测试用的智能体。");
        agent.setMaxSteps(maxSteps);
        agent.setMaxToolCalls(100);
        agent.setTimeout(Duration.ofSeconds(30));
        return agent;
    }

    private static boolean containsBudgetWarning(Prompt prompt) {
        return prompt.getInstructions().stream()
                .anyMatch(message -> message instanceof SystemMessage system
                        && system.getText().contains("剩余步数不足"));
    }

    /**
     * 计数器工具：永远不会让任务结束（模型自己决定何时收尾）。
     */
    public static final class CounterTool {

        private final AtomicInteger counter = new AtomicInteger();

        @Tool(name = TOOL_NAME, description = "测试用计数器工具")
        public String count(
                @ToolParam(description = "任意参数") String value
        ) {
            return "计数=" + counter.incrementAndGet();
        }
    }

    /**
     * 脚本化 ChatModel：
     * - 带工具的调用 → 永远返回工具调用请求（模拟"模型停不下来"）；
     * - 不带工具的调用（收尾）→ 返回尽力回答；
     * - forcedCallFails=true 时收尾调用抛异常。
     */
    private static final class ScriptedModel implements ChatModel {

        private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

        private final AtomicInteger forcedCallCount = new AtomicInteger();

        private final boolean forcedCallFails;

        private volatile boolean answerImmediately;

        private volatile boolean lastForcedPromptHadNoTools;

        private ScriptedModel(boolean forcedCallFails) {
            this.forcedCallFails = forcedCallFails;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);

            if (!hasTools(prompt)) {
                forcedCallCount.incrementAndGet();
                lastForcedPromptHadNoTools = true;
                if (forcedCallFails) {
                    throw new IllegalStateException("模拟收尾调用失败");
                }
                return textResponse("尽力回答：已获取的信息是……");
            }

            if (answerImmediately) {
                return textResponse("直接给出最终回答。");
            }

            return toolCallResponse();
        }

        private List<Prompt> agentPrompts() {
            return prompts.stream().filter(this::hasTools).toList();
        }

        private boolean lastForcedPromptHadNoTools() {
            return lastForcedPromptHadNoTools;
        }

        private boolean hasTools(Prompt prompt) {
            if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
                return false;
            }
            List<ToolCallback> callbacks = options.getToolCallbacks();
            return callbacks != null && !callbacks.isEmpty();
        }

        private ChatResponse toolCallResponse() {
            AssistantMessage assistantMessage = new AssistantMessage(
                    "",
                    Map.of(),
                    List.of(new AssistantMessage.ToolCall(
                            "call_1",
                            "function",
                            TOOL_NAME,
                            "{\"value\":\"x\"}"
                    ))
            );
            return new ChatResponse(
                    List.of(new Generation(assistantMessage)),
                    metadata()
            );
        }

        private ChatResponse textResponse(String text) {
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(text))),
                    metadata()
            );
        }

        private ChatResponseMetadata metadata() {
            return ChatResponseMetadata.builder()
                    .model("stub-model")
                    .usage(new StubUsage(10, 5))
                    .build();
        }
    }

    /**
     * 最小 Usage 实现：每次调用固定 10 / 5 tokens。
     */
    private record StubUsage(int promptTokens, int completionTokens)
            implements Usage {

        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }
}
