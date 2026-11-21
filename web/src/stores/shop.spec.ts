import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/errors'
import {
  createShopStore,
  describeShopError,
  type ShopDeps,
} from './shop'
import type {
  ShopInventoryVO,
  ShopOrderVO,
  ShopProductVO,
  ShopSessionVO,
} from '@/api/shop'

const SESSION: ShopSessionVO = {
  userId: 'music-user-1',
  role: 'BUYER',
  identityMode: 'DEMO',
  canOrder: true,
  canOperate: false,
}

const PRODUCT: ShopProductVO = {
  id: 2001,
  sku: 'SKU-KEYBOARD-K87',
  name: '机械键盘 K87',
  subtitle: '87 键',
  priceCents: 29900,
  priceText: '¥299.00',
  available: 10,
  purchasable: true,
}

function orderOf(overrides: Partial<ShopOrderVO> = {}): ShopOrderVO {
  return {
    orderNo: 'SO1',
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
    movements: null,
    ...overrides,
  }
}

const INVENTORY: ShopInventoryVO = {
  balances: [{ sku: 'SKU-KEYBOARD-K87', productId: 2001, productName: '机械键盘 K87', available: 8 }],
  movements: [],
}

function buildDeps(overrides: Partial<ShopDeps> = {}): ShopDeps {
  return {
    session: vi.fn(async () => SESSION),
    products: vi.fn(async () => [PRODUCT]),
    place: vi.fn(async () => orderOf()),
    myOrders: vi.fn(async () => [orderOf()]),
    myOrder: vi.fn(async () => orderOf({ movements: [] })),
    adminOrders: vi.fn(async () => ({ items: [orderOf()], page: 1, size: 20, total: 1 })),
    adminOrder: vi.fn(async () => orderOf({ movements: [] })),
    ship: vi.fn(async () => ({
      applied: true,
      message: '已发货：扣减 SKU-KEYBOARD-K87 2 件，并写入出库流水',
      order: orderOf({ status: 'SHIPPED', statusLabel: '已发货', canShip: false, canSign: true }),
    })),
    sign: vi.fn(async () => ({
      applied: true,
      message: '已签收：记录签收时间，库存不变',
      order: orderOf({ status: 'SIGNED', statusLabel: '已签收', canShip: false, canSign: false }),
    })),
    inventory: vi.fn(async () => INVENTORY),
    ...overrides,
  }
}

describe('商城 store', () => {
  it('下单成功：写入 detail、给出订单号提示，并刷新商品与订单', async () => {
    const deps = buildDeps()
    const store = createShopStore(deps)

    await store.loadSession()
    await store.loadProducts()
    await store.loadMyOrders()

    const placed = await store.place(2001, 2)

    expect(deps.place).toHaveBeenCalledWith({ productId: 2001, quantity: 2 })
    expect(placed?.orderNo).toBe('SO1')
    expect(store.state.detail?.orderNo).toBe('SO1')
    expect(store.state.notice).toContain('SO1')
    expect(store.state.notice).toContain('待发货')
    expect(store.state.error).toBeNull()
    expect(store.state.placing).toBe(false)
    // 下单后要重读库存与订单，避免页面显示"可售 10"却已经卖完
    expect(deps.products).toHaveBeenCalledTimes(2)
    expect(deps.myOrders).toHaveBeenCalledTimes(2)
  })

  it('库存不足：给出明确原因，不设置 detail，也不卡在提交中', async () => {
    const deps = buildDeps({
      place: vi.fn(async () => {
        throw new ApiError('conflict', { status: 409, code: 'INSUFFICIENT_STOCK' })
      }),
    })
    const store = createShopStore(deps)

    const placed = await store.place(2001, 1)

    expect(placed).toBeNull()
    expect(store.state.error).toContain('库存不足')
    expect(store.state.error).toContain('订单仍为待发货')
    expect(store.state.detail).toBeNull()
    expect(store.state.placing).toBe(false)
  })

  it('身份不可用时不去请求订单（避免先闪一个 403）', async () => {
    const myOrders = vi.fn(async () => [orderOf()])
    const deps = buildDeps({
      session: vi.fn(async () => ({
        ...SESSION,
        identityMode: 'DISABLED',
        canOrder: false,
      })),
      myOrders,
    })
    const store = createShopStore(deps)

    await store.loadSession()
    await store.loadMyOrders()

    expect(store.canOrder.value).toBe(false)
    expect(store.identityMode.value).toBe('DISABLED')
    expect(myOrders).not.toHaveBeenCalled()
  })

  it('重复发货：applied=false 时如实显示"未重复执行"，不弹错误', async () => {
    const deps = buildDeps({
      session: vi.fn(async () => ({ ...SESSION, role: 'ADMIN' as const, canOperate: true })),
      ship: vi.fn(async () => ({
        applied: false,
        message: '该订单已发货，本次未重复扣减库存，也未新增出库流水',
        order: orderOf({ status: 'SHIPPED', statusLabel: '已发货', canShip: false, canSign: true }),
      })),
    })
    const store = createShopStore(deps)

    await store.loadSession()
    await store.loadAdminOrders(null)
    await store.ship('SO1')

    expect(store.state.error).toBeNull()
    expect(store.state.notice).toContain('未重复执行')
    expect(store.state.notice).toContain('未重复扣减')
    expect(store.state.adminOrders[0].status).toBe('SHIPPED')
    expect(store.state.busyOrderNo).toBeNull()
  })

  it('发货失败：给出原因并重新拉取订单与库存（可能已被别人改动）', async () => {
    const adminOrders = vi.fn(async () => ({ items: [orderOf()], page: 1, size: 20, total: 1 }))
    const inventory = vi.fn(async () => INVENTORY)
    const deps = buildDeps({
      session: vi.fn(async () => ({ ...SESSION, role: 'ADMIN' as const, canOperate: true })),
      adminOrders,
      inventory,
      ship: vi.fn(async () => {
        throw new ApiError('conflict', { status: 409, code: 'INSUFFICIENT_STOCK' })
      }),
    })
    const store = createShopStore(deps)

    await store.loadSession()
    await store.loadAdminOrders(null)
    expect(adminOrders).toHaveBeenCalledTimes(1)

    await store.ship('SO1')

    expect(store.state.error).toContain('库存不足')
    expect(store.state.notice).toBeNull()
    expect(adminOrders).toHaveBeenCalledTimes(2)
    expect(inventory).toHaveBeenCalled()
    expect(store.state.busyOrderNo).toBeNull()
  })

  it('签收成功后替换列表中的那一单并刷新库存', async () => {
    const inventory = vi.fn(async () => INVENTORY)
    const deps = buildDeps({
      session: vi.fn(async () => ({ ...SESSION, role: 'ADMIN' as const, canOperate: true })),
      adminOrders: vi.fn(async () => ({
        items: [orderOf({ status: 'SHIPPED', statusLabel: '已发货', canShip: false, canSign: true })],
        page: 1,
        size: 20,
        total: 1,
      })),
      inventory,
    })
    const store = createShopStore(deps)

    await store.loadSession()
    await store.loadAdminOrders(null)
    await store.sign('SO1')

    expect(store.state.adminOrders[0].status).toBe('SIGNED')
    expect(store.state.notice).toContain('已签收')
    expect(inventory).toHaveBeenCalled()
  })

  it('切换状态筛选时回到第 1 页并按状态请求', async () => {
    const adminOrders = vi.fn(async () => ({ items: [], page: 1, size: 20, total: 0 }))
    const store = createShopStore(buildDeps({ adminOrders }))

    await store.loadAdminOrders('SHIPPED')

    expect(adminOrders).toHaveBeenLastCalledWith({ status: 'SHIPPED', page: 1, size: 20 })
    expect(store.state.adminStatus).toBe('SHIPPED')
    expect(store.state.adminPage).toBe(1)
  })

  it('reset 清空全部状态（切身份时不能残留上一个人的订单）', async () => {
    const store = createShopStore(buildDeps())

    await store.loadSession()
    await store.loadProducts()
    await store.loadMyOrders()
    expect(store.state.myOrders).toHaveLength(1)

    store.reset()

    expect(store.state.session).toBeNull()
    expect(store.state.products).toEqual([])
    expect(store.state.myOrders).toEqual([])
    expect(store.state.detail).toBeNull()
    expect(store.canOrder.value).toBe(false)
  })
})

describe('商城错误文案', () => {
  it('按稳定原因码给出可执行提示', () => {
    expect(
      describeShopError(new ApiError('forbidden', { status: 403, code: 'SHOP_IDENTITY_NOT_CONFIGURED' })),
    ).toContain('demo profile')

    expect(
      describeShopError(new ApiError('forbidden', { status: 403, code: 'NOT_ADMIN' })),
    ).toContain('不是商城后台人员')

    expect(
      describeShopError(new ApiError('not_found', { status: 404, code: 'ORDER_NOT_FOUND' })),
    ).toContain('不属于当前身份')
  })

  it('不认识的 code 回退到通用文案，不泄露后端 message', () => {
    const message = describeShopError(
      new ApiError('forbidden', { status: 403, code: 'SOMETHING_NEW' }),
    )
    expect(message).toContain('权限')
    expect(message).not.toContain('SOMETHING_NEW')
  })

  it('非 ApiError 一律走通用兜底', () => {
    expect(describeShopError(new Error('boom'))).toContain('未预期')
  })
})
