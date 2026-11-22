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
import type { AgentEvent, ConversationDetailVO, ConversationVO } from './types'

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

/** 售后事件（详情里的处理记录）。提交、批准、驳回各一条。 */
export interface AfterSaleEventVO {
  type: string
  label: string
  actorId: string | null
  note: string | null
  createdAt: string | null
}

/**
 * 客服决策（一单一决）。
 *
 * 买家在自己的详情里也看得到：状态变成「已批准 / 已驳回」之后，
 * 他需要知道"谁在什么时候、为什么"。
 */
export interface AfterSaleDecisionVO {
  /** APPROVED / REJECTED */
  decision: string
  label: string
  /** 驳回原因；批准时为 null */
  reason: string | null
  decidedBy: string
  decidedAt: string | null
}

/** 退货申请。列表接口 `events` / `decision` 为 null（未加载），详情接口才有值。 */
export interface AfterSaleReturnVO {
  returnNo: string
  orderNo: string
  buyerId: string
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
  decision: AfterSaleDecisionVO | null
  events: AfterSaleEventVO[] | null
}

/** 客服后台的分页响应（与订单后台同构）。 */
export interface AfterSalePageVO<T> {
  items: T[]
  page: number
  size: number
  total: number
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

// ---------------------------------------------------------------
// 客服（B1）
// ---------------------------------------------------------------

/**
 * 客服售后列表。
 *
 * **不做买家归属过滤**：单店 demo 里客服就是这家店的处理人。
 * 服务端用 ADMIN 角色守卫；前端隐藏按钮只是不让人白点一次。
 */
export function listAdminReturns(
  params: { status?: string | null; page?: number; size?: number },
  signal?: AbortSignal,
): Promise<AfterSalePageVO<AfterSaleReturnVO>> {
  return requestJson<AfterSalePageVO<AfterSaleReturnVO>>('/api/shop/admin/aftersale/returns', {
    query: {
      status: params.status ?? undefined,
      page: params.page ?? 1,
      size: params.size ?? 20,
    },
    signal,
  })
}

/** 客服视角的退货申请详情（含事件流与决策记录）。 */
export function getAdminReturn(
  returnNo: string,
  signal?: AbortSignal,
): Promise<AfterSaleReturnVO> {
  return requestJson<AfterSaleReturnVO>(
    `/api/shop/admin/aftersale/returns/${encodeURIComponent(returnNo)}`,
    { signal },
  )
}

/**
 * 批准：保留原额度。
 *
 * 重复批准返回 409 `ALREADY_APPROVED`；此时**不要**当成成功，
 * 去读详情核对原决策即可（详情里有决策人、时间与结果）。
 */
export function approveAdminReturn(
  returnNo: string,
  signal?: AbortSignal,
): Promise<AfterSaleReturnVO> {
  return requestJson<AfterSaleReturnVO>(
    `/api/shop/admin/aftersale/returns/${encodeURIComponent(returnNo)}/approve`,
    { method: 'POST', signal },
  )
}

/**
 * 驳回：释放本单申请数量（同一事务内）。
 *
 * `reason` 必填——"为什么驳回"是买家唯一能拿到的解释。
 * 请求体里没有操作人字段：决策人由服务端身份决定。
 */
export function rejectAdminReturn(
  returnNo: string,
  reason: string,
  signal?: AbortSignal,
): Promise<AfterSaleReturnVO> {
  return requestJson<AfterSaleReturnVO>(
    `/api/shop/admin/aftersale/returns/${encodeURIComponent(returnNo)}/reject`,
    { method: 'POST', body: { reason }, signal },
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
 * 售后会话接口。
 *
 * **不要用 `/api/ai/conversations`**：那个入口的守卫要求 `music_user`，
 * 合法的商城买家可能没有开通音乐服务，用它会 403 → 建不出会话 → 追问退化成单轮。
 * 这里是售后自己的入口：守卫是买家身份，会话领域钉死为 AFTER_SALES，
 * 与音乐会话互不可见（拿错领域的 id 一律 404）。
 */
export function createAfterSaleConversation(
  signal?: AbortSignal,
): Promise<{ conversationId: string }> {
  return requestJson<{ conversationId: string }>('/api/shop/aftersale/conversations', {
    method: 'POST',
    signal,
  })
}

/** 售后会话详情；不是本人或不是售后领域 → 404（同一句文案）。 */
export function getAfterSaleConversation(
  conversationId: string,
  signal?: AbortSignal,
): Promise<ConversationDetailVO> {
  return requestJson<ConversationDetailVO>(
    `/api/shop/aftersale/conversations/${encodeURIComponent(conversationId)}`,
    { signal },
  )
}

/** 我的售后会话列表（最近活动在前）。 */
export function listAfterSaleConversations(signal?: AbortSignal): Promise<ConversationVO[]> {
  return requestJson<ConversationVO[]>('/api/shop/aftersale/conversations', { signal })
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
