package com.example.mkagent.eval;

import java.util.List;

/**
 * 一轮评测的执行产物：用例结果 + 预算快照。
 *
 * 拆成独立记录而不是往 EvalReport 里塞可变状态，
 * 是为了让 AgentEvaluator 保持无状态（除只读配置外不持有运行期数据）。
 */
public record EvalRun(
        List<EvalResult> results,
        EvalBudgetSummary budget
) {

    public EvalRun {
        results = results == null ? List.of() : List.copyOf(results);
    }

    /**
     * 是否因预算提前结束。
     */
    public boolean budgetStopped() {
        return budget != null && budget.stopped();
    }
}
