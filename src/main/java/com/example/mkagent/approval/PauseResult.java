package com.example.mkagent.approval;

/**
 * 暂停结果：审批 ID + 已经渲染好的 approval_required 事件 data。
 *
 * 事件 JSON 由暂停服务生成而不是由 BaseAgent 拼装，
 * 避免"事件里的摘要"和"库里的摘要"各写一份、慢慢走偏。
 *
 * @param approvalId 审批记录 ID
 * @param eventJson  approval_required 事件的 data（已脱敏）
 */
public record PauseResult(
        String approvalId,
        String eventJson
) {
}
