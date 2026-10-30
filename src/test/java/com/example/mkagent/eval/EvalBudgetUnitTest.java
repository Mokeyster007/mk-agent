package com.example.mkagent.eval;

import com.example.mkagent.model.AgentState;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测预算保护单元测试。
 *
 * 全程使用桩 ChatModel，不启动 Spring、不调用真实模型：
 * 1. 条数 / Token / 时长三种上限各自能独立触发；
 * 2. 上限为 0 表示不限；
 * 3. 预算耗尽后不再启动新用例，剩余用例计入"预算跳过"；
 * 4. LLM judge 的 Token 也计入整轮预算（judge 同样花钱）；
 * 5. 报告在预算触发时明确写出"只覆盖已执行用例"。
 */
@Timeout(30)
class EvalBudgetUnitTest {

    // ==================== 1. 预算闸门本身 ====================

    @Test
    void caseLimitStopsFurtherCases() {
        EvalBudget budget = new EvalBudget(0L, 0L, 2);

        assertThat(budget.allowsNextCase()).isTrue();
        budget.record(100L);

        assertThat(budget.allowsNextCase()).isTrue();
        budget.record(100L);

        assertThat(budget.allowsNextCase()).isFalse();
        assertThat(budget.getStopReason())
                .isEqualTo(EvalBudget.StopReason.CASE_LIMIT);
        assertThat(budget.getStopDetail()).contains("上限 2 条");
    }

    @Test
    void tokenBudgetStopsAfterCrossingLimit() {
        EvalBudget budget = new EvalBudget(100L, 0L, 0);

        assertThat(budget.allowsNextCase()).isTrue();
        budget.record(60L);
        assertThat(budget.allowsNextCase()).isTrue();

        budget.record(50L);
        assertThat(budget.allowsNextCase()).isFalse();
        assertThat(budget.getStopReason())
                .isEqualTo(EvalBudget.StopReason.TOKEN_BUDGET);
        assertThat(budget.getConsumedTokens()).isEqualTo(110L);
    }

    @Test
    void durationBudgetStopsWithInjectedClock() {
        AtomicLong nanos = new AtomicLong(0L);
        EvalBudget budget = new EvalBudget(0L, 1000L, 0, nanos::get);

        assertThat(budget.allowsNextCase()).isTrue();

        // 未到 1s：仍可继续。
        nanos.set(999_000_000L);
        assertThat(budget.allowsNextCase()).isTrue();

        // 到 1s：触发时长预算。
        nanos.set(1_000_000_000L);
        assertThat(budget.allowsNextCase()).isFalse();
        assertThat(budget.getStopReason())
                .isEqualTo(EvalBudget.StopReason.TIME_BUDGET);
        assertThat(budget.elapsedMillis()).isEqualTo(1000L);
    }

    @Test
    void zeroLimitsMeanUnlimited() {
        EvalBudget budget = new EvalBudget(0L, 0L, 0);

        for (int i = 0; i < 50; i++) {
            assertThat(budget.allowsNextCase()).isTrue();
            budget.record(1_000_000L);
        }

        assertThat(budget.exhausted()).isFalse();
        assertThat(budget.getStopReason())
                .isEqualTo(EvalBudget.StopReason.NONE);
    }

    @Test
    void summaryReportsSkippedCases() {
        EvalBudget budget = new EvalBudget(0L, 0L, 1);
        budget.record(42L);
        budget.allowsNextCase();

        EvalBudgetSummary summary = budget.summary(32);

        assertThat(summary.executedCases()).isEqualTo(1);
        assertThat(summary.skippedCases()).isEqualTo(31);
        assertThat(summary.consumedTokens()).isEqualTo(42L);
        assertThat(summary.stopped()).isTrue();
        assertThat(summary.stopReason())
                .isEqualTo(EvalBudget.StopReason.CASE_LIMIT);
    }

    // ==================== 2. 与 AgentEvaluator 的集成 ====================

    @Test
    void evaluatorStopsRemainingCasesWhenTokenBudgetExhausted() {
        // 每次 Agent 调用 15 Token；上限 10 → 第一条用例跑完即耗尽。
        ChatModel agent = new ScriptedChatModel(
                prompt -> textResponse("已处理完成。", 10, 5)
        );

        EvalRun run = evaluator(agent, agent, 10L, 0L, 0)
                .evaluateWithBudget(cases(3, "直接回答"));

        assertThat(run.results()).hasSize(1);
        assertThat(run.results().get(0).execution().state())
                .isEqualTo(AgentState.SUCCEEDED);
        assertThat(run.budgetStopped()).isTrue();
        assertThat(run.budget().stopReason())
                .isEqualTo(EvalBudget.StopReason.TOKEN_BUDGET);
        assertThat(run.budget().executedCases()).isEqualTo(1);
        assertThat(run.budget().skippedCases()).isEqualTo(2);
    }

    @Test
    void judgeTokensCountTowardBudget() {
        ChatModel agent = new ScriptedChatModel(
                prompt -> textResponse("信息不足，请补充项目名称。", 10, 5)
        );
        // 评委一次调用 1000 Token，远超 500 的预算上限。
        ChatModel judge = new ScriptedChatModel(
                prompt -> textResponse("{\"pass\":true,\"reason\":\"ok\"}", 500, 500)
        );

        EvalRun run = evaluator(agent, judge, 500L, 0L, 0)
                .evaluateWithBudget(cases(2, null));

        assertThat(run.results()).hasSize(1);
        assertThat(run.results().get(0).judgeUsed()).isTrue();
        assertThat(run.budget().consumedTokens())
                .as("judge 的 Token 必须计入整轮预算")
                .isGreaterThanOrEqualTo(1000L);
        assertThat(run.budget().stopReason())
                .isEqualTo(EvalBudget.StopReason.TOKEN_BUDGET);
    }

    @Test
    void budgetNotTriggeredRunsAllCases() {
        ChatModel agent = new ScriptedChatModel(
                prompt -> textResponse("已处理完成。", 1, 1)
        );

        EvalRun run = evaluator(agent, agent, 0L, 0L, 0)
                .evaluateWithBudget(cases(3, "直接回答"));

        assertThat(run.results()).hasSize(3);
        assertThat(run.budgetStopped()).isFalse();
        assertThat(run.budget().skippedCases()).isZero();
    }

    // ==================== 3. 报告渲染 ====================

    @Test
    void reportRendersBudgetSectionWhenStopped() {
        EvalCase evalCase = evalCase("BUDGET-01", "测试", "直接回答");
        EvalResult result = new EvalResult(
                evalCase,
                new EvalExecution(
                        AgentState.SUCCEEDED,
                        1,
                        0,
                        10,
                        5,
                        15,
                        100,
                        "stub-model",
                        List.of(),
                        "直接回答",
                        List.of()
                ),
                true,
                List.of(),
                false,
                false,
                ""
        );

        EvalBudgetSummary summary = new EvalBudgetSummary(
                1000L,
                1800_000L,
                0,
                900L,
                5_000L,
                1,
                2,
                EvalBudget.StopReason.TOKEN_BUDGET,
                "已用 900 Token，上限 1000 Token"
        );

        EvalReport report = new EvalReport(
                List.of(result),
                "stub-model",
                2,
                null,
                summary
        );

        String markdown = report.toMarkdown();

        assertThat(markdown)
                .contains("## 预算执行情况")
                .contains("| Token（Agent + LLM judge） |")
                .contains("1000 Token")
                .contains("预算已触发")
                .contains("Token 预算耗尽")
                .contains("剩余 2 条未执行")
                .contains("不能当作全量评测结论");
    }

    @Test
    void reportOmitsBudgetSectionWhenNotProvided() {
        EvalReport report = new EvalReport(List.of(), "stub-model", 2);

        assertThat(report.toMarkdown())
                .doesNotContain("预算执行情况");
    }

    // ==================== 辅助方法 ====================

    private AgentEvaluator evaluator(
            ChatModel agent,
            ChatModel judge,
            long maxTotalTokens,
            long maxDurationSeconds,
            int maxCases
    ) {
        EvalProperties properties = new EvalProperties();
        properties.setMaxConcurrency(2);
        properties.setJudgeEnabled(true);
        properties.setJudgeTemperature(0.0);
        properties.setJudgeMaxAnswerChars(4000);
        properties.setCaseTimeoutSeconds(30);
        properties.setMaxTotalTokens(maxTotalTokens);
        properties.setMaxDurationSeconds(maxDurationSeconds);
        properties.setMaxCases(maxCases);

        return new AgentEvaluator(
                agent,
                judge,
                new ToolCallback[0],
                new DefaultToolEventMessageProvider(),
                properties
        );
    }

    /**
     * 生成若干条用例；keywords 为 null 表示交给 LLM judge 判定。
     */
    private List<EvalCase> cases(int count, String keyword) {
        List<EvalCase> cases = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            cases.add(evalCase("BUDGET-" + i, "任务 " + i, keyword));
        }
        return cases;
    }

    private EvalCase evalCase(String id, String userPrompt, String keyword) {
        return new EvalCase(
                id,
                userPrompt,
                List.of(),
                List.of(),
                3,
                2,
                keyword == null ? List.of() : List.of(keyword),
                "NO_TOOL"
        );
    }

    private static ChatResponse textResponse(
            String text,
            int promptTokens,
            int completionTokens
    ) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("stub-model")
                .usage(new StubUsage(promptTokens, completionTokens))
                .build();

        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                metadata
        );
    }

    private static final class ScriptedChatModel implements ChatModel {

        private final java.util.function.Function<Prompt, ChatResponse> handler;

        private ScriptedChatModel(
                java.util.function.Function<Prompt, ChatResponse> handler
        ) {
            this.handler = handler;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return handler.apply(prompt);
        }
    }

    private static final class StubUsage implements Usage {

        private final Integer promptTokens;

        private final Integer completionTokens;

        private StubUsage(int promptTokens, int completionTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

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
