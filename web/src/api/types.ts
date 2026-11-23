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

/** 电商 Agent 入口类型，对应后端 AgentType */
export type ShopAgentEntry = 'SHOP_ASSISTANT' | 'AFTER_SALES'

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
  | 'structured_data'
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

/**
 * 确认卡上的一条"标签 值"。
 *
 * 售后用它固定订单号、商品、数量、原因；音乐链路没有 details（空数组），
 * 仍然走 playlistName / songs 那一套渲染。
 */
export interface ApprovalSummaryDetail {
  label: string
  value: string
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
  /** 业务扩展给出的结构化字段；没有时为空数组 */
  details: ApprovalSummaryDetail[]
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

/* ==================== 会话（同一窗口连续聊天） ==================== */

/**
 * 会话消息，见 ConversationController.MessageVO。
 *
 * 只存"用户输入"与"模型最终回答"的文本：工具调用不进会话，
 * 回放旧工具调用会让模型把历史指令当成本轮指令再执行一次。
 */
export interface ConversationMessageVO {
  runId: string
  role: 'USER' | 'ASSISTANT' | string
  content: string
  /** 该轮 run 的终态快照；WAITING_APPROVAL 表示还在等审批 */
  state: string | null
  createdAt: string | null
  /** 该轮仍有待审批时的 approvalId，否则 null（刷新后靠它重建审批卡） */
  approvalId: string | null
}

/** 会话列表项，见 ConversationController.ConversationVO。 */
export interface ConversationVO {
  conversationId: string
  title: string | null
  messageCount: number
  lastState: string | null
  updatedAt: string | null
}

/** 会话详情，见 ConversationController.ConversationDetailVO。 */
export interface ConversationDetailVO {
  conversationId: string
  title: string | null
  messages: ConversationMessageVO[]
  updatedAt: string | null
}

/* ==================== 播放（MVP） ==================== */

/** 音源交付方式，见 PlaybackMode。本轮只真实实现 DIRECT_AUDIO。 */
export type PlaybackMode =
  | 'DIRECT_AUDIO'
  | 'PROVIDER_SDK'
  | 'EXTERNAL_LINK'
  | 'UNAVAILABLE'

/** 曲目信息（播放器显示用，不含任何内部路径）。 */
export interface PlaybackSongVO {
  id: number
  title: string
  artist: string
  album: string | null
  durationSeconds: number | null
  releaseYear: number | null
}

/**
 * 解析结果，见 PlaybackService.ResolveResult。
 *
 * `playable=false` 时一定带 `reasonCode` 与 `reason`：
 * 页面必须显示"为什么不能播"，而不是一个笼统的播放失败。
 */
export interface PlaybackResolveVO {
  playable: boolean
  song: PlaybackSongVO
  mode: PlaybackMode | string
  provider: string | null
  format: string | null
  contentType: string | null
  /** 短期媒体地址（相对路径，含 /api 前缀）；不可播时为 null */
  streamUrl: string | null
  /** 地址过期时间（epoch 秒）；不可播时为 null */
  expiresAt: number | null
  capabilities: { seek: boolean; range: boolean; eq: boolean }
  reasonCode: string | null
  reason: string | null
}

/** 会话内候选，见 PlaybackService.Candidate。 */
export interface PlaybackCandidateVO {
  songId: number
  title: string
  artist: string
  album: string | null
  durationSeconds: number | null
  playable: boolean
  format: string | null
  unavailableReason: string | null
}

/** 当前曲目，见 PlaybackService.NowPlayingView。 */
export interface NowPlayingVO {
  songId: number
  title: string
  artist: string
  state: 'PLAYING' | 'PAUSED' | 'STOPPED' | string
  updatedAtEpochSecond: number
}
