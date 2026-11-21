import { describe, expect, it } from 'vitest'
import { QUICK_ENTRIES, READ_ONLY_CAPABILITY_TEXT, WRITE_CAPABILITY_TEXT } from './examples'
import { resolveIdentityPolicy } from '@/api/identity'

/**
 * 内容与身份策略测试。
 *
 * 三个"不能骗人"的点：
 * 1. 空状态入口里不能出现写操作示例（只读装配下点了必然失败）；
 * 2. 「查看我的歌单」是本地抽屉，不能被当成一次模型任务提交；
 * 3. 正式构建不能出现切换身份的入口。
 */
describe('空状态快捷入口', () => {
  it('正好三个入口，符合空状态设计', () => {
    expect(QUICK_ENTRIES).toHaveLength(3)
  })

  it('入口文案里没有写操作', () => {
    const text = QUICK_ENTRIES.map((item) => `${item.title}${item.prompt}`).join(' ')

    expect(text).not.toContain('创建歌单')
    expect(text).not.toContain('建一个歌单')
    expect(text).not.toContain('加到')
    expect(text).not.toContain('删除')
  })

  it('只有歌单入口走本地抽屉，其余都是回填提示词', () => {
    const library = QUICK_ENTRIES.filter((item) => item.action === 'library')
    const prompts = QUICK_ENTRIES.filter((item) => !item.action)

    expect(library).toHaveLength(1)
    expect(library[0].title).toBe('查看我的歌单')
    expect(prompts).toHaveLength(2)
    for (const entry of prompts) {
      expect(entry.prompt.length).toBeGreaterThan(4)
    }
  })

  it('能力说明与装配一致：只读文案不宣称可写', () => {
    expect(READ_ONLY_CAPABILITY_TEXT).toContain('只读')
    expect(READ_ONLY_CAPABILITY_TEXT).toContain('写操作还没有开放')
    expect(WRITE_CAPABILITY_TEXT).toContain('审批')
  })
})

describe('身份策略', () => {
  it('开发模式允许切换联调身份', () => {
    expect(resolveIdentityPolicy({ dev: true }).allowSwitch).toBe(true)
  })

  it('正式构建一律不允许切换身份', () => {
    expect(resolveIdentityPolicy({ dev: false }).allowSwitch).toBe(false)
    expect(resolveIdentityPolicy({ dev: false, allow: 'true' }).allowSwitch).toBe(false)
  })

  it('显式关闭时开发模式也不允许切换', () => {
    expect(resolveIdentityPolicy({ dev: true, allow: 'false' }).allowSwitch).toBe(false)
  })
})
