/**
 * 任务状态中心。
 *
 * 设计要点（每一条都对应一个真实的坑）：
 *
 * 1. **一次点击只发一次请求**
 *    transport 在 await 之前同步置为 submitting，按钮同时置灰；
 *    另外用 submitting 标志做二次保险，避免 watch / mounted 触发重复提交。
 *
 * 2. **旧响应不能覆盖新任务**
 *    每个任务持有唯一 token，事件回调先校验 token 是否仍是当前任务；
 *    事件只写入它自己所属的任务对象，绝不写"当前选中任务"。
 *
 * 3. **DONE 不等于成功**
 *    流结束只把 transport 置为 closed，成功与否一律以
 *    GET /ai/runs/{runId} 的 state 为准；未到终态时做有界轮询（带退避）。
 *
 * 4. **中止不等于取消**
 *    abort 只停止前端读取，任务可能仍在后端执行，
 *    因此界面显示"结果未知"，并提供"核对状态"，绝不写"已取消"。
 *
 * 5. **审批决定与恢复是两件事**
 *    决定失败 → 重新查询审批视图；只有 status 真的变了才算"决定已保存"。
 *    恢复失败 → 保留决定，暴露"手动恢复"，不重发原 prompt、不新建任务。
 */
import { computed, reactive, ref } from 'vue'
import type {
  AgentEvent,
  AgentRunVO,
  ApprovalRequiredPayload,
  ApprovalView,
  ConversationDetailVO,
  ConversationMessageVO,
  MusicEntry,
} from '@/api/types'
import type { ErrorKind } from '@/api/errors'
import { ApiError, describeError, describeRunErrorCode } from '@/api/errors'
import { runMusicChat } from '@/api/music'
import { cancelRun, getRun, isTerminalState, pageRuns } from '@/api/runs'
import { decideApproval, getApproval, resumeApproval } from '@/api/approvals'
import { parseApprovalPayload } from '@/api/approvalPayload'
import type { Transport } from '@/utils/status'

/**
 * 重新导出：历史上 `parseApprovalPayload` 定义在本文件里，
 * 测试与其它调用方按这个路径引用。实现已经搬到 `@/api/approvalPayload`，
 * 因为售后链路也要用同一份解析（确认卡多一个 details 字段时不能只改一处）。
 */
export { parseApprovalPayload }


export interface TaskError {
  kind: ErrorKind | 'task'
  code: string | null
  text: string
  /** 是否已经拿到过 runId（决定能否用查询核实） */
  hasRunId: boolean
}

export interface SessionTask {
  id: string
  /** 防陈旧响应用的序号 */
  token: number
  prompt: string
  entry: MusicEntry
  /**
   * 所属会话；null 表示独立任务（老的入口 / 历史任务重开）。
   *
   * 注意它不是 runId：会话跨多轮不变，runId 每轮新建。
   */
  conversationId: string | null
  createdAt: number
  transport: Transport
  runId: string | null
  serverState: string | null
  events: AgentEvent[]
  answer: string
  error: TaskError | null
  approvalPayload: ApprovalRequiredPayload | null
  approval: ApprovalView | null
  approvalError: string | null
  detail: AgentRunVO | null
  detailError: string | null
  verifying: boolean
  deciding: boolean
  finishedAt: number | null
  /** 后端主动推了 cancelled 事件 */
  cancelledByServer: boolean
}

let tokenSeq = 0
let idSeq = 0
let activeController: AbortController | null = null

/**
 * 正在提交决定的审批，跨组件重挂载也不会重复提交。
 *
 * 键里带任务实例 id：同一个 approvalId 在不同任务对象上互不干扰，
 * 避免测试之间（或任务重建之后）残留的锁把后续操作堵死。
 */
const decidingApprovals = new Set<string>()

function decideKey(task: SessionTask, approvalId: string): string {
  return `${task.id}::${approvalId}`
}

function newTask(
  prompt: string,
  entry: MusicEntry,
  conversationId: string | null = null,
): SessionTask {
  idSeq += 1
  /*
   * 必须用 reactive 包裹。
   *
   * 原因：submit 之后所有事件都通过闭包里的 task 引用直接赋值。
   * 如果 task 是普通对象，即使它被放进了 ref 数组，Vue 的深层代理也只在
   * 通过代理读取时才建立依赖；对原始对象的赋值不经过 setter，
   * 不会通知视图——界面会永远停在"接收中"，而数据其实已经完成。
   */
  return reactive<SessionTask>({
    id: `t-${Date.now()}-${idSeq}`,
    token: ++tokenSeq,
    prompt,
    entry,
    conversationId,
    createdAt: Date.now(),
    transport: 'idle',
    runId: null,
    serverState: null,
    events: [],
    answer: '',
    error: null,
    approvalPayload: null,
    approval: null,
    approvalError: null,
    detail: null,
    detailError: null,
    verifying: false,
    deciding: false,
    finishedAt: null,
    cancelledByServer: false,
  })
}

const tasks = ref<SessionTask[]>([])
const activeId = ref<string | null>(null)
const submitting = ref(false)

const activeTask = computed<SessionTask | null>(
  () => tasks.value.find((task) => task.id === activeId.value) ?? null,
)

const isBusy = computed(() => {
  const task = activeTask.value
  return !!task && (task.transport === 'submitting' || task.transport === 'streaming')
})

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 读取 transport 的函数形式。
 *
 * 直接用 task.transport 会被 TS 的控制流分析"记住"上一次赋值（'submitting'），
 * 从而认为后续比较 'aborted' 不可能成立——但 await 期间它确实可能被 abort 改掉。
 * 用函数返回可以避免这种错误收窄。
 */
function transportOf(task: SessionTask): Transport {
  return task.transport
}

/** 把后端任务详情写回本地任务对象。 */
function applyDetail(task: SessionTask, detail: AgentRunVO): void {
  task.detail = detail
  task.serverState = detail.state
  if (detail.finalAnswer) {
    task.answer = detail.finalAnswer
  }
  if (isTerminalState(detail.state)) {
    task.finishedAt = Date.now()
    task.transport = task.transport === 'aborted' ? 'aborted' : 'closed'
  }
}

/**
 * 用数据库里的真实状态核实任务。
 *
 * 只在"流已结束但状态还不是终态"时轮询，且次数有限、带退避、遇终态即停。
 * 这是状态查询，属于可重试请求；执行类请求永不重试。
 */
async function verify(
  task: SessionTask,
  options: { poll?: boolean; signal?: AbortSignal } = {},
): Promise<void> {
  if (!task.runId) {
    return
  }
  const { poll = false, signal } = options
  task.verifying = true
  task.detailError = null

  const maxAttempts = poll ? 5 : 1
  let delay = 800

  try {
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      const detail = await getRun(task.runId, signal)
      applyDetail(task, detail)

      if (isTerminalState(detail.state) || detail.state === 'WAITING_APPROVAL') {
        return
      }
      if (attempt === maxAttempts - 1) {
        return
      }
      await sleep(delay)
      delay = Math.min(delay * 2, 4000)
    }
  } catch (error) {
    if (error instanceof ApiError && error.kind === 'aborted') {
      return
    }
    task.detailError = describeError(error)
  } finally {
    task.verifying = false
  }
}

/** 把一条 SSE 事件写入任务。 */
function applyEvent(task: SessionTask, event: AgentEvent): void {
  switch (event.type) {
    case 'run_id':
      if (event.runId) {
        task.runId = event.runId
      }
      break
    case 'tool_start':
    case 'tool_result':
    case 'step':
    case 'status':
      task.events.push(event)
      break
    case 'final_answer':
      // 先记下来，稍后仍以 agent_run.final_answer 为准
      if (event.message) {
        task.answer = event.message
      }
      task.events.push(event)
      break
    case 'error':
      task.events.push(event)
      task.error = {
        kind: 'task',
        code: null,
        text: event.message || '任务执行失败。',
        hasRunId: !!task.runId,
      }
      break
    case 'cancelled':
      task.cancelledByServer = true
      task.events.push(event)
      break
    case 'approval_required': {
      task.events.push(event)
      const payload = parseApprovalPayload(event.message)
      task.approvalPayload = payload
      task.serverState = 'WAITING_APPROVAL'
      break
    }
    case 'done':
      // done 只表示"这一段连接结束"，任务可能还在 WAITING_APPROVAL
      break
    default:
      break
  }
}

/** 查询审批真实状态（决定是否真的保存、恢复是否真的起来，都看这里）。 */
async function syncApproval(task: SessionTask): Promise<void> {
  const approvalId = task.approvalPayload?.approvalId
  if (!approvalId) {
    return
  }
  try {
    task.approval = await getApproval(approvalId)
    task.approvalError = null
  } catch (error) {
    task.approvalError = describeError(error)
  }
}

export const taskStore = {
  tasks,
  activeId,
  activeTask,
  isBusy,
  submitting,

  /** 新建空白任务（清空工作区） */
  startNew(): void {
    if (isBusy.value) {
      return
    }
    activeId.value = null
  },

  /** 选中一个已有任务 */
  select(id: string): void {
    if (tasks.value.some((task) => task.id === id)) {
      activeId.value = id
    }
  },

  /**
   * 提交任务。返回 false 表示被重复提交守卫拦下。
   *
   * conversationId 为空时是老的独立任务（行为完全不变）；
   * 有值时这一轮会挂到该会话上：后端新建 runId，
   * 并把最近几轮文本与"会话已知对象"注入给模型。
   * 前端**不**自己拼历史——只拼前端而不给模型历史，等于假记忆。
   */
  async submit(
    prompt: string,
    entry: MusicEntry,
    conversationId: string | null = null,
  ): Promise<boolean> {
    const text = prompt.trim()
    if (!text || submitting.value || isBusy.value) {
      return false
    }

    submitting.value = true
    const task = newTask(text, entry, conversationId)
    tasks.value = [task, ...tasks.value]
    activeId.value = task.id
    task.transport = 'submitting'

    const controller = new AbortController()
    activeController = controller

    try {
      await runMusicChat({
        message: text,
        entry,
        conversationId,
        signal: controller.signal,
        onEvent: (event) => {
          /*
           * 迟到事件守卫：任务已经进入终态后，任何流事件都不能再改写它。
           * 典型场景是"流断了一半、查询已判定终态、旧缓冲才到达"。
           * 事件只写自己所属的任务对象，因此不需要（也不应该）按 activeId 过滤——
           * 后台任务继续接收事件是正常行为，只要别污染别的任务即可。
           */
          if (isTerminalState(task.serverState)) {
            return
          }
          if (task.transport === 'submitting') {
            task.transport = 'streaming'
          }
          applyEvent(task, event)
        },
      })

      if (transportOf(task) !== 'aborted') {
        task.transport = 'closed'
      }

      // 暂停在审批上：拉一次真实审批状态
      if (task.approvalPayload) {
        await syncApproval(task)
      }

      // 无论收到什么事件，最终都以数据库状态为准
      await verify(task, { poll: true, signal: controller.signal })
    } catch (error) {
      const apiError = error instanceof ApiError ? error : null
      if (apiError?.kind === 'aborted') {
        // 主动中止：不写"已取消"，只标记结果未知
        task.transport = 'aborted'
        task.error = {
          kind: 'aborted',
          code: null,
          text: '连接已被前端中止。后端任务可能仍在执行，请核对任务状态。',
          hasRunId: !!task.runId,
        }
      } else {
        task.transport = 'closed'
        task.error = {
          kind: apiError?.kind ?? 'unknown',
          code: apiError?.code ?? null,
          text: describeError(error),
          hasRunId: !!task.runId,
        }
        // 错误也可能是"流中途断了"，此时用查询兜底核实
        if (task.runId) {
          await verify(task, { poll: false })
        }
      }
    } finally {
      submitting.value = false
      if (activeController === controller) {
        activeController = null
      }
    }

    /*
     * 任务已经落到服务端，左侧"历史任务"里的数据就旧了。
     * 这里补一次只读查询，避免任务完成后导航仍显示"暂无历史任务"。
     * 失败无所谓：历史只是辅助入口，不影响当前任务。
     */
    if (task.runId) {
      void loadHistory({ reset: true })
    }

    return true
  },

  /** 主动停止等待（只影响前端） */
  abortWaiting(): void {
    activeController?.abort()
  },

  /** 取消任务（服务端确认后才算取消） */
  async cancel(id: string): Promise<void> {
    const task = tasks.value.find((item) => item.id === id)
    if (!task?.runId) {
      return
    }
    try {
      await cancelRun(task.runId)
      await verify(task, { poll: false })
    } catch (error) {
      task.error = {
        kind: error instanceof ApiError ? error.kind : 'unknown',
        code: error instanceof ApiError ? error.code : null,
        text: describeError(error),
        hasRunId: true,
      }
      // 状态冲突（409）时刷新真实状态，避免界面停在过期状态
      await verify(task, { poll: false })
    }
  },

  /** 重新核对状态（用于断流、超时之后） */
  async recheck(id: string): Promise<void> {
    const task = tasks.value.find((item) => item.id === id)
    if (!task) {
      return
    }
    await verify(task, { poll: true })
  },

  /** 重新读取审批真实状态（用户点"刷新状态"时调用） */
  async refreshApproval(id: string): Promise<void> {
    const task = tasks.value.find((item) => item.id === id)
    if (!task) {
      return
    }
    await syncApproval(task)
  },

  /**
   * 提交审批决定。
   *
   * 双保险防重复：task.deciding + 模块级 decidingApprovals。
   * 失败后一定重新查询审批视图，用 status/resumeState 判断
   * "决定是否已保存、恢复是否已起来"，不靠文案猜。
   */
  async decide(id: string, approve: boolean): Promise<void> {
    const task = tasks.value.find((item) => item.id === id)
    const approvalId = task?.approvalPayload?.approvalId
    if (!task || !approvalId) {
      return
    }
    if (task.deciding || decidingApprovals.has(decideKey(task, approvalId))) {
      return
    }

    decidingApprovals.add(decideKey(task, approvalId))
    task.deciding = true
    task.error = null
    const controller = new AbortController()
    activeController = controller
    task.transport = 'submitting'

    try {
      await decideApproval(approvalId, approve, {
        signal: controller.signal,
        onEvent: (event) => applyEvent(task, event),
      })
      task.transport = 'closed'
      await syncApproval(task)
      await verify(task, { poll: true, signal: controller.signal })
    } catch (error) {
      task.transport = 'closed'
      const apiError = error instanceof ApiError ? error : null
      // 关键：决定可能已经落库，只是恢复没起来。用真实状态判断。
      await syncApproval(task)
      /*
       * 只有 APPROVED / REJECTED 才算"决定已保存"。
       * EXPIRED / CANCELLED 虽然 status 也变了，但那是审批失效，不是用户决定落库，
       * 把它们算成"已保存"会让用户以为可以手动恢复，实际恢复不了。
       */
      const decided =
        task.approval?.status === 'APPROVED' || task.approval?.status === 'REJECTED'
      task.error = {
        kind: apiError?.kind ?? 'unknown',
        code: apiError?.code ?? null,
        text: decided
          ? `${describeError(error)}决定已保存，可以点"手动恢复"重试。`
          : describeError(error),
        hasRunId: !!task.runId,
      }
      await verify(task, { poll: false })
    } finally {
      decidingApprovals.delete(decideKey(task, approvalId))
      task.deciding = false
      if (activeController === controller) {
        activeController = null
      }
    }
  },

  /** 手动恢复（决定已保存、恢复没起来时使用）。必须沿用原 approvalId。 */
  async resume(id: string): Promise<void> {
    const task = tasks.value.find((item) => item.id === id)
    const approvalId = task?.approvalPayload?.approvalId
    if (!task || !approvalId) {
      return
    }
    if (task.deciding || decidingApprovals.has(decideKey(task, approvalId))) {
      return
    }

    decidingApprovals.add(decideKey(task, approvalId))
    task.deciding = true
    task.error = null
    const controller = new AbortController()
    activeController = controller
    task.transport = 'submitting'

    try {
      await resumeApproval(approvalId, {
        signal: controller.signal,
        onEvent: (event) => applyEvent(task, event),
      })
      task.transport = 'closed'
      await syncApproval(task)
      await verify(task, { poll: true, signal: controller.signal })
    } catch (error) {
      task.transport = 'closed'
      const apiError = error instanceof ApiError ? error : null
      await syncApproval(task)
      task.error = {
        kind: apiError?.kind ?? 'unknown',
        code: apiError?.code ?? null,
        text: describeError(error),
        hasRunId: !!task.runId,
      }
      await verify(task, { poll: false })
    } finally {
      decidingApprovals.delete(decideKey(task, approvalId))
      task.deciding = false
      if (activeController === controller) {
        activeController = null
      }
    }
  },

  /** 把一条历史任务加载进工作区（只读查看，不重新执行） */
  async openHistoryRun(runId: string): Promise<void> {
    const existing = tasks.value.find((task) => task.runId === runId)
    if (existing) {
      activeId.value = existing.id
      return
    }
    const placeholder = newTask('', 'MUSIC_USER')
    placeholder.runId = runId
    placeholder.transport = 'closed'
    tasks.value = [placeholder, ...tasks.value]
    activeId.value = placeholder.id
    await verify(placeholder, { poll: false })
    if (placeholder.detail) {
      placeholder.prompt = placeholder.detail.userPrompt ?? ''
      placeholder.entry = (placeholder.detail.agentType as MusicEntry) ?? 'MUSIC_USER'
    }
  },

  /** 任务级原因码解释（PROCESS_RESTART 等） */
  runErrorHint(task: SessionTask): string {
    return describeRunErrorCode(task.detail?.errorCode ?? null)
  },

  /**
   * 把一个会话的历史消息还原成可渲染的轮次。
   *
   * <p>为什么复用 SessionTask：一轮 = 一个 run = 一张审批卡 + 一条回答，
   * 与现有的任务视图完全同构。另造一套"消息"模型会让审批卡、
   * 断流核对、状态徽标都要重写一遍。
   *
   * <p>两条纪律：
   * 1. 只按 runId 认领已有任务，绝不重复插入（live 任务可能已经在列表里）；
   * 2. 待审批的轮次要**重建审批卡**——只显示"等待审批"却不给按钮，
   *    用户只能新建会话重来。approvalId 来自服务端 join，不是前端猜的。
   */
  async hydrateConversation(detail: ConversationDetailVO): Promise<void> {
    const byRun = new Map<
      string,
      { user?: ConversationMessageVO; assistant?: ConversationMessageVO }
    >()

    for (const message of detail.messages) {
      const pair = byRun.get(message.runId) ?? {}
      if (message.role === 'USER') {
        pair.user = message
      } else if (message.role === 'ASSISTANT') {
        pair.assistant = message
      }
      byRun.set(message.runId, pair)
    }

    const pending: Array<{ task: SessionTask; approvalId: string }> = []

    for (const [runId, pair] of byRun) {
      const existing = tasks.value.find((task) => task.runId === runId)
      const task =
        existing ?? newTask(pair.user?.content ?? '', 'MUSIC_USER', detail.conversationId)

      if (!existing) {
        task.runId = runId
        task.transport = 'closed'
        const at = pair.user?.createdAt ? Date.parse(pair.user.createdAt) : NaN
        if (!Number.isNaN(at)) {
          task.createdAt = at
        }
        tasks.value = [task, ...tasks.value]
      }

      task.conversationId = detail.conversationId
      if (pair.user?.content) {
        task.prompt = pair.user.content
      }
      if (pair.assistant) {
        task.answer = pair.assistant.content ?? ''
        task.serverState = pair.assistant.state ?? task.serverState
      }

      /*
       * 待审批：服务端 join 出来的 approvalId 是唯一可信来源。
       * 拿到就立刻查一次审批视图，用真实 summary 重建卡片。
       */
      if (pair.user?.approvalId && !task.approvalPayload) {
        task.serverState = 'WAITING_APPROVAL'
        pending.push({ task, approvalId: pair.user.approvalId })
      }
    }

    await Promise.all(
      pending.map(async ({ task, approvalId }) => {
        try {
          const view = await getApproval(approvalId)
          task.approval = view
          if (!view.summary) {
            return
          }
          task.approvalPayload = {
            approvalId: view.approvalId,
            expiresAt: view.expiresAt ?? '',
            summary: {
              tool: view.summary.tool ?? '',
              toolLabel: view.summary.toolLabel ?? '',
              playlistName: view.summary.playlistName ?? null,
              songCount: view.summary.songCount ?? null,
              songs: view.summary.songs ?? [],
              roundToolCount: view.summary.roundToolCount ?? 0,
              text: view.summary.text ?? '',
              details: view.summary.details ?? [],
            },
          }
        } catch {
          // 审批可能刚好被处理掉：查不到就不显示卡片，不编一张出来
        }
      }),
    )
  },
}

/* ==================== 历史列表 ==================== */

interface HistoryState {
  records: AgentRunVO[]
  loading: boolean
  error: string | null
  pageNum: number
  pages: number
  total: number
}

export const history = reactive<HistoryState>({
  records: [],
  loading: false,
  error: null,
  pageNum: 1,
  pages: 0,
  total: 0,
})

let historyController: AbortController | null = null

/**
 * 拉取任务历史。
 *
 * 只查自己的任务（后端按请求头身份过滤），并且只查音乐类 Agent。
 * 这是查询接口，允许重复调用；失败不自动重试，由用户点"重试"。
 */
export async function loadHistory(options: { reset?: boolean } = {}): Promise<void> {
  const { reset = false } = options
  if (history.loading) {
    return
  }
  historyController?.abort()
  historyController = new AbortController()

  history.loading = true
  history.error = null
  const pageNum = reset ? 1 : history.pageNum + 1

  try {
    const result = await pageRuns({
      pageNum,
      pageSize: 20,
      signal: historyController.signal,
    })
    const musicRecords = result.records.filter(
      (record) => record.agentType === 'MUSIC_USER' || record.agentType === 'MUSIC_ADMIN',
    )
    history.records = reset ? musicRecords : [...history.records, ...musicRecords]
    history.pageNum = result.pageNum
    history.pages = result.pages
    history.total = result.total
  } catch (error) {
    if (error instanceof ApiError && error.kind === 'aborted') {
      return
    }
    history.error = describeError(error)
  } finally {
    history.loading = false
  }
}

export function hasMoreHistory(): boolean {
  return history.pages > 0 && history.pageNum < history.pages
}
