import { describe, expect, it } from 'vitest'
import {
  createResumeStore,
  defaultResumeStorage,
  RESUME_KEY_PREFIX,
  RESUME_TTL_MS,
  type ResumeStorage,
} from './resume'

/**
 * 续播快照的边界。
 *
 * 这里的每一条都对应一个"看起来能用、实际会出事"的实现：
 * - 不按用户隔离 → 换身份后看到别人的进度；
 * - 不检查过期 → 三个月前的进度被当成"上次听到"；
 * - 不校验结构 → 被手改过的 JSON 让播放器崩在初始化里。
 */

function memoryStorage(): ResumeStorage & { dump(): Map<string, string> } {
  const map = new Map<string, string>()
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => {
      map.set(key, value)
    },
    removeItem: (key) => {
      map.delete(key)
    },
    dump: () => map,
  }
}

const SNAPSHOT = {
  songId: 1901,
  title: '测试音 A（琶音）',
  artist: 'MkAgent Test Fixtures',
  album: null,
  durationSeconds: 10,
  positionSeconds: 4.5,
}

describe('续播快照', () => {
  it('保存后可读回，并带上用户与写入时间', () => {
    const storage = memoryStorage()
    const store = createResumeStore({
      storage,
      userId: () => 'music-user-1',
      now: () => 1_000_000,
    })

    const saved = store.save(SNAPSHOT)

    expect(saved?.userId).toBe('music-user-1')
    expect(saved?.updatedAt).toBe(1_000_000)
    expect(store.load()).toMatchObject({ songId: 1901, positionSeconds: 4.5 })
  })

  it('按用户隔离：换身份读不到别人的快照', () => {
    const storage = memoryStorage()
    let user = 'music-user-1'
    const store = createResumeStore({ storage, userId: () => user })

    store.save(SNAPSHOT)
    expect(store.load()?.songId).toBe(1901)

    user = 'music-user-2'
    expect(store.load()).toBeNull()

    // 键名带用户，两个人的快照可以共存
    expect(storage.dump().has(RESUME_KEY_PREFIX + 'music-user-1')).toBe(true)
  })

  it('超过 12 小时：不恢复，并清掉这一条（不动其它数据）', () => {
    const storage = memoryStorage()
    let now = 1_000_000
    const store = createResumeStore({ storage, userId: () => 'music-user-1', now: () => now })

    store.save(SNAPSHOT)

    // 差一秒到期：仍然恢复
    now += RESUME_TTL_MS - 1_000
    expect(store.load()).not.toBeNull()

    // 超过 12 小时：不恢复
    now += 2_000
    expect(store.load()).toBeNull()
    expect(storage.dump().size).toBe(0)
  })

  it('结构被破坏：直接丢弃，不把半个对象交给播放器', () => {
    const storage = memoryStorage()
    const store = createResumeStore({ storage, userId: () => 'music-user-1' })

    storage.setItem(RESUME_KEY_PREFIX + 'music-user-1', '{"songId":"1901"}')

    expect(store.load()).toBeNull()
    expect(storage.dump().size).toBe(0)
  })

  it('存储不可用（隐私模式）：读写都不抛异常', () => {
    const broken: ResumeStorage = {
      getItem: () => {
        throw new Error('SecurityError')
      },
      setItem: () => {
        throw new Error('QuotaExceededError')
      },
      removeItem: () => {
        throw new Error('SecurityError')
      },
    }

    const store = createResumeStore({ storage: broken, userId: () => 'music-user-1' })

    expect(() => store.save(SNAPSHOT)).not.toThrow()
    expect(store.save(SNAPSHOT)).toBeNull()
    expect(store.load()).toBeNull()
    expect(() => store.clear()).not.toThrow()
  })

  it('没有 localStorage 的环境退化为内存实现，本次会话仍可用', () => {
    // jsdom 有 localStorage，这里只验证函数不会抛，并且返回可用的接口
    const storage = defaultResumeStorage()
    expect(() => storage.setItem('probe', '1')).not.toThrow()
    expect(storage.getItem('probe')).toBe('1')
    storage.removeItem('probe')
  })
})
