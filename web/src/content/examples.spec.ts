import { describe, expect, it } from 'vitest'
import { examplesFor } from './examples'
import { resolveIdentityPolicy } from '@/api/identity'

/**
 * 内容与身份策略测试。
 *
 * 两个"不能骗人"的点：
 * 1. 只读模式下不能出现任何写操作示例（点了必然失败）；
 * 2. 正式构建不能出现切换身份的入口。
 */
describe('示例任务', () => {
  it('只读模式不出现任何写操作示例', () => {
    const examples = examplesFor('readonly')
    const text = examples.map((item) => `${item.title}${item.prompt}`).join(' ')

    expect(text).not.toContain('创建歌单')
    expect(text).not.toContain('建一个歌单')
    expect(text).not.toContain('加到')
    expect(text).not.toContain('删除')
  })

  it('写能力模式才给出写操作示例', () => {
    const text = examplesFor('write_approval')
      .map((item) => `${item.title}${item.prompt}`)
      .join(' ')

    expect(text).toContain('建一个歌单')
    expect(text).toContain('需审批')
  })

  it('每个示例都有标题与可提交的提示词', () => {
    for (const example of examplesFor('readonly')) {
      expect(example.title.length).toBeGreaterThan(0)
      expect(example.prompt.length).toBeGreaterThan(4)
    }
  })

  it('只读示例数量在 3–4 条之间，符合空状态设计', () => {
    expect(examplesFor('readonly').length).toBeGreaterThanOrEqual(3)
    expect(examplesFor('readonly').length).toBeLessThanOrEqual(4)
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
