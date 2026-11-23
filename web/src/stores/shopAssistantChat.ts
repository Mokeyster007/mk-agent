/**
 * 电商导购 Agent 对话状态。
 *
 * 纪律：
 * 1. 商品卡、对比表、购物车快照、结算预览、订单号全部来自服务端 `structured_data` 事件，
 *    绝不从聊天文案正则反解析 SKU、价格或订单号；
 * 2. 下单确认卡沿用平台统一审批机制（`approval_required` + `GET /ai/approvals/{id}`）；
 * 3. 加购、修改购物车或批准确认下单后，自动回调 `onBusinessChanged()` 同步页面共享购物车与我的订单。
 */
import { computed, reactive, ref } from 'vue'
import type {
  AgentEvent,
  ApprovalRequiredPayload,
  ApprovalView,
  ConversationDetailVO,
} from '@/api/types'
import type {
  ShopCartVO,
  ShopCatalogSearchVO,
  ShopCheckoutPreviewVO,
  ShopComparisonVO,
  ShopOrderVO,
} from '@/api/shop'
import { ApiError } from '@/api/errors'
import { decideApproval, getApproval, resumeApproval } from '@/api/approvals'
import {
  prepareCheckoutApproval,
  runShopAssistantChat,
} from '@/api/shop'
import { parseApprovalPayload } from '@/api/approvalPayload'
import { describeShopError } from '@/stores/shop'
import type { Transport } from '@/utils/status'

export interface ShopAssistantTurn {
  id: string
  prompt: string
  transport: Transport
  runId: string | null
  answer: string
  steps: string[]
  error: string | null
  searchResult: ShopCatalogSearchVO | null
  comparison: ShopComparisonVO | null
  cartSnapshot: ShopCartVO | null
  checkoutPreview: ShopCheckoutPreviewVO | null
  createdOrderNo: string | null
  createdOrder: ShopOrderVO | null
  checkoutFailedMessage: string | null
  approvalPayload: ApprovalRequiredPayload | null
  approval: ApprovalView | null
  deciding: boolean
  createdAt: number
}

const MAX_STEPS = 12
let turnSeq = 0

export interface ShopAssistantChatDeps {
  chat: typeof runShopAssistantChat
  conversation: () => Promise<{ conversationId: string }>
  conversationDetail?: (conversationId: string) => Promise<ConversationDetailVO>
  prepareCheckout?: typeof prepareCheckoutApproval
  storageKey?: () => string | null
  approval: typeof getApproval
  decide: typeof decideApproval
  resume: typeof resumeApproval
  onBusinessChanged?: () => Promise<void> | void
}

export function createShopAssistantChatStore(deps: ShopAssistantChatDeps) {
  const turns = ref<ShopAssistantTurn[]>([])
  const submitting = ref(false)
  const conversationId = ref<string | null>(readStoredId())
  const conversationUnavailable = ref(false)
  const restoring = ref(false)
  const panelError = ref<string | null>(null)

  let controller: AbortController | null = null

  const busy = computed(
    () => submitting.value || turns.value.some((turn) => turn.deciding),
  )

  function storageKey(): string | null {
    try {
      return deps.storageKey?.() ?? null
    } catch {
      return null
    }
  }

  function readStoredId(): string | null {
    const key = storageKey()
    if (!key) return null
    try {
      return window.localStorage.getItem(key)
    } catch {
      return null
    }
  }

  function persistId(id: string | null): void {
    const key = storageKey()
    if (!key) return
    try {
      if (id) window.localStorage.setItem(key, id)
      else window.localStorage.removeItem(key)
    } catch {
      // 忽略
    }
  }

  function newTurn(prompt: string): ShopAssistantTurn {
    turnSeq += 1
    return reactive<ShopAssistantTurn>({
      id: `shop-${Date.now()}-${turnSeq}`,
      prompt,
      transport: 'idle',
      runId: null,
      answer: '',
      steps: [],
      error: null,
      searchResult: null,
      comparison: null,
      cartSnapshot: null,
      checkoutPreview: null,
      createdOrderNo: null,
      createdOrder: null,
      checkoutFailedMessage: null,
      approvalPayload: null,
      approval: null,
      deciding: false,
      createdAt: Date.now(),
    })
  }

  async function ensureConversation(): Promise<string | null> {
    if (conversationId.value) return conversationId.value
    if (conversationUnavailable.value) return null
    try {
      const created = await deps.conversation()
      conversationId.value = created.conversationId
      persistId(conversationId.value)
      return conversationId.value
    } catch {
      conversationUnavailable.value = true
      return null
    }
  }

  function payloadFromApproval(approval: ApprovalView): ApprovalRequiredPayload | null {
    const summary = approval.summary
    if (!summary) return null
    return {
      approvalId: approval.approvalId,
      expiresAt: approval.expiresAt ?? '',
      summary: {
        tool: summary.tool,
        toolLabel: summary.toolLabel,
        playlistName: summary.playlistName ?? null,
        songCount: summary.songCount ?? null,
        songs: summary.songs ?? [],
        roundToolCount: summary.roundToolCount ?? 0,
        text: summary.text ?? '',
        details: summary.details ?? [],
      },
    }
  }

  function applyStructured(turn: ShopAssistantTurn, raw: string): boolean {
    if (!raw) return false
    try {
      const parsed = JSON.parse(raw) as Record<string, unknown>
      const kind = parsed.kind
      if (kind === 'product_cards' && parsed.data) {
        turn.searchResult = parsed.data as ShopCatalogSearchVO
        return false
      }
      if (kind === 'product_comparison' && parsed.data) {
        turn.comparison = parsed.data as ShopComparisonVO
        return false
      }
      if (kind === 'cart_snapshot' && parsed.data) {
        turn.cartSnapshot = parsed.data as ShopCartVO
        return true
      }
      if (kind === 'checkout_preview' && parsed.data) {
        turn.checkoutPreview = parsed.data as ShopCheckoutPreviewVO
        return false
      }
      if (kind === 'order_created') {
        if (typeof parsed.orderNo === 'string' && parsed.orderNo) {
          turn.createdOrderNo = parsed.orderNo
        }
        if (parsed.order && typeof parsed.order === 'object') {
          turn.createdOrder = parsed.order as ShopOrderVO
        }
        return true
      }
      if (kind === 'checkout_failed') {
        turn.checkoutFailedMessage =
          typeof parsed.message === 'string' ? parsed.message : '下单未成功，未创建订单。'
        return false
      }
    } catch {
      // 忽略解析异常
    }
    return false
  }

  function applyEvent(turn: ShopAssistantTurn, event: AgentEvent): boolean {
    switch (event.type) {
      case 'run_id':
        if (event.runId) turn.runId = event.runId
        return false
      case 'tool_start':
      case 'tool_result':
      case 'status':
      case 'step':
        if (event.message && turn.steps.length < MAX_STEPS) {
          turn.steps.push(event.message)
        }
        return false
      case 'structured_data':
        return applyStructured(turn, event.message)
      case 'final_answer':
        if (event.message) turn.answer = event.message
        return false
      case 'error':
        turn.error = event.message || '导购助手本轮执行失败。'
        return false
      case 'approval_required':
        turn.approvalPayload = parseApprovalPayload(event.message)
        return false
      case 'cancelled':
        turn.error = '服务端取消了本轮执行。'
        return false
      default:
        return false
    }
  }

  async function syncApproval(turn: ShopAssistantTurn): Promise<void> {
    const approvalId = turn.approvalPayload?.approvalId
    if (!approvalId) return
    try {
      turn.approval = await deps.approval(approvalId)
    } catch (error) {
      turn.error = describeShopError(error)
    }
  }

  async function restore(): Promise<boolean> {
    const id = conversationId.value
    if (!id || !deps.conversationDetail || restoring.value) return false
    restoring.value = true
    try {
      const detail = await deps.conversationDetail(id)
      const list: ShopAssistantTurn[] = []
      let current: ShopAssistantTurn | null = null
      for (const msg of detail.messages) {
        if (msg.role === 'USER') {
          current = newTurn(msg.content)
          current.runId = msg.runId
          current.transport = 'closed'
          if (msg.approvalId) {
            try {
              const ap = await deps.approval(msg.approvalId)
              current.approval = ap
              current.approvalPayload = payloadFromApproval(ap)
            } catch {
              // 忽略
            }
          }
          list.push(current)
        } else if (msg.role === 'ASSISTANT' && current) {
          current.answer = msg.content
        }
      }
      turns.value = list
      return list.length > 0
    } catch (error) {
      if (error instanceof ApiError && error.kind === 'not_found') {
        conversationId.value = null
        persistId(null)
        return false
      }
      panelError.value = describeShopError(error)
      return false
    } finally {
      restoring.value = false
    }
  }

  async function submit(prompt: string): Promise<boolean> {
    const text = prompt.trim()
    if (!text || submitting.value || busy.value) return false

    submitting.value = true
    panelError.value = null
    const turn = newTurn(text)
    turns.value = [...turns.value, turn]
    turn.transport = 'submitting'

    const id = await ensureConversation()
    const local = new AbortController()
    controller = local
    let businessChanged = false

    try {
      await deps.chat({
        message: text,
        conversationId: id,
        signal: local.signal,
        onEvent: (event) => {
          if (turn.transport === 'submitting') {
            turn.transport = 'streaming'
          }
          if (applyEvent(turn, event)) {
            businessChanged = true
          }
        },
      })
      turn.transport = 'closed'
      if (turn.approvalPayload) {
        await syncApproval(turn)
      }
      if (businessChanged) {
        await deps.onBusinessChanged?.()
      }
    } catch (error) {
      const apiError = error instanceof ApiError ? error : null
      turn.transport = apiError?.kind === 'aborted' ? 'aborted' : 'closed'
      turn.error = describeShopError(error)
    } finally {
      submitting.value = false
      if (controller === local) controller = null
    }
    return true
  }

  async function prepareCheckoutCard(
    productId: number,
    productName: string,
    quantity: number,
    removeFromCart = true,
  ): Promise<boolean> {
    if (!deps.prepareCheckout || submitting.value || busy.value) return false
    submitting.value = true
    panelError.value = null
    const turn = newTurn(`准备下单确认卡：${productName} × ${quantity}`)
    turns.value = [...turns.value, turn]
    turn.transport = 'submitting'

    try {
      const convId = await ensureConversation()
      const res = await deps.prepareCheckout({
        productId,
        quantity,
        removeFromCart,
        conversationId: convId,
      })
      turn.runId = res.runId
      const ap = await deps.approval(res.approvalId)
      turn.approval = ap
      turn.approvalPayload = res.payload ?? payloadFromApproval(ap)
      turn.transport = 'closed'
      return true
    } catch (error) {
      turn.transport = 'closed'
      turn.error = describeShopError(error)
      return false
    } finally {
      submitting.value = false
    }
  }

  async function decide(turnId: string, approve: boolean): Promise<void> {
    const turn = turns.value.find((item) => item.id === turnId)
    const approvalId = turn?.approvalPayload?.approvalId
    if (!turn || !approvalId || turn.deciding) return

    turn.deciding = true
    turn.error = null
    turn.checkoutFailedMessage = null
    turn.transport = 'submitting'

    const local = new AbortController()
    controller = local

    try {
      await deps.decide(approvalId, approve, {
        signal: local.signal,
        onEvent: (event) => {
          applyEvent(turn, event)
        },
      })
      turn.transport = 'closed'
      await syncApproval(turn)
      if (approve) {
        await deps.onBusinessChanged?.()
      }
    } catch (error) {
      turn.transport = 'closed'
      await syncApproval(turn)
      const decided =
        turn.approval?.status === 'APPROVED' || turn.approval?.status === 'REJECTED'
      const base = describeShopError(error)
      turn.error = decided ? `${base}决定已保存，可以点「手动恢复」重试。` : base
      if (approve) {
        await deps.onBusinessChanged?.()
      }
    } finally {
      turn.deciding = false
      if (controller === local) controller = null
    }
  }

  async function resume(turnId: string): Promise<void> {
    const turn = turns.value.find((item) => item.id === turnId)
    const approvalId = turn?.approvalPayload?.approvalId
    if (!turn || !approvalId || turn.deciding) return

    turn.deciding = true
    turn.error = null
    turn.transport = 'submitting'
    const local = new AbortController()
    controller = local

    try {
      await deps.resume(approvalId, {
        signal: local.signal,
        onEvent: (event) => {
          applyEvent(turn, event)
        },
      })
      turn.transport = 'closed'
      await syncApproval(turn)
      await deps.onBusinessChanged?.()
    } catch (error) {
      turn.transport = 'closed'
      await syncApproval(turn)
      turn.error = describeShopError(error)
      await deps.onBusinessChanged?.()
    } finally {
      turn.deciding = false
      if (controller === local) controller = null
    }
  }

  async function refreshApproval(turnId: string): Promise<void> {
    const turn = turns.value.find((item) => item.id === turnId)
    if (turn) await syncApproval(turn)
  }

  function reset(): void {
    controller?.abort()
    controller = null
    turns.value = []
    submitting.value = false
    restoring.value = false
    panelError.value = null
    conversationId.value = null
    conversationUnavailable.value = false
    persistId(null)
  }

  return {
    turns,
    submitting,
    restoring,
    busy,
    conversationId,
    conversationUnavailable,
    panelError,
    submit,
    prepareCheckoutCard,
    decide,
    resume,
    refreshApproval,
    restore,
    reset,
  }
}

export type ShopAssistantChatStore = ReturnType<typeof createShopAssistantChatStore>
