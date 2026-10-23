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
 * 3. 评测用例与报告路径可独立配置。
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
}
