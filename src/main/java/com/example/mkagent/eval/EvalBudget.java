package com.example.mkagent.eval;

import java.util.function.LongSupplier;

/**
 * 一轮离线评测的预算闸门。
 *
 * 为什么需要它：
 * 真实评测会串行跑 32 条用例，每条用例又可能触发多轮模型调用 + LLM judge，
 * 一次全量评测就是真金白银。仅有"用例级"预算（maxSteps / maxToolCalls）
 * 约束不了整轮成本，所以再加一层"整轮预算"：
 *
 * 1. Token 上限（mkagent.eval.max-total-tokens，0 表示不限）；
 * 2. 墙钟时长上限（mkagent.eval.max-duration-seconds，0 表示不限）；
 * 3. 用例条数上限（mkagent.eval.max-cases，0 表示不限，主要用于冒烟跑）。
 *
 * 语义是"软停止"：预算耗尽后不再启动新用例，已执行的用例结果照常计入报告，
 * 未执行的用例在报告中记为"预算跳过"，而不是伪造成失败。
 *
 * 预算统计口径：
 * - Token 计入 Agent 模型调用 + LLM judge 调用（两者都花钱）；
 * - 时长从 evaluate() 开始计，包含 judge 耗时。
 */
public final class EvalBudget {

    /**
     * 停止原因。
     */
    public enum StopReason {

        /**
         * 未触发任何上限。
         */
        NONE("未触发上限"),

        /**
         * Token 预算耗尽。
         */
        TOKEN_BUDGET("Token 预算耗尽"),

        /**
         * 墙钟时长预算耗尽。
         */
        TIME_BUDGET("时长预算耗尽"),

        /**
         * 用例条数上限。
         */
        CASE_LIMIT("达到用例条数上限");

        private final String label;

        StopReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final long maxTotalTokens;

    private final long maxDurationMillis;

    private final int maxCases;

    private final LongSupplier nanoClock;

    private final long startedAtNanos;

    private long consumedTokens;

    private int executedCases;

    private StopReason stopReason = StopReason.NONE;

    private String stopDetail = "";

    public EvalBudget(long maxTotalTokens, long maxDurationMillis, int maxCases) {
        this(maxTotalTokens, maxDurationMillis, maxCases, System::nanoTime);
    }

    /**
     * 包级可见构造器：注入可控时钟，便于单测直接验证时长预算。
     */
    EvalBudget(
            long maxTotalTokens,
            long maxDurationMillis,
            int maxCases,
            LongSupplier nanoClock
    ) {
        this.maxTotalTokens = Math.max(0L, maxTotalTokens);
        this.maxDurationMillis = Math.max(0L, maxDurationMillis);
        this.maxCases = Math.max(0, maxCases);
        this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
        this.startedAtNanos = this.nanoClock.getAsLong();
    }

    /**
     * 按配置构建预算。
     */
    public static EvalBudget from(EvalProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("EvalProperties 不能为空");
        }
        return new EvalBudget(
                properties.getMaxTotalTokens(),
                properties.getMaxDurationSeconds() * 1000L,
                properties.getMaxCases()
        );
    }

    /**
     * 是否还能再启动一条用例。
     *
     * 三个上限按"条数 → 时长 → Token"顺序检查，先命中者作为停止原因，
     * 保证报告里的停止原因是可解释的单一原因。
     */
    public boolean allowsNextCase() {
        if (stopReason != StopReason.NONE) {
            return false;
        }

        if (maxCases > 0 && executedCases >= maxCases) {
            return stop(
                    StopReason.CASE_LIMIT,
                    "已执行 " + executedCases + " 条，上限 " + maxCases + " 条"
            );
        }

        long elapsedMillis = elapsedMillis();
        if (maxDurationMillis > 0 && elapsedMillis >= maxDurationMillis) {
            return stop(
                    StopReason.TIME_BUDGET,
                    "已用 " + elapsedMillis + " ms，上限 " + maxDurationMillis + " ms"
            );
        }

        if (maxTotalTokens > 0 && consumedTokens >= maxTotalTokens) {
            return stop(
                    StopReason.TOKEN_BUDGET,
                    "已用 " + consumedTokens + " Token，上限 " + maxTotalTokens + " Token"
            );
        }

        return true;
    }

    /**
     * 记录一条用例的实际消耗。
     *
     * @param tokens Agent 模型 + LLM judge 的 Token 合计，负数按 0 处理
     */
    public void record(long tokens) {
        executedCases++;
        consumedTokens += Math.max(0L, tokens);
    }

    public boolean exhausted() {
        return stopReason != StopReason.NONE;
    }

    public StopReason getStopReason() {
        return stopReason;
    }

    public String getStopDetail() {
        return stopDetail;
    }

    public long getConsumedTokens() {
        return consumedTokens;
    }

    public int getExecutedCases() {
        return executedCases;
    }

    public long elapsedMillis() {
        long elapsedNanos = nanoClock.getAsLong() - startedAtNanos;
        return Math.max(0L, elapsedNanos / 1_000_000L);
    }

    /**
     * 生成可写入报告的预算快照。
     *
     * @param totalCases 本轮计划执行的用例总数，用于计算跳过条数
     */
    public EvalBudgetSummary summary(int totalCases) {
        int planned = Math.max(0, totalCases);
        return new EvalBudgetSummary(
                maxTotalTokens,
                maxDurationMillis,
                maxCases,
                consumedTokens,
                elapsedMillis(),
                executedCases,
                Math.max(0, planned - executedCases),
                stopReason,
                stopDetail
        );
    }

    private boolean stop(StopReason reason, String detail) {
        this.stopReason = reason;
        this.stopDetail = detail;
        return false;
    }
}
