/**
 * 商城接口（A0 订单/库存 + 一期商品检索/比较/共享购物车/结算预览与确认 + 导购 Agent）。
 */
import { requestJson } from './client'
import { streamEvents } from './sse'
import { parseEvent } from './agentEvents'
import type { AgentEvent, ApprovalRequiredPayload, ConversationDetailVO, ConversationVO } from './types'

export type ShopOrderStatus = 'PENDING_SHIPMENT' | 'SHIPPED' | 'SIGNED'

export interface ShopProductVO {
  id: number
  sku: string
  name: string
  subtitle: string | null
  priceCents: number
  priceText: string
  available: number
  purchasable: boolean
  status?: string
  category?: string
  categoryLabel?: string
  connectivity?: string
  connectivityLabel?: string
  wireless?: boolean
  macCompatible?: boolean | null
  macCompatibleLabel?: string
  silentFeature?: boolean | null
  silentLabel?: string
  switchType?: string
  batteryInfo?: string
  weightGrams?: number | null
  specs?: Record<string, string>
}

export interface ShopCatalogSearchParams {
  keyword?: string | null
  category?: string | null
  maxPriceYuan?: number | null
  minPriceYuan?: number | null
  wirelessOnly?: boolean | null
  macCompatible?: boolean | null
  preferSilent?: boolean | null
  inStockOnly?: boolean | null
}

export interface ShopCatalogSearchVO {
  items: ShopProductVO[]
  appliedHardFilters: Record<string, unknown>
  softPreferences: string[]
  relaxSuggestions: string[]
}

export interface ShopComparisonDimensionVO {
  label: string
  values: string[]
}

export interface ShopComparisonVO {
  products: ShopProductVO[]
  dimensions: ShopComparisonDimensionVO[]
}

export interface ShopCartItemVO {
  id: number
  productId: number
  sku: string
  productName: string
  subtitle: string | null
  currentUnitPriceCents: number
  currentUnitPriceText: string
  quantity: number
  lineCents: number
  lineText: string
  available: number
  active: boolean
  checkoutReady: boolean
}

export interface ShopCartVO {
  items: ShopCartItemVO[]
  totalQuantity: number
  totalCents: number
  totalText: string
  singleSkuNotice: string
  stockNotice: string
}

export interface ShopCheckoutPreviewVO {
  productId: number
  sku: string
  productName: string
  subtitle: string | null
  quantity: number
  unitPriceCents: number
  unitPriceText: string
  totalCents: number
  totalText: string
  available: number
  active: boolean
  eligible: boolean
  code: string | null
  message: string | null
  stockNotice: string
  singleSkuNotice: string
}

export interface ShopCheckoutConfirmVO {
  replayed: boolean
  order: ShopOrderVO
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
  typeLabel: string
  orderId: number | null
  orderItemId: number | null
  receiptId: number | null
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
  movements: ShopMovementVO[] | null
}

export interface ShopSessionVO {
  userId: string | null
  role: 'BUYER' | 'ADMIN' | 'UNKNOWN'
  identityMode: string
  canOrder: boolean
  canOperate: boolean
}

export interface ShopActionResultVO {
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

export function searchShopProducts(
  params: ShopCatalogSearchParams,
  signal?: AbortSignal,
): Promise<ShopCatalogSearchVO> {
  return requestJson<ShopCatalogSearchVO>('/api/shop/products/search', {
    query: {
      keyword: params.keyword || undefined,
      category: params.category || undefined,
      maxPriceYuan: params.maxPriceYuan ?? undefined,
      minPriceYuan: params.minPriceYuan ?? undefined,
      wirelessOnly: params.wirelessOnly ? 'true' : undefined,
      macCompatible: params.macCompatible ? 'true' : undefined,
      preferSilent: params.preferSilent ? 'true' : undefined,
      inStockOnly: params.inStockOnly ? 'true' : undefined,
    },
    signal,
  })
}

export function compareShopProducts(
  keys: string[],
  signal?: AbortSignal,
): Promise<ShopComparisonVO> {
  return requestJson<ShopComparisonVO>('/api/shop/products/compare', {
    method: 'POST',
    body: { keys },
    signal,
  })
}

export function getMyCart(signal?: AbortSignal): Promise<ShopCartVO> {
  return requestJson<ShopCartVO>('/api/shop/cart', { signal })
}

export function addCartItem(
  input: { productId?: number; sku?: string; quantity: number },
  signal?: AbortSignal,
): Promise<ShopCartVO> {
  return requestJson<ShopCartVO>('/api/shop/cart/items', {
    method: 'POST',
    body: input,
    signal,
  })
}

export function updateCartItem(
  productId: number,
  quantity: number,
  signal?: AbortSignal,
): Promise<ShopCartVO> {
  return requestJson<ShopCartVO>(`/api/shop/cart/items/${encodeURIComponent(String(productId))}`, {
    method: 'PATCH',
    body: { quantity },
    signal,
  })
}

export function removeCartItem(productId: number, signal?: AbortSignal): Promise<ShopCartVO> {
  return requestJson<ShopCartVO>(`/api/shop/cart/items/${encodeURIComponent(String(productId))}`, {
    method: 'DELETE',
    signal,
  })
}

export function previewCheckout(
  input: { productId: number; quantity: number },
  signal?: AbortSignal,
): Promise<ShopCheckoutPreviewVO> {
  return requestJson<ShopCheckoutPreviewVO>('/api/shop/checkout/preview', {
    method: 'POST',
    body: input,
    signal,
  })
}

export function confirmCheckout(
  input: {
    productId: number
    quantity: number
    expectedUnitPriceCents: number
    removeFromCart?: boolean
    clientRequestId: string
  },
  signal?: AbortSignal,
): Promise<ShopCheckoutConfirmVO> {
  return requestJson<ShopCheckoutConfirmVO>('/api/shop/checkout/confirm', {
    method: 'POST',
    body: input,
    signal,
  })
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

/* ==================== 电商导购 Agent 接口 ==================== */

export interface ShopAssistantChatOptions {
  message: string
  conversationId?: string | null
  signal: AbortSignal
  onEvent: (event: AgentEvent) => void
}

export function createShopConversation(signal?: AbortSignal): Promise<{ conversationId: string }> {
  return requestJson<{ conversationId: string }>('/api/shop/assistant/conversations', {
    method: 'POST',
    signal,
  })
}

export function getShopConversation(
  conversationId: string,
  signal?: AbortSignal,
): Promise<ConversationDetailVO> {
  return requestJson<ConversationDetailVO>(
    `/api/shop/assistant/conversations/${encodeURIComponent(conversationId)}`,
    { signal },
  )
}

export function listShopConversations(signal?: AbortSignal): Promise<ConversationVO[]> {
  return requestJson<ConversationVO[]>('/api/shop/assistant/conversations', { signal })
}

export async function runShopAssistantChat(options: ShopAssistantChatOptions): Promise<void> {
  const { message, conversationId, signal, onEvent } = options
  await streamEvents(
    '/api/ai/shop/chat',
    {
      method: 'GET',
      query: { message, conversationId: conversationId ?? undefined },
      signal,
    },
    (frame) => {
      const event = parseEvent(frame.data)
      if (event) {
        onEvent(event)
      }
    },
  )
}

export interface PrepareCheckoutApprovalResponse {
  runId: string
  approvalId: string
  payload?: ApprovalRequiredPayload
}

export function prepareCheckoutApproval(
  input: {
    productId: number
    quantity: number
    removeFromCart?: boolean
    conversationId?: string | null
  },
  signal?: AbortSignal,
): Promise<PrepareCheckoutApprovalResponse> {
  return requestJson<PrepareCheckoutApprovalResponse>(
    '/api/shop/assistant/conversations/prepare-checkout-approval',
    {
      method: 'POST',
      body: input,
      signal,
    },
  )
}
