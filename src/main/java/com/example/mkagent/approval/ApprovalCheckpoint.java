package com.example.mkagent.approval;

import java.util.List;

/**
 * 审批暂停点的检查点（M3）。
 *
 * 恢复所需的全部信息都在这里，**不包含 system prompt 原文**：
 * 只存 {@link #configVersion()} 指纹，恢复时要求精确相等。
 *
 * 为什么必须单独存 {@link #pendingAssistant()}：
 * {@code ToolCallAgent.act()} 执行工具前，{@code ctx.messages} 里还没有本轮的
 * Assistant(toolCalls)——它由 {@code ToolCallingManager} 在执行时追加。
 * 只存 history 会让恢复后的 {@code toolCallId} 配对断裂，
 * 模型会看到"工具结果没有对应的调用请求"。
 *
 * 恢复时的正确用法（批准路径）：
 * 用 {@code messages} 构造 Prompt，把 {@code pendingAssistant} 包装成
 * {@code ChatResponse} 交给 {@code executeToolCalls}，由框架追加一次。
 * **不要**先把它 add 进 messages——那会导致 assistant 出现两次、
 * 工具结果与调用对不上（本项目的测试专门锁这条）。
 * 拒绝路径不执行工具，才由应用手动追加 assistant + 拒绝回执。
 *
 * @param schemaVersion   JSON 结构版本，当前 {@link #CURRENT_SCHEMA_VERSION}
 * @param configVersion   Agent 配置指纹（提示词/工具/参数 Schema/审批标记）
 * @param runId           任务 ID（恢复时不能重新生成）
 * @param userId          归属用户
 * @param agentType       Agent 类型
 * @param step            已消耗步数（暂停那一轮已计入，恢复不再 +1）
 * @param toolCallCount   已执行工具次数（暂停轮为 0 增量）
 * @param messages        本轮**之前**的历史（不含 pendingAssistant）
 * @param pendingAssistant 本轮模型返回的 Assistant 消息（文本 + toolCalls）
 * @param pendingToolCalls 本轮待执行的工具调用（整轮）
 * @param budget          任务预算快照
 * @param usage           模型 Usage 累计
 * @param timing          原始开始时间与累计执行时间
 * @param whitelistHash   暂停时白名单工具名集合哈希
 */
public record ApprovalCheckpoint(
        int schemaVersion,
        String configVersion,
        String runId,
        String userId,
        String agentType,
        int step,
        int toolCallCount,
        List<CheckpointMessage> messages,
        CheckpointMessage pendingAssistant,
        List<PendingToolCall> pendingToolCalls,
        BudgetSnapshot budget,
        UsageSnapshot usage,
        TimingSnapshot timing,
        String whitelistHash
) {

    /**
     * 当前检查点 JSON 结构版本。
     *
     * 结构变化（增删字段、改语义）时必须 +1；
     * 恢复端遇到不认识的版本直接拒绝，不做"尽力兼容"。
     */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * 一条消息的可序列化形式。
     *
     * 不直接序列化 Spring AI 的 Message：{@code AssistantMessage} /
     * {@code ToolResponseMessage} 没有 {@code @JsonCreator}、没有无参构造，
     * Jackson 反序列化必失败（已用 javap 核对过注解）。
     *
     * @param role           system / user / assistant / tool
     * @param text           文本内容（tool 消息为 null）
     * @param toolCalls      仅 assistant 有值
     * @param toolResponses  仅 tool 有值
     */
    public record CheckpointMessage(
            String role,
            String text,
            List<PendingToolCall> toolCalls,
            List<ToolResponseItem> toolResponses
    ) {
    }

    /**
     * 一条待执行（或已执行）的工具调用。
     *
     * @param id         toolCallId，必须与模型返回的完全一致
     * @param type       通常为 "function"
     * @param name       工具名
     * @param arguments  参数 JSON 原文（执行需要；不进入摘要、不下发前端）
     * @param argsHash   参数规范化哈希，批准即绑定这组参数
     */
    public record PendingToolCall(
            String id,
            String type,
            String name,
            String arguments,
            String argsHash
    ) {
    }

    /**
     * 一条工具结果（仅用于把历史消息完整往返）。
     */
    public record ToolResponseItem(
            String id,
            String name,
            String responseData
    ) {
    }

    /**
     * 任务预算快照。
     *
     * 恢复必须用快照，不能换成"当前 Bean 配置"：
     * 否则运维改一次 max-steps，已暂停任务的剩余额度就被悄悄改了。
     */
    public record BudgetSnapshot(
            int maxSteps,
            int maxToolCalls,
            long timeoutSeconds
    ) {
    }

    /**
     * 模型 Usage 累计快照（恢复后继续累加，不重新计数）。
     */
    public record UsageSnapshot(
            String model,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            int modelCallCount,
            long totalModelCallMillis
    ) {
    }

    /**
     * 时间快照：原始开始时间（展示）+ 累计执行时间（预算计算）。
     *
     * 审批等待不计入 activeElapsedMillis。
     */
    public record TimingSnapshot(
            String originalStartedAt,
            long activeElapsedMillis
    ) {
    }
}
