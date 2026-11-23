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
import { parseEvent } from './agentEvents'
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

/** 库存流水（退货入库那一条会带 receiptId）。 */
export interface AfterSaleMovementVO {
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

/**
 * 收货进度：**算出来的**，不是退货单上的第四个状态。
 *
 * 未批准时为 null；批准后 code 只会是 NONE / PARTIAL / COMPLETE。
 */
export interface AfterSaleProgressVO {
  code: string
  label: string
  approvedQty: number
  receivedQty: number
  remainingQty: number
}

/** 一批收货 / 验货记录；整批损坏时 `movement` 为 null（没有入库）。 */
export interface AfterSaleReceiptVO {
  receiptNo: string
  receivedQty: number
  qualifiedQty: number
  damagedQty: number
  note: string | null
  operatorId: string
  createdAt: string | null
  movement: AfterSaleMovementVO | null
}

/**
 * 模拟退款记录（B3）：一张退货单最多一笔。
 *
 * **它是"模拟"**：不接支付平台，没有真实资金流，`label` 恒为「模拟退款」。
 * 界面不能写成"已到账""已原路退回"——那是在骗人。
 *
 * 与 `AfterSaleReturnVO.refundCents`（提交时的**预计**退款）区别：
 * 那个是按下单时的申请数量估的，这个是**收货完成后**按累计实收结算的。
 */
export interface AfterSaleRefundVO {
  refundNo: string
  amountCents: number
  amountText: string
  operatorId: string
  createdAt: string | null
  /** 服务端给的口径文案（「模拟退款」），页面直接用它，不自己造词 */
  label: string
}

/**
 * 处理阶段（C1）：买家"看懂到哪一步"的唯一口径。
 *
 * 它是**派生值**（status + 收货进度 + 退款记录算出来），不是新的业务状态，
 * 所以不要拿它做写操作的开关——那要看 `status` 与 `progress.code`。
 *
 * code 取值：
 * - `WAITING_DECISION` 待客服处理
 * - `REJECTED` 已驳回（终止：不会再收货，也不会有退款）
 * - `WAITING_RECEIPT` 待收货
 * - `PARTIAL_RECEIPT` 部分收货
 * - `RECEIVED_NOT_REFUNDED` 已全部收货，尚未登记模拟退款
 * - `REFUNDED` 已登记模拟退款
 */
export interface AfterSaleStageVO {
  code: string
  label: string
  /** 只描述"接下来等什么"，不预测时间、不承诺到账 */
  hint: string
}

/**
 * 退货申请。列表接口 `events` / `receipts` 为 null（未加载），详情接口才有值。
 */
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
  /** 提交时按申请数量估的退款（不是结算金额） */
  refundCents: number
  refundText: string
  reason: string
  createdAt: string | null
  /** 处理到哪一步（服务端派生，页面与售后 Agent 共用同一套措辞） */
  stage: AfterSaleStageVO
  progress: AfterSaleProgressVO | null
  receipts: AfterSaleReceiptVO[] | null
  /** 已生成的模拟退款；没退过是 null */
  refund: AfterSaleRefundVO | null
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

/**
 * 收货 + 验货（B2）。
 *
 * 只有**已批准**的退货单能收；`qualifiedQty + damagedQty` 必须等于 `receivedQty`，
 * 累计实收不得超过批准数量。合格数量增加可售库存，损坏数量只留记录。
 *
 * `clientRequestId` 是**这一批收货动作**的标识：重试沿用同一个值（返回原记录、
 * 不重复加库存），用户改了数量或备注就是另一批，必须换新值。
 * 请求体里没有操作人字段：收货人由服务端身份决定。
 */
export function receiveAdminReturn(
  returnNo: string,
  input: {
    receivedQty: number
    qualifiedQty: number
    damagedQty: number
    note?: string | null
    clientRequestId: string
  },
  signal?: AbortSignal,
): Promise<AfterSaleReturnVO> {
  return requestJson<AfterSaleReturnVO>(
    `/api/shop/admin/aftersale/returns/${encodeURIComponent(returnNo)}/receipts`,
    {
      method: 'POST',
      body: {
        receivedQty: input.receivedQty,
        qualifiedQty: input.qualifiedQty,
        damagedQty: input.damagedQty,
        note: input.note ?? null,
        clientRequestId: input.clientRequestId,
      },
      signal,
    },
  )
}

/**
 * 模拟退款（B3）。
 *
 * 只有「已批准且全部实收」的退货单能退；金额由**服务端**按
 * 原订单成交单价 × 累计实收算出来，请求体里没有金额字段
 * （前端传不了，也改不了）。
 *
 * 一张退货单最多一笔：重复调用返回 409 `REFUND_ALREADY_EXISTS`，
 * 消息里带着已存在的那张退款单号。**不要**当成成功，去读详情核对即可。
 */
export function refundAdminReturn(
  returnNo: string,
  signal?: AbortSignal,
): Promise<AfterSaleReturnVO> {
  return requestJson<AfterSaleReturnVO>(
    `/api/shop/admin/aftersale/returns/${encodeURIComponent(returnNo)}/refund`,
    { method: 'POST', signal },
  )
}

export type AfterSaleChatBusinessStatus =
  | 'NEEDS_INFO'
  | 'READY_FOR_CONFIRMATION'
  | 'NOT_SUBMITTED'
  | 'SUBMIT_FAILED'
  | 'SUBMITTED'

export interface AfterSaleReturnDraft {
  orderNo: string
  productName: string
  sku?: string
  quantity: number
  reason: string
  refundText?: string
  refundCents?: number
}

export interface AfterSaleCandidateOrder {
  orderNo: string
  productName: string
  sku: string
  remainingQty: number
  returnable: boolean
}

export interface AfterSaleStructuredStatus {
  kind: 'aftersale_status'
  status: AfterSaleChatBusinessStatus
  statusLabel: string
  message?: string | null
  returnNo?: string | null
  approvalId?: string | null
  draft?: AfterSaleReturnDraft | null
  candidates?: AfterSaleCandidateOrder[] | null
}

export interface AfterSaleChatOptions {
  message: string
  /** 同一会话的续聊；不传则本轮没有上下文（追问会失忆） */
  conversationId?: string | null
  /** 从订单详情或签收订单卡带入的明确订单上下文 */
  orderNo?: string | null
  signal: AbortSignal
  onEvent: (event: AgentEvent) => void
}

export interface PrepareAfterSaleApprovalInput {
  orderNo: string
  quantity: number
  reason: string
  conversationId?: string | null
}

export interface PrepareAfterSaleApprovalResponse {
  runId: string
  approvalId: string
  payload?: import('./types').ApprovalRequiredPayload
}

export function prepareAfterSaleApproval(
  input: PrepareAfterSaleApprovalInput,
  signal?: AbortSignal,
): Promise<PrepareAfterSaleApprovalResponse> {
  return requestJson<PrepareAfterSaleApprovalResponse>(
    '/api/shop/aftersale/conversations/prepare-approval',
    {
      method: 'POST',
      body: input,
      signal,
    },
  )
}

/**
 * 售后会话接口。
 *
 * 守卫是买家身份，会话领域钉死为 AFTER_SALES。
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
  const { message, conversationId, orderNo, signal, onEvent } = options

  await streamEvents(
    '/api/ai/aftersale/chat',
    {
      method: 'GET',
      query: {
        message,
        conversationId: conversationId ?? undefined,
        orderNo: orderNo ?? undefined,
      },
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
