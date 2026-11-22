/**
 * 售后 store（A1）单测。
 *
 * 重点不是"能不能提交"，而是三条容易被写错的语义：
 * 1. **重试沿用同一个 clientRequestId**（后端据此重放，不会重复建单）；
 * 2. **改参数必须换新 id**（否则后端按"同键不同参数"拒绝，用户会看到莫名 409）；
 * 3. **失败文案认 code**（不解析后端中文 message）。
 */
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/errors'
import {
  createAfterSaleStore,
  describeAfterSaleError,
  newClientRequestId,
  type AfterSaleDeps,
} from './aftersale'
import type {
  AfterSalePreviewVO,
  AfterSaleReturnVO,
  AfterSaleSignedOrderVO,
} from '@/api/aftersale'

function signedOrder(overrides: Partial<AfterSaleSignedOrderVO> = {}): AfterSaleSignedOrderVO {
  return {
    orderNo: 'SO20261122100000-0001',
    sku: 'SKU-KEYBOARD-K87',
    productName: '机械键盘 K87',
    unitPriceCents: 29900,
    unitPriceText: '¥299.00',
    quota: { purchased: 2, occupied: 0, remaining: 2 },
    signedAt: '2026-11-22T10:00:00',
    deadline: '2026-11-29T10:00:00',
    expired: false,
    returnable: true,
    ...overrides,
  }
}

function returnOf(overrides: Partial<AfterSaleReturnVO> = {}): AfterSaleReturnVO {
  return {
    returnNo: 'RT20261122101500-00A1',
    orderNo: 'SO20261122100000-0001',
    buyerId: 'music-user-1',
    status: 'SUBMITTED',
    statusLabel: '已提交',
    source: 'FORM',
    sourceLabel: '普通表单',
    sku: 'SKU-KEYBOARD-K87',
    productName: '机械键盘 K87',
    unitPriceCents: 29900,
    unitPriceText: '¥299.00',
    quantity: 1,
    refundCents: 29900,
    refundText: '¥299.00',
    reason: '键盘按键失灵',
    createdAt: '2026-11-22T10:15:00',
    progress: null,
    receipts: null,
    decision: null,
    events: null,
    ...overrides,
  }
}

function previewOf(overrides: Partial<AfterSalePreviewVO> = {}): AfterSalePreviewVO {
  return {
    orderNo: 'SO20261122100000-0001',
    sku: 'SKU-KEYBOARD-K87',
    productName: '机械键盘 K87',
    unitPriceCents: 29900,
    unitPriceText: '¥299.00',
    requestedQty: 1,
    refundCents: 29900,
    refundText: '¥299.00',
    quota: { purchased: 2, occupied: 0, remaining: 2 },
    eligible: true,
    code: null,
    message: null,
    ...overrides,
  }
}

function buildDeps(overrides: Partial<AfterSaleDeps> = {}): AfterSaleDeps {
  let seq = 0
  return {
    orders: vi.fn(async () => [signedOrder()]),
    returns: vi.fn(async () => [returnOf()]),
    detail: vi.fn(async () => returnOf({ events: [] })),
    preview: vi.fn(async () => previewOf()),
    submit: vi.fn(async () => returnOf()),
    newRequestId: () => {
      seq += 1
      return `req-${seq}`
    },
    ...overrides,
  }
}

describe('describeAfterSaleError', () => {
  it('按 code 给出可操作文案', () => {
    expect(describeAfterSaleError(new ApiError('conflict', { code: 'RETURN_QUOTA_EXCEEDED' }))).toContain(
      '可退额度不足',
    )
    expect(describeAfterSaleError(new ApiError('conflict', { code: 'RETURN_WINDOW_CLOSED' }))).toContain(
      '退货窗口',
    )
    expect(describeAfterSaleError(new ApiError('forbidden', { code: 'SHOP_IDENTITY_NOT_CONFIGURED' }))).toContain(
      '售后身份未启用',
    )
    expect(describeAfterSaleError(new ApiError('conflict', { code: 'IDEMPOTENCY_KEY_REUSED' }))).toContain(
      '标识已经用于另一笔',
    )
  })

  it('不认识的 code 退回通用文案，非 ApiError 也不抛', () => {
    expect(describeAfterSaleError(new ApiError('server', { code: 'SOMETHING_NEW' }))).toBe(
      '服务端处理失败，请稍后重试。',
    )
    expect(describeAfterSaleError(new Error('boom'))).toContain('未预期')
  })
})

describe('newClientRequestId', () => {
  it('满足后端 [A-Za-z0-9_.:-]{8,64} 的长度与字符集', () => {
    for (let i = 0; i < 20; i += 1) {
      const value = newClientRequestId()
      expect(value.length).toBeGreaterThanOrEqual(8)
      expect(value.length).toBeLessThanOrEqual(64)
      expect(value).toMatch(/^[A-Za-z0-9_.:-]+$/)
    }
  })
})

describe('售后 store', () => {
  it('读取订单时保留服务端给的额度三个数，不自己算', async () => {
    const deps = buildDeps({
      orders: vi.fn(async () => [
        signedOrder({ quota: { purchased: 2, occupied: 1, remaining: 1 } }),
      ]),
    })
    const store = createAfterSaleStore(deps)

    await store.loadOrders()

    expect(store.state.orders).toHaveLength(1)
    expect(store.quotaOf('SO20261122100000-0001')?.quota).toEqual({
      purchased: 2,
      occupied: 1,
      remaining: 1,
    })
  })

  it('提交成功后展示真实退货单号，并重新读取列表与额度', async () => {
    const deps = buildDeps()
    const store = createAfterSaleStore(deps)
    store.selectOrder('SO20261122100000-0001')
    store.setReason('键盘按键失灵')

    const created = await store.submitForm()

    expect(created?.returnNo).toBe('RT20261122101500-00A1')
    expect(store.state.notice).toContain('RT20261122101500-00A1')
    expect(store.state.notice).toContain('已提交')
    expect(deps.returns).toHaveBeenCalledTimes(1)
    expect(deps.orders).toHaveBeenCalledTimes(1)
    expect(store.state.error).toBeNull()
  })

  it('失败后重试沿用同一个 clientRequestId（后端可重放，不会重复建单）', async () => {
    const submit = vi
      .fn()
      .mockRejectedValueOnce(new ApiError('network'))
      .mockResolvedValueOnce(returnOf())
    const deps = buildDeps({ submit })
    const store = createAfterSaleStore(deps)
    store.selectOrder('SO20261122100000-0001')
    store.setReason('键盘按键失灵')

    expect(await store.submitForm()).toBeNull()
    expect(store.state.error).toContain('无法连接')

    const created = await store.submitForm()

    expect(created).not.toBeNull()
    const first = submit.mock.calls[0][0]
    const second = submit.mock.calls[1][0]
    expect(second.clientRequestId).toBe(first.clientRequestId)
    expect(first.reason).toBe('键盘按键失灵')
  })

  it('改了订单 / 数量 / 原因就换新 id（另一笔申请，不能被当成重放）', () => {
    const store = createAfterSaleStore(buildDeps())
    const initial = store.form.clientRequestId

    store.selectOrder('SO20261122100000-0001')
    const afterOrder = store.form.clientRequestId
    store.setQuantity(2)
    const afterQuantity = store.form.clientRequestId
    store.setReason('尺寸不合适')
    const afterReason = store.form.clientRequestId

    expect(new Set([initial, afterOrder, afterQuantity, afterReason]).size).toBe(4)
  })

  it('提交成功之后下一笔申请用新 id', async () => {
    const deps = buildDeps()
    const store = createAfterSaleStore(deps)
    store.selectOrder('SO20261122100000-0001')
    store.setReason('键盘按键失灵')

    await store.submitForm()
    store.selectOrder('SO20261122100000-0001')
    store.setReason('还是想退')
    await store.submitForm()

    const [first, second] = (deps.submit as ReturnType<typeof vi.fn>).mock.calls
    expect(second[0].clientRequestId).not.toBe(first[0].clientRequestId)
  })

  it('额度不足时按 code 报错，且不清掉可重试的提交身份', async () => {
    const submit = vi.fn().mockRejectedValue(new ApiError('conflict', { code: 'RETURN_QUOTA_EXCEEDED' }))
    const store = createAfterSaleStore(buildDeps({ submit }))
    store.selectOrder('SO20261122100000-0001')
    store.setReason('键盘按键失灵')
    const before = store.form.clientRequestId

    expect(await store.submitForm()).toBeNull()
    expect(store.state.error).toContain('可退额度不足')
    expect(store.form.clientRequestId).toBe(before)
  })

  it('本地校验拦住空订单与空原因：不发请求', async () => {
    const deps = buildDeps()
    const store = createAfterSaleStore(deps)

    expect(await store.submitForm()).toBeNull()
    expect(store.state.error).toContain('请先选择要退的订单')

    store.selectOrder('SO20261122100000-0001')
    expect(await store.submitForm()).toBeNull()
    expect(store.state.error).toContain('退货原因必填')

    expect(deps.submit).not.toHaveBeenCalled()
  })

  it('试算只读：只调预览，不调提交', async () => {
    const deps = buildDeps({
      preview: vi.fn(async () =>
        previewOf({ eligible: false, code: 'RETURN_QUOTA_EXCEEDED', message: '可退额度不足' }),
      ),
    })
    const store = createAfterSaleStore(deps)
    store.selectOrder('SO20261122100000-0001')
    store.setReason('键盘按键失灵')

    const preview = await store.previewForm()

    expect(preview?.eligible).toBe(false)
    expect(store.state.preview?.code).toBe('RETURN_QUOTA_EXCEEDED')
    expect(deps.submit).not.toHaveBeenCalled()
    expect(deps.returns).not.toHaveBeenCalled()
  })

  it('数量被夹在 1 与已购数量之间', async () => {
    const store = createAfterSaleStore(buildDeps())
    await store.loadOrders()
    store.selectOrder('SO20261122100000-0001')

    store.setQuantity(0)
    expect(store.form.quantity).toBe(1)

    store.setQuantity(9)
    expect(store.form.quantity).toBe(2)

    store.setQuantity(Number.NaN)
    expect(store.form.quantity).toBe(1)
  })

  it('详情迟到响应不覆盖后点的那一单', async () => {
    const holder: { resolve?: (value: AfterSaleReturnVO) => void } = {}
    const detail = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<AfterSaleReturnVO>((resolve) => {
            holder.resolve = resolve
          }),
      )
      .mockResolvedValueOnce(returnOf({ returnNo: 'RT-B', events: [] }))

    const store = createAfterSaleStore(buildDeps({ detail }))

    const first = store.openReturn('RT-A')
    await store.openReturn('RT-B')
    holder.resolve?.(returnOf({ returnNo: 'RT-A', events: [] }))
    await first

    expect(store.state.detail?.returnNo).toBe('RT-B')
  })
})
