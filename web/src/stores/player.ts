/**
 * 播放器状态（全局单例）。
 *
 * 为什么状态放在 store 而不是组件里：
 * 1. 「切会话不断播」的前提是**播放器不随会话组件卸载**——
 *    音频元素由本模块创建并持有，组件只读状态、只发指令；
 * 2. 全站只能有一个 `<audio>`：两个元素同时出声是这类页面最常见的 bug；
 * 3. 状态机（loading / playing / paused / ended / error）只在事件回调里推进，
 *    组件不"猜"当前是否在播。
 *
 * 三个容易出错的地方，这里都显式处理：
 * - **快速切歌**：每次 load 递增 `loadSeq`，迟到的解析结果直接丢弃；
 * - **自动播放被拦**：`play()` 的 NotAllowedError 单独成一个状态
 *   （`autoplayBlocked`），界面给"点击播放"入口，而不是报"播放失败"；
 * - **地址过期**：媒体地址是短期的，过期后一次重新解析即可继续，
 *   不该把用户踢回"播放失败"。
 */
import { computed, reactive } from 'vue'
import { apiUrl } from '@/api/client'
import { ApiError, describeError } from '@/api/errors'
import { getUserId } from '@/api/identity'
import { reportNowPlaying, resolvePlayback } from '@/api/playback'
import {
  createEqualizer,
  FLAT_EQ,
  type EqSettings,
  type Equalizer,
} from '@/audio/equalizer'
import {
  createResumeStore,
  defaultResumeStorage,
  type ResumeSnapshot,
  type ResumeStore,
} from '@/audio/resume'

/** 队列里的一首。解析成功后才会补齐 streamUrl / format 等字段。 */
export interface PlayerTrack {
  songId: number
  title: string
  artist: string
  album?: string | null
  durationSeconds?: number | null
  /** 解析后填：音源格式（只展示已知格式，不推断音质） */
  format?: string | null
  mode?: string | null
  streamUrl?: string | null
  /** 地址过期时间（epoch 秒） */
  expiresAt?: number | null
  /** 该来源是否允许调音（服务端 capabilities.eq） */
  eqAvailable?: boolean
}

export type PlayerStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'ended' | 'error'

export interface PlayerError {
  /** 稳定原因码：NO_SOURCE / TRACK_FORBIDDEN / MEDIA_MISSING / MEDIA_ERROR / NETWORK */
  code: string
  message: string
}

/**
 * 对 `<audio>` 的最小抽象：只为了能在 jsdom 里测状态机。
 *
 * jsdom 的 HTMLMediaElement 不实现 play()，直接依赖它会让"快速切歌"
 * 这类关键行为无法回归。真实环境注入 `new Audio()`。
 */
export interface AudioLike {
  src: string
  currentTime: number
  duration: number
  paused: boolean
  volume: number
  muted: boolean
  play(): Promise<void>
  pause(): void
  load(): void
  addEventListener(type: string, listener: () => void): void
  removeEventListener(type: string, listener: () => void): void
}

export interface PlayerDeps {
  createAudio: () => AudioLike
  resolve: typeof resolvePlayback
  report: typeof reportNowPlaying
  createEq?: () => Equalizer | null
  /** 本机续播快照（可选：没有它播放器仍能正常工作，只是不记忆进度） */
  resume?: ResumeStore
  /** 心跳间隔；默认 60 秒。测试里可以调小或关闭（0 = 不发心跳） */
  heartbeatIntervalMs?: number
  /** 生成播放实例标识；默认 crypto.randomUUID() */
  createInstanceId?: () => string
}

interface PlayerState {
  queue: PlayerTrack[]
  index: number
  status: PlayerStatus
  currentTime: number
  duration: number
  error: PlayerError | null
  /** 浏览器拦截了自动播放：需要用户点一下 */
  autoplayBlocked: boolean
  volume: number
  muted: boolean
  /** 环境是否支持 Web Audio（不支持则整个 EQ 入口禁用） */
  eqSupported: boolean
  eq: EqSettings & { enabled: boolean }
  /**
   * 本机续播快照：只用于"上次听到这里"的展示与恢复。
   * 它**不是**正在播放的事实，不参与任何上报或审批。
   */
  resume: ResumeSnapshot | null
}

const DEFAULT_VOLUME = 0.85

/** 续播快照的写入频率：5 秒一次。太密会频繁写 localStorage，太疏会丢进度。 */
const RESUME_SAVE_INTERVAL_MS = 5_000

function initialState(): PlayerState {
  return {
    queue: [],
    index: -1,
    status: 'idle',
    currentTime: 0,
    duration: 0,
    error: null,
    autoplayBlocked: false,
    volume: DEFAULT_VOLUME,
    muted: false,
    eqSupported: false,
    eq: { ...FLAT_EQ, enabled: false },
    resume: null,
  }
}

/** 播放实例标识：UUID 命中服务端的 `[A-Za-z0-9._:-]{1,64}` 校验。 */
function newInstanceId(): string {
  const cryptoApi = globalThis.crypto
  if (cryptoApi && typeof cryptoApi.randomUUID === 'function') {
    return cryptoApi.randomUUID()
  }
  return `inst-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

export function createPlayer(deps: PlayerDeps) {
  const state = reactive<PlayerState>(initialState())

  const audio = deps.createAudio()
  audio.volume = DEFAULT_VOLUME

  const eq: Equalizer | null = (deps.createEq ?? createEqualizer)()
  state.eqSupported = !!eq

  const resumeStore: ResumeStore | null = deps.resume ?? null
  const heartbeatIntervalMs = deps.heartbeatIntervalMs ?? 60_000
  const createInstanceId = deps.createInstanceId ?? newInstanceId

  /*
   * 启动时只"读出来给用户看"，绝不自动播放：
   * 浏览器要求播放必须由用户手势触发，而且用户重开页面时
   * 期待的是"我上次听到这里"，不是"突然出声"。
   */
  state.resume = resumeStore?.load() ?? null

  /** 每次 load 递增；迟到的解析结果按这个值丢弃 */
  let loadSeq = 0
  /** 正在为"地址过期"做一次自动重解析，避免错误事件风暴 */
  let refreshing = false
  /** 已经上报过的 (songId, event, instance)，避免重复请求 */
  let lastReported = ''
  /** 本次播放尝试的实例标识：心跳 / 暂停 / 停止都带它 */
  let instanceId = createInstanceId()
  /** 服务端已经判定本实例过期：停止心跳，下一次真正播放时换新实例 */
  let instanceStale = false
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null
  let lastResumeSaveAt = 0
  /** 续播恢复时先记住目标进度，等 metadata 就绪再 seek */
  let pendingSeek = 0

  const current = computed<PlayerTrack | null>(() => state.queue[state.index] ?? null)
  const hasTrack = computed(() => !!current.value)
  const isPlaying = computed(() => state.status === 'playing')
  const canSeek = computed(() => !!current.value?.streamUrl && state.duration > 0)

  /** 当前曲目是否允许调音：环境支持 + 服务端对该来源确认可处理 */
  const eqAvailable = computed(
    () => state.eqSupported && !!current.value?.eqAvailable,
  )

  /* ==================== 事件 ==================== */

  function onPlaying(): void {
    const track = current.value
    if (!track) {
      return
    }

    /*
     * 上一轮被服务端判定为过期实例（比如在另一个标签页里切歌了）：
     * 现在用户在这个页面主动播放，就换一个新实例重新登记。
     * 不换的话，后续心跳会一直 409，服务端永远不知道这里在播。
     */
    if (instanceStale) {
      instanceId = createInstanceId()
      instanceStale = false
    }

    state.status = 'playing'
    state.autoplayBlocked = false
    state.error = null

    startHeartbeat()
    reportOnce(track.songId, 'START')
    saveResume()
  }

  function onPause(): void {
    // 切歌 / 结束也会触发 pause：只有"本来在播"才视为用户暂停
    if (state.status === 'playing') {
      state.status = 'paused'
      stopHeartbeat()
      const track = current.value
      if (track) {
        reportOnce(track.songId, 'PAUSE')
        saveResume()
      }
    }
  }

  function onEnded(): void {
    const track = current.value
    if (!track) {
      return
    }
    if (state.index + 1 < state.queue.length) {
      void load(state.index + 1, true)
      return
    }
    state.status = 'ended'
    state.currentTime = state.duration
    stopHeartbeat()
    // 播完仍保留快照（位置=结尾），续播时会被夹回开头
    saveResume(state.duration)
    reportOnce(track.songId, 'STOP')
  }

  function onTimeUpdate(): void {
    state.currentTime = Number.isFinite(audio.currentTime) ? audio.currentTime : 0
    if (Number.isFinite(audio.duration) && audio.duration > 0) {
      state.duration = audio.duration
    }
    maybeSaveResume()
  }

  function onLoadedMetadata(): void {
    if (Number.isFinite(audio.duration) && audio.duration > 0) {
      state.duration = audio.duration
    }
    applyPendingSeek()
  }

  function onMediaError(): void {
    const track = current.value
    if (!track) {
      return
    }

    /*
     * 过期是最常见的"播放中突然报错"：短地址到期后浏览器再请求就是 403。
     * 这里自动重新解析一次（保留进度），而不是让用户手动重试。
     */
    if (isExpired(track) && !refreshing) {
      void refreshSource(track)
      return
    }

    state.status = 'error'
    state.error = {
      code: 'MEDIA_ERROR',
      message: '音频加载失败：可能是媒体地址已失效或文件不可用。',
    }
  }

  audio.addEventListener('playing', onPlaying)
  audio.addEventListener('pause', onPause)
  audio.addEventListener('ended', onEnded)
  audio.addEventListener('timeupdate', onTimeUpdate)
  audio.addEventListener('loadedmetadata', onLoadedMetadata)
  audio.addEventListener('error', onMediaError)

  /* ==================== 内部 ==================== */

  function isExpired(track: PlayerTrack): boolean {
    return !!track.expiresAt && Date.now() / 1000 >= track.expiresAt - 5
  }

  /* ==================== 活跃播放上报 ==================== */

  /**
   * 状态类事件（START / PAUSE / STOP）去重上报。
   *
   * 心跳不走这里：它必须每次都发，否则服务端会认为播放已经停了。
   */
  function reportOnce(
    songId: number,
    event: 'START' | 'PAUSE' | 'STOP',
  ): void {
    const key = `${songId}:${event}:${instanceId}`
    if (key === lastReported) {
      return
    }
    lastReported = key
    send(songId, event)
  }

  function send(songId: number, event: 'START' | 'HEARTBEAT' | 'PAUSE' | 'STOP'): void {
    /*
     * 上报失败不影响播放：它只服务"把正在播放的歌加入歌单"这条链路，
     * 用户听歌这件事不应该因为一次统计请求失败而中断。
     */
    void deps
      .report(songId, event, instanceId)
      .catch((error: unknown) => handleStale(error))
  }

  /**
   * 409 = 这个播放实例已经不是服务端的当前实例。
   *
   * 典型场景：同一个账号在另一个标签页切了歌，本页的定时器还在跑。
   * 正确处理是**停止上报并静默**，而不是弹错——用户听歌没有受到影响，
   * 只是"这一页不再是播放事实的来源"。
   */
  function handleStale(error: unknown): void {
    if (error instanceof ApiError && (error.status === 409 || error.kind === 'conflict')) {
      instanceStale = true
      stopHeartbeat()
    }
  }

  function startHeartbeat(): void {
    stopHeartbeat()
    if (!heartbeatIntervalMs || heartbeatIntervalMs <= 0) {
      return
    }
    heartbeatTimer = setInterval(() => {
      const track = current.value
      if (!track || state.status !== 'playing' || instanceStale) {
        return
      }
      send(track.songId, 'HEARTBEAT')
    }, heartbeatIntervalMs)
  }

  function stopHeartbeat(): void {
    if (heartbeatTimer !== null) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  /* ==================== 续播快照 ==================== */

  function saveResume(position?: number): void {
    if (!resumeStore) {
      return
    }
    const track = current.value
    if (!track) {
      return
    }

    const snapshot = resumeStore.save({
      songId: track.songId,
      title: track.title,
      artist: track.artist,
      album: track.album ?? null,
      durationSeconds: track.durationSeconds ?? (state.duration || null),
      positionSeconds: position ?? state.currentTime,
    })

    if (snapshot) {
      state.resume = snapshot
      lastResumeSaveAt = Date.now()
    }
  }

  /** timeupdate 很密（约 4 次/秒），按 5 秒节流写盘 */
  function maybeSaveResume(): void {
    if (!resumeStore || state.status !== 'playing') {
      return
    }
    if (Date.now() - lastResumeSaveAt < RESUME_SAVE_INTERVAL_MS) {
      return
    }
    saveResume()
  }

  function clearResume(): void {
    resumeStore?.clear()
    state.resume = null
  }

  /** metadata 就绪后再 seek：过早设置 currentTime 在部分浏览器会抛 InvalidStateError */
  function applyPendingSeek(): void {
    if (pendingSeek <= 0) {
      return
    }
    const target = pendingSeek
    pendingSeek = 0

    // 已经播到结尾（或超过时长）的续播没有意义：从 0 开始
    if (state.duration > 0 && target >= state.duration - 1) {
      return
    }

    try {
      audio.currentTime = target
      state.currentTime = target
    } catch {
      // 忽略：metadata 之后仍然失败时，用户手动拖一下即可
    }
  }

  async function refreshSource(track: PlayerTrack): Promise<void> {
    refreshing = true
    const resumeAt = state.currentTime
    const wasPlaying = state.status === 'playing'

    try {
      const resolved = await deps.resolve(track.songId)
      if (!resolved.playable || !resolved.streamUrl) {
        state.status = 'error'
        state.error = {
          code: resolved.reasonCode ?? 'MEDIA_UNAVAILABLE',
          message: resolved.reason ?? '音源不可用',
        }
        return
      }

      applyResolution(track, resolved)
      audio.src = apiUrl(resolved.streamUrl)
      audio.load()
      audio.currentTime = resumeAt

      if (wasPlaying) {
        await playAudio()
      }
    } catch (error) {
      state.status = 'error'
      state.error = {
        code: 'MEDIA_ERROR',
        message: describeError(error),
      }
    } finally {
      refreshing = false
    }
  }

  function applyResolution(
    track: PlayerTrack,
    resolved: Awaited<ReturnType<typeof resolvePlayback>>,
  ): void {
    track.streamUrl = resolved.streamUrl
    track.format = resolved.format
    track.mode = resolved.mode
    track.expiresAt = resolved.expiresAt
    track.eqAvailable = resolved.capabilities.eq
    track.title = resolved.song.title || track.title
    track.artist = resolved.song.artist || track.artist
    if (resolved.song.durationSeconds) {
      track.durationSeconds = resolved.song.durationSeconds
    }
  }

  async function playAudio(): Promise<void> {
    try {
      eq?.resume()
      await audio.play()
    } catch (error) {
      const name = (error as { name?: string })?.name

      if (name === 'NotAllowedError') {
        /*
         * 浏览器的自动播放策略：必须有用户手势。
         * 这不是错误，是"需要你点一下"，所以单独一个状态。
         */
        state.autoplayBlocked = true
        state.status = 'paused'
        return
      }

      state.status = 'error'
      state.error = {
        code: 'MEDIA_ERROR',
        message: '无法开始播放：浏览器拒绝了这次播放请求。',
      }
    }
  }

  async function load(index: number, autoplay: boolean, startAt = 0): Promise<void> {
    const track = state.queue[index]
    if (!track) {
      return
    }

    const seq = ++loadSeq

    /*
     * 每一次 load 都是一次新的播放尝试：换实例标识。
     * 这样服务端能干净地区分"用户切了一首"和"上一首的心跳迟到了"。
     */
    instanceId = createInstanceId()
    instanceStale = false
    stopHeartbeat()

    state.index = index
    state.status = 'loading'
    state.error = null
    state.currentTime = startAt
    state.duration = track.durationSeconds ?? 0
    pendingSeek = startAt
    lastReported = ''

    // 先把上一首停掉：否则两首歌会短暂叠在一起
    audio.pause()

    try {
      const resolved = await deps.resolve(track.songId)

      // 迟到的结果：用户已经切到别的歌了，丢弃
      if (seq !== loadSeq) {
        return
      }

      if (!resolved.playable || !resolved.streamUrl) {
        state.status = 'error'
        state.error = {
          code: resolved.reasonCode ?? 'NOT_PLAYABLE',
          message: resolved.reason ?? '这首曲子暂时不能播放',
        }
        pendingSeek = 0
        return
      }

      applyResolution(track, resolved)

      // 第一次真正播放时才接入处理图：AudioContext 需要用户手势
      eq?.attach(audio as unknown as HTMLAudioElement)
      applyEqSettings()

      /*
       * 换歌后立刻把快照指向**新**曲目（位置 0 / 续播目标），
       * 不能让上一首继续作为"上次听到"的条目。
       */
      saveResume(startAt)

      audio.src = apiUrl(resolved.streamUrl)
      audio.load()

      if (autoplay) {
        await playAudio()
      }
    } catch (error) {
      if (seq !== loadSeq) {
        return
      }

      pendingSeek = 0
      state.status = 'error'
      state.error = mapResolveError(error)
    }
  }

  function mapResolveError(error: unknown): PlayerError {
    if (error instanceof ApiError) {
      if (error.kind === 'forbidden') {
        return { code: 'TRACK_FORBIDDEN', message: '无权播放这首曲目。' }
      }
      if (error.kind === 'not_found') {
        return { code: 'SONG_NOT_FOUND', message: '曲目不存在或已下架。' }
      }
      return { code: 'RESOLVE_FAILED', message: describeError(error) }
    }
    return { code: 'NETWORK', message: '网络异常，无法解析播放地址。' }
  }

  function applyEqSettings(): void {
    if (!eq) {
      return
    }
    const enabled = state.eq.enabled && !!current.value?.eqAvailable
    eq.apply(state.eq, !enabled)
  }

  /* ==================== 对外动作 ==================== */

  async function playQueue(
    tracks: PlayerTrack[],
    startIndex = 0,
    startAt = 0,
  ): Promise<void> {
    if (!tracks.length) {
      return
    }
    const index = Math.min(Math.max(startIndex, 0), tracks.length - 1)
    state.queue = tracks.map((track) => ({ ...track }))
    await load(index, true, startAt)
  }

  /**
   * 续播：用户点击"继续播放"后调用。
   *
   * 必须由用户手势触发（浏览器自动播放策略），并且**重新解析**音源：
   * 快照里只有曲目引用与进度，没有任何地址——地址是短期的，
   * 也不允许被存进 localStorage。
   */
  async function resumeLast(): Promise<void> {
    const snapshot = state.resume
    if (!snapshot) {
      return
    }

    const track: PlayerTrack = {
      songId: snapshot.songId,
      title: snapshot.title,
      artist: snapshot.artist,
      album: snapshot.album ?? null,
      durationSeconds: snapshot.durationSeconds ?? null,
    }

    // 进入播放器后不再显示续播卡；解析失败时再放回来
    state.resume = null

    await playQueue([track], 0, snapshot.positionSeconds)

    if (state.status === 'error') {
      state.resume = snapshot
    }
  }

  async function toggle(): Promise<void> {
    const track = current.value
    if (!track) {
      return
    }

    if (state.status === 'playing') {
      audio.pause()
      state.status = 'paused'
      stopHeartbeat()
      reportOnce(track.songId, 'PAUSE')
      saveResume()
      return
    }

    if (state.autoplayBlocked) {
      state.autoplayBlocked = false
    }

    if (!track.streamUrl) {
      // 解析失败 / 还没解析：重新走一次
      await load(state.index, true)
      return
    }

    if (state.status === 'ended') {
      audio.currentTime = 0
      state.currentTime = 0
    }

    await playAudio()
  }

  /** 手动下一首：最后一首之后回到第一首 */
  async function next(): Promise<void> {
    if (!state.queue.length) {
      return
    }
    const nextIndex = (state.index + 1) % state.queue.length
    await load(nextIndex, true)
  }

  /** 上一首：播过 3 秒先回到本曲开头（与常见播放器一致） */
  async function previous(): Promise<void> {
    if (!state.queue.length) {
      return
    }
    if (state.currentTime > 3) {
      seek(0)
      return
    }
    const prevIndex = (state.index - 1 + state.queue.length) % state.queue.length
    await load(prevIndex, true)
  }

  function seek(seconds: number): void {
    if (!current.value?.streamUrl) {
      return
    }
    const target = Math.max(0, Math.min(seconds, state.duration || seconds))
    try {
      audio.currentTime = target
      state.currentTime = target
      // 用户拖到哪，就记住哪：这是"下次从这里继续"最直接的来源
      saveResume(target)
    } catch {
      // 某些浏览器在 metadata 之前会抛 InvalidStateError：忽略即可
    }
  }

  /** 跳到队列中的某一首 */
  async function jumpTo(index: number): Promise<void> {
    if (index < 0 || index >= state.queue.length) {
      return
    }
    await load(index, true)
  }

  /** 停止并清空队列（用户主动"关闭播放器"） */
  function stop(): void {
    const track = current.value
    loadSeq += 1
    stopHeartbeat()
    audio.pause()
    audio.src = ''
    state.queue = []
    state.index = -1
    state.status = 'idle'
    state.currentTime = 0
    state.duration = 0
    state.error = null
    state.autoplayBlocked = false
    pendingSeek = 0
    /*
     * 主动关闭 = 明确表示"不想继续了"，所以续播快照也清掉。
     * 注意这是**唯一**会清快照的用户动作；关标签页、崩溃都不会走到这里。
     */
    clearResume()
    if (track) {
      reportOnce(track.songId, 'STOP')
    }
  }

  function setVolume(value: number): void {
    const volume = Math.max(0, Math.min(1, value))
    state.volume = volume
    audio.volume = volume
    if (volume > 0 && state.muted) {
      state.muted = false
      audio.muted = false
    }
  }

  function toggleMute(): void {
    state.muted = !state.muted
    audio.muted = state.muted
  }

  /** 更新 EQ 参数并立即生效（旁路由 enabled 控制） */
  function setEq(patch: Partial<EqSettings & { enabled: boolean }>): void {
    if (!eqAvailable.value && patch.enabled !== false) {
      // 当前来源不支持调音：不允许把开关打开，避免"看起来在调、实际没生效"
      state.eq = { ...state.eq, ...patch, enabled: false }
      applyEqSettings()
      return
    }
    state.eq = { ...state.eq, ...patch }
    applyEqSettings()
  }

  /** 一键旁路 / 恢复 */
  function toggleEqBypass(): void {
    setEq({ enabled: !state.eq.enabled })
  }

  function resetEq(): void {
    state.eq = { ...FLAT_EQ, enabled: state.eq.enabled && eqAvailable.value }
    applyEqSettings()
  }

  /** 仅供测试/退出时清理 */
  function dispose(): void {
    stopHeartbeat()
    audio.removeEventListener('playing', onPlaying)
    audio.removeEventListener('pause', onPause)
    audio.removeEventListener('ended', onEnded)
    audio.removeEventListener('timeupdate', onTimeUpdate)
    audio.removeEventListener('loadedmetadata', onLoadedMetadata)
    audio.removeEventListener('error', onMediaError)
    eq?.dispose()
  }

  return {
    state,
    current,
    hasTrack,
    isPlaying,
    canSeek,
    eqAvailable,
    playQueue,
    playTrack: (track: PlayerTrack) => playQueue([track], 0),
    resumeLast,
    toggle,
    next,
    previous,
    seek,
    jumpTo,
    stop,
    setVolume,
    toggleMute,
    setEq,
    toggleEqBypass,
    resetEq,
    dispose,
    /** 测试可见：底层音频元素（真实环境就是页面里唯一的 <audio>） */
    audioElement: audio,
  }
}

export type Player = ReturnType<typeof createPlayer>

/**
 * 全站唯一的播放器实例。
 *
 * 音频元素在模块加载时就创建（`new Audio()` 不依赖 DOM 挂载），
 * 因此切会话、切路由都不会中断播放。
 */
export const player: Player = createPlayer({
  createAudio: () => new Audio() as unknown as AudioLike,
  resolve: resolvePlayback,
  report: reportNowPlaying,
  /*
   * 续播快照按 MkAgent 用户隔离：同一个浏览器换身份不会看到别人的进度。
   * 只存曲目引用与进度，不存签名地址（地址是短期的，也不该落到磁盘上）。
   */
  resume: createResumeStore({
    storage: defaultResumeStorage(),
    userId: getUserId,
  }),
  heartbeatIntervalMs: 60_000,
})

/*
 * 把音频元素挂进 DOM（无 controls、不参与布局）。
 *
 * 为什么挂进去：
 * 1. 元素不挂在文档上时，某些浏览器在页面切到后台会回收媒体资源；
 * 2. 调试与端到端验收需要一个稳定入口（`[data-testid="mk-audio"]`）
 *    读取 currentTime / paused，否则只能靠"听起来像在播"。
 * 它仍然只有一个，仍然不属于任何组件，卸载界面不会带走它。
 */
if (typeof document !== 'undefined') {
  const element = player.audioElement as unknown as HTMLAudioElement
  if (element && typeof element.setAttribute === 'function') {
    element.setAttribute('data-testid', 'mk-audio')
    element.setAttribute('preload', 'metadata')
    element.style.display = 'none'
    document.body.appendChild(element)
  }
}
