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

    private String finalAnswer;

    private String errorMessage;

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
        vo.finalAnswer = entity.getFinalAnswer();
        vo.errorMessage = entity.getErrorMessage();
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

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public String getErrorMessage() {
        return errorMessage;
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
