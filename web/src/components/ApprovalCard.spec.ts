import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ApprovalCard from './ApprovalCard.vue'
import type { ApprovalRequiredPayload, ApprovalView } from '@/api/types'

const checkoutPayload: ApprovalRequiredPayload = {
  approvalId: 'ap-checkout-1',
  expiresAt: '2026-11-23T01:30:00',
  summary: {
    tool: 'submit_checkout_order',
    toolLabel: '确认提交演示订单',
    playlistName: null,
    songCount: null,
    songs: [],
    roundToolCount: 1,
    text: '确认下单：无线轻音机械键盘 Air75 × 1，预计总额 ¥369.00（演示付款）。',
    details: [
      { label: '商品', value: '无线轻音机械键盘 Air75（SKU-KEYBOARD-A75）' },
      { label: '数量', value: '1 件' },
      { label: '单价', value: '¥369.00' },
      { label: '预计总额', value: '¥369.00' },
    ],
  },
}

function view(overrides: Partial<ApprovalView> = {}): ApprovalView {
  return {
    approvalId: 'ap-checkout-1',
    runId: 'run-1',
    agentType: 'SHOP_ASSISTANT',
    step: 2,
    primaryTool: 'submit_checkout_order',
    toolCount: 1,
    summaryText: checkoutPayload.summary.text,
    summary: checkoutPayload.summary,
    status: 'PENDING',
    resumeState: 'PENDING_RESUME',
    resumeAttempts: 0,
    decidedAt: null,
    expiresAt: '2026-11-23T01:30:00',
    ...overrides,
  }
}

function mountCard(props: Record<string, unknown> = {}) {
  return mount(ApprovalCard, {
    props: {
      payload: checkoutPayload,
      approval: view(),
      ...props,
    },
  })
}

describe('ApprovalCard', () => {
  it('展示下单确认卡的结构化明细，且不出现音乐歌单占位', () => {
    const wrapper = mountCard()
    const text = wrapper.text()

    expect(text).toContain('确认提交演示订单')
    expect(text).toContain('Air75')
    expect(text).toContain('¥369.00')
    expect(text).toContain('「我的订单」')
    expect(text).not.toContain('目标歌单')
    expect(text).not.toContain('将写入的歌曲')
  })

  it('待确认状态可以提交决定，提交中禁用防重复点击', async () => {
    const wrapper = mountCard()
    const approve = wrapper.findAll('button').find((b) => b.text().includes('批准并执行'))
    expect(approve?.attributes('disabled')).toBeUndefined()

    await approve?.trigger('click')
    expect(wrapper.emitted('approve')).toHaveLength(1)

    const busyWrapper = mountCard({ busy: true })
    const busyApprove = busyWrapper.findAll('button').find((b) => b.text().includes('提交中'))
    expect(busyApprove?.attributes('disabled')).toBeDefined()
  })

  it('已处理状态不再允许提交决定，决定已保存但未恢复时展示手动恢复', () => {
    const finished = mountCard({ approval: view({ status: 'APPROVED', resumeState: 'FINISHED' }) })
    expect(finished.findAll('button').find((b) => b.text().includes('批准并执行'))?.attributes('disabled')).toBeDefined()
    expect(finished.text()).not.toContain('手动恢复')

    const pendingResume = mountCard({
      approval: view({ status: 'APPROVED', resumeState: 'PENDING_RESUME', resumeAttempts: 2 }),
    })
    expect(pendingResume.text()).toContain('手动恢复')
    expect(pendingResume.text()).toContain('已尝试恢复 2 次')
  })

  it('售后确认卡按 details 展示，且脚注指向「我的售后」', () => {
    const aftersale: ApprovalRequiredPayload = {
      approvalId: 'ap-aftersale',
      expiresAt: '2026-11-23T10:30:00',
      summary: {
        tool: 'submit_return',
        toolLabel: '提交退货申请',
        playlistName: null,
        songCount: null,
        songs: [],
        roundToolCount: 1,
        text: '提交退货申请：订单号 SO1；退货数量 1 件。',
        details: [
          { label: '订单号', value: 'SO20261122100000-0001' },
          { label: '商品', value: '机械键盘 K87' },
          { label: '退货数量', value: '1 件' },
          { label: '退货原因', value: '键盘按键失灵' },
        ],
      },
    }

    const wrapper = mountCard({
      payload: aftersale,
      approval: view({
        approvalId: 'ap-aftersale',
        agentType: 'AFTER_SALES',
        primaryTool: 'submit_return',
        summaryText: aftersale.summary.text,
        summary: aftersale.summary,
      }),
    })
    const text = wrapper.text()
    expect(text).toContain('提交退货申请')
    expect(text).toContain('SO20261122100000-0001')
    expect(text).toContain('「我的售后」')
    expect(text).not.toContain('目标歌单')
  })
})
