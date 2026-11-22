/**
 * 售后接口（A1）。
 *
 * 五条约定：
 * 1. 身份只走请求头（X-User-Id），不进 URL、不进请求体；
 * 2. 提交只传 `orderNo` + `quantity` + `reason` + `clientRequestId`：
 *    买家、单价、签收时间、已购数量全部由服务端读，前端传了也绑不进去；
 * 3. `clientRequestId` 是**同一次提交**的稳定标识：重试必须沿用同一个值，
 *    用户改了订单/数量/原因就是另一次提交，必须换新值。
 *    换不换由 store 决定，页面不自己造；
 * 4. 预览（POST /preview）不写库、不占额度，因此**不是**资格凭证——
 *    真正能不能退，以提交结果为准；
 * 5. 错误一律按 `ApiError.code` 分支，界面不解析后端中文文案。
 */
import { requestJson } from './client'
import { streamEvents } from './sse'
import { parseEvent } from './music'
import type { AgentEvent } from './types'

/** 额度三个数：已购 / 已占用 / 剩余。A1 里只有"已提交"的申请占额度。 */
export interface AfterSaleQuotaVO {
  purchased: number
  occupied: number
  remaining: number
}

/** 已签收订单（可申请退货的候选），带每单当前额度。 */
export interface AfterSaleSignedOrderVO {
  orderNo: string
  sku: string
  productName: string
  unitPriceCents: number
  unitPriceText: string
  quota: AfterSaleQuotaVO
  signedAt: string | null
  deadline: string | null
  /** 已超过 demo 退货窗口 */
  expired: boolean
  /** 现在能不能退（未过期且还有额度） */
  returnable: boolean
}

/** 售后事件（详情里的处理记录）。A1 只有"买家提交退货申请"一种。 */
export interface AfterSaleEventVO {
  type: string
  label: string
  actorId: string | null
  note: string | null
  createdAt: string | null
}

/** 退货申请。列表接口 `events` 为 null（未加载），详情接口为数组。 */
export interface AfterSaleReturnVO {
  returnNo: string
  orderNo: string
  status: string
  statusLabel: string
  source: string
  sourceLabel: string
  sku: string
  productName: string
  unitPriceCents: number
  unitPriceText: string
  quantity: number
  refundCents: number
  refundText: string
  reason: string
  createdAt: string | null
  events: AfterSaleEventVO[] | null
}

/** 预览结果；`eligible=false` 时 `code` 说明原因（后端稳定码）。 */
export interface AfterSalePreviewVO {
  orderNo: string
  sku: string
  productName: string
  unitPriceCents: number
  unitPriceText: string
  requestedQty: number
  refundCents: number
  refundText: string
  quota: AfterSaleQuotaVO
  eligible: boolean
  code: string | null
  message: string | null
}

export interface PreviewAfterSaleInput {
  orderNo: string
  quantity: number
  reason: string
}

export interface SubmitAfterSaleInput extends PreviewAfterSaleInput {
  /** 同一次提交重试必须保持不变 */
  clientRequestId: string
}

/** 我的已签收订单 + 每单额度。 */
export function listAfterSaleOrders(signal?: AbortSignal): Promise<AfterSaleSignedOrderVO[]> {
  return requestJson<AfterSaleSignedOrderVO[]>('/api/shop/aftersale/orders', { signal })
}

/**
 * 预览：退几件、退多少钱、为什么不能退。
 *
 * 用 POST 只是因为原因文本可能较长；它不写任何数据。
 */
export function previewAfterSaleReturn(
  input: PreviewAfterSaleInput,
  signal?: AbortSignal,
): Promise<AfterSalePreviewVO> {
  return requestJson<AfterSalePreviewVO>('/api/shop/aftersale/preview', {
    method: 'POST',
    body: { orderNo: input.orderNo, quantity: input.quantity, reason: input.reason },
    signal,
  })
}

/** 提交退货申请（普通表单）。成功返回 201 + 数据库里的真实退货单号。 */
export function submitAfterSaleReturn(
  input: SubmitAfterSaleInput,
  signal?: AbortSignal,
): Promise<AfterSaleReturnVO> {
  return requestJson<AfterSaleReturnVO>('/api/shop/aftersale/returns', {
    method: 'POST',
    body: {
      orderNo: input.orderNo,
      quantity: input.quantity,
      reason: input.reason,
      clientRequestId: input.clientRequestId,
    },
    signal,
  })
}

/** 我的售后列表（新的在前，不含事件流）。 */
export function listMyReturns(signal?: AbortSignal): Promise<AfterSaleReturnVO[]> {
  return requestJson<AfterSaleReturnVO[]>('/api/shop/aftersale/returns', { signal })
}

/** 售后详情（含事件流）。别人的退货单是 404，不是 403。 */
export function getMyReturn(returnNo: string, signal?: AbortSignal): Promise<AfterSaleReturnVO> {
  return requestJson<AfterSaleReturnVO>(
    `/api/shop/aftersale/returns/${encodeURIComponent(returnNo)}`,
    { signal },
  )
}

export interface AfterSaleChatOptions {
  message: string
  /** 同一会话的续聊；不传则本轮没有上下文（追问会失忆） */
  conversationId?: string | null
  signal: AbortSignal
  onEvent: (event: AgentEvent) => void
}

/**
 * 发起一轮售后对话。
 *
 * 入口只决定 agentType（AFTER_SALES），客户端传不进去。
 * 身份不足（401/403）在 Agent 启动之前就判定，因此失败时拿到的是普通 JSON 错误体。
 */
export async function runAfterSaleChat(options: AfterSaleChatOptions): Promise<void> {
  const { message, conversationId, signal, onEvent } = options

  await streamEvents(
    '/api/ai/aftersale/chat',
    { method: 'GET', query: { message, conversationId: conversationId ?? undefined }, signal },
    (frame) => {
      const event = parseEvent(frame.data)
      if (event) {
        onEvent(event)
      }
    },
  )
}
