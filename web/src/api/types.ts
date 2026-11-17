/**
 * 后端接口的类型定义。
 *
 * 全部字段都对照当前后端代码抄写，不做"顺手美化"：
 * - AgentRunVO / PageResult：com.example.mkagent.model.vo
 * - AgentEvent / AgentEventType：com.example.mkagent.model
 * - ApprovalView：com.example.mkagent.approval
 * - approval_required 事件 data：ApprovalPauseService.buildEventJson
 *
 * 后端新增字段是向后兼容的，因此这里对未知字段保持宽容（不启用严格校验）。
 */

/** agent_run.state 的取值，见 AgentState */
export type AgentState =
  | 'IDLE'
  | 'RUNNING'
  | 'WAITING_APPROVAL'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMED_OUT'
  | 'MAX_STEPS_REACHED'

/** 音乐相关的两个入口，对应 AgentType */
export type MusicEntry = 'MUSIC_USER' | 'MUSIC_ADMIN'

export interface AgentRunVO {
  runId: string
  userId: string
  agentType: string
  userPrompt: string | null
  state: AgentState | string | null
  currentStep: number | null
  toolCallCount: number | null
  /** 逗号分隔的工具名序列，不含参数与结果原文 */
  toolTrace: string | null
  finalAnswer: string | null
  errorMessage: string | null
  /** 稳定原因码，前端只认这个，不解析 errorMessage */
  errorCode: string | null
  model: string | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  startedAt: string | null
  finishedAt: string | null
  totalCostMillis: number | null
  createdAt: string | null
}

export interface PageResult<T> {
  pageNum: number
  pageSize: number
  total: number
  pages: number
  records: T[]
}

/** SSE 事件类型，与后端 AgentEventType.wireName 一一对应 */
export type AgentEventType =
  | 'run_id'
  | 'status'
  | 'step'
  | 'tool_start'
  | 'tool_result'
  | 'final_answer'
  | 'error'
  | 'done'
  | 'cancelled'
  | 'approval_required'

export interface AgentEvent {
  runId: string
  type: AgentEventType | string
  /** 已脱敏的用户可读文本；approval_required 时是一段 JSON 字符串 */
  message: string
  step: number | null
  timestamp: number
}

/** 审批摘要，见 com.example.mkagent.approval.ApprovalSummary */
export interface ApprovalSummary {
  tool: string
  toolLabel: string
  playlistName: string | null
  songCount: number | null
  songs: string[]
  roundToolCount: number
  text: string
}

/** approval_required 事件 data 解析后的结构 */
export interface ApprovalRequiredPayload {
  approvalId: string
  expiresAt: string
  summary: ApprovalSummary
}

/** 审批查询视图，见 com.example.mkagent.approval.ApprovalView */
export interface ApprovalView {
  approvalId: string
  runId: string
  agentType: string
  step: number
  primaryTool: string
  toolCount: number
  summaryText: string
  summary: ApprovalSummary | null
  /** PENDING / APPROVED / REJECTED / EXPIRED / CANCELLED ... */
  status: string
  /** PENDING_RESUME / CLAIMED / RUNNING / FINISHED / FAILED ... */
  resumeState: string
  resumeAttempts: number
  decidedAt: string | null
  expiresAt: string | null
}

/** 歌单视图，见 com.example.mkagent.controller.MusicLibraryController.PlaylistVO */
export interface PlaylistVO {
  id: number
  name: string
  description: string | null
  isPublic: boolean
  songCount: number
}

/** 歌曲视图，见 MusicLibraryController.SongVO */
export interface SongVO {
  id: number
  title: string
  artist: string
  album: string | null
  durationSeconds: number | null
  genre: string | null
  releaseYear: number | null
}

/**
 * 能力探测，见 MusicLibraryController.CapabilitiesVO。
 *
 * 这是**运行时**结果，不是构建期常量：后端装配决定模型有没有写工具，
 * 界面照它显示，避免"页面说能写、后端其实没给工具"。
 */
export interface CapabilitiesVO {
  writeApproval: boolean
  promptVersion: string
}
