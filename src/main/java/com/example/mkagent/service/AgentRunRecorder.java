package com.example.mkagent.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.mkagent.entity.AgentRunEntity;
import com.example.mkagent.mapper.AgentRunMapper;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * AgentRun 生命周期记录器：把 Agent 任务关键节点写入 agent_run 表。
 *
 * 核心原则（与 Agent 主流程解耦）：
 * 1. 所有方法内部捕获全部异常并记录日志，
 *    数据库更新失败绝不中断 Agent 主流程；
 * 2. 写入时机只有三类，不写高频数据：
 *    - recordStart：任务开始（一次）
 *    - recordProgress：每完成一轮 step（每轮一次，不是每个 token）
 *    - recordFinish：任务终态（一次）
 * 3. 不保存 API Key、系统提示词、Cookie、敏感工具原始结果；
 *    userPrompt 只保存用户输入，errorMessage 只保存脱敏摘要。
 */
@Component
public class AgentRunRecorder {

    private static final Logger log =
            LoggerFactory.getLogger(AgentRunRecorder.class);

    /**
     * 长文本截断长度，防止异常输入撑爆存储。
     */
    private static final int MAX_PROMPT_LENGTH = 4000;

    private static final int MAX_FINAL_ANSWER_LENGTH = 20000;

    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;

    private final AgentRunMapper agentRunMapper;

    public AgentRunRecorder(AgentRunMapper agentRunMapper) {
        this.agentRunMapper = agentRunMapper;
    }

    /**
     * 任务开始：创建 RUNNING 记录。
     */
    public void recordStart(AgentRunContext ctx, String userPrompt) {
        try {
            LocalDateTime now = LocalDateTime.now();

            AgentRunEntity entity = new AgentRunEntity();
            entity.setRunId(ctx.getRunId().toString());
            entity.setUserId(ctx.getUserId());
            entity.setAgentType(agentTypeName(ctx.getAgentType()));
            entity.setUserPrompt(truncate(userPrompt, MAX_PROMPT_LENGTH));
            entity.setState(ctx.getState().name());
            entity.setCurrentStep(0);
            entity.setToolCallCount(0);
            entity.setStartedAt(now);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);

            agentRunMapper.insert(entity);

            log.info("AgentRun 已持久化：runId={}, userId={}, state=RUNNING",
                    ctx.getRunId(), ctx.getUserId());
        } catch (Exception e) {
            log.error("AgentRun 创建记录持久化失败（不影响任务主流程）：runId={}",
                    ctx.getRunId(), e);
        }
    }

    /**
     * 每完成一轮 step：同步 current_step / tool_call_count / state。
     *
     * 注意：只在每轮 step 结束后调用一次，
     * 绝不在 token / chunk 级别调用，避免高频写库。
     */
    public void recordProgress(AgentRunContext ctx) {
        try {
            LambdaUpdateWrapper<AgentRunEntity> wrapper =
                    new LambdaUpdateWrapper<AgentRunEntity>()
                            .eq(AgentRunEntity::getRunId, ctx.getRunId().toString())
                            .set(AgentRunEntity::getState, ctx.getState().name())
                            .set(AgentRunEntity::getCurrentStep, ctx.getCurrentStep())
                            .set(AgentRunEntity::getToolCallCount, ctx.getToolCallCount())
                            .set(AgentRunEntity::getUpdatedAt, LocalDateTime.now());

            agentRunMapper.update(null, wrapper);
        } catch (Exception e) {
            log.error("AgentRun 进度更新持久化失败（不影响任务主流程）：runId={}, step={}",
                    ctx.getRunId(), ctx.getCurrentStep(), e);
        }
    }

    /**
     * 任务终态：写入最终状态、结果与耗时。
     *
     * 由任务线程在 finally 中调用（单点写入，无并发冲突）：
     * 成功 → SUCCEEDED + finalAnswer；
     * 失败 → FAILED + 脱敏 errorMessage；
     * 取消 / 超时 / 达到预算 → 对应终态。
     *
     * @param errorMessage    脱敏后的失败原因（成功时传 null）
     * @param totalCostMillis 任务总耗时
     */
    public void recordFinish(
            AgentRunContext ctx,
            String errorMessage,
            long totalCostMillis
    ) {
        try {
            String finalAnswer = truncate(
                    ctx.getFinalAnswer(), MAX_FINAL_ANSWER_LENGTH);
            String safeErrorMessage = truncate(
                    errorMessage, MAX_ERROR_MESSAGE_LENGTH);

            LambdaUpdateWrapper<AgentRunEntity> wrapper =
                    new LambdaUpdateWrapper<AgentRunEntity>()
                            .eq(AgentRunEntity::getRunId, ctx.getRunId().toString())
                            .set(AgentRunEntity::getState, ctx.getState().name())
                            .set(AgentRunEntity::getCurrentStep, ctx.getCurrentStep())
                            .set(AgentRunEntity::getToolCallCount, ctx.getToolCallCount())
                            .set(finalAnswer != null,
                                    AgentRunEntity::getFinalAnswer, finalAnswer)
                            .set(safeErrorMessage != null,
                                    AgentRunEntity::getErrorMessage, safeErrorMessage)
                            /*
                             * 模型 Usage（成本观测）：
                             * 模型未返回 usage 时 totalTokens 为 0，
                             * 此时不写 Token 字段，库中保持 NULL，
                             * 区分“未提供”与“真实为 0”。
                             */
                            .set(ctx.getModel() != null,
                                    AgentRunEntity::getModel, ctx.getModel())
                            .set(ctx.getTotalTokens() > 0,
                                    AgentRunEntity::getPromptTokens,
                                    ctx.getPromptTokens())
                            .set(ctx.getTotalTokens() > 0,
                                    AgentRunEntity::getCompletionTokens,
                                    ctx.getCompletionTokens())
                            .set(ctx.getTotalTokens() > 0,
                                    AgentRunEntity::getTotalTokens,
                                    ctx.getTotalTokens())
                            .set(AgentRunEntity::getFinishedAt, LocalDateTime.now())
                            .set(AgentRunEntity::getTotalCostMillis, totalCostMillis)
                            .set(AgentRunEntity::getUpdatedAt, LocalDateTime.now());

            agentRunMapper.update(null, wrapper);

            log.info(
                    "AgentRun 终态已持久化：runId={}, state={}, cost={}ms, model={}, totalTokens={}, modelCalls={}({}ms)",
                    ctx.getRunId(), ctx.getState(), totalCostMillis,
                    ctx.getModel(), ctx.getTotalTokens(),
                    ctx.getModelCallCount(), ctx.getTotalModelCallMillis()
            );
        } catch (Exception e) {
            log.error("AgentRun 终态更新持久化失败（不影响任务主流程）：runId={}, state={}",
                    ctx.getRunId(), ctx.getState(), e);
        }
    }

    private String agentTypeName(AgentType agentType) {
        return agentType == null ? "UNKNOWN" : agentType.name();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
