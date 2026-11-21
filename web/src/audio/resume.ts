/**
 * 本机续播快照。
 *
 * 三件事必须说清楚，否则很容易做成"另一个播放事实"：
 *
 * 1. **它不是"正在播放"**。快照只表示"上次听到哪里"，用于刷新/重开页面后
 *    显示一张可点击的续播卡。它不上报、不参与审批、不能被 Agent 当作
 *    "正在播放的曲目"。服务端的活跃播放由心跳独立维护，两者互不依赖。
 * 2. **它只存在本机**。按 MkAgent 用户隔离（同一个浏览器换身份不会串），
 *    不存签名地址、不存第三方 token、不存任何凭据——只存曲目引用与进度。
 * 3. **它会过期**。默认 12 小时；过期只删这一条快照，不碰歌单、不碰播放历史。
 *
 * 为什么不用服务端存：续播是"这台设备上一次听到这里"的体验，
 * 多设备同步是另一个产品问题；本轮先保证单实例流程与边界清楚。
 */

/** 快照里保存的字段。刻意很小：只够画出续播卡并定位到进度。 */
export interface ResumeSnapshot {
  /** 归属用户（MkAgent 用户标识），用于隔离与校验 */
  userId: string
  songId: number
  title: string
  artist: string
  album?: string | null
  durationSeconds?: number | null
  /** 上次听到的秒数 */
  positionSeconds: number
  /** 最后一次写入的时间（epoch 毫秒） */
  updatedAt: number
}

/** 默认有效期：12 小时。 */
export const RESUME_TTL_MS = 12 * 60 * 60 * 1000

/** 存储键前缀。带版本号，将来改结构可以直接弃用旧键。 */
export const RESUME_KEY_PREFIX = 'mkagent.resume.v1.'

/** 只依赖这几个方法，便于在 jsdom / 隐私模式下替换实现。 */
export interface ResumeStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export interface ResumeStoreDeps {
  storage: ResumeStorage
  /** 当前用户；每次读写都重新取，避免切换身份后仍写旧键 */
  userId: () => string
  now?: () => number
}

export interface ResumeStore {
  save(snapshot: Omit<ResumeSnapshot, 'userId' | 'updatedAt'>): ResumeSnapshot | null
  load(): ResumeSnapshot | null
  clear(): void
  /** 只读当前键名（测试与排障用） */
  key(): string
}

/**
 * 创建一个按用户隔离的续播存储。
 *
 * 所有存储操作都包在 try/catch 里：Safari 隐私模式、配额满、
 * 被策略禁用时 `localStorage` 会直接抛异常。续播是"锦上添花"，
 * 不能因为它把播放器初始化搞崩。
 */
export function createResumeStore(deps: ResumeStoreDeps): ResumeStore {
  const now = deps.now ?? (() => Date.now())

  function key(): string {
    return RESUME_KEY_PREFIX + deps.userId()
  }

  function clear(): void {
    try {
      deps.storage.removeItem(key())
    } catch {
      // 存不进去也删不掉：忽略
    }
  }

  function load(): ResumeSnapshot | null {
    let raw: string | null = null
    try {
      raw = deps.storage.getItem(key())
    } catch {
      return null
    }
    if (!raw) {
      return null
    }

    const parsed = parse(raw)
    if (!parsed) {
      // 结构不认识（旧版本 / 被手改）：直接丢掉，不要让它反复报错
      clear()
      return null
    }

    if (parsed.userId !== deps.userId()) {
      // 键是按用户分的，理论上不会发生；发生了也不采用
      return null
    }

    if (now() - parsed.updatedAt > RESUME_TTL_MS) {
      /*
       * 过期只清快照。歌单、播放历史、当前播放都不受影响——
       * 这是"12 小时后不恢复"，不是"12 小时后清空数据"。
       */
      clear()
      return null
    }

    return parsed
  }

  function save(
    snapshot: Omit<ResumeSnapshot, 'userId' | 'updatedAt'>,
  ): ResumeSnapshot | null {
    if (!Number.isFinite(snapshot.positionSeconds) || snapshot.positionSeconds < 0) {
      return null
    }

    const record: ResumeSnapshot = {
      ...snapshot,
      userId: deps.userId(),
      updatedAt: now(),
    }

    try {
      deps.storage.setItem(key(), JSON.stringify(record))
      return record
    } catch {
      return null
    }
  }

  return { save, load, clear, key }
}

/** 结构校验：宁可丢弃，也不要把半个对象交给播放器。 */
function parse(raw: string): ResumeSnapshot | null {
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    return null
  }

  if (!value || typeof value !== 'object') {
    return null
  }

  const candidate = value as Record<string, unknown>

  if (
    typeof candidate.userId !== 'string' ||
    typeof candidate.songId !== 'number' ||
    !Number.isFinite(candidate.songId) ||
    candidate.songId <= 0 ||
    typeof candidate.title !== 'string' ||
    typeof candidate.artist !== 'string' ||
    typeof candidate.positionSeconds !== 'number' ||
    !Number.isFinite(candidate.positionSeconds) ||
    typeof candidate.updatedAt !== 'number' ||
    !Number.isFinite(candidate.updatedAt)
  ) {
    return null
  }

  return {
    userId: candidate.userId,
    songId: candidate.songId,
    title: candidate.title,
    artist: candidate.artist,
    album: typeof candidate.album === 'string' ? candidate.album : null,
    durationSeconds:
      typeof candidate.durationSeconds === 'number' &&
      Number.isFinite(candidate.durationSeconds)
        ? candidate.durationSeconds
        : null,
    positionSeconds: Math.max(0, candidate.positionSeconds),
    updatedAt: candidate.updatedAt,
  }
}

/** 浏览器环境下的默认存储；不可用时退化为内存实现（本次会话仍可用）。 */
export function defaultResumeStorage(): ResumeStorage {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      const probe = '__mk_resume_probe__'
      window.localStorage.setItem(probe, '1')
      window.localStorage.removeItem(probe)
      return window.localStorage
    }
  } catch {
    // 隐私模式 / 被禁用
  }
  return memoryStorage()
}

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
