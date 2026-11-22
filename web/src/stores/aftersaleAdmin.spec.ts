import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/errors'
import type { AfterSaleReturnVO } from '@/api/aftersale'
import { createAfterSaleAdminStore, type AfterSaleAdminDeps } from './aftersaleAdmin'

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

function buildDeps(overrides: Partial<AfterSaleAdminDeps> = {}): AfterSaleAdminDeps {
  return {
    list: vi.fn().mockResolvedValue({
      items: [returnOf()],
      page: 1,
      size: 20,
      total: 1,
    }),
    detail: vi.fn().mockResolvedValue(returnOf()),
    approve: vi.fn().mockResolvedValue(
      returnOf({
        status: 'APPROVED',
        statusLabel: '已批准',
        decision: {
          decision: 'APPROVED',
          label: '已批准',
          reason: null,
          decidedBy: 'music-admin-1',
          decidedAt: '2026-11-22T11:00:00',
        },
      }),
    ),
    reject: vi.fn().mockResolvedValue(
      returnOf({
        status: 'REJECTED',
        statusLabel: '已驳回',
        decision: {
          decision: 'REJECTED',
          label: '已驳回',
          reason: '超过期限',
          decidedBy: 'music-admin-1',
          decidedAt: '2026-11-22T11:00:00',
        },
      }),
    ),
    receive: vi.fn().mockResolvedValue(
      returnOf({
        status: 'APPROVED',
        statusLabel: '已批准',
        progress: { code: 'PARTIAL', label: '部分收货', approvedQty: 2, receivedQty: 1, remainingQty: 1 },
        receipts: [],
      }),
    ),
    ...overrides,
  }
}

describe('客服售后 store', () => {
  it('读列表：把筛选与分页原样传给后端，并写入总数', async () => {
    const deps = buildDeps()
    const store = createAfterSaleAdminStore(deps)

    await store.loadList('SUBMITTED')

    expect(deps.list).toHaveBeenCalledWith({ status: 'SUBMITTED', page: 1, size: 20 })
    expect(store.state.returns).toHaveLength(1)
    expect(store.state.total).toBe(1)
  })

  it('读列表失败时给出可读文案，不抛异常', async () => {
    const deps = buildDeps({
      list: vi.fn().mockRejectedValue(new ApiError('forbidden', { status: 403, code: 'NOT_ADMIN' })),
    })
    const store = createAfterSaleAdminStore(deps)

    await store.loadList()

    expect(store.state.error).toContain('不是商城后台人员')
    expect(store.state.returns).toEqual([])
  })

  it('批准成功：详情换成决策后的单据，提示里是真实单号，并重载列表', async () => {
    const deps = buildDeps()
    const store = createAfterSaleAdminStore(deps)

    const updated = await store.approve('RT20261122101500-00A1')

    expect(deps.approve).toHaveBeenCalledWith('RT20261122101500-00A1')
    expect(updated?.status).toBe('APPROVED')
    expect(store.state.detail?.statusLabel).toBe('已批准')
    expect(store.state.notice).toContain('RT20261122101500-00A1')
    expect(store.state.notice).toContain('保留原额度')
    expect(deps.list).toHaveBeenCalled()
    expect(store.state.busyReturnNo).toBeNull()
  })

  it('驳回必须填原因：空原因不发请求', async () => {
    const deps = buildDeps()
    const store = createAfterSaleAdminStore(deps)

    const result = await store.reject('RT20261122101500-00A1', '   ')

    expect(result).toBeNull()
    expect(deps.reject).not.toHaveBeenCalled()
    expect(store.state.error).toContain('驳回原因必填')
  })

  it('驳回成功：提示里写明释放了多少件', async () => {
    const deps = buildDeps()
    const store = createAfterSaleAdminStore(deps)

    await store.reject('RT20261122101500-00A1', '超过期限')

    expect(deps.reject).toHaveBeenCalledWith('RT20261122101500-00A1', '超过期限')
    expect(store.state.notice).toContain('已驳回')
    expect(store.state.notice).toContain('释放 1 件')
    expect(store.state.detail?.decision?.reason).toBe('超过期限')
  })

  it('409（已经决策过）：重新读详情与列表，把真实决策摆出来', async () => {
    const decided = returnOf({
      status: 'REJECTED',
      statusLabel: '已驳回',
      decision: {
        decision: 'REJECTED',
        label: '已驳回',
        reason: '第一次驳回',
        decidedBy: 'music-admin-1',
        decidedAt: '2026-11-22T11:00:00',
      },
    })

    const deps = buildDeps({
      reject: vi.fn().mockRejectedValue(
        new ApiError('conflict', { status: 409, code: 'ALREADY_REJECTED' }),
      ),
      detail: vi.fn().mockResolvedValue(decided),
    })
    const store = createAfterSaleAdminStore(deps)

    const result = await store.reject('RT20261122101500-00A1', '再来一次')

    expect(result).toBeNull()
    expect(store.state.error).toContain('已经是「已驳回」')
    expect(deps.detail).toHaveBeenCalledWith('RT20261122101500-00A1')
    expect(store.state.detail?.decision?.reason).toBe('第一次驳回')
    expect(store.state.notice).toBeNull()
  })

  it('处理中不接受第二个决策（防连点造成两个请求）', async () => {
    const holder: { resolve?: (value: AfterSaleReturnVO) => void } = {}
    const approve = vi.fn().mockImplementation(
      () => new Promise<AfterSaleReturnVO>((resolve) => {
        holder.resolve = resolve
      }),
    )
    const deps = buildDeps({ approve })
    const store = createAfterSaleAdminStore(deps)

    const first = store.approve('RT20261122101500-00A1')
    await vi.waitFor(() => expect(holder.resolve).toBeTypeOf('function'))

    const second = await store.reject('RT20261122101500-00A1', '同时点驳回')
    expect(second).toBeNull()
    expect(deps.reject).not.toHaveBeenCalled()

    holder.resolve!(returnOf({ status: 'APPROVED', statusLabel: '已批准' }))
    await first
    expect(store.state.busyReturnNo).toBeNull()
  })

  it('翻页夹紧在 1..totalPages', async () => {
    const deps = buildDeps({
      list: vi.fn().mockResolvedValue({ items: [], page: 1, size: 20, total: 45 }),
    })
    const store = createAfterSaleAdminStore(deps)

    await store.loadList()
    expect(store.totalPages.value).toBe(3)

    await store.goPage(99)
    expect(store.state.page).toBe(3)

    await store.goPage(-5)
    expect(store.state.page).toBe(1)
  })

  it('收货成功：数量与备注原样传给后端，提示里写清"合格回补 / 损坏只记录"', async () => {
    const deps = buildDeps()
    const store = createAfterSaleAdminStore(deps)

    const updated = await store.receive('RT20261122101500-00A1', {
      receivedQty: 1,
      qualifiedQty: 1,
      damagedQty: 0,
      note: '  外箱完好  ',
      clientRequestId: 'req-1',
    })

    expect(deps.receive).toHaveBeenCalledWith('RT20261122101500-00A1', {
      receivedQty: 1,
      qualifiedQty: 1,
      damagedQty: 0,
      note: '外箱完好',
      clientRequestId: 'req-1',
    })
    expect(updated?.progress?.code).toBe('PARTIAL')
    expect(store.state.notice).toContain('合格 1 件已回补可售库存')
    expect(store.state.notice).toContain('部分收货（1/2）')
    expect(deps.list).toHaveBeenCalled()
    expect(store.state.busyReturnNo).toBeNull()
  })

  it('整批损坏：提示写明没有增加可售库存', async () => {
    const deps = buildDeps({
      receive: vi.fn().mockResolvedValue(
        returnOf({
          status: 'APPROVED',
          statusLabel: '已批准',
          progress: { code: 'COMPLETE', label: '已收满', approvedQty: 2, receivedQty: 2, remainingQty: 0 },
          receipts: [],
        }),
      ),
    })
    const store = createAfterSaleAdminStore(deps)

    await store.receive('RT20261122101500-00A1', {
      receivedQty: 1,
      qualifiedQty: 0,
      damagedQty: 1,
      clientRequestId: 'req-2',
    })

    expect(store.state.notice).toContain('1 件全部损坏，未增加可售库存')
    expect(store.state.notice).toContain('已收满（2/2）')
  })

  it('数量不自洽（合格 + 损坏 ≠ 实收）时不发请求', async () => {
    const deps = buildDeps()
    const store = createAfterSaleAdminStore(deps)

    const result = await store.receive('RT20261122101500-00A1', {
      receivedQty: 2,
      qualifiedQty: 1,
      damagedQty: 0,
      clientRequestId: 'req-3',
    })

    expect(result).toBeNull()
    expect(deps.receive).not.toHaveBeenCalled()
    expect(store.state.error).toContain('必须等于实收')
  })

  it('实收 0 件时不发请求', async () => {
    const deps = buildDeps()
    const store = createAfterSaleAdminStore(deps)

    await store.receive('RT20261122101500-00A1', {
      receivedQty: 0,
      qualifiedQty: 0,
      damagedQty: 0,
      clientRequestId: 'req-4',
    })

    expect(deps.receive).not.toHaveBeenCalled()
    expect(store.state.error).toContain('必须大于 0')
  })

  it('409 超收：先重读详情与列表，再写错误（刷新不会擦掉文案）', async () => {
    const stale = returnOf({
      status: 'APPROVED',
      statusLabel: '已批准',
      progress: { code: 'COMPLETE', label: '已收满', approvedQty: 2, receivedQty: 2, remainingQty: 0 },
    })

    const deps = buildDeps({
      receive: vi.fn().mockRejectedValue(
        new ApiError('conflict', { status: 409, code: 'RECEIPT_OVER_APPROVED' }),
      ),
      detail: vi.fn().mockResolvedValue(stale),
    })
    const store = createAfterSaleAdminStore(deps)

    const result = await store.receive('RT20261122101500-00A1', {
      receivedQty: 1,
      qualifiedQty: 1,
      damagedQty: 0,
      clientRequestId: 'req-5',
    })

    expect(result).toBeNull()
    expect(deps.detail).toHaveBeenCalledWith('RT20261122101500-00A1')
    expect(store.state.detail?.progress?.remainingQty).toBe(0)
    expect(store.state.error).toContain('超过批准数量')
    expect(store.state.notice).toBeNull()
  })

  it('收货与决策共用一个忙碌标记：处理中不接受第二个写动作', async () => {
    const holder: { resolve?: (value: AfterSaleReturnVO) => void } = {}
    const receive = vi.fn().mockImplementation(
      () => new Promise<AfterSaleReturnVO>((resolve) => {
        holder.resolve = resolve
      }),
    )
    const deps = buildDeps({ receive })
    const store = createAfterSaleAdminStore(deps)

    const first = store.receive('RT20261122101500-00A1', {
      receivedQty: 1,
      qualifiedQty: 1,
      damagedQty: 0,
      clientRequestId: 'req-6',
    })
    await vi.waitFor(() => expect(holder.resolve).toBeTypeOf('function'))

    const second = await store.approve('RT20261122101500-00A1')
    expect(second).toBeNull()
    expect(deps.approve).not.toHaveBeenCalled()

    holder.resolve!(returnOf({ status: 'APPROVED', statusLabel: '已批准' }))
    await first
    expect(store.state.busyReturnNo).toBeNull()
  })
})
