package com.example.mkagent.approval;

import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.service.AgentRunRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 暂停写入（M3，批次 1）。
 *
 * 一个事务里做三件事：
 * 1. `agent_run` CAS 成 WAITING_APPROVAL（state=RUNNING 且 execution_epoch 匹配）；
 * 2. 覆盖写检查点；
 * 3. 插入审批记录（PENDING / PENDING_RESUME）。
 *
 * 为什么必须同事务：
 * - 只写审批不写检查点：恢复时没有消息与 pending calls，只能失败；
 * - 只写检查点不写审批：用户永远看不到待确认项，任务卡在 WAITING_APPROVAL。
 *
 * 为什么先做 CAS 再写两张表：
 * CAS 是最可能失败的一步（被取消/重复暂停），先失败先回滚，不产生垃圾行。
 */
@Service
public class ApprovalPauseService implements ApprovalPauseGateway {

    private static final Logger log =
            LoggerFactory.getLogger(ApprovalPauseService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final JdbcTemplate jdbcTemplate;

    private final AgentRunRecorder runRecorder;

    private final ApprovalProperties properties;

    public ApprovalPauseService(
            JdbcTemplate jdbcTemplate,
            AgentRunRecorder runRecorder,
            ApprovalProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.runRecorder = runRecorder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public PauseResult persist(
            AgentRunContext ctx,
            ApprovalCheckpoint checkpoint,
            ApprovalSummary summary
    ) {
        int rows = runRecorder.recordPause(ctx);
        if (rows != 1) {
            throw new PauseRejectedException(
                    "暂停失败：任务状态或执行代次已变化，runId=" + ctx.getRunId());
        }

        String runId = ctx.getRunId().toString();

        /*
         * 单任务审批次数上限（R4）：recordPause 已经把 approval_count +1，
         * 这里读回**递增后**的值来判断是否越界。
         *
         * 越界时抛异常让整个事务回滚——approval_count 复原、检查点不写、
         * 审批记录不插，然后由 ToolCallAgent 把任务判成 APPROVAL_LIMIT_REACHED。
         * 先写再判而不是"先查再写"：只有这样才能让并发暂停也受同一个上限约束。
         */
        Integer approvalCount = jdbcTemplate.queryForObject(
                "SELECT approval_count FROM agent_run WHERE run_id = ?",
                Integer.class, runId);

        if (approvalCount != null && approvalCount > properties.getMaxPerRun()) {
            throw new PauseLimitReachedException(
                    "审批次数已达上限：" + properties.getMaxPerRun()
                            + "，runId=" + runId);
        }

        /*
         * 恢复后再次暂停：旧审批的执行阶段到此结束（FINISHED），
         * 新审批紧接着以 PENDING 插入。
         *
         * 必须在同一个事务里做，否则会出现"旧审批还 RUNNING、新审批已 PENDING"
         * 的重叠窗口；也不能等到任务终态才收尾——run 此时仍是 WAITING_APPROVAL，
         * 永远等不到终态。
         */
        if (ctx.getResumeApprovalId() != null) {
            int finished = jdbcTemplate.update(
                    "UPDATE agent_approval "
                            + "SET resume_state = 'FINISHED', updated_at = CURRENT_TIMESTAMP "
                            + "WHERE approval_id = ? "
                            + "  AND resume_state IN ('RUNNING', 'CLAIMED')",
                    ctx.getResumeApprovalId()
            );
            log.info("再次暂停：上一条审批执行阶段已收尾：approvalId={}, rows={}",
                    ctx.getResumeApprovalId(), finished);
        }

        String payload = CheckpointCodec.encode(checkpoint);

        /*
         * 同一个 run 可能暂停多次（每次是新的 step），
         * 检查点按 runId 只保留最新一份：恢复只可能从"最后一次暂停点"继续。
         * 先删后插而不是 upsert：H2 与 PostgreSQL 的 upsert 语法不完全一致，
         * 而单实例下同一个 run 只有一个写者，删除+插入是安全的。
         */
        jdbcTemplate.update("DELETE FROM agent_checkpoint WHERE run_id = ?", runId);
        jdbcTemplate.update(
                "INSERT INTO agent_checkpoint "
                        + "(run_id, schema_version, config_version, payload_json, "
                        + " created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                runId,
                checkpoint.schemaVersion(),
                checkpoint.configVersion(),
                payload
        );

        String approvalId = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plus(properties.getTtl());
        String summaryJson = toJson(summary);

        jdbcTemplate.update(
                "INSERT INTO agent_approval "
                        + "(approval_id, run_id, user_id, agent_type, step, "
                        + " primary_tool, tool_count, args_hash, summary_text, summary_json, "
                        + " status, resume_state, resume_attempts, expires_at, "
                        + " created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + " 'PENDING', 'PENDING_RESUME', 0, ?, "
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                approvalId,
                runId,
                ctx.getUserId(),
                ctx.getAgentType() == null ? "UNKNOWN" : ctx.getAgentType().name(),
                ctx.getCurrentStep(),
                summary.tool(),
                summary.roundToolCount(),
                ApprovalHashes.roundHash(checkpoint.pendingToolCalls()),
                summary.text(),
                summaryJson,
                expiresAt
        );

        log.info(
                "审批已创建：approvalId={}, runId={}, step={}, tool={}, toolCount={}, expiresAt={}",
                approvalId, runId, ctx.getCurrentStep(),
                summary.tool(), summary.roundToolCount(), expiresAt
        );

        return new PauseResult(approvalId, buildEventJson(approvalId, expiresAt, summary));
    }

    @Override
    @Transactional
    public void abandon(String approvalId, String reason) {
        if (approvalId == null) {
            return;
        }

        int rows = jdbcTemplate.update(
                "UPDATE agent_approval "
                        + "SET status = 'CANCELLED', resume_state = 'FAILED', "
                        + "    updated_at = CURRENT_TIMESTAMP "
                        + "WHERE approval_id = ? AND status = 'PENDING'",
                approvalId
        );

        jdbcTemplate.update(
                "DELETE FROM agent_checkpoint WHERE run_id = "
                        + "(SELECT run_id FROM agent_approval WHERE approval_id = ?)",
                approvalId
        );

        log.warn("暂停被放弃：approvalId={}, rows={}, reason={}",
                approvalId, rows, reason);
    }

    /**
     * approval_required 事件的 data。
     *
     * 只含 approvalId、有效期、脱敏摘要：
     * 不含工具参数原文、userId、任何密钥。
     */
    private String buildEventJson(
            String approvalId,
            LocalDateTime expiresAt,
            ApprovalSummary summary
    ) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("approvalId", approvalId);
        root.put("expiresAt", TIME_FORMAT.format(expiresAt));
        root.set("summary", MAPPER.valueToTree(summary));
        return root.toString();
    }

    private String toJson(ApprovalSummary summary) {
        try {
            return MAPPER.writeValueAsString(summary);
        } catch (Exception e) {
            // 摘要字段都是字符串/数字，序列化失败属于编程错误；
            // 不因此阻断暂停，退化成只存文本。
            log.warn("审批摘要序列化失败，仅保留文本", e);
            return null;
        }
    }
}
