import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ApprovalCard from './ApprovalCard.vue'
import type { ApprovalRequiredPayload, ApprovalView } from '@/api/types'

/**
 * 审批卡测试。
 *
 * 守两条底线：
 * 1. 卡片内容只能来自服务端摘要（payload / approval view），
 *    不存在"根据模型文本拼一个审批"的路径；
 * 2. 状态决定按钮可用性：待确认才可提交、提交中禁用、预览模式全禁用。
 */
const payload: ApprovalRequiredPayload = {
  approvalId: 'ap-1',
  expiresAt: '2026-11-17T01:30:00',
  summary: {
    tool: 'create_playlist',
    toolLabel: '创建歌单',
    playlistName: '清晨慢跑',
    songCount: 2,
    songs: ['城市霓虹', '午夜电台'],
    roundToolCount: 1,
    text: '即将创建歌单「清晨慢跑」，并放入 2 首歌曲。',
  },
}

function view(overrides: Partial<ApprovalView> = {}): ApprovalView {
  return {
    approvalId: 'ap-1',
    runId: 'run-1',
    agentType: 'MUSIC_USER',
    step: 3,
    primaryTool: 'create_playlist',
    toolCount: 1,
    summaryText: payload.summary.text,
    summary: payload.summary,
    status: 'PENDING',
    resumeState: 'PENDING_RESUME',
    resumeAttempts: 0,
    decidedAt: null,
    expiresAt: '2026-11-17T01:30:00',
    ...overrides,
  }
}

function mountCard(props: Record<string, unknown> = {}) {
  return mount(ApprovalCard, {
    props: {
      payload,
      approval: view(),
      ...props,
    },
  })
}

describe('ApprovalCard', () => {
  it('展示服务端摘要里的操作、歌单与歌曲', () => {
    const wrapper = mountCard()
    const text = wrapper.text()

    expect(text).toContain('创建歌单')
    expect(text).toContain('清晨慢跑')
    expect(text).toContain('2 首')
    expect(text).toContain('城市霓虹')
    expect(text).toContain('午夜电台')
    expect(text).toContain('2026-11-17 01:30:00')
  })

  it('摘要没有歌曲 ID 时不编造 ID', () => {
    const wrapper = mountCard()
    expect(wrapper.text()).toContain('没有可靠的歌曲 ID 映射')
  })

  it('待确认状态可以提交决定', () => {
    const wrapper = mountCard()
    const buttons = wrapper.findAll('button')
    const approve = buttons.find((button) => button.text().includes('批准并执行'))

    expect(approve?.attributes('disabled')).toBeUndefined()
  })

  it('提交中禁用批准与拒绝，防重复点击', () => {
    const wrapper = mountCard({ busy: true })
    const approve = wrapper.findAll('button').find((b) => b.text().includes('提交中'))

    expect(approve?.attributes('disabled')).toBeDefined()
  })

  it('已处理状态不再允许提交决定', () => {
    const wrapper = mountCard({ approval: view({ status: 'APPROVED', resumeState: 'FINISHED' }) })
    const approve = wrapper.findAll('button').find((b) => b.text().includes('批准并执行'))

    expect(approve?.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('已批准')
  })

  it('决定已保存但恢复未完成时出现手动恢复', () => {
    const wrapper = mountCard({
      approval: view({ status: 'APPROVED', resumeState: 'PENDING_RESUME', resumeAttempts: 2 }),
    })

    expect(wrapper.text()).toContain('手动恢复')
    expect(wrapper.text()).toContain('已尝试恢复 2 次')
  })

  it('恢复已完成时不显示手动恢复', () => {
    const wrapper = mountCard({ approval: view({ status: 'APPROVED', resumeState: 'FINISHED' }) })

    expect(wrapper.text()).not.toContain('手动恢复')
  })

  it('预览模式：按钮全禁用且标注不发起请求', () => {
    const wrapper = mountCard({ mode: 'preview' })
    // 注意：disabled 属性存在时 attributes('disabled') 返回空串，
    // 用 === undefined 判断"可点击"，否则空串会被当成 falsy 误判为可用。
    const enabled = wrapper
      .findAll('button')
      .filter((button) => button.attributes('disabled') === undefined)

    expect(enabled).toHaveLength(0)
    expect(wrapper.text()).toContain('预览模式：按钮不发起任何请求')
  })

  it('点击批准只发一次事件', async () => {
    const wrapper = mountCard()
    const approve = wrapper.findAll('button').find((b) => b.text().includes('批准并执行'))

    await approve?.trigger('click')

    expect(wrapper.emitted('approve')).toHaveLength(1)
    expect(wrapper.emitted('reject')).toBeUndefined()
  })

  it('未确认到审批状态时提示先刷新，而不是假装待确认', () => {
    const wrapper = mountCard({ approval: null })

    expect(wrapper.text()).toContain('状态未确认')
    expect(wrapper.text()).toContain('建议先刷新')
  })
})
