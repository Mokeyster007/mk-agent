/**
 * 商城接口（A0）。
 *
 * 三条约定：
 * 1. 身份仍然只走请求头（X-User-Id），不写进 URL、不写进请求体；
 * 2. 下单只提交 `productId` + `quantity`：单价与买家由服务端决定，
 *    前端就算多传字段也不会被绑定（后端 DTO 里没有这两个字段）；
 * 3. 错误一律交给 `ApiError` 的 code 分支，界面不解析后端中文文案。
 */
import { requestJson } from './client'

export type ShopOrderStatus = 'PENDING_SHIPMENT' | 'SHIPPED' | 'SIGNED'

export interface ShopProductVO {
  id: number
  sku: string
  name: string
  subtitle: string | null
  priceCents: number
  /** 服务端格式化好的金额（¥299.00），避免前端各处自己除 100 */
  priceText: string
  available: number
  /** 是否可下单：只取决于是否上架，与库存无关（下单不锁库存） */
  purchasable: boolean
}

export interface ShopOrderItemVO {
  productId: number
  sku: string
  productName: string
  unitPriceCents: number
  unitPriceText: string
  quantity: number
  lineCents: number
  lineText: string
}

export interface ShopMovementVO {
  id: number
  sku: string
  quantityDelta: number
  type: string
  orderId: number | null
  orderItemId: number | null
  operatorId: string | null
  createdAt: string | null
}

export interface ShopOrderVO {
  orderNo: string
  status: ShopOrderStatus | 'UNKNOWN'
  statusLabel: string
  totalCents: number
  totalText: string
  paymentMode: string
  buyerId: string
  createdAt: string | null
  shippedAt: string | null
  shippedBy: string | null
  signedAt: string | null
  signedBy: string | null
  canShip: boolean
  canSign: boolean
  item: ShopOrderItemVO | null
  /** 列表接口为 null（未加载），详情接口为数组 */
  movements: ShopMovementVO[] | null
}

export interface ShopSessionVO {
  userId: string | null
  role: 'BUYER' | 'ADMIN' | 'UNKNOWN'
  /** DEMO / TRUSTED / DISABLED */
  identityMode: string
  canOrder: boolean
  canOperate: boolean
}

export interface ShopActionResultVO {
  /** 本次请求是否真的改变了状态；false = 状态本来就已到位 */
  applied: boolean
  message: string
  order: ShopOrderVO
}

export interface ShopBalanceVO {
  sku: string
  productId: number
  productName: string | null
  available: number
}

export interface ShopInventoryVO {
  balances: ShopBalanceVO[]
  movements: ShopMovementVO[]
}

export interface ShopPageVO<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export function getShopSession(signal?: AbortSignal): Promise<ShopSessionVO> {
  return requestJson<ShopSessionVO>('/api/shop/session', { signal })
}

export function listShopProducts(signal?: AbortSignal): Promise<ShopProductVO[]> {
  return requestJson<ShopProductVO[]>('/api/shop/products', { signal })
}

export function placeShopOrder(
  input: { productId: number; quantity: number },
  signal?: AbortSignal,
): Promise<ShopOrderVO> {
  return requestJson<ShopOrderVO>('/api/shop/orders', {
    method: 'POST',
    body: { productId: input.productId, quantity: input.quantity },
    signal,
  })
}

export function listMyOrders(signal?: AbortSignal): Promise<ShopOrderVO[]> {
  return requestJson<ShopOrderVO[]>('/api/shop/orders', { signal })
}

export function getMyOrder(orderNo: string, signal?: AbortSignal): Promise<ShopOrderVO> {
  return requestJson<ShopOrderVO>(`/api/shop/orders/${encodeURIComponent(orderNo)}`, { signal })
}

export function listAdminOrders(
  params: { status?: ShopOrderStatus | null; page?: number; size?: number },
  signal?: AbortSignal,
): Promise<ShopPageVO<ShopOrderVO>> {
  return requestJson<ShopPageVO<ShopOrderVO>>('/api/shop/admin/orders', {
    query: {
      status: params.status ?? undefined,
      page: params.page ?? 1,
      size: params.size ?? 20,
    },
    signal,
  })
}

export function getAdminOrder(orderNo: string, signal?: AbortSignal): Promise<ShopOrderVO> {
  return requestJson<ShopOrderVO>(
    `/api/shop/admin/orders/${encodeURIComponent(orderNo)}`,
    { signal },
  )
}

export function shipOrder(orderNo: string, signal?: AbortSignal): Promise<ShopActionResultVO> {
  return requestJson<ShopActionResultVO>(
    `/api/shop/admin/orders/${encodeURIComponent(orderNo)}/ship`,
    { method: 'POST', signal },
  )
}

export function signOrder(orderNo: string, signal?: AbortSignal): Promise<ShopActionResultVO> {
  return requestJson<ShopActionResultVO>(
    `/api/shop/admin/orders/${encodeURIComponent(orderNo)}/sign`,
    { method: 'POST', signal },
  )
}

export function getInventory(signal?: AbortSignal): Promise<ShopInventoryVO> {
  return requestJson<ShopInventoryVO>('/api/shop/admin/inventory', { signal })
}
