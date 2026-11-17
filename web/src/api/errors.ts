/**
 * 统一错误模型。
 *
 * 后端错误契约（GlobalExceptionHandler）：
 *   HTTP <status> + { success: false, code?: string, message: string }
 *
 * 前端只按 status + code 分支，**不解析 message 文案**——
 * 文案会变，code 是稳定契约（后端注释里明确写了这一点）。
 *
 * 这里同时承担"脱敏"职责：给用户看的文案一律来自本文件，
 * 不把后端 message、堆栈、URL 直接抛到界面上。
 */

export type ErrorKind =
  | 'auth_missing'
  | 'forbidden'
  | 'not_found'
  | 'conflict'
  | 'rate_limited'
  | 'recovering'
  | 'unavailable'
  | 'server'
  | 'network'
  | 'aborted'
  | 'unknown'

export class ApiError extends Error {
  readonly status: number | null
  readonly code: string | null
  readonly kind: ErrorKind

  constructor(
    kind: ErrorKind,
    options: { status?: number | null; code?: string | null; message?: string } = {},
  ) {
    super(options.message ?? '')
    this.name = 'ApiError'
    this.kind = kind
    this.status = options.status ?? null
    this.code = options.code ?? null
  }
}

/** 后端稳定原因码 → 分类。顺序：先认 code，再退回 status。 */
const CODE_KIND: Record<string, ErrorKind> = {
  RECOVERY_IN_PROGRESS: 'recovering',
  AGENT_UNAVAILABLE: 'unavailable',
  RESUME_CONCURRENCY_LIMIT: 'rate_limited',
  RESUME_IN_PROGRESS: 'conflict',
  RESUME_CONTEXT_FAILED: 'conflict',
  RESUME_SUBMIT_FAILED: 'conflict',
  APPROVAL_NOT_FOUND: 'not_found',
  APPROVAL_EXPIRED: 'conflict',
  APPROVAL_NOT_PENDING: 'conflict',
  APPROVAL_NOT_DECIDED: 'conflict',
  APPROVAL_ALREADY_FINISHED: 'conflict',
  APPROVAL_FAILED: 'conflict',
  ALREADY_APPROVED: 'conflict',
  ALREADY_REJECTED: 'conflict',
  CHECKPOINT_CORRUPT: 'conflict',
  CHECKPOINT_UNSUPPORTED_AGENT: 'conflict',
  APPROVAL_RESUME_TIMEOUT: 'conflict',
}

function kindOf(status: number | null, code: string | null): ErrorKind {
  if (code && CODE_KIND[code]) {
    return CODE_KIND[code]
  }
  if (status === 401) return 'auth_missing'
  if (status === 403) return 'forbidden'
  if (status === 404) return 'not_found'
  if (status === 409) return 'conflict'
  if (status === 410) return 'conflict'
  if (status === 429) return 'rate_limited'
  if (status === 503) return 'unavailable'
  if (status !== null && status >= 500) return 'server'
  return 'unknown'
}

/** 用户可读、已脱敏的提示文案。 */
export function describeError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return '出现了未预期的问题，请稍后重试。'
  }

  switch (error.kind) {
    case 'auth_missing':
      return '缺少用户身份，请求未被接受。请检查开发身份设置。'
    case 'forbidden':
      return '当前身份没有该入口的访问权限。'
    case 'not_found':
      return '任务或审批不存在，或者不属于当前身份。'
    case 'conflict':
      return describeConflict(error.code)
    case 'rate_limited':
      return '请求过于频繁，已触发限流。请稍等片刻再试。'
    case 'recovering':
      return '服务正在恢复中断的任务，暂时不能开始或推进执行。请稍后重试。'
    case 'unavailable':
      return '后端服务暂时不可用，请稍后重试。'
    case 'server':
      return '服务端处理失败，请稍后重试。'
    case 'network':
      return '无法连接后端服务。请确认后端已启动、代理配置正确。'
    case 'aborted':
      return '请求已被前端中止。'
    default:
      return '出现了未预期的问题，请稍后重试。'
  }
}

function describeConflict(code: string | null): string {
  switch (code) {
    case 'APPROVAL_EXPIRED':
      return '该审批已过期，需要重新发起任务。'
    case 'ALREADY_APPROVED':
      return '该审批已经批准过，不能重复提交。'
    case 'ALREADY_REJECTED':
      return '该审批已经拒绝过，不能重复提交。'
    case 'APPROVAL_NOT_PENDING':
      return '审批已不在待确认状态，请刷新查看最新状态。'
    case 'APPROVAL_ALREADY_FINISHED':
      return '该审批对应的执行已经结束。'
    case 'APPROVAL_FAILED':
      return '该审批对应的执行已失败，不能继续恢复。'
    case 'RESUME_IN_PROGRESS':
      return '该任务正在被恢复，请不要重复操作。'
    case 'APPROVAL_NOT_DECIDED':
      return '还没有保存批准或拒绝的决定。'
    case 'CHECKPOINT_CORRUPT':
    case 'CHECKPOINT_UNSUPPORTED_AGENT':
    case 'RESUME_CONTEXT_FAILED':
    case 'RESUME_SUBMIT_FAILED':
      return '恢复所需的执行上下文不可用，无法继续这个任务。'
    default:
      return '当前状态不允许这个操作，请刷新后重试。'
  }
}

/**
 * 任务级原因码（agent_run.error_code）→ 提示。
 *
 * 与 HTTP 错误分开：任务已经结束，这个码解释"为什么结束"。
 */
export function describeRunErrorCode(code: string | null): string {
  switch (code) {
    case 'PROCESS_RESTART':
      return '执行被进程重启中断。此前已提交的操作可能仍然有效，建议核对结果。'
    case 'APPROVAL_EXPIRED':
      return '审批在有效期内没有被处理，任务已终止。'
    case 'USER_CANCELLED':
      return '任务被主动取消。'
    case 'APPROVAL_LIMIT_REACHED':
      return '审批次数达到上限，任务被终止。'
    case 'APPROVAL_PERSIST_FAILED':
      return '审批暂停写入失败，任务已终止。'
    default:
      return ''
  }
}

/** 从响应体里尽力取出 code / message，取不到就返回空。 */
export function parseErrorBody(body: unknown): { code: string | null; message: string | null } {
  if (!body || typeof body !== 'object') {
    return { code: null, message: null }
  }
  const record = body as Record<string, unknown>
  const code = typeof record.code === 'string' ? record.code : null
  const message = typeof record.message === 'string' ? record.message : null
  return { code, message }
}

export function toApiError(status: number, body: unknown): ApiError {
  const { code } = parseErrorBody(body)
  return new ApiError(kindOf(status, code), { status, code })
}

/** 网络层异常归一化。AbortError 单独成一类，不能当成"服务端取消"。 */
export function toNetworkError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }
  if (error instanceof DOMException && error.name === 'AbortError') {
    return new ApiError('aborted')
  }
  if (error instanceof Error && error.name === 'AbortError') {
    return new ApiError('aborted')
  }
  return new ApiError('network')
}
