/**
 * 商城状态（买家页 + 后台页共用）。
 *
 * 三条边界：
 * 1. **不在前端判断权限**：能不能下单、能不能发货，读的是 `/shop/session`
 *    给出的服务端结论；这里只负责把它显示出来。
 * 2. **成功与失败必须分开**：`notice` 是"做成了什么"，
 *    `error` 是"为什么没做成"。混在一起时，用户分不清"下单成功"和"下单被拒"。
 * 3. **重复操作不是错误**：后端对重复发货/签收返回 200 + applied=false，
 *    这里如实显示"未重复扣减"，不弹红条。
 */
import { computed, reactive } from 'vue'
import { ApiError, describeError } from '@/api/errors'
import {
  getAdminOrder,
  getInventory,
  getMyOrder,
  getShopSession,
  listAdminOrders,
  listMyOrders,
  listShopProducts,
  placeShopOrder,
  shipOrder,
  signOrder,
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
  myOrders: ShopOrderVO[]
  detail: ShopOrderVO | null
  adminOrders: ShopOrderVO[]
  adminStatus: ShopOrderStatus | null
  adminPage: number
  adminTotal: number
  inventory: ShopInventoryVO | null
  loadingProducts: boolean
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
    myOrders: [],
    detail: null,
    adminOrders: [],
    adminStatus: null,
    adminPage: 1,
    adminTotal: 0,
    inventory: null,
    loadingProducts: false,
    loadingOrders: false,
    loadingAdmin: false,
    placing: false,
    busyOrderNo: null,
    error: null,
    notice: null,
  }
}

/**
 * 商城错误 → 用户可读文案。
 *
 * 先认 code（稳定契约），认不出来才退回通用文案。
 * 这里**不拼接后端 message**：文案会变，而且一旦某天后端把参数回显进 message，
 * 界面就会跟着泄露内部信息。
 */
export function describeShopError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return '出现了未预期的问题，请稍后重试。'
  }

  switch (error.code) {
    case 'SHOP_IDENTITY_NOT_CONFIGURED':
      return '商城身份未启用：当前无法确认买家身份。本地演示请用 demo profile 启动（mkagent.shop.demo-identity-enabled=true）。'
    case 'SHOP_USER_NOT_PROVISIONED':
      return '当前身份未开通商城。演示可用 music-user-1 / music-user-2（买家）或 music-admin-1（后台）。'
    case 'NOT_ADMIN':
      return '当前身份不是商城后台人员，不能发货或签收。'
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
      return '模型不可用；商城下单与发货不依赖模型，可以继续使用。'
    default:
      return describeError(error)
  }
}

export interface ShopDeps {
  session: typeof getShopSession
  products: typeof listShopProducts
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

  /** 只保留最后一次列表请求的结果（切状态时旧请求迟到不覆盖新结果） */
  let adminSeq = 0
  let orderSeq = 0

  const canOrder = computed(() => state.session?.canOrder === true)
  const canOperate = computed(() => state.session?.canOperate === true)
  const identityMode = computed(() => state.session?.identityMode ?? 'UNKNOWN')

  /** 切身份或离开页面时清空，避免上一个人的订单短暂留在界面上 */
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

  /**
   * 下单。
   *
   * @return 成功返回订单，失败返回 null（错误已写进 state.error）
   */
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
      // 下单后刷新两处：库存数量（可能被别人买走）与我的订单
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
      // 身份不可用时不去请求：否则页面会先闪一个 403 再显示"未启用"
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

  /**
   * 发货 / 签收共用的收尾逻辑。
   *
   * <p>三种结果都要如实反映：
   * 真的做了（applied=true）、本来就做过（applied=false）、失败（抛错）。
   */
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
      /*
       * 先记下失败原因，再刷新列表与库存，最后把原因写回。
       * 顺序不能反：刷新方法自己会清 error（它们是"重新读取"，不是"操作结果"），
       * 先写 error 再刷新会把刚发生的失败悄悄抹掉。
       */
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
  place: placeShopOrder,
  myOrders: listMyOrders,
  myOrder: getMyOrder,
  adminOrders: listAdminOrders,
  adminOrder: getAdminOrder,
  ship: shipOrder,
  sign: signOrder,
  inventory: getInventory,
})
