import { describe, expect, it, vi } from 'vitest'
import { createProvidersStore } from './providers'
import { ApiError } from '@/api/errors'
import type {
  ProviderAggregateVO,
  ProviderTrackVO,
  ProviderVO,
} from '@/api/providers'

/**
 * 音乐账号 store 的边界。
 *
 * 重点不是"能不能请求"，而是三种容易做错的状态：
 * 1. 零连接时不能把界面变成错误页（本地功能仍可用）；
 * 2. 一个来源失败不能拖垮整页；
 * 3. 同名歌曲不能在前端被合并。
 */

function provider(overrides: Partial<ProviderVO> = {}): ProviderVO {
  return {
    provider: 'demo-alpha',
    displayName: 'Alpha 音乐（模拟）',
    simulated: true,
    configured: true,
    capabilities: ['READ_LIKED', 'READ_PLAYLISTS', 'READ_RECENT', 'CATALOG_SEARCH'],
    connected: false,
    status: null,
    scopes: null,
    providerUserId: null,
    connectedAtEpochSecond: null,
    ...overrides,
  }
}

function track(overrides: Partial<ProviderTrackVO> = {}): ProviderTrackVO {
  return {
    provider: 'demo-alpha',
    providerTrackId: 'a-1',
    title: '夜航星',
    artist: '林晚',
    album: '夜航',
    version: null,
    durationSeconds: 241,
    playable: false,
    playableReason: '外部来源仅只读展示：本轮不代理第三方流',
    ...overrides,
  }
}

function aggregate(overrides: Partial<ProviderAggregateVO> = {}): ProviderAggregateVO {
  return { tracks: [], playlists: [], sources: [], ...overrides }
}

function build(overrides: {
  list?: () => Promise<ProviderVO[]>
  read?: (params: unknown) => Promise<ProviderAggregateVO>
  connect?: (provider: string) => Promise<ProviderVO>
  disconnect?: (provider: string) => Promise<void>
} = {}) {
  const list = vi.fn(overrides.list ?? (async () => [provider()]))
  const read = vi.fn(overrides.read ?? (async () => aggregate()))
  const connect = vi.fn(overrides.connect ?? (async (id: string) => provider({ provider: id, connected: true })))
  const disconnect = vi.fn(overrides.disconnect ?? (async () => undefined))

  const store = createProvidersStore({
    list: list as never,
    read: read as never,
    connect: connect as never,
    disconnect: disconnect as never,
  })

  return { store, list, read, connect, disconnect }
}

describe('音乐账号 store', () => {
  it('零连接：目录可用、连接数为 0、数据为空且不是错误', async () => {
    const { store } = build()

    await store.open()

    expect(store.state.catalog).toHaveLength(1)
    expect(store.connectedCount.value).toBe(0)
    expect(store.state.tracks).toHaveLength(0)
    expect(store.state.error).toBeNull()
  })

  it('单连接：连接后刷新目录，并把过滤切到该来源', async () => {
    const { store, list, read } = build({
      list: async () => [provider({ connected: true, status: 'CONNECTED' })],
      read: async () => aggregate({ tracks: [track()] }),
    })

    await store.connect('demo-alpha')

    expect(list).toHaveBeenCalled()
    expect(store.connectedCount.value).toBe(1)
    expect(store.state.filter).toBe('demo-alpha')
    expect(read).toHaveBeenCalledWith(
      expect.objectContaining({ provider: 'demo-alpha', kind: 'LIKED' }),
    )
    expect(store.state.tracks).toHaveLength(1)
  })

  it('多连接：两个来源的同名歌曲都保留（不按歌名去重）', async () => {
    const { store } = build({
      list: async () => [
        provider({ connected: true, status: 'CONNECTED' }),
        provider({
          provider: 'demo-beta',
          displayName: 'Beta 音乐（模拟）',
          connected: true,
          status: 'CONNECTED',
        }),
      ],
      read: async () =>
        aggregate({
          tracks: [
            track({ provider: 'demo-alpha', providerTrackId: 'a-1' }),
            track({ provider: 'demo-beta', providerTrackId: 'b-1' }),
          ],
          sources: [
            { provider: 'demo-alpha', displayName: 'Alpha', simulated: true, status: 'OK', errorCode: null, message: null, elapsedMs: 1 },
            { provider: 'demo-beta', displayName: 'Beta', simulated: true, status: 'OK', errorCode: null, message: null, elapsedMs: 1 },
          ],
        }),
    })

    await store.open()

    const nightSongs = store.state.tracks.filter((item) => item.title === '夜航星')
    expect(nightSongs).toHaveLength(2)
    expect(store.itemsOf('demo-alpha')).toHaveLength(1)
    expect(store.itemsOf('demo-beta')).toHaveLength(1)
  })

  it('一个来源超时：其它来源的条目照常展示，整页不报错', async () => {
    const { store } = build({
      list: async () => [
        provider({ connected: true, status: 'CONNECTED' }),
        provider({ provider: 'demo-beta', connected: true, status: 'CONNECTED' }),
      ],
      read: async () =>
        aggregate({
          tracks: [track({ provider: 'demo-alpha' })],
          sources: [
            { provider: 'demo-alpha', displayName: 'Alpha', simulated: true, status: 'OK', errorCode: null, message: null, elapsedMs: 1 },
            { provider: 'demo-beta', displayName: 'Beta', simulated: true, status: 'TIMEOUT', errorCode: 'PROVIDER_TIMEOUT', message: '该来源响应超时', elapsedMs: 1500 },
          ],
        }),
    })

    await store.open()

    expect(store.state.error).toBeNull()
    expect(store.state.tracks).toHaveLength(1)
    expect(store.statusOf('demo-beta')?.status).toBe('TIMEOUT')
  })

  it('传输层失败：整页错误，条目清空（与来源级失败区分）', async () => {
    const { store } = build({
      read: async () => {
        throw new ApiError('network', { message: '网络异常' })
      },
    })

    await store.open()

    expect(store.state.error).not.toBeNull()
    expect(store.state.tracks).toHaveLength(0)
  })

  it('切换来源过滤会重新读取，并回到第 1 页', async () => {
    const { store, read } = build({
      read: async () => aggregate({ tracks: [track()] }),
    })

    await store.open()
    await store.setFilter('demo-beta')

    expect(store.state.page).toBe(1)
    expect(read).toHaveBeenLastCalledWith(
      expect.objectContaining({ provider: 'demo-beta' }),
    )
  })

  it('断开当前过滤来源：过滤回到"全部"', async () => {
    const { store } = build({
      list: async () => [provider({ connected: false })],
      read: async () => aggregate(),
    })

    await store.open()
    await store.setFilter('demo-alpha')
    await store.disconnect('demo-alpha')

    expect(store.state.filter).toBeNull()
  })

  it('绑定被拒绝：给出可读原因，而不是原始状态码', async () => {
    const { store } = build({
      connect: async () => {
        throw new ApiError('conflict', {
          status: 409,
          code: 'IDENTITY_NOT_TRUSTED',
          message: 'identity not trusted',
        })
      },
    })

    await store.connect('test-real')

    expect(store.state.catalogError).toContain('可信的服务端身份')
    expect(store.state.catalogError).not.toContain('409')
  })
})
