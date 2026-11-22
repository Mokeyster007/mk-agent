/**
 * 售后对话（Agent 入口）的状态。
 *
 * 五条纪律（与音乐工作台同一套，不重复发明）：
 * 1. **一次点击只发一条消息**：`submitting` 在 await 之前同步置位；
 * 2. **确认卡内容只来自服务端**：初始来自 `approval_required` 事件，
 *    随后用 `GET /ai/approvals/{id}` 校准；绝不根据聊天文本拼卡、绝不编辑参数；
 * 3. **"批准"只是保存决定**：是否真的建单，以「我的售后」列表为准，
 *    因此批准/恢复之后主动刷新业务数据（`onExecuted`）；
 * 4. **拒绝是零写入**：拒绝后不刷新额度、不显示"已提交"，
 *    只在对话里说明"没有创建申请"；
 * 5. **没有会话就退化，不假装有记忆**：会话创建失败时单轮问答照常可用，
 *    界面明确提示"追问不会带上文"。
 */
import { computed, reactive, ref } from 'vue'
import type { AgentEvent, ApprovalRequiredPayload, ApprovalView } from '@/api/types'
import { ApiError } from '@/api/errors'
import { decideApproval, getApproval, resumeApproval } from '@/api/approvals'
import { runAfterSaleChat } from '@/api/aftersale'
import { parseApprovalPayload } from '@/api/approvalPayload'
import { describeAfterSaleError } from '@/stores/aftersale'
import type { Transport } from '@/utils/status'

/** 单轮对话。一轮 = 一个 run = 最多一张确认卡 + 一条回答。 */
export interface AfterSaleTurn {
  id: string
  prompt: string
  transport: Transport
  runId: string | null
  /** 服务端最终回答（以 agent_run / final_answer 事件为准） */
  answer: string
  /** 进度行（工具调用、状态），只用于让用户看到"在做什么" */
  steps: string[]
  error: string | null
  approvalPayload: ApprovalRequiredPayload | null
  approval: ApprovalView | null
  deciding: boolean
  createdAt: number
}

const MAX_STEPS = 12

let turnSeq = 0

export interface AfterSaleChatDeps {
  chat: typeof runAfterSaleChat
  conversation: () => Promise<{ conversationId: string }>
  approval: typeof getApproval
  decide: typeof decideApproval
  resume: typeof resumeApproval
  /** 批准/恢复之后调用，用于刷新「我的售后」与额度 */
  onExecuted?: () => Promise<void> | void
}

export function createAfterSaleChatStore(deps: AfterSaleChatDeps) {
  const turns = ref<AfterSaleTurn[]>([])
  const submitting = ref(false)
  const conversationId = ref<string | null>(null)
  /** 会话建不出来（比如身份不是音乐用户）：退化单轮，并在界面上说清楚 */
  const conversationUnavailable = ref(false)
  const panelError = ref<string | null>(null)

  let controller: AbortController | null = null

  const busy = computed(
    () => submitting.value || turns.value.some((turn) => turn.deciding),
  )

  function newTurn(prompt: string): AfterSaleTurn {
    turnSeq += 1
    /*
     * reactive 包裹是必须的：之后所有事件都通过闭包直接赋值，
     * 普通对象即使被放进 ref 数组也不会触发视图更新（界面会永远停在"接收中"）。
     */
    return reactive<AfterSaleTurn>({
      id: `as-${Date.now()}-${turnSeq}`,
      prompt,
      transport: 'idle',
      runId: null,
      answer: '',
      steps: [],
      error: null,
      approvalPayload: null,
      approval: null,
      deciding: false,
      createdAt: Date.now(),
    })
  }

  /** 尽力建一个会话；失败就退化单轮，不阻断对话。 */
  async function ensureConversation(): Promise<string | null> {
    if (conversationId.value) {
      return conversationId.value
    }
    if (conversationUnavailable.value) {
      return null
    }
    try {
      const created = await deps.conversation()
      conversationId.value = created.conversationId
      return conversationId.value
    } catch {
      conversationUnavailable.value = true
      return null
    }
  }

  function applyEvent(turn: AfterSaleTurn, event: AgentEvent): void {
    switch (event.type) {
      case 'run_id':
        if (event.runId) {
          turn.runId = event.runId
        }
        break
      case 'tool_start':
      case 'tool_result':
      case 'status':
      case 'step':
        if (event.message && turn.steps.length < MAX_STEPS) {
          turn.steps.push(event.message)
        }
        break
      case 'final_answer':
        if (event.message) {
          turn.answer = event.message
        }
        break
      case 'error':
        turn.error = event.message || '这一轮执行失败。'
        break
      case 'approval_required':
        turn.approvalPayload = parseApprovalPayload(event.message)
        break
      case 'cancelled':
        turn.error = '服务端取消了这一轮执行。'
        break
      default:
        break
    }
  }

  async function syncApproval(turn: AfterSaleTurn): Promise<void> {
    const approvalId = turn.approvalPayload?.approvalId
    if (!approvalId) {
      return
    }
    try {
      turn.approval = await deps.approval(approvalId)
    } catch (error) {
      turn.error = describeAfterSaleError(error)
    }
  }

  /** 发一条消息。返回 false 表示被重复提交守卫拦下。 */
  async function submit(prompt: string): Promise<boolean> {
    const text = prompt.trim()
    if (!text || submitting.value || busy.value) {
      return false
    }

    submitting.value = true
    panelError.value = null
    const turn = newTurn(text)
    turns.value = [...turns.value, turn]
    turn.transport = 'submitting'

    const id = await ensureConversation()
    const local = new AbortController()
    controller = local

    try {
      await deps.chat({
        message: text,
        conversationId: id,
        signal: local.signal,
        onEvent: (event) => {
          if (turn.transport === 'submitting') {
            turn.transport = 'streaming'
          }
          applyEvent(turn, event)
        },
      })
      turn.transport = 'closed'
      if (turn.approvalPayload) {
        await syncApproval(turn)
      }
    } catch (error) {
      const apiError = error instanceof ApiError ? error : null
      turn.transport = apiError?.kind === 'aborted' ? 'aborted' : 'closed'
      turn.error = describeAfterSaleError(error)
    } finally {
      submitting.value = false
      if (controller === local) {
        controller = null
      }
    }

    return true
  }

  /**
   * 提交确认决定。
   *
   * 批准 → 服务端执行写工具 → 刷新业务数据；
   * 拒绝 → 服务端不执行任何写操作，因此不刷新额度，也不显示"已提交"。
   */
  async function decide(turnId: string, approve: boolean): Promise<void> {
    const turn = turns.value.find((item) => item.id === turnId)
    const approvalId = turn?.approvalPayload?.approvalId
    if (!turn || !approvalId || turn.deciding) {
      return
    }

    turn.deciding = true
    turn.error = null
    panelError.value = null
    turn.transport = 'submitting'

    const local = new AbortController()
    controller = local

    try {
      await deps.decide(approvalId, approve, {
        signal: local.signal,
        onEvent: (event) => applyEvent(turn, event),
      })
      turn.transport = 'closed'
      await syncApproval(turn)
      if (approve) {
        await deps.onExecuted?.()
      }
    } catch (error) {
      turn.transport = 'closed'
      // 决定可能已经落库、只是恢复没起来：以真实审批状态为准
      await syncApproval(turn)
      const decided =
        turn.approval?.status === 'APPROVED' || turn.approval?.status === 'REJECTED'
      const base = describeAfterSaleError(error)
      turn.error = decided ? `${base}决定已保存，可以点「手动恢复」重试。` : base
      if (approve) {
        await deps.onExecuted?.()
      }
    } finally {
      turn.deciding = false
      if (controller === local) {
        controller = null
      }
    }
  }

  /** 手动恢复（决定已保存、执行没接上时用）。沿用原 approvalId，不重发原消息。 */
  async function resume(turnId: string): Promise<void> {
    const turn = turns.value.find((item) => item.id === turnId)
    const approvalId = turn?.approvalPayload?.approvalId
    if (!turn || !approvalId || turn.deciding) {
      return
    }

    turn.deciding = true
    turn.error = null
    turn.transport = 'submitting'

    const local = new AbortController()
    controller = local

    try {
      await deps.resume(approvalId, {
        signal: local.signal,
        onEvent: (event) => applyEvent(turn, event),
      })
      turn.transport = 'closed'
      await syncApproval(turn)
      await deps.onExecuted?.()
    } catch (error) {
      turn.transport = 'closed'
      await syncApproval(turn)
      turn.error = describeAfterSaleError(error)
      await deps.onExecuted?.()
    } finally {
      turn.deciding = false
      if (controller === local) {
        controller = null
      }
    }
  }

  async function refreshApproval(turnId: string): Promise<void> {
    const turn = turns.value.find((item) => item.id === turnId)
    if (!turn) {
      return
    }
    await syncApproval(turn)
  }

  /** 停止等待（只影响前端读取，后端可能仍在执行）。 */
  function abortWaiting(): void {
    controller?.abort()
  }

  function reset(): void {
    controller?.abort()
    controller = null
    turns.value = []
    submitting.value = false
    panelError.value = null
    conversationId.value = null
    conversationUnavailable.value = false
  }

  return {
    turns,
    submitting,
    busy,
    conversationId,
    conversationUnavailable,
    panelError,
    submit,
    decide,
    resume,
    refreshApproval,
    abortWaiting,
    reset,
  }
}

export type AfterSaleChatStore = ReturnType<typeof createAfterSaleChatStore>
