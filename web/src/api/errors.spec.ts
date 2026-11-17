import { describe, expect, it } from 'vitest'
import { ApiError, describeError, describeRunErrorCode, toApiError, toNetworkError } from './errors'

/**
 * 错误映射测试。
 *
 * 目标不是"文案好看"，而是保证：
 * - 503 + RECOVERY_IN_PROGRESS 被识别为"恢复中"，不是"任务失败"；
 * - 401/403/404/409/429 各有独立分类，不被统一成"出错了"；
 * - 网络异常与主动中止不混为一谈（中止不能冒充服务端取消）。
 */
describe('错误分类', () => {
  it('503 + RECOVERY_IN_PROGRESS → 恢复中，文案明确不可开始执行', () => {
    const error = toApiError(503, { success: false, code: 'RECOVERY_IN_PROGRESS' })

    expect(error.kind).toBe('recovering')
    expect(describeError(error)).toContain('恢复')
    expect(describeError(error)).toContain('暂时不能')
  })

  it('503 但没有原因码 → 服务不可用，不当成恢复中', () => {
    const error = toApiError(503, { success: false, message: 'x' })
    expect(error.kind).toBe('unavailable')
  })

  it('401 缺少身份', () => {
    expect(toApiError(401, { success: false }).kind).toBe('auth_missing')
  })

  it('403 无权限', () => {
    expect(toApiError(403, { success: false }).kind).toBe('forbidden')
  })

  it('404 不存在或不属于自己', () => {
    const error = toApiError(404, { success: false })
    expect(error.kind).toBe('not_found')
    expect(describeError(error)).toContain('不存在')
  })

  it('409 + ALREADY_APPROVED → 冲突并给出具体原因', () => {
    const error = toApiError(409, { success: false, code: 'ALREADY_APPROVED' })
    expect(error.kind).toBe('conflict')
    expect(describeError(error)).toContain('已经批准')
  })

  it('429 → 限流', () => {
    expect(toApiError(429, { success: false }).kind).toBe('rate_limited')
  })

  it('5xx → 服务端失败', () => {
    expect(toApiError(500, { success: false }).kind).toBe('server')
    expect(toApiError(502, null).kind).toBe('server')
  })

  it('AbortError → aborted，不当作网络故障', () => {
    const error = toNetworkError(new DOMException('aborted', 'AbortError'))
    expect(error.kind).toBe('aborted')
    expect(describeError(error)).toContain('中止')
  })

  it('普通异常 → network', () => {
    expect(toNetworkError(new TypeError('Failed to fetch')).kind).toBe('network')
  })

  it('已经是 ApiError 时原样返回，不二次包装', () => {
    const original = new ApiError('not_found', { status: 404 })
    expect(toNetworkError(original)).toBe(original)
  })

  it('未知错误不泄露原始信息', () => {
    const text = describeError(new Error('java.lang.NullPointerException at com.example'))
    expect(text).not.toContain('NullPointerException')
    expect(text).not.toContain('com.example')
  })
})

describe('任务原因码', () => {
  it('PROCESS_RESTART 提示副作用可能仍有效，不说已回滚', () => {
    const text = describeRunErrorCode('PROCESS_RESTART')
    expect(text).toContain('可能仍然有效')
    expect(text).not.toContain('回滚')
  })

  it('未知原因码返回空串，界面自行决定是否展示', () => {
    expect(describeRunErrorCode('SOMETHING_NEW')).toBe('')
  })
})
