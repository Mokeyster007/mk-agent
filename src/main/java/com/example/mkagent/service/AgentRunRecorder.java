package com.example.mkagent.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.mkagent.entity.AgentRunEntity;
import com.example.mkagent.mapper.AgentRunMapper;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.AgentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AgentRun 生命周期记录器：把 Agent 任务关键节点写入 agent_run 表。
 *
 * 核心原则（与 Agent 主流程解耦）：
 * 1. 所有方法内部捕获全部异常并记录日志，
 *    数据库更新失败绝不中断 Agent 主流程；
 * 2. 写入时机只有四类，不写高频数据：
 *    - recordStart：任务开始（一次）
 *    - recordProgress：每完成一轮 step（每轮一次，不是每个 token）
 *    - recordPause：审批暂停（每次暂停一次，不写 finished_at）
 *    - recordFinish：任务终态（一次）
 * 3. 不保存 API Key、系统提示词、Cookie、敏感工具原始结果；
 *    userPrompt 只保存用户输入，errorMessage 只保存脱敏摘要；
 *    toolTrace 只保存工具名序列（不含参数与结果原文）。
 *
 * 代次（execution_epoch）：
 * 除 recordStart 外，所有更新都带 `execution_epoch = 当前代次` 条件。
 * 审批恢复会给同一个 runId 开新代次，旧执行线程的迟到写入因此影响 0 行，
 * 不会把新代次的 RUNNING 覆盖成旧终态。
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

    /**
     * 工具轨迹最大长度。
     *
     * 正常轨迹很短（maxToolCalls 默认 12 个工具名 ≈ 200 字符），
     * 这里的上限只是防御：万一将来放开工具次数，
     * 也不会让一行 TEXT 无限增长。
     */
    private static final int MAX_TOOL_TRACE_LENGTH = 2000;

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
            entity.setExecutionEpoch(ctx.getExecutionEpoch());
            entity.setActiveElapsedMillis(0L);
            entity.setApprovalCount(0);

            agentRunMapper.insert(entity);

            log.info("AgentRun 已持久化：runId={}, userId={}, state=RUNNING",
                    ctx.getRunId(), ctx.getUserId());
        } catch (Exception e) {
            log.error("AgentRun 创建记录持久化失败（不影响任务主流程）：runId={}",
                    ctx.getRunId(), e);
        }
    }

    /**
     * 每完成一轮 step：同步 current_step / tool_call_count / tool_trace / state。
     *
     * 注意：只在每轮 step 结束后调用一次，
     * 绝不在 token / chunk 级别调用，避免高频写库。
     */
    public void recordProgress(AgentRunContext ctx) {
        /*
         * 暂停不是终态，进度写入会先把 state 改成 WAITING_APPROVAL，
         * 但此时检查点还没提交——一旦进程在中间崩溃，
         * 库里就是"等待审批但没有审批记录"的坏状态。
         * 暂停状态由 recordPause 与检查点在同一事务里写。
         */
        if (ctx.getState() == AgentState.WAITING_APPROVAL) {
            return;
        }

        try {
            String toolTrace = toolTraceOf(ctx);

            LambdaUpdateWrapper<AgentRunEntity> wrapper =
                    new LambdaUpdateWrapper<AgentRunEntity>()
                            .eq(AgentRunEntity::getRunId, ctx.getRunId().toString())
                            .eq(AgentRunEntity::getExecutionEpoch, ctx.getExecutionEpoch())
                            .set(AgentRunEntity::getState, ctx.getState().name())
                            .set(AgentRunEntity::getCurrentStep, ctx.getCurrentStep())
                            .set(AgentRunEntity::getToolCallCount, ctx.getToolCallCount())
                            /*
                             * 轨迹为空时不写：保持 NULL，
                             * 与"还没调用过工具"语义一致（区分空串与未发生）。
                             */
                            .set(toolTrace != null,
                                    AgentRunEntity::getToolTrace, toolTrace)
                            .set(AgentRunEntity::getUpdatedAt, LocalDateTime.now());

            agentRunMapper.update(null, wrapper);
        } catch (Exception e) {
            log.error("AgentRun 进度更新持久化失败（不影响任务主流程）：runId={}, step={}",
                    ctx.getRunId(), ctx.getCurrentStep(), e);
        }
    }

    /**
     * 审批暂停：把任务标为 WAITING_APPROVAL，**不写 finished_at**。
     *
     * 由 {@code ApprovalPauseService} 在暂停事务内调用，
     * 与"写检查点 + 写审批记录"同事务提交。
     *
     * 为什么必须 CAS：
     * 暂停期间任务可能刚好被取消（ctx 已转 CANCELLED）。
     * 只有"数据库仍是 RUNNING 且代次匹配"时才允许改成 WAITING_APPROVAL，
     * 否则应放弃暂停，不能留下一条没人能处理的审批记录。
     *
     * @return 受影响行数；1 表示暂停成功，0 表示状态已被他人改变
     * @throws IllegalStateException 数据库写入失败
     *         （本方法是"记录器不抛异常"约定的唯一例外：
     *         暂停必须与检查点同事务，失败就不能继续，
     *         否则会留下"任务已暂停但没有检查点"的坏状态）
     */
    public int recordPause(AgentRunContext ctx) {
        try {
            LambdaUpdateWrapper<AgentRunEntity> wrapper =
                    new LambdaUpdateWrapper<AgentRunEntity>()
                            .eq(AgentRunEntity::getRunId, ctx.getRunId().toString())
                            .eq(AgentRunEntity::getState, AgentState.RUNNING.name())
                            .eq(AgentRunEntity::getExecutionEpoch, ctx.getExecutionEpoch())
                            .set(AgentRunEntity::getState, AgentState.WAITING_APPROVAL.name())
                            .set(AgentRunEntity::getCurrentStep, ctx.getCurrentStep())
                            .set(AgentRunEntity::getToolCallCount, ctx.getToolCallCount())
                            .set(AgentRunEntity::getActiveElapsedMillis,
                                    ctx.getActiveElapsedMillis())
                            .set(ctx.getConfigVersion() != null,
                                    AgentRunEntity::getConfigVersion,
                                    ctx.getConfigVersion())
                            /*
                             * 只在创建新审批时 +1（R4）。
                             * 拒绝/重复请求/恢复重试都不会走到这里，
                             * 所以不会重复计数。
                             */
                            .setSql("approval_count = approval_count + 1")
                            .set(AgentRunEntity::getUpdatedAt, LocalDateTime.now());

            int rows = agentRunMapper.update(null, wrapper);

            if (rows == 1) {
                log.info(
                        "AgentRun 已暂停：runId={}, state=WAITING_APPROVAL, step={}, epoch={}, activeElapsed={}ms",
                        ctx.getRunId(), ctx.getCurrentStep(), ctx.getExecutionEpoch(),
                        ctx.getActiveElapsedMillis()
                );
            } else {
                log.warn(
                        "暂停 CAS 未命中（状态或代次已变化）：runId={}, epoch={}, rows={}",
                        ctx.getRunId(), ctx.getExecutionEpoch(), rows
                );
            }
            return rows;
        } catch (Exception e) {
            log.error("AgentRun 暂停持久化失败：runId={}", ctx.getRunId(), e);
            throw new IllegalStateException("暂停持久化失败", e);
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
        /*
         * 暂停不是终态：不能写 finished_at、不能写 final_answer。
         * 暂停行由 recordPause 写，恢复后会以新代次重新走终态写入。
         */
        if (ctx.getState() == AgentState.WAITING_APPROVAL) {
            log.info("跳过终态写入（任务处于 WAITING_APPROVAL）：runId={}", ctx.getRunId());
            return;
        }

        try {
            String finalAnswer = truncate(
                    ctx.getFinalAnswer(), MAX_FINAL_ANSWER_LENGTH);
            String safeErrorMessage = truncate(
                    errorMessage, MAX_ERROR_MESSAGE_LENGTH);
            String toolTrace = toolTraceOf(ctx);

            LambdaUpdateWrapper<AgentRunEntity> wrapper =
                    new LambdaUpdateWrapper<AgentRunEntity>()
                            .eq(AgentRunEntity::getRunId, ctx.getRunId().toString())
                            .eq(AgentRunEntity::getExecutionEpoch, ctx.getExecutionEpoch())
                            .set(AgentRunEntity::getState, ctx.getState().name())
                            .set(AgentRunEntity::getCurrentStep, ctx.getCurrentStep())
                            .set(AgentRunEntity::getToolCallCount, ctx.getToolCallCount())
                            /*
                             * 终态也写一次轨迹：最后一轮 step 之后不会再触发
                             * recordProgress，漏写会丢掉"最后一个工具"。
                             */
                            .set(toolTrace != null,
                                    AgentRunEntity::getToolTrace, toolTrace)
                            .set(finalAnswer != null,
                                    AgentRunEntity::getFinalAnswer, finalAnswer)
                            .set(safeErrorMessage != null,
                                    AgentRunEntity::getErrorMessage, safeErrorMessage)
                            .set(ctx.getErrorCode() != null,
                                    AgentRunEntity::getErrorCode, ctx.getErrorCode())
                            .set(AgentRunEntity::getActiveElapsedMillis,
                                    ctx.getActiveElapsedMillis())
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

    /**
     * 把 ctx 上的工具轨迹拼成逗号分隔的一行。
     *
     * 数据源就是 {@code AgentRunContext.toolTrace}（只含工具名），
     * 因此这里不可能泄露参数或结果原文。
     *
     * @return 轨迹为空时返回 null（调用方据此不写列，保持 NULL）
     */
    private String toolTraceOf(AgentRunContext ctx) {
        List<String> trace = ctx.getToolTrace();
        if (trace == null || trace.isEmpty()) {
            return null;
        }
        return truncate(String.join(",", trace), MAX_TOOL_TRACE_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
