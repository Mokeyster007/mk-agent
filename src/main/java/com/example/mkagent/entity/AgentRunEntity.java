package com.example.mkagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * agent_run 表实体：一条 Agent 任务运行记录。
 *
 * 与 db/schema.sql 中的 agent_run 表一一对应。
 * 字段命名使用驼峰，MyBatis-Plus 默认映射到下划线列名。
 *
 * 安全约束：
 * 不保存 API Key、完整系统提示词、Cookie、敏感工具原始结果；
 * userPrompt 只保存用户输入，errorMessage 只保存脱敏摘要。
 */
@TableName("agent_run")
public class AgentRunEntity {

    /**
     * 数据库主键：MyBatis-Plus 雪花算法生成，不依赖数据库自增。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 任务业务唯一键（AgentRunContext 生成的 UUID），唯一索引。
     */
    private String runId;

    /**
     * 任务归属用户（当前来自请求头 X-User-Id，占位方案）。
     */
    private String userId;

    /**
     * Agent 类型：CHAT / MANUS / FILE。
     */
    private String agentType;

    /**
     * 用户输入的任务（不保存系统提示词）。
     */
    private String userPrompt;

    /**
     * 任务状态：AgentState 枚举名。
     */
    private String state;

    /**
     * 已执行的 Agent Loop 轮数。
     */
    private Integer currentStep;

    /**
     * 累计工具调用次数。
     */
    private Integer toolCallCount;

    /**
     * 模型最终回答。
     */
    private String finalAnswer;

    /**
     * 脱敏后的失败原因摘要（不含堆栈）。
     */
    private String errorMessage;

    /**
     * 本次任务实际使用的模型名（来自模型响应 metadata）。
     */
    private String model;

    /**
     * 累计输入 Token 数（模型未返回 usage 时为 null）。
     */
    private Long promptTokens;

    /**
     * 累计输出 Token 数。
     */
    private Long completionTokens;

    /**
     * 累计总 Token 数（成本统计主键）。
     */
    private Long totalTokens;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long totalCostMillis;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAgentType() {
        return agentType;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public void setUserPrompt(String userPrompt) {
        this.userPrompt = userPrompt;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Integer getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(Integer currentStep) {
        this.currentStep = currentStep;
    }

    public Integer getToolCallCount() {
        return toolCallCount;
    }

    public void setToolCallCount(Integer toolCallCount) {
        this.toolCallCount = toolCallCount;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Long getTotalCostMillis() {
        return totalCostMillis;
    }

    public void setTotalCostMillis(Long totalCostMillis) {
        this.totalCostMillis = totalCostMillis;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
