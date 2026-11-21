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
     * 所属会话；null 表示独立任务（历史记录保持原样，不强制迁移）。
     *
     * <p>不要把它当 runId 用：conversationId 跨多轮不变，runId 每轮新建。
     */
    private String conversationId;

    /**
     * Agent 类型：CHAT / MANUS / FILE / EVAL。
     * EVAL 仅用于离线评测；评测 Agent 不注入记录器，不会写入本表。
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
     * 工具名执行序列（逗号分隔），按实际执行顺序记录。
     *
     * 例如 {@code "web_search,web_search,web_scrape,terminate_task"}。
     * 只记录工具名，**不包含任何参数或结果原文**——
     * 搜索词、文件路径、密钥不会因此进入数据库。
     *
     * 数据来源：{@code AgentRunContext.toolTrace}，
     * 由 ToolCallAgent.act() 每执行完一个工具追加一次，
     * 失败的工具调用同样记录（模型看到了失败结果，也是轨迹的一部分）。
     */
    private String toolTrace;

    /**
     * 模型最终回答。
     */
    private String finalAnswer;

    /**
     * 脱敏后的失败原因摘要（不含堆栈）。
     */
    private String errorMessage;

    /**
     * 稳定原因码（M3）：前端只认码，不解析中文 error_message。
     * 例如 APPROVAL_EXPIRED / PROCESS_RESTART。
     */
    private String errorCode;

    /**
     * 执行代次：首次 0，每次审批恢复 +1。
     * 所有执行期写库都以它做 CAS，旧代次不能覆盖新代次。
     */
    private Integer executionEpoch;

    /**
     * 暂停时的 Agent 配置指纹（提示词 / 工具 / 参数 Schema / 审批标记）。
     * 恢复必须精确相等，否则拒绝恢复。
     */
    private String configVersion;

    /**
     * 累计执行时间（毫秒），不含审批等待。
     */
    private Long activeElapsedMillis;

    /**
     * 本任务创建过的审批数量（max-per-run 限制）。
     */
    private Integer approvalCount;

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

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
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

    public String getToolTrace() {
        return toolTrace;
    }

    public void setToolTrace(String toolTrace) {
        this.toolTrace = toolTrace;
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

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Integer getExecutionEpoch() {
        return executionEpoch;
    }

    public void setExecutionEpoch(Integer executionEpoch) {
        this.executionEpoch = executionEpoch;
    }

    public String getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(String configVersion) {
        this.configVersion = configVersion;
    }

    public Long getActiveElapsedMillis() {
        return activeElapsedMillis;
    }

    public void setActiveElapsedMillis(Long activeElapsedMillis) {
        this.activeElapsedMillis = activeElapsedMillis;
    }

    public Integer getApprovalCount() {
        return approvalCount;
    }

    public void setApprovalCount(Integer approvalCount) {
        this.approvalCount = approvalCount;
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
