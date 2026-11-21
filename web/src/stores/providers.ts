/**
 * 音乐账号状态。
 *
 * 三条边界：
 * 1. **只读**：这里没有"保存到我的歌单""同步"之类动作。
 *    能读不等于允许写回平台，也不等于允许把数据交给模型。
 * 2. **按来源隔离**：聚合接口返回每个来源的状态；一个来源超时 / 权限不足，
 *    只影响它那一块，不会把整页变成错误页。
 * 3. **不合并同名歌曲**：条目身份是 provider + providerTrackId，
 *    界面按来源分组展示，不按歌名去重。
 */
import { computed, reactive } from 'vue'
import { ApiError, describeError } from '@/api/errors'
import {
  connectProvider,
  disconnectProvider,
  listProviders,
  readProviderTracks,
  type ProviderPlaylistVO,
  type ProviderSourceStatusVO,
  type ProviderTrackKind,
  type ProviderTrackVO,
  type ProviderVO,
} from '@/api/providers'

const PAGE_SIZE = 20

interface ProvidersState {
  catalog: ProviderVO[]
  kind: ProviderTrackKind
  /** null = 全部已连接来源 */
  filter: string | null
  tracks: ProviderTrackVO[]
  playlists: ProviderPlaylistVO[]
  sources: ProviderSourceStatusVO[]
  page: number
  loading: boolean
  /** 只有**传输层**失败才会进这里；来源级错误在 sources 里 */
  error: string | null
  loadedAt: number | null
  busyProvider: string | null
  catalogError: string | null
}

function initialState(): ProvidersState {
  return {
    catalog: [],
    kind: 'LIKED',
    filter: null,
    tracks: [],
    playlists: [],
    sources: [],
    page: 1,
    loading: false,
    error: null,
    loadedAt: null,
    busyProvider: null,
    catalogError: null,
  }
}

export function createProvidersStore(deps: {
  list: typeof listProviders
  connect: typeof connectProvider
  disconnect: typeof disconnectProvider
  read: typeof readProviderTracks
}) {
  const state = reactive<ProvidersState>(initialState())

  /** 只保留最后一次请求的结果：切来源时旧请求迟到不该覆盖新结果 */
  let seq = 0

  const connections = computed(() => state.catalog.filter((item) => item.connected))

  const connectedCount = computed(() => connections.value.length)

  const available = computed(() => state.catalog.filter((item) => !item.connected))

  /** 每个来源是否还有下一页：用"本页返回数量是否达到页大小"近似判断 */
  const hasMore = computed(() => {
    const total = state.tracks.length + state.playlists.length
    return total >= PAGE_SIZE
  })

  function itemsOf(provider: string): ProviderTrackVO[] {
    return state.tracks.filter((track) => track.provider === provider)
  }

  function statusOf(provider: string): ProviderSourceStatusVO | null {
    return state.sources.find((source) => source.provider === provider) ?? null
  }

  async function refreshCatalog(): Promise<void> {
    try {
      state.catalog = await deps.list()
      state.catalogError = null
    } catch (error) {
      state.catalogError = describeError(error)
    }
  }

  async function reload(): Promise<void> {
    const current = ++seq
    state.loading = true
    state.error = null

    try {
      const result = await deps.read({
        kind: state.kind,
        provider: state.filter,
        page: state.page,
        size: PAGE_SIZE,
      })

      if (current !== seq) {
        return
      }

      state.tracks = result.tracks
      state.playlists = result.playlists
      state.sources = result.sources
      state.loadedAt = Date.now()
    } catch (error) {
      if (current !== seq) {
        return
      }
      /*
       * 传输层失败（断网 / 5xx）：这是"整页读不到"，与"某个来源失败"不同。
       * 后者在 sources 里逐条展示。
       */
      state.error = describeError(error)
      state.tracks = []
      state.playlists = []
      state.sources = []
    } finally {
      if (current === seq) {
        state.loading = false
      }
    }
  }

  async function setKind(kind: ProviderTrackKind): Promise<void> {
    if (state.kind === kind) {
      return
    }
    state.kind = kind
    state.page = 1
    await reload()
  }

  async function setFilter(provider: string | null): Promise<void> {
    state.filter = provider
    state.page = 1
    await reload()
  }

  async function nextPage(): Promise<void> {
    if (!hasMore.value || state.loading) {
      return
    }
    state.page += 1
    await reload()
  }

  async function prevPage(): Promise<void> {
    if (state.page <= 1 || state.loading) {
      return
    }
    state.page -= 1
    await reload()
  }

  async function connect(provider: string): Promise<void> {
    state.busyProvider = provider
    try {
      await deps.connect(provider)
      await refreshCatalog()
      state.filter = provider
      state.page = 1
      await reload()
    } catch (error) {
      state.catalogError = connectErrorMessage(error)
    } finally {
      state.busyProvider = null
    }
  }

  async function disconnect(provider: string): Promise<void> {
    state.busyProvider = provider
    try {
      await deps.disconnect(provider)
      await refreshCatalog()
      if (state.filter === provider) {
        state.filter = null
      }
      await reload()
    } catch (error) {
      state.catalogError = describeError(error)
    } finally {
      state.busyProvider = null
    }
  }

  /** 打开抽屉时：先拿目录，再读当前类别 */
  async function open(): Promise<void> {
    await refreshCatalog()
    await reload()
  }

  function reset(): void {
    Object.assign(state, initialState())
  }

  return {
    state,
    connections,
    connectedCount,
    available,
    hasMore,
    itemsOf,
    statusOf,
    refreshCatalog,
    reload,
    setKind,
    setFilter,
    nextPage,
    prevPage,
    connect,
    disconnect,
    open,
    reset,
  }
}

/**
 * 绑定失败的用户可读文案。
 *
 * 后端返回的 code 是稳定契约；这里把它翻译成"用户该做什么"，
 * 而不是把 409 直接显示出来。
 */
function connectErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'IDENTITY_NOT_TRUSTED') {
      return '当前身份不是可信的服务端身份，无法绑定真实音乐平台。'
    }
    if (error.code === 'PROVIDER_NOT_CONFIGURED') {
      return '该平台的客户端凭据与合作权限尚未配置。'
    }
    if (error.code === 'PROVIDER_OAUTH_NOT_IMPLEMENTED') {
      return '该平台需要通过官方授权页完成绑定，本轮尚未接入。'
    }
    if (error.code === 'PROVIDER_DEMO_DISABLED') {
      return '演示来源已关闭。'
    }
  }
  return describeError(error)
}

export type ProvidersStore = ReturnType<typeof createProvidersStore>

export const providers = createProvidersStore({
  list: listProviders,
  connect: connectProvider,
  disconnect: disconnectProvider,
  read: readProviderTracks,
})
