package com.example.mkagent.eval;

import com.example.mkagent.model.AgentState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 确定性检查层。
 *
 * 只做可重复、可解释的硬性检查：
 * 1. 任务终态是否为 SUCCEEDED；
 * 2. expectedTools 是否全部实际调用；
 * 3. forbiddenTools 是否均未调用；
 * 4. 步数 / 工具调用次数是否在预算内；
 * 5. 最终回答是否为空；
 * 6. expectedKeywords 是否全部出现在最终回答中。
 *
 * expectedKeywords 为空时，内容正确性无法用关键词判断，
 * 该层只返回“通过”，由 LLM-as-judge 做语义评审。
 */
public final class DeterministicChecker {

    /**
     * 执行确定性检查。
     */
    public CheckResult check(EvalCase evalCase, EvalExecution execution) {
        List<String> failures = new ArrayList<>();

        if (execution.state() != AgentState.SUCCEEDED) {
            failures.add(
                    "终态不是 SUCCEEDED，实际为 " + execution.state()
                            + stopReason(execution.state())
            );
        }

        List<String> missingTools = evalCase.getExpectedTools()
                .stream()
                .filter(tool -> !execution.toolTrace().contains(tool))
                .toList();
        if (!missingTools.isEmpty()) {
            failures.add(
                    "缺少必须调用的工具 " + missingTools
                            + "，实际工具轨迹 " + execution.toolTrace()
            );
        }

        List<String> usedForbiddenTools = evalCase.getForbiddenTools()
                .stream()
                .filter(execution.toolTrace()::contains)
                .toList();
        if (!usedForbiddenTools.isEmpty()) {
            failures.add("调用了禁用工具 " + usedForbiddenTools);
        }

        if (execution.steps() > evalCase.getMaxSteps()) {
            failures.add(
                    "步数超出预算：" + execution.steps()
                            + " > " + evalCase.getMaxSteps()
            );
        }

        if (execution.toolCallCount() > evalCase.getMaxToolCalls()) {
            failures.add(
                    "工具调用次数超出预算：" + execution.toolCallCount()
                            + " > " + evalCase.getMaxToolCalls()
            );
        }

        if (execution.finalAnswer().isBlank()) {
            failures.add("最终回答为空");
        }

        if (!evalCase.getExpectedKeywords().isEmpty()) {
            String normalizedAnswer = normalize(execution.finalAnswer());
            List<String> missingKeywords = evalCase.getExpectedKeywords()
                    .stream()
                    .filter(keyword -> !normalizedAnswer.contains(normalize(keyword)))
                    .toList();

            if (!missingKeywords.isEmpty()) {
                failures.add("最终回答缺少关键词 " + missingKeywords);
            }
        }

        return new CheckResult(failures.isEmpty(), List.copyOf(failures));
    }

    /**
     * 为常见非成功终态补充可读原因，便于报告定位。
     */
    private String stopReason(AgentState state) {
        return switch (state) {
            case MAX_STEPS_REACHED -> "（达到步数或工具调用预算）";
            case TIMED_OUT -> "（任务超时）";
            case FAILED -> "（执行异常）";
            case CANCELLED -> "（任务被取消）";
            default -> "";
        };
    }

    /**
     * 大小写与空白归一化：
     * 避免 "Tool Calling" 与 "tool calling" 这类差异造成误判。
     */
    private String normalize(String text) {
        return text == null
                ? ""
                : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * 确定性检查结果。
     */
    public record CheckResult(
            boolean passed,
            List<String> failures
    ) {
        public CheckResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }
}
