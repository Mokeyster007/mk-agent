import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ShopOrderDetailPanel from './ShopOrderDetailPanel.vue'
import type { ShopOrderVO } from '@/api/shop'

function orderOf(overrides: Partial<ShopOrderVO> = {}): ShopOrderVO {
  return {
    orderNo: 'SO20261122100000-ABCD',
    status: 'PENDING_SHIPMENT',
    statusLabel: '待发货',
    totalCents: 59800,
    totalText: '¥598.00',
    paymentMode: 'DEMO',
    buyerId: 'music-user-1',
    createdAt: '2026-11-22T10:00:00',
    shippedAt: null,
    shippedBy: null,
    signedAt: null,
    signedBy: null,
    canShip: true,
    canSign: false,
    item: {
      productId: 2001,
      sku: 'SKU-KEYBOARD-K87',
      productName: '机械键盘 K87',
      unitPriceCents: 29900,
      unitPriceText: '¥299.00',
      quantity: 2,
      lineCents: 59800,
      lineText: '¥598.00',
    },
    movements: [],
    ...overrides,
  }
}

describe('订单详情抽屉', () => {
  it('渲染订单号、状态、金额与成交快照', () => {
    const wrapper = mount(ShopOrderDetailPanel, {
      props: { order: orderOf() },
    })

    expect(wrapper.text()).toContain('SO20261122100000-ABCD')
    expect(wrapper.text()).toContain('待发货')
    expect(wrapper.text()).toContain('¥598.00')
    expect(wrapper.text()).toContain('机械键盘 K87')
    expect(wrapper.text()).toContain('SKU-KEYBOARD-K87')
    expect(wrapper.text()).toContain('¥299.00 × 2')
    expect(wrapper.text()).toContain('演示付款')
  })

  it('未发货时明确说明"库存未扣减"，不显示出库记录', () => {
    const wrapper = mount(ShopOrderDetailPanel, {
      props: { order: orderOf() },
    })

    expect(wrapper.text()).toContain('尚未发货（库存未扣减）')
    expect(wrapper.text()).toContain('暂无出库记录')
  })

  it('有流水时显示数量与操作人', () => {
    const wrapper = mount(ShopOrderDetailPanel, {
      props: {
        order: orderOf({
          status: 'SHIPPED',
          statusLabel: '已发货',
          shippedAt: '2026-11-22T11:00:00',
          shippedBy: 'music-admin-1',
          canShip: false,
          canSign: true,
          movements: [
            {
              id: 1,
              sku: 'SKU-KEYBOARD-K87',
              quantityDelta: -2,
              type: 'OUTBOUND_SHIPMENT',
              typeLabel: '发货出库',
              orderId: 1,
              orderItemId: 1,
              receiptId: null,
              operatorId: 'music-admin-1',
              createdAt: '2026-11-22T11:00:00',
            },
          ],
        }),
      },
    })

    expect(wrapper.text()).toContain('-2')
    expect(wrapper.text()).toContain('OUTBOUND_SHIPMENT')
    expect(wrapper.text()).toContain('music-admin-1')
    expect(wrapper.text()).not.toContain('暂无出库记录')
  })

  it('后台模式下可发货，点击后抛出 ship 事件', async () => {
    const wrapper = mount(ShopOrderDetailPanel, {
      props: { order: orderOf(), showActions: true },
    })

    const shipButton = wrapper.find('button.mk-btn--primary')
    expect(shipButton.attributes('disabled')).toBeUndefined()

    await shipButton.trigger('click')
    expect(wrapper.emitted('ship')).toHaveLength(1)
  })

  it('已签收时两个操作都不可用，并说明原因', () => {
    const wrapper = mount(ShopOrderDetailPanel, {
      props: {
        order: orderOf({
          status: 'SIGNED',
          statusLabel: '已签收',
          shippedAt: '2026-11-22T11:00:00',
          signedAt: '2026-11-22T12:00:00',
          canShip: false,
          canSign: false,
        }),
        showActions: true,
      },
    })

    const buttons = wrapper.findAll('button.mk-btn')
    expect(buttons.length).toBe(2)
    expect(buttons[0].attributes('disabled')).toBeDefined()
    expect(buttons[1].attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('没有可执行的操作')
  })

  it('买家模式不渲染操作按钮', () => {
    const wrapper = mount(ShopOrderDetailPanel, {
      props: { order: orderOf() },
    })

    expect(wrapper.findAll('button.mk-btn')).toHaveLength(0)
  })
})
