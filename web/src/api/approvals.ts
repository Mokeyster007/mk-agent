/**
 * 审批接口。
 *
 *   GET  /api/ai/approvals/{approvalId}          查询状态与脱敏摘要
 *   POST /api/ai/approvals/{approvalId}/approve  批准 → 落决定 → 尝试恢复 → 续流
 *   POST /api/ai/approvals/{approvalId}/reject   拒绝 → 落决定 → 回填拒绝 → 续流
 *   POST /api/ai/approvals/{approvalId}/resume   仅恢复（决定已保存但恢复没起来时用）
 *
 * 决定接口**只接受 approvalId**，不读请求体：
 * 审批绑定的是"暂停那一刻的工具与参数哈希"，
 * 允许客户端在决定时再传参数就等于绕过哈希校验（批准 A、执行 B）。
 * 所以前端也绝不能构造参数、不能编辑关键字段、不能自造幂等键。
 */
import { requestJson } from './client'
import { streamEvents } from './sse'
import type { AgentEvent, ApprovalView } from './types'
import { parseEvent } from './agentEvents'

export function getApproval(approvalId: string, signal?: AbortSignal): Promise<ApprovalView> {
  return requestJson<ApprovalView>(`/api/ai/approvals/${encodeURIComponent(approvalId)}`, { signal })
}

export interface DecideOptions {
  signal: AbortSignal
  onEvent: (event: AgentEvent) => void
}

/**
 * 提交审批决定并接收续流。
 *
 * 注意区分两种失败：
 * 1. 决定没落库（409/410/404）→ 什么都不该变；
 * 2. 决定已落库、恢复没起来（409 RESUME_*、429）→ 决定保留，
 *    用户可以点"手动恢复"重试，**不需要也不允许重新发起任务**。
 * 前端无法从 HTTP 状态区分这两者，所以失败后一律重新查询审批视图，
 * 用 status / resumeState 这两个真实字段判断，而不是猜。
 */
export async function decideApproval(
  approvalId: string,
  approve: boolean,
  options: DecideOptions,
): Promise<void> {
  const action = approve ? 'approve' : 'reject'
  await streamEvents(
    `/api/ai/approvals/${encodeURIComponent(approvalId)}/${action}`,
    { method: 'POST', signal: options.signal },
    (frame) => {
      const event = parseEvent(frame.data)
      if (event) {
        options.onEvent(event)
      }
    },
  )
}

/** 仅恢复执行（决定已存在）。必须使用原 approvalId，不能重发原 prompt。 */
export async function resumeApproval(
  approvalId: string,
  options: DecideOptions,
): Promise<void> {
  await streamEvents(
    `/api/ai/approvals/${encodeURIComponent(approvalId)}/resume`,
    { method: 'POST', signal: options.signal },
    (frame) => {
      const event = parseEvent(frame.data)
      if (event) {
        options.onEvent(event)
      }
    },
  )
}
