package com.example.mkagent.agent;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
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
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算耗尽收尾的各种失败形状：全部必须落到确定性兜底，或按取消语义退出。
 *
 * 真实冒烟里第 8 步之后的收尾调用拿到了 27 个 completion token 却没有文本，
 * 最终 final_answer 为空、SSE 没有 final_answer、库里是 NULL。
 * 这里把"没有文本"拆成可构造的几种形状逐一固定下来：
 * 空串、null、只有 tool_call、文本+tool_call、空 generations、无 output、抛异常、
 * 以及**中断/取消**——最后两种绝不能变成"预算耗尽"的正常回答。
 */
class BudgetFallbackBehaviourTest {

    private static final String TOOL_NAME = "counter_tool";

    @AfterEach
    void clearInterruptFlag() {
        // 中断标志是线程级的：不清掉会污染后续用例。
        Thread.interrupted();
    }

    // ===== 1. 各种"没有文本"的响应形状 =====

    @Test
    @DisplayName("收尾返回空串 → 确定性兜底")
    void blankTextFallsBack() {
        assertFallback(ForcedReply.BLANK);
    }

    @Test
    @DisplayName("收尾返回 null 文本 → 确定性兜底")
    void nullTextFallsBack() {
        assertFallback(ForcedReply.NULL_TEXT);
    }

    @Test
    @DisplayName("收尾只返回 tool_call（无文本）→ 兜底，且绝不执行该工具")
    void toolCallOnlyFallsBackAndIsNeverExecuted() {
        assertFallback(ForcedReply.TOOL_CALL_ONLY);
    }

    @Test
    @DisplayName("空 generations → 确定性兜底")
    void emptyGenerationsFallsBack() {
        assertFallback(ForcedReply.NO_GENERATION);
    }

    @Test
    @DisplayName("generation 没有 output → 确定性兜底")
    void missingOutputFallsBack() {
        assertFallback(ForcedReply.NO_OUTPUT);
    }

    @Test
    @DisplayName("收尾调用抛异常 → 确定性兜底，不抛给调用方")
    void exceptionFallsBack() {
        assertFallback(ForcedReply.THROW);
    }

    @Test
    @DisplayName("文本与 tool_call 同时存在 → 采用文本，但工具不执行")
    void textWithToolCallUsesTextAndDoesNotExecuteTool() {
        ModeModel model = new ModeModel(ForcedReply.TOOL_AND_TEXT);
        ToolCallAgent agent = newAgent(model, 2);

        AgentRunContext ctx = new AgentRunContext();
        String result = agent.run("反复调用工具直到预算耗尽", ctx);

        assertEquals(AgentState.MAX_STEPS_REACHED, ctx.getState());
        assertNotNull(ctx.getFinalAnswer());
        assertTrue(ctx.getFinalAnswer().startsWith(ToolCallAgent.FORCED_FINAL_ANSWER_PREFIX),
                "有文本时应使用尽力回答前缀，实际：" + ctx.getFinalAnswer());
        assertTrue(ctx.getFinalAnswer().contains("模型写下的结论"),
                "应采用模型文本，实际：" + ctx.getFinalAnswer());
        assertEquals(result, ctx.getFinalAnswer());

        assertEquals(2, model.toolExecutions.get(),
                "两步工具轮各执行一次；收尾阶段出现的 tool_call 绝不允许执行");
    }

    // ===== 2. 取消 / 中断 =====

    @Test
    @DisplayName("收尾调用因中断抛异常 → 不写兜底，终态仍是 MAX_STEPS_REACHED")
    void interruptionExceptionIsNotMaskedAsBudgetFallback() {
        ModeModel model = new ModeModel(ForcedReply.THROW_INTERRUPTED);
        ToolCallAgent agent = newAgent(model, 2);

        AgentRunContext ctx = new AgentRunContext();
        String result = agent.run("反复调用工具直到预算耗尽", ctx);

        /*
         * 最终状态必须是 MAX_STEPS_REACHED，而不是 CANCELLED。
         *
         * 原因：收尾调用发生在 finishIfStillRunning 之后，
         * 那时 MAX_STEPS_REACHED 已经是终态；异常虽然按"中断语义"
         * 原样抛出，BaseAgent.failOrCancel 里的 transitionTo(CANCELLED)
         * 会被终态保护拒绝（AgentRunContext.transitionTo 在
         * state.isTerminal() 时直接返回 false）。
         *
         * 所以"按取消语义退出"只描述异常路径与日志，
         * 不描述终态——文档里不能写成"变成 CANCELLED"。
         */
        assertEquals(AgentState.MAX_STEPS_REACHED, ctx.getState(),
                "终态在收尾前已确定，中断异常不得把状态改成 CANCELLED");

        assertNull(ctx.getFinalAnswer(),
                "中断不得产出兜底答案（否则取消会被伪装成正常收尾）");
        assertFalse(result.contains(ToolCallAgent.BUDGET_FALLBACK_PREFIX),
                "返回值不得是兜底文案，实际：" + result);
        assertEquals(1, model.forcedCalls.get(), "只尝试一次，不重试");
        /*
         * toolExecutions 统计口径：CounterTool 每被真正执行一次 +1。
         * 两步工具轮（maxSteps=2）各触发一次 think→act，act 执行 counter_tool，
         * 所以工具轮结束后 toolExecutions=2。收尾调用走 forcedResponse()，
         * 不经过 ToolCallingManager，不执行任何工具——如果收尾阶段执行了工具，
         * 这里会变成 3。断言 2 就是"进入收尾前后工具执行次数不增加"。
         */
        assertEquals(2, model.toolExecutions.get(),
                "中断后不得再执行任何工具：两步工具轮各一次（=2），收尾阶段不得追加");
        // 同理：ctx 记录的工具调用次数也应等于工具轮的执行次数。
        assertEquals(2, ctx.getToolCallCount(),
                "ctx 的 toolCallCount 也应等于工具轮执行次数，收尾不追加");
    }

    @Test
    @DisplayName("收尾调用期间线程被中断 → 不写兜底，只回退到步骤摘要")
    void interruptedThreadSkipsFallback() {
        ModeModel model = new ModeModel(ForcedReply.INTERRUPT_THEN_BLANK);
        ToolCallAgent agent = newAgent(model, 2);

        AgentRunContext ctx = new AgentRunContext();
        String result = agent.run("反复调用工具直到预算耗尽", ctx);

        assertEquals(AgentState.MAX_STEPS_REACHED, ctx.getState(),
                "线程被中断也不改终态");
        assertNull(ctx.getFinalAnswer(),
                "线程已中断时不得再写兜底答案");

        /*
         * 没有兜底、没有收尾文本时，同步 run() 回退到步骤摘要，
         * 其中只有一条"安全停止"提示——不会触发第二次总结调用。
         */
        assertTrue(result.contains("安全停止"),
                "应回退到步骤摘要，实际：" + result);
        assertEquals(1, model.forcedCalls.get(), "收尾只尝试一次");
    }

    @Test
    @DisplayName("兜底写入的前置条件：必须是预算终态且线程未中断")
    void fallbackGuardContract() {
        AgentRunContext ctx = new AgentRunContext();
        ctx.transitionTo(AgentState.RUNNING);

        ctx.transitionTo(AgentState.MAX_STEPS_REACHED);
        assertTrue(ToolCallAgent.canWriteBudgetFallback(ctx),
                "预算终态且未中断时应允许写兜底");

        AgentRunContext timedOut = new AgentRunContext();
        timedOut.transitionTo(AgentState.RUNNING);
        timedOut.transitionTo(AgentState.TIMED_OUT);
        assertFalse(ToolCallAgent.canWriteBudgetFallback(timedOut),
                "超时终态不得写预算兜底");

        AgentRunContext cancelled = new AgentRunContext();
        cancelled.transitionTo(AgentState.RUNNING);
        cancelled.transitionTo(AgentState.CANCELLED);
        assertFalse(ToolCallAgent.canWriteBudgetFallback(cancelled),
                "取消终态不得写预算兜底");

        AgentRunContext interrupted = new AgentRunContext();
        interrupted.transitionTo(AgentState.RUNNING);
        interrupted.transitionTo(AgentState.MAX_STEPS_REACHED);
        Thread.currentThread().interrupt();
        try {
            assertFalse(ToolCallAgent.canWriteBudgetFallback(interrupted),
                    "线程已中断时不得写预算兜底");
        } finally {
            Thread.interrupted();
        }
    }

    // ===== 3. 兜底文案本身 =====

    @Test
    @DisplayName("兜底文案：只含步数与工具次数，不含工具名/URL/参数/异常")
    void fallbackTextIsDesensitized() {
        ModeModel model = new ModeModel(ForcedReply.THROW_WITH_SECRET);
        ToolCallAgent agent = newAgent(model, 2);

        AgentRunContext ctx = new AgentRunContext();
        agent.run("抓取 https://secret.example.com/a 的正文", ctx);

        String answer = ctx.getFinalAnswer();
        assertNotNull(answer);

        assertTrue(answer.startsWith(ToolCallAgent.BUDGET_FALLBACK_PREFIX),
                "兜底文案前缀不对，实际：" + answer);
        assertTrue(answer.contains("本次已执行"),
                "应说明执行了多少步，实际：" + answer);
        assertTrue(answer.contains("请缩小任务范围后重试"),
                "应给出下一步建议，实际：" + answer);

        assertFalse(answer.contains(TOOL_NAME), "不得出现工具名");
        assertFalse(answer.contains("secret.example.com"), "不得出现 URL");
        assertFalse(answer.contains("api-key-123"), "不得出现异常细节");
        assertFalse(answer.contains("{"), "不得出现参数 JSON");
    }

    // ===== 辅助 =====

    private void assertFallback(ForcedReply reply) {
        ModeModel model = new ModeModel(reply);
        ToolCallAgent agent = newAgent(model, 2);

        AgentRunContext ctx = new AgentRunContext();
        String result = agent.run("反复调用工具直到预算耗尽", ctx);

        assertEquals(AgentState.MAX_STEPS_REACHED, ctx.getState(),
                "兜底不得改变终态");
        assertEquals(1, model.forcedCalls.get(), "收尾调用只允许一次");

        assertNotNull(ctx.getFinalAnswer(), "必须有兜底答案");
        assertTrue(ctx.getFinalAnswer().startsWith(ToolCallAgent.BUDGET_FALLBACK_PREFIX),
                "兜底前缀不对，实际：" + ctx.getFinalAnswer());
        assertEquals(ctx.getFinalAnswer(), result,
                "同步 run() 必须返回同一个兜底答案");

        /*
         * Usage 只记已经真实发生的调用：
         * 2 次工具轮 + 1 次收尾（抛异常那次没有响应，自然没有 Token 可记）。
         */
        int expectedCalls = reply == ForcedReply.THROW ? 2 : 3;
        assertEquals(expectedCalls, ctx.getModelCallCount(),
                "收尾调用必须计入模型调用次数（异常那次没有响应）");
        assertEquals(10L * expectedCalls, ctx.getPromptTokens());
        assertEquals(5L * expectedCalls, ctx.getCompletionTokens());

        /*
         * toolExecutions 统计口径同 interruptionExceptionIsNotMaskedAsBudgetFallback：
         * 两步工具轮各执行一次（=2），收尾阶段不得追加工具执行。
         */
        assertEquals(2, model.toolExecutions.get(),
                "两步工具轮各执行一次（=2）；收尾阶段出现的 tool_call 绝不允许执行");
    }

    private ToolCallAgent newAgent(ModeModel model, int maxSteps) {
        ToolCallback[] tools = ToolCallbacks.from(new CounterTool(model.toolExecutions));

        ToolCallAgent agent = new ToolCallAgent(
                model,
                tools,
                Runnable::run,
                new DefaultToolEventMessageProvider()
        );
        agent.setName("budget-fallback-test-agent");
        agent.setSystemPrompt("你是一个测试用的智能体。");
        agent.setMaxSteps(maxSteps);
        agent.setMaxToolCalls(100);
        agent.setTimeout(Duration.ofSeconds(30));
        return agent;
    }

    /**
     * 收尾调用的各种返回形状。
     */
    private enum ForcedReply {
        /** 空字符串。 */
        BLANK,
        /** content 为 null。 */
        NULL_TEXT,
        /** 只有 tool_call，没有文本。 */
        TOOL_CALL_ONLY,
        /** 既有文本又有 tool_call。 */
        TOOL_AND_TEXT,
        /** generations 为空。 */
        NO_GENERATION,
        /** generation 没有 output。 */
        NO_OUTPUT,
        /** 抛普通运行时异常。 */
        THROW,
        /** 异常链里带 InterruptedException（取消/超时）。 */
        THROW_INTERRUPTED,
        /** 先把线程标记为中断，再返回空串。 */
        INTERRUPT_THEN_BLANK,
        /** 抛一个带敏感信息的异常，用于验证兜底不泄露。 */
        THROW_WITH_SECRET
    }

    /**
     * 工具轮永远请求工具；收尾轮按 {@link ForcedReply} 返回。
     */
    private static final class ModeModel implements ChatModel {

        private final ForcedReply reply;

        private final AtomicInteger forcedCalls = new AtomicInteger();

        private final AtomicInteger toolExecutions = new AtomicInteger();

        private ModeModel(ForcedReply reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (hasTools(prompt)) {
                return toolCallResponse();
            }

            forcedCalls.incrementAndGet();
            return forcedResponse();
        }

        private ChatResponse forcedResponse() {
            return switch (reply) {
                case BLANK -> textResponse("");
                case NULL_TEXT -> textResponse(null);
                case TOOL_CALL_ONLY -> toolCallResponse();
                case TOOL_AND_TEXT -> new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "模型写下的结论",
                                Map.of(),
                                List.of(toolCall(TOOL_NAME))
                        ))),
                        metadata()
                );
                case NO_GENERATION -> new ChatResponse(List.of(), metadata());
                case NO_OUTPUT -> new ChatResponse(
                        List.of(Mockito.mock(Generation.class)),
                        metadata()
                );
                case THROW -> throw new IllegalStateException("模拟收尾调用失败");
                case THROW_INTERRUPTED -> throw new RuntimeException(
                        "模拟收尾期间被中断",
                        new InterruptedException("interrupted")
                );
                case INTERRUPT_THEN_BLANK -> {
                    Thread.currentThread().interrupt();
                    yield textResponse("");
                }
                case THROW_WITH_SECRET -> throw new IllegalStateException(
                        "api-key-123 调用失败：{\"url\":\"https://secret.example.com/a\"}");
            };
        }

        private ChatResponse toolCallResponse() {
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(
                            "",
                            Map.of(),
                            List.of(toolCall(TOOL_NAME))
                    ))),
                    metadata()
            );
        }

        private static AssistantMessage.ToolCall toolCall(String name) {
            return new AssistantMessage.ToolCall(
                    "call_" + name, "function", name, "{}");
        }

        private ChatResponse textResponse(String text) {
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(text))),
                    metadata()
            );
        }

        private static boolean hasTools(Prompt prompt) {
            if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
                return false;
            }
            List<ToolCallback> callbacks = options.getToolCallbacks();
            return callbacks != null && !callbacks.isEmpty();
        }

        private static ChatResponseMetadata metadata() {
            return ChatResponseMetadata.builder()
                    .model("stub-model")
                    .usage(new StubUsage(10, 5))
                    .build();
        }
    }

    /**
     * 计数工具：每被真正执行一次就 +1。
     */
    public static final class CounterTool {

        private final AtomicInteger executions;

        public CounterTool(AtomicInteger executions) {
            this.executions = executions;
        }

        @Tool(name = TOOL_NAME, description = "计数")
        public String count() {
            executions.incrementAndGet();
            return "ok";
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
