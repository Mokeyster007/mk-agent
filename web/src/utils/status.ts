/**
 * 状态映射（集中一处，界面只引用这里）。
 *
 * 原则：**不为了界面好看去改后端枚举**，只做"枚举 → 文案 + 色调"的翻译。
 * 遇到未知枚举一律显示原值 + 中性色，不猜测含义。
 */

export type Tone = 'neutral' | 'active' | 'wait' | 'ok' | 'warn' | 'danger'

export interface StateMeta {
  label: string
  tone: Tone
  /** 是否属于"任务已结束" */
  terminal: boolean
  /** 给用户的一句话解释 */
  hint?: string
}

/** agent_run.state → 展示信息。取值来自后端 AgentState。 */
const STATE_MAP: Record<string, StateMeta> = {
  IDLE: { label: '已创建', tone: 'neutral', terminal: false },
  RUNNING: { label: '执行中', tone: 'active', terminal: false },
  WAITING_APPROVAL: {
    label: '等待确认',
    tone: 'wait',
    terminal: false,
    hint: '任务已暂停，等待对写操作做出决定。',
  },
  SUCCEEDED: { label: '已完成', tone: 'ok', terminal: true },
  FAILED: { label: '失败', tone: 'danger', terminal: true },
  CANCELLED: { label: '已取消', tone: 'neutral', terminal: true },
  TIMED_OUT: { label: '已超时', tone: 'warn', terminal: true },
  MAX_STEPS_REACHED: {
    label: '已达步数上限',
    tone: 'warn',
    terminal: true,
    hint: '任务因达到步数或工具调用上限被安全停止。',
  },
}

export function stateMeta(state: string | null | undefined): StateMeta {
  if (!state) {
    return { label: '状态未知', tone: 'neutral', terminal: false }
  }
  return STATE_MAP[state] ?? { label: state, tone: 'neutral', terminal: false }
}

/**
 * 前端传输层状态。
 *
 * 与后端 state 严格分开：
 * 传输层描述"这条连接怎么了"，后端 state 描述"任务怎么了"。
 * 两者不能互相冒充——连接断开不等于任务取消。
 */
export type Transport = 'idle' | 'submitting' | 'streaming' | 'closed' | 'aborted' | 'unknown'

const TRANSPORT_MAP: Record<Transport, { label: string; tone: Tone }> = {
  idle: { label: '未开始', tone: 'neutral' },
  submitting: { label: '正在提交', tone: 'active' },
  streaming: { label: '接收中', tone: 'active' },
  closed: { label: '连接已结束', tone: 'neutral' },
  aborted: { label: '连接已中止', tone: 'warn' },
  unknown: { label: '结果未知', tone: 'warn' },
}

export function transportMeta(transport: Transport): { label: string; tone: Tone } {
  return TRANSPORT_MAP[transport] ?? TRANSPORT_MAP.unknown
}

/** agent_approval.status → 文案 */
const APPROVAL_STATUS_MAP: Record<string, { label: string; tone: Tone }> = {
  PENDING: { label: '待确认', tone: 'wait' },
  APPROVED: { label: '已批准', tone: 'ok' },
  REJECTED: { label: '已拒绝', tone: 'neutral' },
  EXPIRED: { label: '已过期', tone: 'warn' },
  CANCELLED: { label: '已撤销', tone: 'neutral' },
}

export function approvalStatusMeta(status: string | null | undefined): { label: string; tone: Tone } {
  if (!status) {
    return { label: '未知', tone: 'neutral' }
  }
  return APPROVAL_STATUS_MAP[status] ?? { label: status, tone: 'neutral' }
}

/**
 * agent_approval.resume_state → 文案。
 *
 * 这一列回答的是"决定保存之后，执行有没有被真正接上"，
 * 是"决定已保存、恢复失败"这类中间态的唯一可靠依据。
 */
const RESUME_STATE_MAP: Record<string, { label: string; tone: Tone }> = {
  PENDING_RESUME: { label: '等待恢复', tone: 'wait' },
  CLAIMED: { label: '已领取执行权', tone: 'active' },
  RUNNING: { label: '正在恢复执行', tone: 'active' },
  FINISHED: { label: '恢复执行已结束', tone: 'ok' },
  FAILED: { label: '恢复失败', tone: 'danger' },
}

export function resumeStateMeta(state: string | null | undefined): { label: string; tone: Tone } {
  if (!state) {
    return { label: '未知', tone: 'neutral' }
  }
  return RESUME_STATE_MAP[state] ?? { label: state, tone: 'neutral' }
}

/** 工具名 → 中文动作名。只映射后端白名单里真实存在的工具。 */
const TOOL_LABEL_MAP: Record<string, string> = {
  search_songs: '搜索歌曲',
  get_song_detail: '查询歌曲详情',
  get_user_playlists: '查询我的歌单',
  get_playlist_songs: '查询歌单歌曲',
  get_play_stats: '统计播放数据',
  get_top_songs: '查询热门歌曲',
  create_playlist: '创建歌单',
  add_songs_to_playlist: '向歌单添加歌曲',
  terminate_task: '结束任务',
}

export function toolLabel(tool: string | null | undefined): string {
  if (!tool) {
    return '未知工具'
  }
  return TOOL_LABEL_MAP[tool] ?? tool
}

/** 只读工具集合，用于给工具轨迹加"只读/写入"标记 */
const WRITE_TOOLS = new Set(['create_playlist', 'add_songs_to_playlist'])

export function isWriteTool(tool: string | null | undefined): boolean {
  return !!tool && WRITE_TOOLS.has(tool)
}
