package com.example.mkagent.tools;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.music.IdempotencyKeys;
import com.example.mkagent.music.IdempotencyReplayService;
import com.example.mkagent.music.IdempotentWriteExecutor;
import com.example.mkagent.music.IdempotentWriteExecutor.BusinessEnvelopeFailure;
import com.example.mkagent.music.IdempotentWriteExecutor.Call;
import com.example.mkagent.music.IdempotentWriteExecutor.IdempotencyRecordConflictException;
import com.example.mkagent.music.IdempotentWriteExecutor.WriteRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 同库写工具的幂等装饰器。
 *
 * <h2>它解决什么</h2>
 *
 * 一次逻辑写调用（模型给出的一条 toolCall）只能产生一次业务副作用。
 * 重复的来源有三种：模型重复请求、恢复后重跑暂停轮、并发/重试。
 * 这里给每次调用算一个稳定身份，交给 {@link IdempotentWriteExecutor}
 * 在**一个事务**里完成"占位 → 业务 → 结果"，
 * 重复到达时由唯一索引裁决并回放已提交结果。
 *
 * <h2>身份从哪里来</h2>
 *
 * {@code ToolCallback} 拿不到 toolCallId（spring-ai-model 1.0.0 的接口签名
 * 只有 {@code call(String)} / {@code call(String, ToolContext)}），
 * 因此由 {@code ToolCallAgent} 在执行前把整轮 ToolCall 登记到
 * {@link ToolCallPlanHolder}，这里按顺序认领。认领不到就失败关闭：
 * 宁可不执行，也不能把副作用记到错误的身份上。
 *
 * <h2>错误契约</h2>
 *
 * - 幂等相关的失败（身份不可判定、身份冲突、记录不可回放、无访问权、
 *   任务代次失效、锁等待超时、事务未能提交）→ 返回**脱敏错误信封**，
 *   不抛异常，模型能看到"这次没做成"并自行决定下一步；
 * - 业务自己返回的失败信封 → 原样返回，文案与未包装时完全一致；
 * - 其它异常（包括业务代码抛出的运行时异常）→ 原样向上抛，
 *   保持"工具异常会让本次任务失败"的既有契约，不在这里吞掉。
 */
public final class IdempotentToolCallback implements ToolCallback {

    private static final Logger log =
            LoggerFactory.getLogger(IdempotentToolCallback.class);

    private final ToolCallback delegate;

    private final IdempotentWriteExecutor executor;

    private final IdempotencyReplayService replayService;

    private final String toolName;

    public IdempotentToolCallback(
            ToolCallback delegate,
            IdempotentWriteExecutor executor,
            IdempotencyReplayService replayService
    ) {
        this.delegate = delegate;
        this.executor = executor;
        this.replayService = replayService;
        this.toolName = delegate.getToolDefinition().name();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return callIdempotent(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return callIdempotent(toolInput, toolContext);
    }

    /**
     * 幂等调用主流程。
     */
    private String callIdempotent(String toolInput, ToolContext toolContext) {
        // 缺少上下文属于编程错误：与工具内部 requireUserId() 一样失败关闭。
        String userId = AgentRunContextHolder.requireUserId();
        AgentRunContext ctx = AgentRunContextHolder.current();

        ToolCallPlan.PlannedCall planned;
        try {
            ToolCallPlan plan = ToolCallPlanHolder.current();
            if (plan == null) {
                throw new ToolCallPlan.ToolCallPlanException(ToolCallPlan.REASON_NOT_BOUND);
            }
            planned = plan.claim(toolName, toolInput);
        } catch (ToolCallPlan.ToolCallPlanException e) {
            /*
             * 身份不可判定（缺 id / 重复 id / 参数与计划不符）：
             * 一个字节都不写，直接给模型一个稳定原因。
             */
            log.warn("写工具身份不可判定，已拒绝执行：tool={}, reason={}",
                    toolName, e.getMessage());
            return ToolResponseEnvelope.error(e.getMessage());
        }

        /*
         * 幂等执行器依赖"冲突即整体回滚、然后换新事务读赢家结果"。
         * 如果调用方已经在一个事务里，这个前提不成立（隔离级别可能看不到赢家的提交），
         * 显式拒绝，而不是悄悄降级成"可能读不到"。
         */
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "幂等写工具不得在已有事务中执行：" + toolName);
        }

        Call call = new Call(
                userId,
                ctx.getRunId(),
                ctx.getCurrentStep(),
                planned.toolCallId(),
                toolName,
                IdempotencyKeys.callKey(
                        userId, ctx.getRunId(), ctx.getCurrentStep(), planned.toolCallId()),
                IdempotencyKeys.requestHash(toolName, toolInput),
                ctx.getExecutionEpoch(),
                () -> invoke(toolInput, toolContext)
        );

        try {
            return executor.execute(call);
        } catch (BusinessEnvelopeFailure e) {
            // 业务失败：占位行已随事务回滚，原始信封原样返回（文案不变）。
            return e.getEnvelope();
        } catch (WriteRejectedException e) {
            return ToolResponseEnvelope.error(e.getMessage());
        } catch (IdempotencyRecordConflictException e) {
            return replay(call);
        } catch (PessimisticLockingFailureException | QueryTimeoutException e) {
            /*
             * 唯一索引上的等待超时：事务已回滚，业务没有发生。
             * 不自动重试、不换新身份——让模型自己决定要不要再来一次。
             */
            log.warn("写工具等待超时：tool={}, runId={}, reason={}",
                    toolName, ctx.getRunId(), e.getClass().getSimpleName());
            return ToolResponseEnvelope.error("该操作正在处理中，请稍后重试");
        } catch (UnexpectedRollbackException e) {
            /*
             * 内层把事务标成了 rollback-only：提交必然失败。
             * 这里如实告诉模型"没有提交"，而不是假装成功。
             */
            log.warn("写工具事务未能提交：tool={}, runId={}", toolName, ctx.getRunId());
            return ToolResponseEnvelope.error("本次操作未能提交，请稍后重试");
        }
    }

    /**
     * 冲突后读取已提交记录并回放。
     */
    private String replay(Call call) {
        try {
            return replayService.replay(call);
        } catch (IdempotencyReplayService.IdempotencyConflictException e) {
            return ToolResponseEnvelope.error(
                    "本次调用与此前记录的请求不一致，已拒绝执行");
        } catch (IdempotencyReplayService.IdempotencyReplayInvalidException e) {
            return ToolResponseEnvelope.error(
                    "此前同类操作的结果无法确认，已拒绝重复执行");
        } catch (IdempotencyReplayService.IdempotencyReplayDeniedException e) {
            return ToolResponseEnvelope.error(
                    "该操作对应的资源已不可访问（历史操作未被撤销）");
        }
    }

    private String invoke(String toolInput, ToolContext toolContext) {
        return toolContext == null
                ? delegate.call(toolInput)
                : delegate.call(toolInput, toolContext);
    }
}
