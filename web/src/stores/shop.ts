/**
 * 商城状态（买家页 + 共享购物车 + 结算预览/确认 + 后台页共用）。
 */
import { computed, reactive } from 'vue'
import { ApiError, describeError } from '@/api/errors'
import {
  addCartItem,
  compareShopProducts,
  confirmCheckout,
  getAdminOrder,
  getInventory,
  getMyCart,
  getMyOrder,
  getShopSession,
  listAdminOrders,
  listMyOrders,
  listShopProducts,
  placeShopOrder,
  previewCheckout,
  removeCartItem,
  searchShopProducts,
  shipOrder,
  signOrder,
  updateCartItem,
  type ShopCartVO,
  type ShopCatalogSearchParams,
  type ShopCatalogSearchVO,
  type ShopComparisonVO,
  type ShopCheckoutPreviewVO,
  type ShopInventoryVO,
  type ShopOrderStatus,
  type ShopOrderVO,
  type ShopProductVO,
  type ShopSessionVO,
} from '@/api/shop'

const ADMIN_PAGE_SIZE = 20

interface ShopState {
  session: ShopSessionVO | null
  products: ShopProductVO[]
  searchResult: ShopCatalogSearchVO | null
  compareKeys: string[]
  comparison: ShopComparisonVO | null
  cart: ShopCartVO | null
  checkoutPreview: ShopCheckoutPreviewVO | null
  checkoutClientRequestId: string | null
  checkoutPriceChanged: boolean
  checkoutBusy: boolean
  myOrders: ShopOrderVO[]
  detail: ShopOrderVO | null
  adminOrders: ShopOrderVO[]
  adminStatus: ShopOrderStatus | null
  adminPage: number
  adminTotal: number
  inventory: ShopInventoryVO | null
  loadingProducts: boolean
  loadingCart: boolean
  loadingOrders: boolean
  loadingAdmin: boolean
  placing: boolean
  busyOrderNo: string | null
  error: string | null
  notice: string | null
}

function initialState(): ShopState {
  return {
    session: null,
    products: [],
    searchResult: null,
    compareKeys: [],
    comparison: null,
    cart: null,
    checkoutPreview: null,
    checkoutClientRequestId: null,
    checkoutPriceChanged: false,
    checkoutBusy: false,
    myOrders: [],
    detail: null,
    adminOrders: [],
    adminStatus: null,
    adminPage: 1,
    adminTotal: 0,
    inventory: null,
    loadingProducts: false,
    loadingCart: false,
    loadingOrders: false,
    loadingAdmin: false,
    placing: false,
    busyOrderNo: null,
    error: null,
    notice: null,
  }
}

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `chk-${crypto.randomUUID()}`
  }
  return `chk-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}

export function describeShopError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return '出现了未预期的问题，请稍后重试。'
  }

  switch (error.code) {
    case 'SHOP_IDENTITY_NOT_CONFIGURED':
      return '商城身份未启用：当前无法确认买家身份。本地演示请用 demo profile 启动（mkagent.shop.demo-identity-enabled=true）。'
    case 'SHOP_USER_NOT_PROVISIONED':
      return '当前身份未开通商城。演示可用 shop-user-1（买家）或 shop-admin-1（后台）。'
    case 'NOT_ADMIN':
      return '当前身份不是商城后台人员，不能发货或签收。'
    case 'PRICE_CHANGED':
      return '商品价格刚发生变动，旧报价未建单；请核对最新报价后再确认下单。'
    case 'PRODUCT_UNAVAILABLE':
      return '商品已下架或暂不可售，未创建订单。'
    case 'INSUFFICIENT_STOCK_AT_CHECKOUT':
      return '当前可售库存不足，未能创建订单；请调整数量或换选其他商品。'
    case 'INSUFFICIENT_STOCK':
      return '库存不足，发货没有执行：订单仍为待发货，库存与出库流水都没有变动。'
    case 'ORDER_NOT_SHIPPED':
      return '订单还没发货，不能签收。'
    case 'ORDER_NOT_FOUND':
      return '订单不存在，或者不属于当前身份。'
    case 'ORDER_STATUS_CONFLICT':
      return '订单状态已变化，请刷新后重试。'
    case 'PRODUCT_NOT_FOUND':
      return '商品不存在或已下架。'
    case 'INVALID_QUANTITY':
      return '购买数量不合法（1 ~ 99 件）。'
    case 'DATA_CONFLICT':
      return '操作与当前数据状态冲突，请刷新后重试。'
    case 'MODEL_UNAVAILABLE':
      return '模型不可用；普通商品筛选、购物车、结算下单与发货不依赖模型，可以继续使用。'
    default:
      return describeError(error)
  }
}

export interface ShopDeps {
  session: typeof getShopSession
  products: typeof listShopProducts
  search?: typeof searchShopProducts
  compare?: typeof compareShopProducts
  cart?: typeof getMyCart
  addCart?: typeof addCartItem
  updateCart?: typeof updateCartItem
  removeCart?: typeof removeCartItem
  previewCheckout?: typeof previewCheckout
  confirmCheckout?: typeof confirmCheckout
  place: typeof placeShopOrder
  myOrders: typeof listMyOrders
  myOrder: typeof getMyOrder
  adminOrders: typeof listAdminOrders
  adminOrder: typeof getAdminOrder
  ship: typeof shipOrder
  sign: typeof signOrder
  inventory: typeof getInventory
}

export function createShopStore(deps: ShopDeps) {
  const state = reactive<ShopState>(initialState())

  let adminSeq = 0
  let orderSeq = 0

  const canOrder = computed(() => state.session?.canOrder === true)
  const canOperate = computed(() => state.session?.canOperate === true)
  const identityMode = computed(() => state.session?.identityMode ?? 'UNKNOWN')

  function reset(): void {
    Object.assign(state, initialState())
  }

  async function loadSession(): Promise<void> {
    try {
      state.session = await deps.session()
    } catch (error) {
      state.session = null
      state.error = describeShopError(error)
    }
  }

  async function loadProducts(): Promise<void> {
    state.loadingProducts = true
    try {
      state.products = await deps.products()
    } catch (error) {
      state.error = describeShopError(error)
    } finally {
      state.loadingProducts = false
    }
  }

  async function searchProducts(params: ShopCatalogSearchParams): Promise<void> {
    if (!deps.search) return
    state.loadingProducts = true
    state.error = null
    try {
      const result = await deps.search(params)
      state.searchResult = result
      state.products = result.items
    } catch (error) {
      state.error = describeShopError(error)
    } finally {
      state.loadingProducts = false
    }
  }

  async function clearSearch(): Promise<void> {
    state.searchResult = null
    await loadProducts()
  }

  function toggleCompare(sku: string): void {
    const idx = state.compareKeys.indexOf(sku)
    if (idx >= 0) {
      state.compareKeys = state.compareKeys.filter((k) => k !== sku)
      if (state.compareKeys.length < 2) {
        state.comparison = null
      }
      return
    }
    if (state.compareKeys.length >= 3) {
      state.compareKeys = [...state.compareKeys.slice(1), sku]
    } else {
      state.compareKeys = [...state.compareKeys, sku]
    }
  }

  async function runCompare(keys: string[] = state.compareKeys): Promise<void> {
    if (!deps.compare || keys.length < 2) {
      return
    }
    state.error = null
    try {
      state.comparison = await deps.compare(keys)
    } catch (error) {
      state.error = describeShopError(error)
    }
  }

  function clearCompare(): void {
    state.compareKeys = []
    state.comparison = null
  }

  async function loadCart(): Promise<void> {
    if (!deps.cart || !state.session?.canOrder) {
      return
    }
    state.loadingCart = true
    try {
      state.cart = await deps.cart()
    } catch (error) {
      state.error = describeShopError(error)
    } finally {
      state.loadingCart = false
    }
  }

  async function addToCart(
    input: { productId?: number; sku?: string; quantity: number },
  ): Promise<boolean> {
    if (!deps.addCart) return false
    state.error = null
    state.notice = null
    try {
      state.cart = await deps.addCart(input)
      state.notice = '已加入共享购物车（加购不等于下单，也不锁定库存）。'
      return true
    } catch (error) {
      state.error = describeShopError(error)
      return false
    }
  }

  async function updateCartQty(productId: number, quantity: number): Promise<void> {
    if (!deps.updateCart) return
    state.error = null
    try {
      state.cart = await deps.updateCart(productId, quantity)
    } catch (error) {
      state.error = describeShopError(error)
    }
  }

  async function removeFromCart(productId: number): Promise<void> {
    if (!deps.removeCart) return
    state.error = null
    try {
      state.cart = await deps.removeCart(productId)
      if (state.checkoutPreview?.productId === productId) {
        closeCheckoutPreview()
      }
    } catch (error) {
      state.error = describeShopError(error)
    }
  }

  async function openCheckoutPreview(productId: number, quantity: number): Promise<void> {
    if (!deps.previewCheckout) return
    state.checkoutBusy = true
    state.error = null
    state.notice = null
    state.checkoutPriceChanged = false
    try {
      state.checkoutPreview = await deps.previewCheckout({ productId, quantity })
      state.checkoutClientRequestId = newRequestId()
    } catch (error) {
      state.error = describeShopError(error)
    } finally {
      state.checkoutBusy = false
    }
  }

  function closeCheckoutPreview(): void {
    state.checkoutPreview = null
    state.checkoutClientRequestId = null
    state.checkoutPriceChanged = false
  }

  async function confirmCurrentCheckout(removeFromCartAfter = true): Promise<ShopOrderVO | null> {
    const preview = state.checkoutPreview
    if (!deps.confirmCheckout || !preview || !preview.eligible || state.checkoutBusy) {
      return null
    }
    const clientRequestId = state.checkoutClientRequestId ?? newRequestId()
    state.checkoutClientRequestId = clientRequestId
    state.checkoutBusy = true
    state.error = null
    state.notice = null

    try {
      const result = await deps.confirmCheckout({
        productId: preview.productId,
        quantity: preview.quantity,
        expectedUnitPriceCents: preview.unitPriceCents,
        removeFromCart: removeFromCartAfter,
        clientRequestId,
      })
      state.detail = result.order
      state.notice = result.replayed
        ? `检测到重复确认，已返回原订单 ${result.order.orderNo}（未多建订单）。`
        : `下单成功：${result.order.orderNo}，${result.order.totalText}（演示付款，发货时才扣库存）。`
      closeCheckoutPreview()
      await Promise.all([loadProducts(), loadCart(), loadMyOrders()])
      return result.order
    } catch (error) {
      if (error instanceof ApiError && error.code === 'PRICE_CHANGED' && deps.previewCheckout) {
        // 价格变动：旧报价未建单，刷新最新报价并生成新请求号，要求用户重新确认
        try {
          state.checkoutPreview = await deps.previewCheckout({
            productId: preview.productId,
            quantity: preview.quantity,
          })
          state.checkoutClientRequestId = newRequestId()
          state.checkoutPriceChanged = true
        } catch {
          // 忽略预览刷新失败，保留主错误提示
        }
      }
      state.error = describeShopError(error)
      return null
    } finally {
      state.checkoutBusy = false
    }
  }

  async function place(productId: number, quantity: number): Promise<ShopOrderVO | null> {
    if (state.placing) {
      return null
    }

    state.placing = true
    state.error = null
    state.notice = null

    try {
      const order = await deps.place({ productId, quantity })
      state.detail = order
      state.notice = `下单成功：${order.orderNo}，${order.totalText}。当前为待发货，尚未扣减库存。`
      await Promise.all([loadProducts(), loadMyOrders()])
      return order
    } catch (error) {
      state.error = describeShopError(error)
      return null
    } finally {
      state.placing = false
    }
  }

  async function loadMyOrders(): Promise<void> {
    if (!state.session?.canOrder) {
      return
    }

    state.loadingOrders = true
    try {
      state.myOrders = await deps.myOrders()
    } catch (error) {
      state.error = describeShopError(error)
    } finally {
      state.loadingOrders = false
    }
  }

  async function openOrder(orderNo: string, admin = false): Promise<void> {
    const current = ++orderSeq
    try {
      const order = admin ? await deps.adminOrder(orderNo) : await deps.myOrder(orderNo)
      if (current === orderSeq) {
        state.detail = order
      }
    } catch (error) {
      if (current === orderSeq) {
        state.error = describeShopError(error)
      }
    }
  }

  function closeDetail(): void {
    state.detail = null
  }

  async function loadAdminOrders(status: ShopOrderStatus | null = state.adminStatus): Promise<void> {
    const current = ++adminSeq
    state.adminStatus = status
    state.adminPage = 1
    state.loadingAdmin = true
    state.error = null

    try {
      const page = await deps.adminOrders({
        status,
        page: 1,
        size: ADMIN_PAGE_SIZE,
      })
      if (current !== adminSeq) {
        return
      }
      state.adminOrders = page.items
      state.adminTotal = page.total
    } catch (error) {
      if (current === adminSeq) {
        state.error = describeShopError(error)
      }
    } finally {
      if (current === adminSeq) {
        state.loadingAdmin = false
      }
    }
  }

  async function goAdminPage(page: number): Promise<void> {
    const safe = Math.max(1, page)
    const current = ++adminSeq
    state.loadingAdmin = true

    try {
      const result = await deps.adminOrders({
        status: state.adminStatus,
        page: safe,
        size: ADMIN_PAGE_SIZE,
      })
      if (current !== adminSeq) {
        return
      }
      state.adminOrders = result.items
      state.adminPage = result.page
      state.adminTotal = result.total
    } catch (error) {
      if (current === adminSeq) {
        state.error = describeShopError(error)
      }
    } finally {
      if (current === adminSeq) {
        state.loadingAdmin = false
      }
    }
  }

  async function ship(orderNo: string): Promise<void> {
    await runAdminAction(orderNo, () => deps.ship(orderNo), '发货')
  }

  async function sign(orderNo: string): Promise<void> {
    await runAdminAction(orderNo, () => deps.sign(orderNo), '签收')
  }

  async function runAdminAction(
    orderNo: string,
    action: () => Promise<{ applied: boolean; message: string; order: ShopOrderVO }>,
    label: string,
  ): Promise<void> {
    if (state.busyOrderNo) {
      return
    }

    state.busyOrderNo = orderNo
    state.error = null
    state.notice = null

    try {
      const result = await action()
      replaceOrder(result.order)
      state.notice = result.applied ? result.message : `${label}未重复执行：${result.message}`
      await loadInventory()
    } catch (error) {
      const message = describeShopError(error)
      await Promise.all([loadAdminOrders(), loadInventory()])
      state.error = message
    } finally {
      state.busyOrderNo = null
    }
  }

  function replaceOrder(order: ShopOrderVO): void {
    const index = state.adminOrders.findIndex((item) => item.orderNo === order.orderNo)
    if (index >= 0) {
      state.adminOrders[index] = order
    }
    if (state.detail && state.detail.orderNo === order.orderNo) {
      state.detail = order
    }
  }

  async function loadInventory(): Promise<void> {
    try {
      state.inventory = await deps.inventory()
    } catch (error) {
      state.error = describeShopError(error)
    }
  }

  function clearFeedback(): void {
    state.error = null
    state.notice = null
  }

  return {
    state,
    canOrder,
    canOperate,
    identityMode,
    reset,
    loadSession,
    loadProducts,
    searchProducts,
    clearSearch,
    toggleCompare,
    runCompare,
    clearCompare,
    loadCart,
    addToCart,
    updateCartQty,
    removeFromCart,
    openCheckoutPreview,
    closeCheckoutPreview,
    confirmCurrentCheckout,
    place,
    loadMyOrders,
    openOrder,
    closeDetail,
    loadAdminOrders,
    goAdminPage,
    ship,
    sign,
    loadInventory,
    clearFeedback,
  }
}

export const shop = createShopStore({
  session: getShopSession,
  products: listShopProducts,
  search: searchShopProducts,
  compare: compareShopProducts,
  cart: getMyCart,
  addCart: addCartItem,
  updateCart: updateCartItem,
  removeCart: removeCartItem,
  previewCheckout,
  confirmCheckout,
  place: placeShopOrder,
  myOrders: listMyOrders,
  myOrder: getMyOrder,
  adminOrders: listAdminOrders,
  adminOrder: getAdminOrder,
  ship: shipOrder,
  sign: signOrder,
  inventory: getInventory,
})
