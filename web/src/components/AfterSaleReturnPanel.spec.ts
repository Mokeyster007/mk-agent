import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AfterSaleReturnPanel from './AfterSaleReturnPanel.vue'
import type { AfterSaleReturnVO, AfterSaleStageVO } from '@/api/aftersale'

const STAGE_WAITING_DECISION: AfterSaleStageVO = {
  code: 'WAITING_DECISION',
  label: '待客服处理',
  hint: '客服还没有给出结论；批准或驳回之后这里会显示下一步。',
}

const STAGE_REJECTED: AfterSaleStageVO = {
  code: 'REJECTED',
  label: '已驳回',
  hint: '处理已终止：不会再收货，也不会有模拟退款。驳回原因见下方处理结果。',
}

const STAGE_RECEIVED_NOT_REFUNDED: AfterSaleStageVO = {
  code: 'RECEIVED_NOT_REFUNDED',
  label: '已全部收货，尚未登记模拟退款',
  hint: '货已收齐；客服登记模拟退款后，这里会显示退款单号与金额。',
}

const STAGE_REFUNDED: AfterSaleStageVO = {
  code: 'REFUNDED',
  label: '已登记模拟退款',
  hint: '已登记一笔模拟退款（RF20261123005720-AD1D）；这是业务记录，没有真实资金流动。',
}

function returnOf(overrides: Partial<AfterSaleReturnVO> = {}): AfterSaleReturnVO {
  return {
    returnNo: 'RT20261123005708-7514',
    orderNo: 'SO20261123005659-10A9',
    buyerId: 'shop-user-1',
    status: 'SUBMITTED',
    statusLabel: '已提交',
    source: 'AGENT',
    sourceLabel: '售后 Agent',
    sku: 'SKU-KEYBOARD-K87',
    productName: '机械键盘 K87',
    unitPriceCents: 29900,
    unitPriceText: '¥299.00',
    quantity: 2,
    refundCents: 59800,
    refundText: '¥598.00',
    reason: '键盘按键失灵',
    createdAt: '2026-11-23T00:57:08',
    stage: STAGE_WAITING_DECISION,
    progress: null,
    receipts: null,
    refund: null,
    decision: null,
    events: [],
    ...overrides,
  }
}

describe('买家售后详情（C1 时间线）', () => {
  it('待客服处理：只显示提交与"等客服"，不出现收货/退款步骤', () => {
    const wrapper = mount(AfterSaleReturnPanel, { props: { value: returnOf() } })

    expect(wrapper.text()).toContain('待客服处理')
    expect(wrapper.text()).toContain('RT20261123005708-7514')
    expect(wrapper.text()).toContain('等客服处理中')
    expect(wrapper.find('[data-step="receipt"]').exists()).toBe(false)
    expect(wrapper.find('[data-step="refund"]').exists()).toBe(false)
  })

  it('已驳回：显示原因与时间，且不出现"待收货 / 待退款"这类还会继续的暗示', () => {
    const wrapper = mount(AfterSaleReturnPanel, {
      props: {
        value: returnOf({
          status: 'REJECTED',
          statusLabel: '已驳回',
          stage: STAGE_REJECTED,
          decision: {
            decision: 'REJECTED',
            label: '已驳回',
            reason: '超过 7 天无理由期限',
            decidedBy: 'music-admin-1',
            decidedAt: '2026-11-23T01:00:00',
          },
        }),
      },
    })

    const text = wrapper.text()
    expect(text).toContain('已驳回')
    expect(text).toContain('超过 7 天无理由期限')
    expect(text).toContain('2026-11-23 01:00:00')
    expect(wrapper.find('[data-step="receipt"]').exists()).toBe(false)
    expect(wrapper.find('[data-step="refund"]').exists()).toBe(false)
    expect(text).not.toContain('待收货')
    expect(text).not.toContain('待模拟退款')
    // F-04：已驳回单不得再显示“预计退款”
    expect(text).not.toContain('预计退款')
  })

  it('部分收货：显示已收 1/2、还差 1 件，并逐批区分实收/合格/损坏', () => {
    const wrapper = mount(AfterSaleReturnPanel, {
      props: {
        value: returnOf({
          status: 'APPROVED',
          statusLabel: '已批准',
          stage: {
            code: 'PARTIAL_RECEIPT',
            label: '部分收货',
            hint: '已收 1 件，还差 1 件；损坏件也计入实收。',
          },
          progress: { code: 'PARTIAL', label: '部分收货', approvedQty: 2, receivedQty: 1, remainingQty: 1 },
          receipts: [
            {
              receiptNo: 'RC20261123010000-AAAA',
              receivedQty: 1,
              qualifiedQty: 1,
              damagedQty: 0,
              note: '外箱完好',
              operatorId: 'music-admin-1',
              createdAt: '2026-11-23T01:00:00',
              movement: null,
            },
          ],
          decision: {
            decision: 'APPROVED',
            label: '已批准',
            reason: null,
            decidedBy: 'music-admin-1',
            decidedAt: '2026-11-23T00:58:00',
          },
        }),
      },
    })

    const text = wrapper.text()
    expect(text).toContain('部分收货 1/2')
    expect(text).toContain('还差 1 件')
    expect(text).toContain('RC20261123010000-AAAA')
    expect(text).toContain('实收 1 件')
    expect(text).toContain('合格 1 件')
    expect(text).toContain('损坏 0 件')
    // 退款这一步还没有，明确"收满之后才能登记"
    expect(wrapper.find('[data-refund]').exists()).toBe(false)
    expect(text).toContain('客服才能登记模拟退款')
  })

  it('全部收货但未退款：明确"尚未登记"，不能显示成已退款', () => {
    const wrapper = mount(AfterSaleReturnPanel, {
      props: {
        value: returnOf({
          status: 'APPROVED',
          statusLabel: '已批准',
          stage: STAGE_RECEIVED_NOT_REFUNDED,
          progress: { code: 'COMPLETE', label: '全部收货', approvedQty: 2, receivedQty: 2, remainingQty: 0 },
          receipts: [
            {
              receiptNo: 'RC20261123010000-AAAA',
              receivedQty: 1,
              qualifiedQty: 1,
              damagedQty: 0,
              note: null,
              operatorId: 'music-admin-1',
              createdAt: '2026-11-23T01:00:00',
              movement: null,
            },
            {
              receiptNo: 'RC20261123011000-BBBB',
              receivedQty: 1,
              qualifiedQty: 0,
              damagedQty: 1,
              note: '整批碎裂，无法二次销售',
              operatorId: 'music-admin-1',
              createdAt: '2026-11-23T01:10:00',
              movement: null,
            },
          ],
          decision: {
            decision: 'APPROVED',
            label: '已批准',
            reason: null,
            decidedBy: 'music-admin-1',
            decidedAt: '2026-11-23T00:58:00',
          },
        }),
      },
    })

    const text = wrapper.text()
    expect(text).toContain('已全部收货，尚未登记模拟退款')
    expect(wrapper.find('[data-refund-pending]').exists()).toBe(true)
    expect(wrapper.find('[data-refund]').exists()).toBe(false)
    expect(text).not.toContain('已退款')
    // 两批都能看到，损坏那批的备注也在
    expect(wrapper.findAll('[data-receipt-no]')).toHaveLength(2)
    expect(text).toContain('整批碎裂，无法二次销售')
  })

  it('已登记模拟退款：显示单号与金额，说明没有资金流动，且不泄露后台操作人', () => {
    const wrapper = mount(AfterSaleReturnPanel, {
      props: {
        value: returnOf({
          status: 'APPROVED',
          statusLabel: '已批准',
          stage: STAGE_REFUNDED,
          progress: { code: 'COMPLETE', label: '全部收货', approvedQty: 2, receivedQty: 2, remainingQty: 0 },
          receipts: [
            {
              receiptNo: 'RC20261123010000-AAAA',
              receivedQty: 2,
              qualifiedQty: 1,
              damagedQty: 1,
              note: null,
              operatorId: 'music-admin-1',
              createdAt: '2026-11-23T01:00:00',
              movement: null,
            },
          ],
          refund: {
            refundNo: 'RF20261123005720-AD1D',
            amountCents: 59800,
            amountText: '¥598.00',
            operatorId: 'music-admin-1',
            createdAt: '2026-11-23T01:20:00',
            label: '模拟退款',
          },
          decision: {
            decision: 'APPROVED',
            label: '已批准',
            reason: null,
            decidedBy: 'music-admin-1',
            decidedAt: '2026-11-23T00:58:00',
          },
        }),
      },
    })

    const text = wrapper.text()
    expect(wrapper.find('[data-refund]').exists()).toBe(true)
    expect(wrapper.find('[data-refund-amount]').text()).toBe('¥598.00')
    expect(text).toContain('RF20261123005720-AD1D')
    expect(text).toContain('没有真实资金流动')
    expect(text).not.toContain('到账')
    // 买家页面不展示后台操作人 ID（接口里虽然有，页面不该渲染）
    expect(text).not.toContain('music-admin-1')
  })
})
