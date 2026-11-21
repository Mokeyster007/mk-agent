/**
 * 会话接口（同一窗口连续聊天）。
 *
 *   GET  /api/ai/conversations                会话列表（最近活动在前）
 *   POST /api/ai/conversations                新建空会话
 *   GET  /api/ai/conversations/{id}           会话详情（消息正序）
 *
 * 这里**没有**"继续上一轮"的接口：续聊是带着 conversationId 再发一条
 * /ai/music/chat，后端新建 runId 并把历史文本与业务引用注入给模型。
 * 终态 run 不会被复活。
 */
import { requestJson } from './client'
import type { ConversationDetailVO, ConversationVO } from './types'

export function listConversations(signal?: AbortSignal): Promise<ConversationVO[]> {
  return requestJson<ConversationVO[]>('/api/ai/conversations', { signal })
}

/**
 * 新建空会话。
 *
 * 先建再发：前端要在发第一条消息之前就知道 conversationId，
 * 否则第一条只能落成独立任务，之后再也接不上这个会话。
 */
export function createConversation(signal?: AbortSignal): Promise<{ conversationId: string }> {
  return requestJson<{ conversationId: string }>('/api/ai/conversations', {
    method: 'POST',
    signal,
  })
}

/** 会话详情；不存在或不是本人 → 404（两种情况同一句文案）。 */
export function getConversation(
  conversationId: string,
  signal?: AbortSignal,
): Promise<ConversationDetailVO> {
  return requestJson<ConversationDetailVO>(
    `/api/ai/conversations/${encodeURIComponent(conversationId)}`,
    { signal },
  )
}
