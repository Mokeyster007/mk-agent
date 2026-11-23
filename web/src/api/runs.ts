/**
 * 任务查询与取消。
 *
 *   GET  /api/ai/runs/{runId}        当前身份的任务详情（不存在/不属于自己 → 404）
 *   GET  /api/ai/runs/page           分页历史（只返回自己的任务）
 *   POST /api/ai/runs/{runId}/cancel 取消（终态 → 409，注册表已无 → 409）
 *
 * 这三个接口是"核对真实状态"的唯一依据：
 * SSE 断流、超时、页面刷新之后，都以这里的 state 为准。
 */
import { requestJson } from './client'
import type { AgentRunVO, PageResult } from './types'

export function getRun(runId: string, signal?: AbortSignal): Promise<AgentRunVO> {
  return requestJson<AgentRunVO>(`/api/ai/runs/${encodeURIComponent(runId)}`, { signal })
}

export interface PageRunsOptions {
  pageNum?: number
  pageSize?: number
  agentType?: string
  state?: string
  signal?: AbortSignal
}

export function pageRuns(options: PageRunsOptions = {}): Promise<PageResult<AgentRunVO>> {
  const { pageNum = 1, pageSize = 20, agentType, state, signal } = options
  return requestJson<PageResult<AgentRunVO>>('/api/ai/runs/page', {
    query: { pageNum, pageSize, agentType, state },
    signal,
  })
}

/** 取消结果后端返回 Map，前端不依赖其内部结构，只用它判断"请求被接受"。 */
export function cancelRun(runId: string, signal?: AbortSignal): Promise<Record<string, unknown>> {
  return requestJson<Record<string, unknown>>(
    `/api/ai/runs/${encodeURIComponent(runId)}/cancel`,
    { method: 'POST', signal },
  )
}

/** 终态判定，与后端 AgentState.isTerminal() 保持一致。 */
const TERMINAL_STATES = new Set([
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'TIMED_OUT',
  'MAX_STEPS_REACHED',
])

export function isTerminalState(state: string | null | undefined): boolean {
  return !!state && TERMINAL_STATES.has(state)
}
