package com.example.mkagent.eval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 离线评测配置。
 *
 * 与线上 Agent 配置完全分离：
 * 1. mkagent.eval.max-concurrency 是评测专用 Semaphore 许可数，
 *    不会占用 mkagent.agent.max-concurrency；
 * 2. 评测 Agent 不注入 AgentRunRecorder，不写 agent_run 表；
 * 3. 评测用例与报告路径可独立配置；
 * 4. 整轮预算（max-total-tokens / max-duration-seconds / max-cases）独立于
 *    单条用例预算（maxSteps / maxToolCalls），用于给一次全量评测封顶成本。
 */
@Component
@ConfigurationProperties(prefix = "mkagent.eval")
public class EvalProperties {

    /**
     * 是否允许运行评测（真实评测 runner 还会检查系统属性）。
     */
    private boolean enabled = false;

    /**
     * 评测专用并发闸门许可数（与线上限流配置无关）。
     */
    private int maxConcurrency = 2;

    /**
     * 用例文件位置，支持 classpath: 前缀。
     */
    private String casesPath = "classpath:eval/cases.json";

    /**
     * Markdown 报告输出路径。
     */
    private String reportPath = "target/eval-report/agent-eval-report.md";

    /**
     * 是否启用 LLM-as-judge。
     */
    private boolean judgeEnabled = true;

    /**
     * 评委模型温度，默认 0.0。
     */
    private double judgeTemperature = 0.0;

    /**
     * 评委提示词中最终回答的最大字符数，避免超长回答撑爆上下文。
     */
    private int judgeMaxAnswerChars = 4000;

    /**
     * 单条用例的最大执行时间（秒）。
     */
    private int caseTimeoutSeconds = 120;

    /**
     * 评测环境是否追加"剩余步数不足"收尾提示（0 = 关闭）。
     *
     * 默认 0：评测度量的是固定预算下的原始策略，
     * 额外提示会改变被测行为，破坏与历史数据的可比性。
     * M4 做对比实验时可临时设为 1~3。
     * 负值由 AgentEvaluator 按 0 处理并 WARN。
     */
    private int budgetWarningSteps = 0;

    /**
     * 评测环境是否在预算耗尽且无答案时再问模型一次
     * （生产配置 mkagent.agent.force-final-answer 的评测开关）。
     *
     * 默认 false：
     * 1. 该调用不改变 MAX_STEPS_REACHED 的失败判定，只会多花 Token；
     * 2. 多出的 Token 计入整轮预算，可能让后续用例更早被跳过；
     * 3. 与 E2 之前的评测口径一致，保证历史数据可比。
     */
    private boolean forceFinalAnswer = false;

    /**
     * 整轮评测的 Token 预算上限（Agent 模型 + LLM judge），0 表示不限。
     */
    private long maxTotalTokens = 2_000_000L;

    /**
     * 整轮评测的墙钟时长上限（秒），0 表示不限。
     */
    private long maxDurationSeconds = 1800L;

    /**
     * 整轮评测最多执行多少条用例，0 表示不限（主要用于冒烟跑）。
     */
    private int maxCases = 0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public String getCasesPath() {
        return casesPath;
    }

    public void setCasesPath(String casesPath) {
        this.casesPath = casesPath;
    }

    public String getReportPath() {
        return reportPath;
    }

    public void setReportPath(String reportPath) {
        this.reportPath = reportPath;
    }

    public boolean isJudgeEnabled() {
        return judgeEnabled;
    }

    public void setJudgeEnabled(boolean judgeEnabled) {
        this.judgeEnabled = judgeEnabled;
    }

    public double getJudgeTemperature() {
        return judgeTemperature;
    }

    public void setJudgeTemperature(double judgeTemperature) {
        this.judgeTemperature = judgeTemperature;
    }

    public int getJudgeMaxAnswerChars() {
        return judgeMaxAnswerChars;
    }

    public void setJudgeMaxAnswerChars(int judgeMaxAnswerChars) {
        this.judgeMaxAnswerChars = judgeMaxAnswerChars;
    }

    public int getCaseTimeoutSeconds() {
        return caseTimeoutSeconds;
    }

    public void setCaseTimeoutSeconds(int caseTimeoutSeconds) {
        this.caseTimeoutSeconds = caseTimeoutSeconds;
    }

    public int getBudgetWarningSteps() {
        return budgetWarningSteps;
    }

    public void setBudgetWarningSteps(int budgetWarningSteps) {
        this.budgetWarningSteps = budgetWarningSteps;
    }

    public boolean isForceFinalAnswer() {
        return forceFinalAnswer;
    }

    public void setForceFinalAnswer(boolean forceFinalAnswer) {
        this.forceFinalAnswer = forceFinalAnswer;
    }

    public long getMaxTotalTokens() {
        return maxTotalTokens;
    }

    public void setMaxTotalTokens(long maxTotalTokens) {
        this.maxTotalTokens = maxTotalTokens;
    }

    public long getMaxDurationSeconds() {
        return maxDurationSeconds;
    }

    public void setMaxDurationSeconds(long maxDurationSeconds) {
        this.maxDurationSeconds = maxDurationSeconds;
    }

    public int getMaxCases() {
        return maxCases;
    }

    public void setMaxCases(int maxCases) {
        this.maxCases = maxCases;
    }
}
