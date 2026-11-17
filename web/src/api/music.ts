/**
 * 音乐 Agent 入口。
 *
 * 两个入口都是 GET + query 参数 message，返回 SSE：
 *   GET /api/ai/music/chat?message=...
 *   GET /api/ai/music/admin/chat?message=...
 *
 * agentType 由入口决定，客户端传不进去，也不该传。
 * 权限（401/403）在 Agent 启动之前就已经判定，因此失败时拿到的是
 * 普通 JSON 错误响应，而不是 SSE 流。
 */
import type { AgentEvent, CapabilitiesVO, MusicEntry, PlaylistVO, SongVO } from './types'
import { streamEvents } from './sse'
import { requestJson } from './client'

const ENTRY_PATH: Record<MusicEntry, string> = {
  MUSIC_USER: '/api/ai/music/chat',
  MUSIC_ADMIN: '/api/ai/music/admin/chat',
}

export function musicEntryPath(entry: MusicEntry): string {
  return ENTRY_PATH[entry]
}

export interface MusicChatOptions {
  message: string
  entry: MusicEntry
  signal: AbortSignal
  onEvent: (event: AgentEvent) => void
}

/**
 * 发起一次音乐任务。
 *
 * 每次调用都会创建一个**独立任务**：后端 AgentRunContext 没有会话关联，
 * 这里也不拼历史消息，所以前端不能假装这是"接着上一轮说"。
 */
export async function runMusicChat(options: MusicChatOptions): Promise<void> {
  const { message, entry, signal, onEvent } = options

  await streamEvents(musicEntryPath(entry), { method: 'GET', query: { message }, signal }, (frame) => {
    const event = parseEvent(frame.data)
    if (event) {
      onEvent(event)
    }
  })
}

/**
 * 解析 SSE data。
 *
 * 后端 data 是 AgentEvent 的 JSON；解析失败说明帧不完整或协议变了，
 * 这时宁可丢弃也不构造假事件——界面上少一条进度，好过多一条编造的进度。
 */
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

/* ==================== 音乐库只读接口 ==================== */

/**
 * 本人歌单（含歌曲数）。
 *
 * 空数组是正常状态（还没有歌单），页面据此显示空状态而不是错误。
 */
export function listPlaylists(signal?: AbortSignal): Promise<PlaylistVO[]> {
  return requestJson<PlaylistVO[]>('/api/ai/music/playlists', { signal })
}

/** 某个本人歌单的曲目（按歌单内顺序）。非本人歌单 → 404。 */
export function listPlaylistSongs(
  playlistId: number,
  signal?: AbortSignal,
): Promise<SongVO[]> {
  return requestJson<SongVO[]>(
    `/api/ai/music/playlists/${encodeURIComponent(String(playlistId))}/songs`,
    { signal },
  )
}

/**
 * 当前装配的能力。
 *
 * 失败时调用方应回退到"只读"文案：能力探测失败不等于能力存在，
 * 宁可显示得保守一点，也不能让用户以为可以写。
 */
export function getCapabilities(signal?: AbortSignal): Promise<CapabilitiesVO> {
  return requestJson<CapabilitiesVO>('/api/ai/music/capabilities', { signal })
}
