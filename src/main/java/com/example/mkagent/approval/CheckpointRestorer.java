package com.example.mkagent.approval;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.AgentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 检查点 → AgentRunContext（批次 2）。
 *
 * 三条硬约束：
 * 1. runId 原样复用，绝不重新生成（否则幂等键、审批、运行记录三者的关联会断）；
 * 2. 步数、工具次数、消息历史、用量原样接上，恢复不是"重新开始"；
 * 3. 超时预算用检查点快照，不读当前 Bean 配置——
 *    运维改一次 max-steps 不应该让已暂停任务的剩余额度变样。
 */
public final class CheckpointRestorer {

    private static final Logger log =
            LoggerFactory.getLogger(CheckpointRestorer.class);

    private CheckpointRestorer() {
    }

    /**
     * 构建恢复用的上下文。
     *
     * 返回的 ctx 状态为 RUNNING、执行段已开启；
     * 调用方（{@code BaseAgent.resume}）负责获取/移交并发许可与登记句柄。
     */
    public static AgentRunContext restore(
            ApprovalRecord record,
            ApprovalCheckpoint checkpoint,
            int executionEpoch
    ) {
        Instant startedAt = parseInstant(checkpoint.timing().originalStartedAt());
        Duration activeElapsed = Duration.ofMillis(checkpoint.timing().activeElapsedMillis());
        Duration timeoutBudget = Duration.ofSeconds(checkpoint.budget().timeoutSeconds());

        AgentRunContext ctx = AgentRunContext.restore(
                UUID.fromString(record.runId()),
                startedAt,
                activeElapsed,
                timeoutBudget,
                executionEpoch
        );

        List<Message> history = CheckpointCodec.toMessages(checkpoint.messages());

        ctx.restoreProgress(
                checkpoint.step(),
                checkpoint.toolCallCount(),
                history,
                checkpoint.usage().model(),
                checkpoint.usage().promptTokens(),
                checkpoint.usage().completionTokens(),
                checkpoint.usage().totalTokens(),
                checkpoint.usage().modelCallCount(),
                checkpoint.usage().totalModelCallMillis()
        );

        ctx.setUserId(record.userId());
        ctx.setAgentType(parseAgentType(record.agentType()));
        ctx.setConfigVersion(checkpoint.configVersion());
        ctx.setApprovalId(null);
        ctx.setPendingApprovalJson(null);
        ctx.setCheckpoint(checkpoint);
        ctx.setResumeApprovalId(record.approvalId());
        ctx.setResumeMode(record.status());

        ctx.transitionTo(AgentState.RUNNING);

        log.info("恢复上下文已构建：runId={}, step={}, toolCallCount={}, history={}, epoch={}",
                record.runId(), checkpoint.step(), checkpoint.toolCallCount(),
                history.size(), executionEpoch);

        return ctx;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            // 展示字段，解析失败不该阻断恢复
            return Instant.now();
        }
    }

    private static AgentType parseAgentType(String value) {
        try {
            return AgentType.valueOf(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
