import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { createPlayer, type AudioLike, type PlayerTrack } from './player'
import { createResumeStore, type ResumeStorage, type ResumeStore } from '@/audio/resume'
import { ApiError } from '@/api/errors'
import type { PlaybackResolveVO } from '@/api/types'

/**
 * 播放器状态机的回归。
 *
 * 这些用例存在的理由：播放器最容易坏的地方不是"能不能出声"，
 * 而是**并发与生命周期**——快速切歌时旧请求迟到、自动播放被拦、
 * 组件卸载顺手把音频停掉。它们都只在真实交互里出现，靠手点很难稳定复现。
 */

/** 可控的假音频元素：jsdom 不实现 play()，只能自己给一个。 */
class FakeAudio implements AudioLike {
  src = ''
  currentTime = 0
  duration = 0
  paused = true
  volume = 1
  muted = false
  playCalls = 0
  failNextPlay: Error | null = null

  private listeners = new Map<string, Set<() => void>>()

  async play(): Promise<void> {
    if (this.failNextPlay) {
      const error = this.failNextPlay
      this.failNextPlay = null
      throw error
    }
    this.paused = false
    this.playCalls += 1
    this.emit('playing')
  }

  pause(): void {
    const wasPlaying = !this.paused
    this.paused = true
    if (wasPlaying) {
      this.emit('pause')
    }
  }

  load(): void {
    // 真实元素加载后会派发 loadedmetadata（续播的 seek 依赖这个时机）
    if (this.duration > 0) {
      this.emit('loadedmetadata')
    }
  }

  addEventListener(type: string, listener: () => void): void {
    if (!this.listeners.has(type)) {
      this.listeners.set(type, new Set())
    }
    this.listeners.get(type)!.add(listener)
  }

  removeEventListener(type: string, listener: () => void): void {
    this.listeners.get(type)?.delete(listener)
  }

  emit(type: string): void {
    this.listeners.get(type)?.forEach((listener) => listener())
  }
}

const TRACK_A: PlayerTrack = { songId: 1901, title: '测试音 A', artist: 'Fixtures' }
const TRACK_B: PlayerTrack = { songId: 1902, title: '测试音 B', artist: 'Fixtures' }

function resolved(songId: number, overrides: Partial<PlaybackResolveVO> = {}): PlaybackResolveVO {
  return {
    playable: true,
    song: {
      id: songId,
      title: songId === 1901 ? '测试音 A' : '测试音 B',
      artist: 'Fixtures',
      album: null,
      durationSeconds: 10,
      releaseYear: 2026,
    },
    mode: 'DIRECT_AUDIO',
    provider: 'local-test-fixture',
    format: 'WAV',
    contentType: 'audio/wav',
    streamUrl: `/api/ai/music/playback/media/v1.token-${songId}.sig`,
    expiresAt: Math.floor(Date.now() / 1000) + 300,
    capabilities: { seek: true, range: true, eq: true },
    reasonCode: null,
    reason: null,
    ...overrides,
  }
}

function build(
  resolveImpl: (songId: number) => Promise<PlaybackResolveVO>,
  options: {
    resume?: ResumeStore
    heartbeatIntervalMs?: number
    createInstanceId?: () => string
  } = {},
) {
  const audio = new FakeAudio()
  const report = vi.fn().mockResolvedValue(undefined)
  const player = createPlayer({
    createAudio: () => audio,
    resolve: resolveImpl as never,
    report: report as never,
    createEq: () => null,
    // 默认关掉心跳：只有专门的用例才需要定时器
    heartbeatIntervalMs: options.heartbeatIntervalMs ?? 0,
    resume: options.resume,
    createInstanceId: options.createInstanceId,
  })
  return { audio, report, player }
}

/** 内存版续播存储（jsdom 的 localStorage 在测试间会互相污染）。 */
function memoryStorage(): ResumeStorage {
  const map = new Map<string, string>()
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => {
      map.set(key, value)
    },
    removeItem: (key) => {
      map.delete(key)
    },
  }
}

function resumeStore(userId = 'music-user-1', now: () => number = () => Date.now()) {
  return createResumeStore({ storage: memoryStorage(), userId: () => userId, now })
}

describe('播放器状态机', () => {
  it('解析成功后才设置地址，并进入 playing', async () => {
    const { audio, player } = build(async (songId) => resolved(songId))

    await player.playQueue([TRACK_A], 0)
    await nextTick()

    expect(audio.src).toBe('/api/ai/music/playback/media/v1.token-1901.sig')
    expect(player.state.status).toBe('playing')
    expect(player.current.value?.format).toBe('WAV')
  })

  it('不可播的曲目进入 error 并带上原因，不设置任何地址', async () => {
    const { audio, player } = build(async (songId) =>
      resolved(songId, {
        playable: false,
        mode: 'UNAVAILABLE',
        streamUrl: null,
        expiresAt: null,
        capabilities: { seek: false, range: false, eq: false },
        reasonCode: 'NO_SOURCE',
        reason: '这首曲子暂无可用音源',
      }),
    )

    await player.playQueue([TRACK_A], 0)

    expect(player.state.status).toBe('error')
    expect(player.state.error?.code).toBe('NO_SOURCE')
    expect(player.state.error?.message).toContain('暂无可用音源')
    expect(audio.src).toBe('')
    expect(audio.playCalls).toBe(0)
  })

  it('快速切歌：迟到的解析结果被丢弃，只播最后选择的那首', async () => {
    const pending = new Map<number, (value: PlaybackResolveVO) => void>()
    const { audio, player } = build(
      (songId) =>
        new Promise<PlaybackResolveVO>((resolve) => {
          pending.set(songId, resolve)
        }),
    )

    const first = player.playQueue([TRACK_A, TRACK_B], 0)
    // 用户立刻点了第二首
    const second = player.playQueue([TRACK_A, TRACK_B], 1)

    // 第二首先回来
    pending.get(1902)!(resolved(1902))
    await second
    expect(audio.src).toContain('token-1902')

    // 第一首的响应迟到：必须被丢弃，不能把地址改回 A
    pending.get(1901)!(resolved(1901))
    await first

    expect(audio.src).toContain('token-1902')
    expect(player.current.value?.songId).toBe(1902)
    expect(player.state.status).toBe('playing')
    expect(audio.playCalls).toBe(1)
  })

  it('自动播放被拦截：状态是 paused + 提示，而不是"播放失败"', async () => {
    const { audio, player } = build(async (songId) => resolved(songId))
    audio.failNextPlay = Object.assign(new Error('blocked'), { name: 'NotAllowedError' })

    await player.playQueue([TRACK_A], 0)

    expect(player.state.autoplayBlocked).toBe(true)
    expect(player.state.status).toBe('paused')
    expect(player.state.error).toBeNull()

    // 用户点击播放：这次成功
    await player.toggle()
    expect(player.state.status).toBe('playing')
    expect(player.state.autoplayBlocked).toBe(false)
  })

  it('播完自动切下一首；最后一首播完进入 ended 并上报 STOPPED', async () => {
    const { audio, player, report } = build(async (songId) => resolved(songId))

    await player.playQueue([TRACK_A, TRACK_B], 0)
    expect(player.current.value?.songId).toBe(1901)

    audio.emit('ended')
    await nextTick()
    await nextTick()

    expect(player.current.value?.songId).toBe(1902)
    expect(audio.src).toContain('token-1902')

    audio.emit('ended')
    await nextTick()

    expect(player.state.status).toBe('ended')
    expect(report).toHaveBeenCalledWith(1902, 'STOP', expect.any(String))
  })

  it('手动下一首在最后一首之后回到第一首', async () => {
    const { player } = build(async (songId) => resolved(songId))

    await player.playQueue([TRACK_A, TRACK_B], 1)
    await player.next()

    expect(player.current.value?.songId).toBe(1901)
  })

  it('权限拒绝映射成 TRACK_FORBIDDEN，不显示"正在播放"', async () => {
    const { player } = build(async () => {
      throw new ApiError('forbidden', { status: 403 })
    })

    await player.playQueue([TRACK_A], 0)

    expect(player.state.status).toBe('error')
    expect(player.state.error?.code).toBe('TRACK_FORBIDDEN')
    expect(player.isPlaying.value).toBe(false)
  })

  it('seek 只改当前时间，不触发重新解析', async () => {
    const resolve = vi.fn(async (songId: number) => resolved(songId))
    const { audio, player } = build(resolve)

    await player.playQueue([TRACK_A], 0)
    audio.duration = 10
    player.seek(4.5)

    expect(audio.currentTime).toBe(4.5)
    expect(resolve).toHaveBeenCalledTimes(1)
  })

  it('播放器实例与音频元素在组件卸载后保持不变（切会话不断播）', async () => {
    const { audio, player } = build(async (songId) => resolved(songId))
    await player.playQueue([TRACK_A], 0)

    // 模拟"切走会话"：承载界面的组件被卸载
    const view = defineComponent({
      setup() {
        return () => h('div', player.state.status)
      },
    })
    const wrapper = mount(view)
    wrapper.unmount()

    expect(player.audioElement).toBe(audio)
    expect(player.state.status).toBe('playing')
    expect(audio.paused).toBe(false)
  })

  it('当前来源不支持调音时，开关不会被打开（不显示假 EQ）', async () => {
    const { player } = build(async (songId) =>
      resolved(songId, { capabilities: { seek: true, range: true, eq: false } }),
    )

    await player.playQueue([TRACK_A], 0)
    player.setEq({ enabled: true })

    expect(player.eqAvailable.value).toBe(false)
    expect(player.state.eq.enabled).toBe(false)
  })

  it('停止后清空队列并回到 idle', async () => {
    const { audio, player, report } = build(async (songId) => resolved(songId))

    await player.playQueue([TRACK_A], 0)
    player.stop()

    expect(player.state.status).toBe('idle')
    expect(player.state.queue).toHaveLength(0)
    expect(player.hasTrack.value).toBe(false)
    expect(audio.src).toBe('')
    expect(report).toHaveBeenCalledWith(1901, 'STOP', expect.any(String))
  })

  /* ==================== 活跃播放：心跳 ==================== */

  it('播放中按间隔发心跳；暂停后不再发', async () => {
    vi.useFakeTimers()
    try {
      const { player, report } = build(async (songId) => resolved(songId), {
        heartbeatIntervalMs: 1_000,
      })

      await player.playQueue([TRACK_A], 0)
      const instance = (report.mock.calls[0] as unknown[])[2]

      expect(report).toHaveBeenCalledWith(1901, 'START', instance)

      vi.advanceTimersByTime(3_000)
      const beats = report.mock.calls.filter((call) => call[1] === 'HEARTBEAT')
      expect(beats).toHaveLength(3)
      expect(beats.every((call) => call[2] === instance)).toBe(true)

      player.stop()
      report.mockClear()
      vi.advanceTimersByTime(5_000)
      expect(report.mock.calls.filter((call) => call[1] === 'HEARTBEAT')).toHaveLength(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('服务端判定实例过期（409）：停止心跳，不弹错误，也不打断播放', async () => {
    vi.useFakeTimers()
    try {
      const report = vi
        .fn()
        .mockResolvedValueOnce(undefined)
        .mockRejectedValue(new ApiError('conflict', { status: 409 }))
      const audio = new FakeAudio()
      const player = createPlayer({
        createAudio: () => audio,
        resolve: (async (songId: number) => resolved(songId)) as never,
        report: report as never,
        createEq: () => null,
        heartbeatIntervalMs: 1_000,
      })

      await player.playQueue([TRACK_A], 0)
      vi.advanceTimersByTime(1_000)
      await Promise.resolve()
      await Promise.resolve()

      expect(player.state.status).toBe('playing')
      expect(player.state.error).toBeNull()

      report.mockClear()
      vi.advanceTimersByTime(5_000)
      expect(report).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })

  /* ==================== 续播快照 ==================== */

  it('切歌后续播快照指向新曲，不保留上一首', async () => {
    const store = resumeStore()
    const { player } = build(async (songId) => resolved(songId), { resume: store })

    await player.playQueue([TRACK_A, TRACK_B], 0)
    expect(player.state.resume?.songId).toBe(1901)

    await player.next()
    expect(player.state.resume?.songId).toBe(1902)
    expect(store.load()?.songId).toBe(1902)
  })

  it('停止播放器会清掉续播快照（唯一主动清除的动作）', async () => {
    const store = resumeStore()
    const { player } = build(async (songId) => resolved(songId), { resume: store })

    await player.playQueue([TRACK_A], 0)
    expect(store.load()).not.toBeNull()

    player.stop()

    expect(store.load()).toBeNull()
    expect(player.state.resume).toBeNull()
  })

  it('续播：重新解析音源并 seek 到上次进度，不存任何地址', async () => {
    const store = resumeStore()
    store.save({
      songId: 1901,
      title: '测试音 A',
      artist: 'Fixtures',
      album: null,
      durationSeconds: 10,
      positionSeconds: 4.2,
    })

    const resolve = vi.fn(async (songId: number) => resolved(songId))
    const audio = new FakeAudio()
    audio.duration = 10
    const player = createPlayer({
      createAudio: () => audio,
      resolve: resolve as never,
      report: vi.fn().mockResolvedValue(undefined) as never,
      createEq: () => null,
      heartbeatIntervalMs: 0,
      resume: store,
    })

    // 打开页面时只显示快照，不自动播放
    expect(player.state.resume?.songId).toBe(1901)
    expect(audio.playCalls).toBe(0)

    await player.resumeLast()

    expect(resolve).toHaveBeenCalledWith(1901)
    expect(audio.currentTime).toBeCloseTo(4.2, 5)
    expect(player.state.status).toBe('playing')
  })

  it('续播时音源不可用：说明原因并保留快照（不假装在播）', async () => {
    const store = resumeStore()
    store.save({
      songId: 1901,
      title: '测试音 A',
      artist: 'Fixtures',
      positionSeconds: 3,
    })

    const { player } = build(
      async (songId) =>
        resolved(songId, {
          playable: false,
          mode: 'UNAVAILABLE',
          streamUrl: null,
          expiresAt: null,
          capabilities: { seek: false, range: false, eq: false },
          reasonCode: 'MEDIA_MISSING',
          reason: '音源文件不可用',
        }),
      { resume: store },
    )

    await player.resumeLast()

    expect(player.state.status).toBe('error')
    expect(player.state.error?.code).toBe('MEDIA_MISSING')
    expect(player.isPlaying.value).toBe(false)
    expect(player.state.resume?.songId).toBe(1901)
  })
})
