package com.example.mkagent.eval;

/**
 * 一轮评测的预算执行快照。
 *
 * 只包含聚合数字与停止原因，不含用例内容，可安全写入报告。
 */
public record EvalBudgetSummary(
        long maxTotalTokens,
        long maxDurationMillis,
        int maxCases,
        long consumedTokens,
        long consumedDurationMillis,
        int executedCases,
        int skippedCases,
        EvalBudget.StopReason stopReason,
        String stopDetail
) {

    public EvalBudgetSummary {
        stopReason = stopReason == null ? EvalBudget.StopReason.NONE : stopReason;
        stopDetail = stopDetail == null ? "" : stopDetail;
    }

    /**
     * 是否因预算提前结束。
     */
    public boolean stopped() {
        return stopReason != EvalBudget.StopReason.NONE;
    }
}
