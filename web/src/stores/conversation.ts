/**
 * 会话状态（同一窗口连续聊天）。
 *
 * 三个 ID 的边界（这里是最容易被混用的一层）：
 *   conversationId —— 会话：一个窗口里连续的多轮，跨刷新存在；
 *   runId          —— 一次提交：每轮新建，**绝不复活**已终态的 run；
 *   epoch          —— 某个 run 的执行代次，只在审批恢复时 +1（后端维护）。
 *
 * 所以本文件里**没有**"继续某个 run"的动作：续聊只有一种做法——
 * 带着 conversationId 再发一条消息，后端新建 run。
 */
import { reactive, ref } from 'vue'
import { ApiError, describeError } from '@/api/errors'
import {
  createConversation,
  getConversation,
  listConversations,
} from '@/api/conversations'
import type { ConversationVO } from '@/api/types'
import { taskStore } from './taskStore'

const STORAGE_KEY = 'mkagent.activeConversationId'

interface ConversationState {
  list: ConversationVO[]
  loading: boolean
  error: string | null
  /** 正在打开某个会话（详情加载中） */
  opening: boolean
  detailError: string | null
}

export const conversations = reactive<ConversationState>({
  list: [],
  loading: false,
  error: null,
  opening: false,
  detailError: null,
})

/**
 * 当前会话。
 *
 * 用 ref 而不是 reactive 对象里的字段：组件里 `activeConversationId.value`
 * 的语义更清楚，也不会被误当成"任务 id"。
 */
export const activeConversationId = ref<string | null>(readStored())

let listController: AbortController | null = null
let detailController: AbortController | null = null

function readStored(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

function persist(id: string | null): void {
  try {
    if (id) {
      window.localStorage.setItem(STORAGE_KEY, id)
    } else {
      window.localStorage.removeItem(STORAGE_KEY)
    }
  } catch {
    // 隐私模式 / 存储被禁用：会话仍然可用，只是刷新后要重新点一次
  }
}

/** 会话标题（列表里没有标题时用首条输入兜底）。 */
export function conversationTitle(conversation: ConversationVO): string {
  return conversation.title?.trim() || '新会话'
}

export async function refreshConversations(): Promise<void> {
  if (conversations.loading) {
    return
  }
  listController?.abort()
  listController = new AbortController()

  conversations.loading = true
  conversations.error = null

  try {
    conversations.list = await listConversations(listController.signal)
  } catch (error) {
    if (error instanceof ApiError && error.kind === 'aborted') {
      return
    }
    conversations.error = describeError(error)
  } finally {
    conversations.loading = false
  }
}

/**
 * 打开一个会话：拉详情并把每一轮还原成可渲染的任务。
 *
 * 刷新页面后靠这条路径恢复"同一个会话 + 同一批消息"，
 * 而不是靠前端内存（内存一刷就没）。
 */
export async function openConversation(conversationId: string): Promise<void> {
  detailController?.abort()
  detailController = new AbortController()

  activeConversationId.value = conversationId
  persist(conversationId)
  conversations.opening = true
  conversations.detailError = null

  try {
    const detail = await getConversation(conversationId, detailController.signal)
    await taskStore.hydrateConversation(detail)
  } catch (error) {
    if (error instanceof ApiError && error.kind === 'aborted') {
      return
    }
    conversations.detailError = describeError(error)
  } finally {
    conversations.opening = false
  }
}

/**
 * 新建会话（只在用户明确点"新建会话"时调用）。
 *
 * 先建再发：发第一条消息之前就必须知道 conversationId，
 * 否则第一条只能落成独立任务，之后再也接不上。
 */
export async function newConversation(): Promise<string> {
  const created = await createConversation()
  activeConversationId.value = created.conversationId
  persist(created.conversationId)
  void refreshConversations()
  return created.conversationId
}

/**
 * 提交前确保有会话：没有就建一个。
 *
 * 失败向上抛——没有会话就发消息，等于又退化成"每条都是独立任务"，
 * 那正是本轮要修的问题，不能静默降级。
 */
export async function ensureConversation(): Promise<string> {
  const current = activeConversationId.value
  if (current) {
    return current
  }
  return newConversation()
}

/**
 * 回到"未开始"状态（清空工作区）。
 *
 * 只是不再选中任何会话，不删除服务端记录：
 * 会话是持久化数据，删除需要单独的显式操作。
 */
export function clearActiveConversation(): void {
  activeConversationId.value = null
  persist(null)
}
