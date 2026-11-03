package com.example.mkagent.approval;

import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;

/**
 * 一条审批记录的快照（批次 2）。
 *
 * 只读投影，不承载业务逻辑：
 * 决定、领取、启动、补偿都基于"读到的这一份 + 条件更新"，
 * 不依赖任何内存状态，进程重启后行为一致。
 *
 * @param approvalId     审批 ID（对外唯一标识）
 * @param runId          所属任务
 * @param userId         归属用户（请求级归属校验的唯一依据）
 * @param agentType      Agent 类型（决定恢复哪个 Agent 实例）
 * @param step           暂停时的步数
 * @param primaryTool    主工具名（摘要展示）
 * @param toolCount      整轮待执行工具数
 * @param argsHash       整轮参数哈希（批准即绑定这组参数）
 * @param summaryText    用户可读摘要
 * @param status         决定状态：PENDING/APPROVED/REJECTED/CANCELLED/EXPIRED/INVALIDATED
 * @param resumeState    执行子状态：PENDING_RESUME/CLAIMED/RUNNING/FINISHED/FAILED
 * @param resumeAttempts 已失败的恢复尝试次数（不含"抢不到许可"）
 * @param claimedEpoch   领取时绑定的执行代次
 * @param decidedAt      决定时间（启动期限从这里算）
 * @param expiresAt      决定有效期（只约束 PENDING）
 */
public record ApprovalRecord(
        String approvalId,
        String runId,
        String userId,
        String agentType,
        int step,
        String primaryTool,
        int toolCount,
        String argsHash,
        String summaryText,
        String status,
        String resumeState,
        int resumeAttempts,
        Integer claimedEpoch,
        LocalDateTime decidedAt,
        LocalDateTime expiresAt
) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_INVALIDATED = "INVALIDATED";

    public static final String RESUME_PENDING = "PENDING_RESUME";
    public static final String RESUME_CLAIMED = "CLAIMED";
    public static final String RESUME_RUNNING = "RUNNING";
    public static final String RESUME_FINISHED = "FINISHED";
    public static final String RESUME_FAILED = "FAILED";

    public boolean isDecided() {
        return STATUS_APPROVED.equals(status) || STATUS_REJECTED.equals(status);
    }

    public boolean isApproved() {
        return STATUS_APPROVED.equals(status);
    }

    /**
     * 数据库行 → 记录。列名与 schema.sql 一致，不做任何推断。
     */
    public static RowMapper<ApprovalRecord> rowMapper() {
        return (rs, rowNum) -> new ApprovalRecord(
                rs.getString("approval_id"),
                rs.getString("run_id"),
                rs.getString("user_id"),
                rs.getString("agent_type"),
                rs.getInt("step"),
                rs.getString("primary_tool"),
                rs.getInt("tool_count"),
                rs.getString("args_hash"),
                rs.getString("summary_text"),
                rs.getString("status"),
                rs.getString("resume_state"),
                rs.getInt("resume_attempts"),
                (Integer) rs.getObject("claimed_epoch", Integer.class),
                rs.getTimestamp("decided_at") == null
                        ? null : rs.getTimestamp("decided_at").toLocalDateTime(),
                rs.getTimestamp("expires_at") == null
                        ? null : rs.getTimestamp("expires_at").toLocalDateTime()
        );
    }
}
