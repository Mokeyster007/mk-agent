package com.example.mkagent.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

/**
 * 审批查询视图（批次 2）。
 *
 * 只含**摘要与状态**：
 * - 不含检查点原文（消息历史、pending tool calls、参数原文）；
 * - 不含 userId 以外的任何身份信息；
 * - 不返回 args_hash 之外的参数细节（哈希本身不可逆，仅用于前端比对"是不是同一组参数"）。
 *
 * 恢复流的进度与结果一律以 {@code agent_run} 为准，
 * 这个视图只回答"这条审批现在是什么状态"。
 */
public record ApprovalView(
        String approvalId,
        String runId,
        String agentType,
        int step,
        String primaryTool,
        int toolCount,
        String summaryText,
        JsonNode summary,
        String status,
        String resumeState,
        int resumeAttempts,
        LocalDateTime decidedAt,
        LocalDateTime expiresAt
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ApprovalView of(ApprovalRecord record, String summaryJson) {
        return new ApprovalView(
                record.approvalId(),
                record.runId(),
                record.agentType(),
                record.step(),
                record.primaryTool(),
                record.toolCount(),
                record.summaryText(),
                parse(summaryJson),
                record.status(),
                record.resumeState(),
                record.resumeAttempts(),
                record.decidedAt(),
                record.expiresAt()
        );
    }

    private static JsonNode parse(String summaryJson) {
        if (summaryJson == null || summaryJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(summaryJson);
        } catch (Exception e) {
            // 摘要只是展示字段，解析失败不该让查询接口 500
            return null;
        }
    }
}
