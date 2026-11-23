/**
 * 买家售后状态（B1：普通表单 + 我的售后 + 客服决策结果）。
 *
 * 四条边界：
 * 1. **额度三个数由服务端给**：已购 / 已占用 / 剩余，前端不自己减。
 *    B1 起「已提交」与「已批准」都占额度，「已驳回」不占（服务端已释放）。
 * 2. **`clientRequestId` 的生命周期只在这里**：
 *    同一次提交重试必须沿用同一个值（后端据此重放，不会重复建单）；
 *    用户改了订单/数量/原因就是另一次提交，必须换新值
 *    （否则后端会按"同键不同参数"明确拒绝，用户会看到莫名其妙的 409）。
 * 3. **成功与失败分开**：`notice` 是"做成了什么"（含真实退货单号），
 *    `error` 是"为什么没做成"。混在一起时用户分不清"已提交"和"被拒"。
 * 4. **预览不是资格凭证**：`preview` 只用于展示预计退款与当前额度，
 *    提交时后端会重新校验；界面不能因为预览通过就宣称"可以退"。
 */
import { reactive } from 'vue'
import { ApiError, describeError } from '@/api/errors'
import type { Tone } from '@/utils/status'
import {
  getMyReturn,
  listAfterSaleOrders,
  listMyReturns,
  previewAfterSaleReturn,
  submitAfterSaleReturn,
  type AfterSalePreviewVO,
  type AfterSaleReturnVO,
  type AfterSaleSignedOrderVO,
  type SubmitAfterSaleInput,
} from '@/api/aftersale'

/**
 * 售后错误 → 用户可读文案。
 *
 * 先认 code（稳定契约），认不出来才退回通用文案；不拼接后端 message。
 */
export function describeAfterSaleError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return '出现了未预期的问题，请稍后重试。'
  }

  switch (error.code) {
    case 'SHOP_IDENTITY_NOT_CONFIGURED':
      return '售后身份未启用：服务端没有可信买家身份，读取与提交都被拒绝。本地演示请用 demo profile 启动（mkagent.shop.demo-identity-enabled=true）。'
    case 'SHOP_USER_NOT_PROVISIONED':
      return '当前身份未开通商城，无法使用售后。演示可切换为 music-user-1 / music-user-2。'
    case 'NOT_BUYER':
      return '当前身份不是买家，不能提交或查看退货申请。'
    case 'ORDER_NOT_FOUND':
      return '订单不存在，或者不属于当前身份。'
    case 'RETURN_NOT_FOUND':
      return '退货申请不存在，或者不属于当前身份。'
    case 'ORDER_NOT_SIGNED':
      return '订单还没有签收，不能申请退货。'
    case 'RETURN_WINDOW_CLOSED':
      return '已超过演示退货窗口（按签收时间计算），不能申请退货。'
    case 'RETURN_QUOTA_EXCEEDED':
      return '可退额度不足：这件商品剩余可退数量已被其它申请占用。刷新后可以查看最新额度。'
    case 'INVALID_QUANTITY':
      return '退货数量不合法：必须是正整数，且不超过剩余可退额度。'
    case 'INVALID_REASON':
      return '退货原因必填（最多 200 字）。'
    case 'INVALID_REQUEST_ID':
      return '提交标识不合法：请刷新页面后重新提交。'
    case 'IDEMPOTENCY_KEY_REUSED':
      return '这次提交的标识已经用于另一笔参数不同的申请，已被拒绝。请刷新后重新填写。'
    case 'SUBMIT_CONFLICT':
      return '同一笔提交还在处理中，请稍等片刻再刷新查看结果。'
    case 'RETURN_WRITE_CONFLICT':
      return '退货单写入冲突（不是重复提交），本次提交没有生效。请重新提交；若反复出现请联系管理员。'
    case 'REFUND_AMOUNT_OUT_OF_RANGE':
      return '退款金额超出可表示范围：请核对成交单价与实收数量（金额按整数分计算）。'
    case 'ALREADY_APPROVED':
      return '这一单已经是「已批准」，重复操作不会产生新的决策或额度变化。请以详情里的决策记录为准。'
    case 'ALREADY_REJECTED':
      return '这一单已经是「已驳回」，重复操作不会再次释放额度。请以详情里的决策记录为准。'
    case 'DECISION_CONFLICT':
      return '这一单已经做出过决策，不能再改成相反结果。请刷新后以详情里的决策记录为准。'
    case 'INVALID_REJECT_REASON':
      return '驳回原因必填（最多 200 字）。'
    case 'RETURN_QUOTA_INCONSISTENT':
      return '额度与退货单不一致，本次操作已回滚（没有释放任何额度）。请联系管理员核对数据。'
    case 'INVALID_STATUS':
      return '状态筛选值不合法，请重新选择。'
    case 'RETURN_NOT_APPROVED':
      return '只有「已批准」的退货单才能收货：待审批的还没同意退，已驳回的实物应退回买家。'
    case 'RECEIPT_OVER_APPROVED':
      return '本批实收超过批准数量（累计实收不能超过批准件数）。请刷新后按最新剩余数量填写。'
    case 'RECEIPT_QUANTITY_INVALID':
      return '数量不合法：实收必须大于 0，且合格 + 损坏必须等于实收。'
    case 'RECEIPT_REQUEST_CONFLICT':
      return '同一个收货标识被用于不同的验货参数，已被拒绝。请刷新后重新填写这一批。'
    case 'RECEIPT_WRITE_CONFLICT':
      return '收货记录写入冲突（不是重复提交），本次收货没有生效。请重新提交；若反复出现请联系管理员。'
    case 'INVALID_RECEIPT_NOTE':
      return '验货备注最多 200 字。'
    case 'REFUND_NOT_ALLOWED':
      return '只有「已批准」的退货单才能模拟退款：待审批的还没同意退，已驳回的本来就不退钱。'
    case 'REFUND_NOT_RECEIVED':
      return '还没收满批准数量，不能模拟退款：必须全部实收（含损坏件）之后才能结算。'
    case 'REFUND_ALREADY_EXISTS':
      return '这张退货单已经生成过一笔模拟退款，重复操作不会产生第二笔。请以详情里的退款记录为准。'
    case 'REFUND_AMOUNT_MISMATCH':
      return '退货单上的单价快照与订单行成交单价不一致，已拒绝退款（没有写入任何金额）。请联系管理员核对数据。'
    case 'INVENTORY_ROW_MISSING':
      return '库存行不存在，本次收货已回滚（库存没有变化）。请联系管理员核对商品库存。'
    case 'NOT_ADMIN':
      return '当前身份不是商城后台人员，不能处理售后。演示可切换为 music-admin-1。'
    case 'ORDER_STATUS_CONFLICT':
    case 'DATA_CONFLICT':
      return '数据状态已变化，请刷新后重试。'
    case 'MODEL_UNAVAILABLE':
      return '模型不可用；售后表单与查询不依赖模型，可以继续使用。'
    default:
      return describeError(error)
  }
}

/**
 * 售后状态 → 色调。
 *
 * 只做"枚举 → 颜色"的翻译，不改变任何后端语义：
 * 已提交（等客服）用 warn，已批准用 ok，已驳回用 neutral
 * （驳回不是"错误"，是"处理完了，结果是不退"）。
 * 未知状态一律中性色 + 原值，不猜。
 */
export function afterSaleStatusTone(status: string): Tone {
  switch (status) {
    case 'SUBMITTED':
      return 'wait'
    case 'APPROVED':
      return 'ok'
    case 'REJECTED':
      return 'neutral'
    default:
      return 'neutral'
  }
}

export interface AfterSaleForm {
  orderNo: string
  quantity: number
  reason: string
  /** 同一次提交的稳定标识；参数一变就换新 */
  clientRequestId: string
}

interface AfterSaleState {
  orders: AfterSaleSignedOrderVO[]
  returns: AfterSaleReturnVO[]
  detail: AfterSaleReturnVO | null
  preview: AfterSalePreviewVO | null
  loadingOrders: boolean
  loadingReturns: boolean
  submitting: boolean
  previewing: boolean
  error: string | null
  notice: string | null
}

/**
 * 提交标识。
 *
 * 格式必须满足后端 `[A-Za-z0-9_.:-]{8,64}`：
 * UUID 是 36 位十六进制 + 连字符，正好合规；没有 crypto 时退回时间戳 + 随机串。
 */
export function newClientRequestId(): string {
  const cryptoRef = globalThis.crypto
  if (cryptoRef && typeof cryptoRef.randomUUID === 'function') {
    return cryptoRef.randomUUID()
  }
  const random = Math.random().toString(36).slice(2, 10)
  return `req-${Date.now().toString(36)}-${random}`
}

export interface AfterSaleDeps {
  orders: typeof listAfterSaleOrders
  returns: typeof listMyReturns
  detail: typeof getMyReturn
  preview: typeof previewAfterSaleReturn
  submit: typeof submitAfterSaleReturn
  newRequestId: () => string
}

export function createAfterSaleStore(deps: AfterSaleDeps) {
  const state = reactive<AfterSaleState>({
    orders: [],
    returns: [],
    detail: null,
    preview: null,
    loadingOrders: false,
    loadingReturns: false,
    submitting: false,
    previewing: false,
    error: null,
    notice: null,
  })

  const form = reactive<AfterSaleForm>({
    orderNo: '',
    quantity: 1,
    reason: '',
    clientRequestId: deps.newRequestId(),
  })

  let detailSeq = 0

  /** 换一次提交身份。参数变了或开始新一轮填写时调用。 */
  function rekey(): void {
    form.clientRequestId = deps.newRequestId()
  }

  function clearFeedback(): void {
    state.error = null
    state.notice = null
  }

  /** 当前选中订单的额度（拿不到就是 null，界面不猜）。 */
  function quotaOf(orderNo: string): AfterSaleSignedOrderVO | null {
    return state.orders.find((item) => item.orderNo === orderNo) ?? null
  }

  async function loadOrders(): Promise<void> {
    state.loadingOrders = true
    try {
      state.orders = await deps.orders()
    } catch (error) {
      state.error = describeAfterSaleError(error)
    } finally {
      state.loadingOrders = false
    }
  }

  async function loadReturns(): Promise<void> {
    state.loadingReturns = true
    try {
      state.returns = await deps.returns()
    } catch (error) {
      state.error = describeAfterSaleError(error)
    } finally {
      state.loadingReturns = false
    }
  }

  /**
   * 打开退货申请详情。
   *
   * 用序号挡住迟到响应：先点 A 再点 B，A 的响应晚到不能覆盖 B。
   */
  async function openReturn(returnNo: string): Promise<void> {
    const current = ++detailSeq
    try {
      const detail = await deps.detail(returnNo)
      if (current === detailSeq) {
        state.detail = detail
      }
    } catch (error) {
      if (current === detailSeq) {
        state.error = describeAfterSaleError(error)
      }
    }
  }

  function closeDetail(): void {
    state.detail = null
  }

  /**
   * 选择要退的订单。
   *
   * 换单 = 换一次提交意图，所以换新提交标识，并清掉上一单的预览结果
   * （否则界面上会留着另一单的退款金额）。
   */
  function selectOrder(orderNo: string): void {
    form.orderNo = orderNo
    form.quantity = 1
    state.preview = null
    rekey()
  }

  function setQuantity(quantity: number): void {
    const order = quotaOf(form.orderNo)
    const max = order ? Math.max(1, order.quota.purchased) : 99
    form.quantity = Math.min(max, Math.max(1, Math.trunc(quantity) || 1))
    state.preview = null
    rekey()
  }

  function setReason(reason: string): void {
    form.reason = reason
    state.preview = null
    rekey()
  }

  /** 试算：只读，不占额度。 */
  async function previewForm(): Promise<AfterSalePreviewVO | null> {
    if (state.previewing) {
      return null
    }
    if (!form.orderNo) {
      state.error = '请先选择要退的订单。'
      return null
    }
    if (!form.reason.trim()) {
      state.error = '退货原因必填。'
      return null
    }

    state.previewing = true
    state.error = null
    try {
      const preview = await deps.preview({
        orderNo: form.orderNo,
        quantity: form.quantity,
        reason: form.reason,
      })
      state.preview = preview
      return preview
    } catch (error) {
      state.preview = null
      state.error = describeAfterSaleError(error)
      return null
    } finally {
      state.previewing = false
    }
  }

  /**
   * 提交退货申请。
   *
   * @return 成功返回退货单（含真实单号），失败返回 null
   */
  async function submitForm(): Promise<AfterSaleReturnVO | null> {
    if (state.submitting) {
      return null
    }
    if (!form.orderNo) {
      state.error = '请先选择要退的订单。'
      return null
    }
    if (!form.reason.trim()) {
      state.error = '退货原因必填。'
      return null
    }

    /*
     * 把参数与标识一起定格：
     * 请求发出后 form 仍可能被输入框改动，而这次请求用的是"点提交那一刻"的值。
     */
    const input: SubmitAfterSaleInput = {
      orderNo: form.orderNo,
      quantity: form.quantity,
      reason: form.reason.trim(),
      clientRequestId: form.clientRequestId,
    }

    state.submitting = true
    state.error = null
    state.notice = null

    try {
      const created = await deps.submit(input)
      state.notice = `退货申请已提交：${created.returnNo}（${created.quantity} 件，预计退款 ${created.refundText}）。当前状态「${created.statusLabel}」，等客服处理；结果会显示在「我的售后」里。`
      state.preview = null
      // 下一笔申请是新的提交身份
      rekey()
      await Promise.all([loadReturns(), loadOrders()])
      return created
    } catch (error) {
      state.error = describeAfterSaleError(error)
      /*
       * 失败时**不换** clientRequestId：
       * 网络抖动后用户点"重试"必须能命中后端已成功的那一次（重放），
       * 而不是再建一单。
       */
      return null
    } finally {
      state.submitting = false
    }
  }

  function reset(): void {
    Object.assign(state, {
      orders: [],
      returns: [],
      detail: null,
      preview: null,
      loadingOrders: false,
      loadingReturns: false,
      submitting: false,
      previewing: false,
      error: null,
      notice: null,
    })
    form.orderNo = ''
    form.quantity = 1
    form.reason = ''
    rekey()
  }

  return {
    state,
    form,
    quotaOf,
    clearFeedback,
    loadOrders,
    loadReturns,
    openReturn,
    closeDetail,
    selectOrder,
    setQuantity,
    setReason,
    previewForm,
    submitForm,
    reset,
  }
}

export const afterSale = createAfterSaleStore({
  orders: listAfterSaleOrders,
  returns: listMyReturns,
  detail: getMyReturn,
  preview: previewAfterSaleReturn,
  submit: submitAfterSaleReturn,
  newRequestId: newClientRequestId,
})

export type AfterSaleStore = ReturnType<typeof createAfterSaleStore>
