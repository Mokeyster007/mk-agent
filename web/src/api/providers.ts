/**
 * 音乐账号（第三方平台只读连接）接口。
 *
 * 三条约定：
 * 1. 所有请求只带 MkAgent 身份（X-User-Id），**不携带**任何第三方凭据；
 * 2. 聚合接口一次返回"条目 + 每个来源的状态"：
 *    一个来源失败只影响它自己那一块，前端不需要自己拼错误；
 * 3. 外部曲目的身份是 `provider + providerTrackId`，不是歌名，
 *    也**没有**本地 songId（不自动导入曲库）。
 */
import { requestJson } from './client'

export type ProviderCapabilityName =
  | 'CATALOG_SEARCH'
  | 'READ_PLAYLISTS'
  | 'READ_LIKED'
  | 'READ_RECENT'
  | 'PLAYBACK'

export interface ProviderVO {
  provider: string
  displayName: string
  /** 是否为模拟来源（没有真实凭据） */
  simulated: boolean
  /** 凭据/合作权限是否到位 */
  configured: boolean
  capabilities: ProviderCapabilityName[]
  connected: boolean
  status: string | null
  scopes: string | null
  providerUserId: string | null
  connectedAtEpochSecond: number | null
}

export interface ProviderConnectionVO {
  provider: string
  displayName: string
  status: string
  scopes: string | null
  connectedAtEpochSecond: number | null
}

export interface ProviderTrackVO {
  provider: string
  providerTrackId: string
  title: string
  artist: string
  album: string | null
  version: string | null
  durationSeconds: number | null
  playable: boolean
  playableReason: string | null
}

export interface ProviderPlaylistVO {
  provider: string
  providerPlaylistId: string
  name: string
  trackCount: number | null
}

/** 单个来源的读取结果。status: OK / TIMEOUT / ERROR / PERMISSION_DENIED / EXPIRED / ... */
export interface ProviderSourceStatusVO {
  provider: string
  displayName: string
  simulated: boolean
  status: string
  errorCode: string | null
  message: string | null
  elapsedMs: number
}

export interface ProviderAggregateVO {
  tracks: ProviderTrackVO[]
  playlists: ProviderPlaylistVO[]
  sources: ProviderSourceStatusVO[]
}

export type ProviderTrackKind = 'PLAYLISTS' | 'LIKED' | 'RECENT' | 'SEARCH'

export function listProviders(signal?: AbortSignal): Promise<ProviderVO[]> {
  return requestJson<ProviderVO[]>('/api/ai/music/providers', { signal })
}

export function listConnections(signal?: AbortSignal): Promise<ProviderConnectionVO[]> {
  return requestJson<ProviderConnectionVO[]>('/api/ai/music/providers/connections', { signal })
}

export function connectProvider(
  provider: string,
  signal?: AbortSignal,
): Promise<ProviderVO> {
  return requestJson<ProviderVO>(
    `/api/ai/music/providers/${encodeURIComponent(provider)}/connect`,
    { method: 'POST', signal },
  )
}

export function disconnectProvider(
  provider: string,
  signal?: AbortSignal,
): Promise<void> {
  return requestJson<void>(
    `/api/ai/music/providers/${encodeURIComponent(provider)}/connection`,
    { method: 'DELETE', signal },
  )
}

export function readProviderTracks(
  params: {
    kind: ProviderTrackKind
    provider?: string | null
    q?: string | null
    page?: number
    size?: number
  },
  signal?: AbortSignal,
): Promise<ProviderAggregateVO> {
  return requestJson<ProviderAggregateVO>('/api/ai/music/providers/tracks', {
    query: {
      kind: params.kind,
      provider: params.provider ?? undefined,
      q: params.q ?? undefined,
      page: params.page ?? 1,
      size: params.size ?? 20,
    },
    signal,
  })
}
