/**
 * 播放接口。
 *
 * 三条约定：
 * 1. 点播请求只发 songId（和可选的 playlistId）——**不发地址**：
 *    音源地址只能由服务端产生，客户端自带 URL 的请求在后端没有对应入参；
 * 2. 媒体地址是短期签名 URL，`<audio>` 直接 GET 即可，不加任何业务请求头
 *    （自定义头加不上去，服务端也不需要：身份签在地址里）；
 * 3. 「正在播放」上报只用于**服务端复核后的播放事实**，
 *    它不改变播放本身，失败也不影响出声。
 */
import { requestJson } from './client'
import type {
  NowPlayingVO,
  PlaybackCandidateVO,
  PlaybackResolveVO,
} from './types'

/** 解析一首歌能不能播、怎么播。 */
export function resolvePlayback(
  songId: number,
  playlistId?: number | null,
  signal?: AbortSignal,
): Promise<PlaybackResolveVO> {
  return requestJson<PlaybackResolveVO>('/api/ai/music/playback/resolve', {
    method: 'POST',
    body: playlistId ? { songId, playlistId } : { songId },
    signal,
  })
}

/** 会话内可播放候选（来自会话真实工具引用）。 */
export function getPlaybackCandidates(
  conversationId: string,
  signal?: AbortSignal,
): Promise<PlaybackCandidateVO[]> {
  return requestJson<PlaybackCandidateVO[]>('/api/ai/music/playback/candidates', {
    query: { conversationId },
    signal,
  })
}

/** 活跃播放事件。语义见后端 PlaybackEvent。 */
export type PlaybackEvent = 'START' | 'HEARTBEAT' | 'PAUSE' | 'STOP'

/**
 * 上报活跃播放事件。
 *
 * 只在真实媒体事件后调用：
 * - `START`    playing 事件首次触发（或从暂停恢复）
 * - `HEARTBEAT` 播放中每 60 秒一次（服务端 180 秒无心跳即失效）
 * - `PAUSE`    pause 事件
 * - `STOP`     用户关闭播放器 / 队列自然播完
 *
 * `instanceId` 是本次播放尝试的标识。心跳、暂停、停止必须带同一个值：
 * 服务端据此拒绝旧标签页的迟到请求（409 PLAYBACK_INSTANCE_STALE）。
 *
 * 失败不影响播放本身，调用方应当静默忽略。
 */
export async function reportNowPlaying(
  songId: number,
  event: PlaybackEvent,
  instanceId: string,
  signal?: AbortSignal,
): Promise<void> {
  await requestJson<NowPlayingVO>('/api/ai/music/playback/now-playing', {
    method: 'POST',
    body: { songId, event, instanceId },
    signal,
  })
}
