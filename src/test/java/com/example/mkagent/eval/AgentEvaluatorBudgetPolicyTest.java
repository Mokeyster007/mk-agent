package com.example.mkagent.eval;

import com.example.mkagent.agent.ToolCallAgent;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.tools.ToolEventMessageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评测预算策略回归测试（纯单元，不启 Spring、不联网）。
 *
 * 覆盖：
 * 1. 默认 0 / false：maxSteps=2 的用例跑满两轮后不再多调模型，
 *    且发送给模型的 Prompt 不含收尾提示；
 * 2. 显式开启后可切换：收尾提示出现，且强制收尾多一次模型调用；
 * 3. 两种情况下 MAX_STEPS_REACHED 都判失败（不因强制回答而“通过”）；
 * 4. 负的 budget-warning-steps 按 0 处理（无提示）。
 */
class AgentEvaluatorBudgetPolicyTest {

    private static final ToolEventMessageProvider NOOP_PROVIDER =
            new ToolEventMessageProvider() {
                @Override
                public String startMessage(String toolName) {
                    return "";
                }

                @Override
                public String resultMessage(String toolName, String rawResult) {
                    return "";
                }
            };

    @Test
    void defaultPolicyAddsNoWarningAndNoExtraCall() {
        EvalProperties properties = new EvalProperties();
        ToolLoopChatModel model = new ToolLoopChatModel();

        EvalResult result = runSingleCase(properties, model);

        assertEquals(2, model.callCount(),
                "默认关闭收尾提示与强制收尾，模型应只被调用 maxSteps 次");
        assertFalse(model.anyPromptContains(ToolCallAgent.BUDGET_WARNING_MESSAGE),
                "默认不应向模型注入收尾提示");
        assertBudgetExhaustedCaseFailed(result);
    }

    @Test
    void enabledPolicyAddsWarningAndForcedFinalCall() {
        EvalProperties properties = new EvalProperties();
        properties.setBudgetWarningSteps(2);
        properties.setForceFinalAnswer(true);
        ToolLoopChatModel model = new ToolLoopChatModel();

        EvalResult result = runSingleCase(properties, model);

        assertEquals(3, model.callCount(),
                "强制收尾应在 maxSteps 之外多一次模型调用");
        assertTrue(model.anyPromptContains(ToolCallAgent.BUDGET_WARNING_MESSAGE),
                "显式开启后，maxSteps=2 的两轮都应带收尾提示");
        assertTrue(model.lastPromptContains("执行预算已经耗尽"),
                "强制收尾调用应携带只输出文本的指令");
        assertBudgetExhaustedCaseFailed(result);
    }

    @Test
    void negativeWarningStepsIsTreatedAsDisabled() {
        EvalProperties properties = new EvalProperties();
        properties.setBudgetWarningSteps(-1);
        ToolLoopChatModel model = new ToolLoopChatModel();

        EvalResult result = runSingleCase(properties, model);

        assertEquals(2, model.callCount());
        assertFalse(model.anyPromptContains(ToolCallAgent.BUDGET_WARNING_MESSAGE),
                "负值应按 0 处理，不得注入收尾提示");
        assertBudgetExhaustedCaseFailed(result);
    }

    private EvalResult runSingleCase(
            EvalProperties properties,
            ToolLoopChatModel model
    ) {
        AgentEvaluator evaluator = new AgentEvaluator(
                model,
                model,
                ToolCallbacks.from(new DemoTool()),
                NOOP_PROVIDER,
                properties
        );

        EvalCase evalCase = new EvalCase(
                "budget-case",
                "反复调用演示工具",
                List.of(),
                List.of(),
                2,
                10,
                List.of(),
                "budget"
        );

        EvalRun run = evaluator.evaluateWithBudget(List.of(evalCase));
        return run.results().get(0);
    }

    private static void assertBudgetExhaustedCaseFailed(EvalResult result) {
        assertEquals(AgentState.MAX_STEPS_REACHED,
                result.execution().state());
        assertFalse(result.passed(),
                "MAX_STEPS_REACHED 一律判失败，强制回答不能改变结论");
        assertTrue(
                result.deterministicFailures().stream()
                        .anyMatch(f -> f.contains("终态不是 SUCCEEDED")),
                "失败原因应明确指向终态不是 SUCCEEDED"
        );
    }

    /**
     * 每轮都请求同一个工具，模拟“模型一直调工具、不写最终回答”。
     */
    private static final class ToolLoopChatModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();

        private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);

            int round = calls.incrementAndGet();
            AssistantMessage message = new AssistantMessage(
                    "",
                    Map.of(),
                    List.of(new AssistantMessage.ToolCall(
                            "call_" + round,
                            "function",
                            "demo_tool",
                            "{}"
                    ))
            );

            return new ChatResponse(List.of(new Generation(message)));
        }

        int callCount() {
            return calls.get();
        }

        boolean anyPromptContains(String text) {
            return prompts.stream().anyMatch(prompt -> promptContains(prompt, text));
        }

        boolean lastPromptContains(String text) {
            return !prompts.isEmpty()
                    && promptContains(prompts.get(prompts.size() - 1), text);
        }

        private boolean promptContains(Prompt prompt, String text) {
            for (Message message : prompt.getInstructions()) {
                if (message instanceof SystemMessage systemMessage
                        && systemMessage.getText().contains(text)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 演示工具：只返回固定文本，保证工具调用链路真实执行。
     */
    static class DemoTool {

        @Tool(name = "demo_tool", description = "演示工具")
        public String invoke() {
            return "ok";
        }
    }
}
