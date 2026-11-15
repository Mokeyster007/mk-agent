package com.example.mkagent.model.vo;

import com.example.mkagent.entity.AgentRunEntity;

import java.time.LocalDateTime;

/**
 * agent_run 任务详情/列表响应对象（不直接暴露 Entity）。
 */
public class AgentRunVO {

    private String runId;

    private String userId;

    private String agentType;

    private String userPrompt;

    private String state;

    private Integer currentStep;

    private Integer toolCallCount;

    /**
     * 工具名执行序列（逗号分隔，按执行顺序，不含参数与结果原文）。
     *
     * 前端可据此画出任务轨迹，例如：
     * web_search → web_search → web_scrape → terminate_task。
     */
    private String toolTrace;

    private String finalAnswer;

    private String errorMessage;

    /**
     * 稳定原因码（批次 4B 新增）。
     *
     * 前端应只认这个码，不解析中文 {@link #errorMessage}。
     * 典型值：{@code PROCESS_RESTART}（执行被进程重启中断，历史副作用可能仍有效）、
     * {@code APPROVAL_EXPIRED}、{@code USER_CANCELLED} 等。
     *
     * 新增字段是向后兼容的：老客户端忽略未知字段即可，原有字段语义不变。
     */
    private String errorCode;

    /**
     * 本次任务使用的模型名（成本观测）。
     */
    private String model;

    private Long promptTokens;

    private Long completionTokens;

    private Long totalTokens;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long totalCostMillis;

    private LocalDateTime createdAt;

    /**
     * Entity → VO 转换。
     */
    public static AgentRunVO fromEntity(AgentRunEntity entity) {
        AgentRunVO vo = new AgentRunVO();
        vo.runId = entity.getRunId();
        vo.userId = entity.getUserId();
        vo.agentType = entity.getAgentType();
        vo.userPrompt = entity.getUserPrompt();
        vo.state = entity.getState();
        vo.currentStep = entity.getCurrentStep();
        vo.toolCallCount = entity.getToolCallCount();
        vo.toolTrace = entity.getToolTrace();
        vo.finalAnswer = entity.getFinalAnswer();
        vo.errorMessage = entity.getErrorMessage();
        vo.errorCode = entity.getErrorCode();
        vo.model = entity.getModel();
        vo.promptTokens = entity.getPromptTokens();
        vo.completionTokens = entity.getCompletionTokens();
        vo.totalTokens = entity.getTotalTokens();
        vo.startedAt = entity.getStartedAt();
        vo.finishedAt = entity.getFinishedAt();
        vo.totalCostMillis = entity.getTotalCostMillis();
        vo.createdAt = entity.getCreatedAt();
        return vo;
    }

    public String getRunId() {
        return runId;
    }

    public String getUserId() {
        return userId;
    }

    public String getAgentType() {
        return agentType;
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public String getState() {
        return state;
    }

    public Integer getCurrentStep() {
        return currentStep;
    }

    public Integer getToolCallCount() {
        return toolCallCount;
    }

    public String getToolTrace() {
        return toolTrace;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getModel() {
        return model;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public Long getTotalCostMillis() {
        return totalCostMillis;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
