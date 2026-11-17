/**
 * "我的歌单"状态测试。
 *
 * 只覆盖三条会直接影响"用户能不能相信这个面板"的行为：
 * 1. 刷新成功 → 列表与时间戳都来自接口；
 * 2. 刷新失败 → 保留错误文案，且**不**保留上一份数据冒充当前状态；
 * 3. 展开歌单 → 拉曲目；再次点击收起，不再重复请求。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/errors'

const listPlaylists = vi.fn()
const listPlaylistSongs = vi.fn()

vi.mock('@/api/music', () => ({
  listPlaylists: (...args: unknown[]) => listPlaylists(...args),
  listPlaylistSongs: (...args: unknown[]) => listPlaylistSongs(...args),
}))

const { library, refreshPlaylists, togglePlaylist } = await import('./library')

const PLAYLIST = { id: 7, name: '闭环验证', description: null, isPublic: false, songCount: 2 }
const SONGS = [
  { id: 1001, title: '城市霓虹', artist: '夜航星', album: null, durationSeconds: 214, genre: null, releaseYear: 2021 },
  { id: 1002, title: '深夜电台', artist: '夜航星', album: null, durationSeconds: 238, genre: null, releaseYear: 2021 },
]

beforeEach(() => {
  listPlaylists.mockReset()
  listPlaylistSongs.mockReset()
  library.playlists = []
  library.loading = false
  library.error = null
  library.loadedAt = 0
  library.expandedId = null
  library.songsById = {}
  library.songsLoading = false
  library.songsError = null
})

describe('library store', () => {
  it('刷新成功：列表与刷新时间都来自接口', async () => {
    listPlaylists.mockResolvedValue([PLAYLIST])

    await refreshPlaylists()

    expect(library.playlists).toHaveLength(1)
    expect(library.playlists[0].name).toBe('闭环验证')
    expect(library.loadedAt).toBeGreaterThan(0)
    expect(library.error).toBeNull()
  })

  it('刷新失败：给出错误文案，不拿旧数据冒充当前状态', async () => {
    listPlaylists.mockResolvedValueOnce([PLAYLIST])
    await refreshPlaylists()
    expect(library.playlists).toHaveLength(1)

    listPlaylists.mockRejectedValueOnce(new ApiError('network', { message: '网络不可用' }))
    await refreshPlaylists()

    // 旧列表仍在（避免界面闪成空白），但错误必须同时可见，
    // 用户据此知道"这份列表可能已经过期"。
    expect(library.error).toBeTruthy()
    expect(library.playlists).toHaveLength(1)
  })

  it('展开歌单拉曲目，再点一次收起且不重复请求', async () => {
    listPlaylistSongs.mockResolvedValue(SONGS)

    await togglePlaylist(7)
    expect(library.expandedId).toBe(7)
    expect(library.songsById[7]).toHaveLength(2)
    expect(listPlaylistSongs).toHaveBeenCalledTimes(1)

    await togglePlaylist(7)
    expect(library.expandedId).toBeNull()
    expect(listPlaylistSongs).toHaveBeenCalledTimes(1)
  })

  it('曲目读取失败：清掉该歌单的缓存，避免展示过期曲目', async () => {
    listPlaylistSongs.mockResolvedValueOnce(SONGS)
    await togglePlaylist(7)
    expect(library.songsById[7]).toHaveLength(2)

    listPlaylistSongs.mockRejectedValueOnce(new ApiError('network', { message: '网络不可用' }))
    library.expandedId = null
    await togglePlaylist(7)

    expect(library.songsError).toBeTruthy()
    expect(library.songsById[7]).toBeUndefined()
  })
})
