package com.example.mkagent.eval.music;

/**
 * 一条任务的评测结果。
 *
 * @param task         任务定义
 * @param mode         运行模式
 * @param observation  客观观测（可能为 null：执行器在产出观测前就失败了）
 * @param outcome      判定结果
 * @param errorMessage 执行器异常信息；正常时为 null
 */
public record MusicEvalResult(
        MusicEvalTask task,
        Mode mode,
        MusicEvalObservation observation,
        MusicEvalChecks.Outcome outcome,
        String errorMessage
) {

    /**
     * 运行模式。
     */
    public enum Mode {

        /**
         * 脚本化：模型回合由任务集给定，不调用真实模型。
         * 用来验证执行链路与判定器本身，不代表模型能力。
         */
        SCRIPTED("脚本化（不调用模型）"),

        /**
         * 真实模型：使用容器里的 ChatModel。
         */
        LIVE("真实模型");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public MusicEvalResult {
        outcome = outcome == null
                ? new MusicEvalChecks.Outcome(MusicEvalChecks.Status.ERROR,
                java.util.List.of(new MusicEvalChecks.Failure(
                        MusicEvalChecks.Dimension.HARNESS, "NO_OUTCOME", "没有判定结果")))
                : outcome;
    }

    /**
     * 执行器失败时的结果。
     */
    public static MusicEvalResult error(
            MusicEvalTask task,
            Mode mode,
            MusicEvalObservation observation,
            String message
    ) {
        String safeMessage = message == null ? "未知异常" : message;
        return new MusicEvalResult(
                task,
                mode,
                observation,
                new MusicEvalChecks.Outcome(
                        MusicEvalChecks.Status.ERROR,
                        java.util.List.of(new MusicEvalChecks.Failure(
                                MusicEvalChecks.Dimension.HARNESS,
                                "HARNESS_ERROR",
                                safeMessage))),
                safeMessage
        );
    }

    /**
     * 未执行：因为安全停止或环境原因没有跑。
     *
     * <p>**必须留在结果列表里**。把未执行从统计中删掉，会让成功率看起来
     * 比实际好（分母被悄悄缩小），这比失败本身更危险。
     */
    public static MusicEvalResult skipped(
            MusicEvalTask task,
            Mode mode,
            String reason
    ) {
        String safeReason = reason == null ? "未执行（未说明原因）" : reason;
        return new MusicEvalResult(
                task,
                mode,
                null,
                new MusicEvalChecks.Outcome(
                        MusicEvalChecks.Status.SKIPPED,
                        java.util.List.of(new MusicEvalChecks.Failure(
                                MusicEvalChecks.Dimension.HARNESS,
                                "NOT_EXECUTED",
                                safeReason))),
                safeReason
        );
    }

    public MusicEvalChecks.Status status() {
        return outcome.status();
    }

    public java.util.List<MusicEvalChecks.Failure> safetyViolations() {
        return outcome.safetyViolations();
    }

    /**
     * 第一个失败项，供表格展示；没有失败时返回空串。
     */
    public String firstFailure() {
        return outcome.failures().isEmpty()
                ? ""
                : outcome.failures().get(0).toString();
    }
}
