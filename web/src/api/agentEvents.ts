/**
 * 通用 Agent SSE 事件解析（供电商导购、售后、审批续流共用）。
 */
import type { AgentEvent } from './types'

export function parseEvent(data: string): AgentEvent | null {
  try {
    const parsed = JSON.parse(data) as Partial<AgentEvent>
    if (!parsed || typeof parsed !== 'object' || typeof parsed.type !== 'string') {
      return null
    }
    return {
      runId: typeof parsed.runId === 'string' ? parsed.runId : '',
      type: parsed.type,
      message: typeof parsed.message === 'string' ? parsed.message : '',
      step: typeof parsed.step === 'number' ? parsed.step : null,
      timestamp: typeof parsed.timestamp === 'number' ? parsed.timestamp : Date.now(),
    }
  } catch {
    return null
  }
}
