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
import type {
  AgentEvent,
  ApprovalRequiredPayload,
  ApprovalView,
  ConversationDetailVO,
} from '@/api/types'
import { ApiError } from '@/api/errors'
import { decideApproval, getApproval, resumeApproval } from '@/api/approvals'
import {
  prepareAfterSaleApproval,
  runAfterSaleChat,
  type AfterSaleCandidateOrder,
  type AfterSaleChatBusinessStatus,
  type AfterSaleReturnDraft,
  type AfterSaleStructuredStatus,
} from '@/api/aftersale'
import { parseApprovalPayload } from '@/api/approvalPayload'
import { describeAfterSaleError } from '@/stores/aftersale'
import type { Transport } from '@/utils/status'

export const AFTERSALE_STATUS_LABELS: Record<AfterSaleChatBusinessStatus, string> = {
  NEEDS_INFO: '信息待补',
  READY_FOR_CONFIRMATION: '已准备、待确认',
  NOT_SUBMITTED: '尚未提交',
  SUBMIT_FAILED: '提交失败',
  SUBMITTED: '提交成功',
}

/** 单轮对话。一轮 = 一个 run = 最多一张确认卡 + 真实业务状态 + 一条回答。 */
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
  /** 真实业务状态（区别于 Agent run SUCCEEDED） */
  businessStatus: AfterSaleChatBusinessStatus | null
  businessMessage: string | null
  /** 只有业务写工具返回真实退货单号时才有值 */
  returnNo: string | null
  /** 预览通过但尚未确认/提交时的结构化草稿，可用于一键发起确认卡或回填表单 */
  draft: AfterSaleReturnDraft | null
  /** 独立聊天中多笔可退订单的候选项，供买家明确点选 */
  candidates: AfterSaleCandidateOrder[]
  createdAt: number
}

const MAX_STEPS = 12

let turnSeq = 0

export interface AfterSaleChatDeps {
  chat: typeof runAfterSaleChat
  conversation: () => Promise<{ conversationId: string }>
  conversationDetail?: (conversationId: string) => Promise<ConversationDetailVO>
  prepareApproval?: typeof prepareAfterSaleApproval
  storageKey?: () => string | null
  approval: typeof getApproval
  decide: typeof decideApproval
  resume: typeof resumeApproval
  /** 批准/恢复之后调用，用于刷新「我的售后」与额度 */
  onExecuted?: () => Promise<void> | void
}

export function createAfterSaleChatStore(deps: AfterSaleChatDeps) {
  const turns = ref<AfterSaleTurn[]>([])
  const submitting = ref(false)
  const conversationId = ref<string | null>(readStoredId())
  /** 当前页面绑定的订单号（从订单详情进入或点击签收订单卡时设置） */
  const activeOrderNo = ref<string | null>(null)
  /** 会话建不出来（身份/后端不可用）：退化单轮，并在界面上说清楚 */
  const conversationUnavailable = ref(false)
  /** 正在从服务端恢复上次的会话（刷新后） */
  const restoring = ref(false)
  const panelError = ref<string | null>(null)

  function storageKey(): string | null {
    try {
      return deps.storageKey?.() ?? null
    } catch {
      return null
    }
  }

  function readStoredId(): string | null {
    const key = storageKey()
    if (!key) {
      return null
    }
    try {
      return window.localStorage.getItem(key)
    } catch {
      return null
    }
  }

  function persistId(id: string | null): void {
    const key = storageKey()
    if (!key) {
      return
    }
    try {
      if (id) {
        window.localStorage.setItem(key, id)
      } else {
        window.localStorage.removeItem(key)
      }
    } catch {
      // 隐私模式 / 存储被禁用：会话仍然可用，只是刷新后要重新建一次
    }
  }

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
      businessStatus: null,
      businessMessage: null,
      returnNo: null,
      draft: null,
      candidates: [],
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
      persistId(conversationId.value)
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
        if (turn.approvalPayload) {
          turn.businessStatus = 'READY_FOR_CONFIRMATION'
          turn.businessMessage = '已准备好退货确认卡，确认前不会建单、不会占用可退额度。'
        }
        break
      case 'structured_data':
        applyStructuredData(turn, event.message)
        break
      case 'cancelled':
        turn.error = '服务端取消了这一轮执行。'
        break
      default:
        break
    }
  }

  function applyStructuredData(turn: AfterSaleTurn, raw: string): void {
    if (!raw) return
    try {
      const parsed = JSON.parse(raw) as Partial<AfterSaleStructuredStatus>
      if (!parsed || parsed.kind !== 'aftersale_status') {
        return
      }
      if (parsed.status) {
        turn.businessStatus = parsed.status
      }
      if (typeof parsed.message === 'string') {
        turn.businessMessage = parsed.message
      }
      if (typeof parsed.returnNo === 'string' && parsed.returnNo) {
        turn.returnNo = parsed.returnNo
      }
      if (parsed.draft && typeof parsed.draft === 'object') {
        turn.draft = parsed.draft
      }
      if (Array.isArray(parsed.candidates)) {
        turn.candidates = parsed.candidates
      }
    } catch {
      // 非结构化载荷忽略
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

  /**
   * 审批查询结果 → 确认卡载荷。
   *
   * <p>内容仍然**全部来自服务端**（`GET /ai/approvals/{id}` 的 summary），
   * 前端只是换了个容器：不补字段、不拼文案、不自己算钱。
   *
   * <p>拿不到 summary 就返回 null：宁可不显示卡，也不显示一张内容不对的卡——
   * 确认卡是"用户同意什么"的唯一凭据。
   */
  function payloadFromApproval(approval: ApprovalView): ApprovalRequiredPayload | null {
    const summary = approval.summary
    if (!summary) {
      return null
    }
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

  /**
   * 把会话详情还原成一轮轮对话。
   *
   * <p>只还原服务端真正存下来的两样东西：用户输入与助手回答的**文本**，
   * 以及"这一轮还有没有待审批"。工具进度不还原（历史进度不是当前进度，
   * 显示出来只会让人以为还在跑）。
   *
   * <p>待审批的那一轮会重新查一次审批，用服务端摘要重建确认卡——
   * 这正是"刷新之后还能接着确认"的落点。
   */
  async function rebuildTurns(detail: ConversationDetailVO): Promise<AfterSaleTurn[]> {
    interface Draft {
      prompt: string
      answer: string
      approvalId: string | null
    }

    const drafts = new Map<string, Draft>()
    const order: string[] = []

    for (const message of detail.messages ?? []) {
      const runId = message.runId
      if (!runId) {
        continue
      }
      let draft = drafts.get(runId)
      if (!draft) {
        draft = { prompt: '', answer: '', approvalId: null }
        drafts.set(runId, draft)
        order.push(runId)
      }
      if (message.role === 'USER') {
        draft.prompt = message.content ?? ''
      } else if (message.role === 'ASSISTANT') {
        draft.answer = message.content ?? ''
      }
      if (message.approvalId) {
        draft.approvalId = message.approvalId
      }
    }

    const restored: AfterSaleTurn[] = []

    for (const runId of order) {
      const draft = drafts.get(runId)
      if (!draft) {
        continue
      }
      const turn = newTurn(draft.prompt)
      turn.runId = runId
      turn.answer = draft.answer
      turn.transport = 'closed'

      if (draft.approvalId) {
        try {
          const approval = await deps.approval(draft.approvalId)
          turn.approval = approval
          turn.approvalPayload = payloadFromApproval(approval)
          if (!turn.approvalPayload) {
            turn.error = '这一轮的确认信息已不可读取，请重新发起申请。'
          }
        } catch {
          /*
           * 审批查不到（过期被清理 / 不是本人）：不构造一张没有依据的卡。
           * 如实说明，用户重新发起即可。
           */
          turn.error = '这一轮的确认信息已不可读取（可能已过期），请重新发起申请。'
        }
      }

      restored.push(turn)
    }

    return restored
  }

  /**
   * 刷新后恢复上次的会话。
   *
   * @return true 表示恢复出了内容；false 表示没有可恢复的会话（不影响继续使用）
   */
  async function restore(): Promise<boolean> {
    const id = conversationId.value
    if (!id || restoring.value) {
      return false
    }
    if (!deps.conversationDetail) {
      return false
    }

    restoring.value = true
    try {
      const detail = await deps.conversationDetail(id)
      const restored = await rebuildTurns(detail)
      turns.value = restored
      return restored.length > 0
    } catch (error) {
      /*
       * 404 = 这个 id 不属于当前身份，或不是售后领域的会话（换人、清库、旧版本遗留）。
       * 丢掉它，下次发送时新建一个；不要把它当成错误弹给用户。
       */
      if (error instanceof ApiError && error.kind === 'not_found') {
        conversationId.value = null
        persistId(null)
        return false
      }
      panelError.value = describeAfterSaleError(error)
      return false
    } finally {
      restoring.value = false
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
        orderNo: activeOrderNo.value,
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
      } else if (!turn.businessStatus && !turn.error && !turn.returnNo) {
        // 没有产出确认卡且没有真实退货单号：绝不把 Agent run SUCCEEDED 当作业务提交成功
        turn.businessStatus = 'NOT_SUBMITTED'
        turn.businessMessage = '本次对话尚未生成确认卡，也没有创建退货申请。'
      }
    } catch (error) {
      const apiError = error instanceof ApiError ? error : null
      turn.transport = apiError?.kind === 'aborted' ? 'aborted' : 'closed'
      turn.error = describeAfterSaleError(error)
      if (!turn.businessStatus) {
        turn.businessStatus = 'SUBMIT_FAILED'
      }
    } finally {
      submitting.value = false
      if (controller === local) {
        controller = null
      }
    }

    return true
  }

  /**
   * 显式可执行的“准备退货确认卡”路径：不依赖模型是否恰好再选 submit_return，
   * 直接经后端校验资格后创建真实 PENDING 确认卡（确认前零建单、零占额）。
   */
  async function prepareApprovalCard(draft: AfterSaleReturnDraft): Promise<boolean> {
    if (!deps.prepareApproval || submitting.value || busy.value) {
      return false
    }
    submitting.value = true
    panelError.value = null
    const turn = newTurn(`准备退货确认卡：订单 ${draft.orderNo} × ${draft.quantity} 件（${draft.reason}）`)
    turn.draft = draft
    turns.value = [...turns.value, turn]
    turn.transport = 'submitting'

    try {
      const convId = await ensureConversation()
      const res = await deps.prepareApproval({
        orderNo: draft.orderNo,
        quantity: draft.quantity,
        reason: draft.reason,
        conversationId: convId,
      })
      turn.runId = res.runId
      const approvalView = await deps.approval(res.approvalId)
      turn.approval = approvalView
      turn.approvalPayload = res.payload ?? payloadFromApproval(approvalView) ?? {
        approvalId: res.approvalId,
        expiresAt: approvalView.expiresAt ?? '',
        summary: {
          tool: 'submit_return',
          toolLabel: '提交退货申请',
          playlistName: null,
          songCount: null,
          songs: [],
          roundToolCount: 1,
          text: approvalView.summaryText ?? '',
          details: [
            { label: '订单号', value: draft.orderNo },
            { label: '退货数量', value: `${draft.quantity} 件` },
            { label: '退货原因', value: draft.reason },
          ],
        },
      }
      turn.businessStatus = 'READY_FOR_CONFIRMATION'
      turn.businessMessage = '已准备好退货确认卡，请核对后点击「批准并执行」；确认前不会创建退货单。'
      turn.transport = 'closed'
      return true
    } catch (error) {
      turn.transport = 'closed'
      turn.businessStatus = 'SUBMIT_FAILED'
      turn.error = describeAfterSaleError(error)
      return false
    } finally {
      submitting.value = false
    }
  }

  function setActiveOrder(orderNo: string | null): void {
    activeOrderNo.value = orderNo && orderNo.trim() ? orderNo.trim() : null
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
      if (!approve) {
        turn.businessStatus = 'NOT_SUBMITTED'
        turn.businessMessage = '你已拒绝本次确认，未创建退货申请，也未占用可退额度。'
      } else {
        if (!turn.returnNo && turn.businessStatus !== 'SUBMIT_FAILED') {
          // 批准完成但若无真实 returnNo（例如校验失败），绝不假装提交成功
          turn.businessStatus = turn.error ? 'SUBMIT_FAILED' : 'NOT_SUBMITTED'
        }
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
    restoring.value = false
    panelError.value = null
    conversationId.value = null
    conversationUnavailable.value = false
    // 换人 / 清空时把持久化的会话 id 一起丢掉，否则刷新后会去续上一个人的会话
    persistId(null)
  }

  return {
    turns,
    submitting,
    restoring,
    busy,
    conversationId,
    activeOrderNo,
    conversationUnavailable,
    panelError,
    submit,
    prepareApprovalCard,
    setActiveOrder,
    decide,
    resume,
    refreshApproval,
    restore,
    abortWaiting,
    reset,
  }
}

export type AfterSaleChatStore = ReturnType<typeof createAfterSaleChatStore>
