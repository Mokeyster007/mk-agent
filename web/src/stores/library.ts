/**
 * "我的歌单"面板状态。
 *
 * 为什么单独一个 store 而不是塞进 taskStore：
 * 歌单是**业务数据**，任务是一次**执行**。任务成功不代表歌单一定在
 * （例如写工具返回失败、或用户拒绝），所以面板必须能独立刷新，
 * 用真实查询结果回答"现在库里到底有什么"。
 *
 * 三条规则：
 * 1. 只读：这里没有任何写方法，写操作只有"模型提请求 + 用户批准"一条路；
 * 2. 每次刷新都重新查库，不用任务结果拼凑；
 * 3. 请求可被新的刷新取消（连点两次刷新不会让旧响应覆盖新结果）。
 */
import { reactive } from 'vue'
import { ApiError, describeError } from '@/api/errors'
import { listPlaylistSongs, listPlaylists } from '@/api/music'
import type { PlaylistVO, SongVO } from '@/api/types'

interface LibraryState {
  playlists: PlaylistVO[]
  loading: boolean
  error: string | null
  /** 上次成功刷新的时间戳（0 = 从未加载成功） */
  loadedAt: number
  /** 展开中的歌单 ID（同时只展开一个，避免长列表滚不完） */
  expandedId: number | null
  songsById: Record<number, SongVO[]>
  songsLoading: boolean
  songsError: string | null
}

export const library = reactive<LibraryState>({
  playlists: [],
  loading: false,
  error: null,
  loadedAt: 0,
  expandedId: null,
  songsById: {},
  songsLoading: false,
  songsError: null,
})

let controller: AbortController | null = null
let songsController: AbortController | null = null

/**
 * 刷新歌单列表。
 *
 * @param options.resetSongs 是否丢弃已缓存的曲目（写操作之后必须丢，
 *        否则展开的歌单会显示旧曲目）
 */
export async function refreshPlaylists(options: { resetSongs?: boolean } = {}): Promise<void> {
  const { resetSongs = false } = options

  controller?.abort()
  controller = new AbortController()

  library.loading = true
  library.error = null
  if (resetSongs) {
    library.songsById = {}
    library.songsError = null
  }

  try {
    const playlists = await listPlaylists(controller.signal)
    library.playlists = playlists
    library.loadedAt = Date.now()
    // 展开的歌单如果已经不存在（理论上不会，但别让界面停在幽灵状态）
    if (library.expandedId !== null && !playlists.some((item) => item.id === library.expandedId)) {
      library.expandedId = null
    }
  } catch (error) {
    if (error instanceof ApiError && error.kind === 'aborted') {
      return
    }
    library.error = describeError(error)
  } finally {
    library.loading = false
  }
}

/**
 * 展开 / 收起某个歌单，并在展开时加载曲目。
 *
 * 每次都重新查：曲目可能刚被别的任务改过，缓存只在同一次展开期间有效。
 */
export async function togglePlaylist(playlistId: number): Promise<void> {
  if (library.expandedId === playlistId) {
    library.expandedId = null
    return
  }

  library.expandedId = playlistId
  await loadSongs(playlistId)
}

/** 读取某个歌单的曲目（覆盖缓存）。 */
export async function loadSongs(playlistId: number): Promise<void> {
  songsController?.abort()
  songsController = new AbortController()

  library.songsLoading = true
  library.songsError = null

  try {
    const songs = await listPlaylistSongs(playlistId, songsController.signal)
    library.songsById = { ...library.songsById, [playlistId]: songs }
  } catch (error) {
    if (error instanceof ApiError && error.kind === 'aborted') {
      return
    }
    library.songsError = describeError(error)
    // 失败时不要留旧数据冒充新数据
    const next = { ...library.songsById }
    delete next[playlistId]
    library.songsById = next
  } finally {
    library.songsLoading = false
  }
}
