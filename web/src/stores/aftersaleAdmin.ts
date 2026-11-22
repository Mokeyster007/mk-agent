/**
 * 客服售后状态（B1：按状态看申请、批准、驳回）。
 *
 * 四条边界：
 * 1. **权限只在服务端**：页面隐藏按钮只是不让人白点一次，
 *    真正拦住越权的是后端 `requireAdmin()`（非 ADMIN 一律 403）。
 * 2. **重复/相反决策是 409，不是成功**：`ALREADY_APPROVED` / `ALREADY_REJECTED` /
 *    `DECISION_CONFLICT` 都表示"这一单已经定了"。此时界面不重试、不改状态，
 *    而是重新读一次详情，把**真实决策**摆出来——用户需要看到"其实已经批过了"，
 *    而不是一句"操作失败"。
 * 3. **驳回原因必填**：这是买家唯一能拿到的解释，前端先挡一次，
 *    后端仍会独立校验（前端校验不算数）。
 * 4. **不显示退款/入库**：B1 只做批准与驳回，页面写"已退款"就是骗人。
 */
import { computed, reactive } from 'vue'
import { ApiError } from '@/api/errors'
import {
  approveAdminReturn,
  getAdminReturn,
  listAdminReturns,
  rejectAdminReturn,
  type AfterSaleReturnVO,
} from '@/api/aftersale'
import { describeAfterSaleError } from '@/stores/aftersale'

/** 决策动作（用于"哪一行正在处理中"）。 */
export type DecisionAction = 'approve' | 'reject'

interface AfterSaleAdminState {
  returns: AfterSaleReturnVO[]
  detail: AfterSaleReturnVO | null
  status: string | null
  page: number
  size: number
  total: number
  loadingList: boolean
  loadingDetail: boolean
  /** 正在处理中的退货单号（同时只允许一个，避免连点造成两个决策） */
  busyReturnNo: string | null
  busyAction: DecisionAction | null
  error: string | null
  notice: string | null
}

export interface AfterSaleAdminDeps {
  list: typeof listAdminReturns
  detail: typeof getAdminReturn
  approve: typeof approveAdminReturn
  reject: typeof rejectAdminReturn
}

export function createAfterSaleAdminStore(deps: AfterSaleAdminDeps) {
  const state = reactive<AfterSaleAdminState>({
    returns: [],
    detail: null,
    status: null,
    page: 1,
    size: 20,
    total: 0,
    loadingList: false,
    loadingDetail: false,
    busyReturnNo: null,
    busyAction: null,
    error: null,
    notice: null,
  })

  const busy = computed(() => state.busyReturnNo !== null)

  const totalPages = computed(() => Math.max(1, Math.ceil(state.total / state.size)))

  function clearFeedback(): void {
    state.error = null
    state.notice = null
  }

  /**
   * 读列表。
   *
   * @param status 状态筛选；传 `undefined` 表示沿用当前筛选，传 `null` 表示全部
   */
  async function loadList(status?: string | null): Promise<void> {
    if (status !== undefined) {
      state.status = status
      state.page = 1
    }

    state.loadingList = true
    clearFeedback()
    try {
      const page = await deps.list({
        status: state.status,
        page: state.page,
        size: state.size,
      })
      state.returns = page.items
      state.total = page.total
    } catch (error) {
      state.error = describeAfterSaleError(error)
    } finally {
      state.loadingList = false
    }
  }

  async function goPage(page: number): Promise<void> {
    state.page = Math.max(1, Math.min(totalPages.value, page))
    await loadList()
  }

  /** 打开详情：客服能看全部，不存在才是 404。 */
  async function openDetail(returnNo: string): Promise<void> {
    state.loadingDetail = true
    clearFeedback()
    try {
      state.detail = await deps.detail(returnNo)
    } catch (error) {
      state.error = describeAfterSaleError(error)
    } finally {
      state.loadingDetail = false
    }
  }

  function closeDetail(): void {
    state.detail = null
  }

  /**
   * 决策（批准 / 驳回）共用一条路径。
   *
   * @return 成功返回决策后的详情；失败返回 null
   */
  async function decide(
    returnNo: string,
    action: DecisionAction,
    reason: string,
  ): Promise<AfterSaleReturnVO | null> {
    if (state.busyReturnNo) {
      return null
    }

    const trimmed = reason.trim()
    if (action === 'reject' && !trimmed) {
      state.error = '驳回原因必填：买家需要知道为什么。'
      return null
    }

    state.busyReturnNo = returnNo
    state.busyAction = action
    clearFeedback()

    try {
      const updated = action === 'approve'
        ? await deps.approve(returnNo)
        : await deps.reject(returnNo, trimmed)

      state.detail = updated
      /*
       * 先重载列表再写提示：`loadList` 会清空上一轮的提示，
       * 顺序反了会把刚写好的"已批准/已驳回"立刻擦掉。
       */
      await loadList()
      state.notice = action === 'approve'
        ? `${updated.returnNo} 已批准：保留原额度，实物仍在买家手里（仓库验收不在本轮）。`
        : `${updated.returnNo} 已驳回：已释放 ${updated.quantity} 件可退额度。`
      return updated
    } catch (error) {
      const message = describeAfterSaleError(error)
      /*
       * 409 说明"这一单其实已经定了"。这时最有用的是把真实决策摆出来，
       * 而不是让用户对着"操作失败"猜发生了什么。
       *
       * 顺序：先刷新（刷新会清空提示），最后写错误——
       * 反过来的话刚写好的文案会被刷新擦掉，用户看到一片空白。
       */
      if (error instanceof ApiError && error.status === 409) {
        await openDetail(returnNo)
        await loadList()
      }
      state.error = message
      return null
    } finally {
      state.busyReturnNo = null
      state.busyAction = null
    }
  }

  function approve(returnNo: string): Promise<AfterSaleReturnVO | null> {
    return decide(returnNo, 'approve', '')
  }

  function reject(returnNo: string, reason: string): Promise<AfterSaleReturnVO | null> {
    return decide(returnNo, 'reject', reason)
  }

  function reset(): void {
    Object.assign(state, {
      returns: [],
      detail: null,
      status: null,
      page: 1,
      size: 20,
      total: 0,
      loadingList: false,
      loadingDetail: false,
      busyReturnNo: null,
      busyAction: null,
      error: null,
      notice: null,
    })
  }

  return {
    state,
    busy,
    totalPages,
    clearFeedback,
    loadList,
    goPage,
    openDetail,
    closeDetail,
    approve,
    reject,
    reset,
  }
}

export const afterSaleAdmin = createAfterSaleAdminStore({
  list: listAdminReturns,
  detail: getAdminReturn,
  approve: approveAdminReturn,
  reject: rejectAdminReturn,
})

export type AfterSaleAdminStore = ReturnType<typeof createAfterSaleAdminStore>
