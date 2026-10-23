package com.example.mkagent.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条用例的最终评测结果。
 *
 * 判定公式：
 *   passed = 确定性检查通过
 *            && (未使用 judge || judge 通过)
 *
 * 确定性检查失败时直接判失败，不再调用 judge，
 * 避免为一堆硬性失败（工具错、超预算）浪费模型 Token。
 */
public record EvalResult(
        EvalCase evalCase,
        EvalExecution execution,
        boolean deterministicPassed,
        List<String> deterministicFailures,
        boolean judgeUsed,
        boolean judgePassed,
        String judgeReason
) {

    public EvalResult {
        deterministicFailures = deterministicFailures == null
                ? List.of()
                : List.copyOf(deterministicFailures);
        judgeReason = judgeReason == null ? "" : judgeReason;
    }

    /**
     * 是否通过。
     */
    public boolean passed() {
        return deterministicPassed && (!judgeUsed || judgePassed);
    }

    /**
     * 汇总所有失败原因，供 Markdown 报告展示。
     */
    public List<String> failureReasons() {
        List<String> reasons = new ArrayList<>(deterministicFailures);

        if (judgeUsed && !judgePassed) {
            reasons.add("LLM judge 不通过：" + judgeReason);
        }

        return List.copyOf(reasons);
    }
}
