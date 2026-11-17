/**
 * HTTP 客户端。
 *
 * 三条硬规则：
 * 1. 不发自动重试。执行类请求（启动 Agent、审批、恢复、写操作）重复发送
 *    会产生真实的重复副作用；HTTP 方法是 GET 不等于它是安全查询。
 * 2. 不把身份写进 URL。X-User-Id 只走请求头。
 * 3. 错误一律转成 ApiError，界面只认 kind / code，不解析后端文案。
 */
import { ApiError, toApiError, toNetworkError } from './errors'
import { getUserId } from './identity'

/**
 * API 前缀。
 *
 * 默认空串：请求走相对路径 /api/**，由 Vite dev server 代理到后端，
 * 因此代码里不出现任何完整服务器地址。
 */
const API_BASE = (import.meta.env.VITE_API_BASE ?? '').replace(/\/+$/, '')

export function apiUrl(path: string, query?: Record<string, string | number | undefined>): string {
  const base = `${API_BASE}${path.startsWith('/') ? path : `/${path}`}`
  if (!query) {
    return base
  }
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') {
      continue
    }
    params.set(key, String(value))
  }
  const qs = params.toString()
  return qs ? `${base}?${qs}` : base
}

export function authHeaders(extra?: Record<string, string>): Record<string, string> {
  return {
    'X-User-Id': getUserId(),
    ...extra,
  }
}

export interface JsonRequestOptions {
  method?: 'GET' | 'POST'
  query?: Record<string, string | number | undefined>
  signal?: AbortSignal
  body?: unknown
}

/**
 * 读取响应体：优先 JSON，失败时返回文本。
 * 后端错误体固定是 JSON，但网关层的 502/504 可能是 HTML，
 * 这里不能让解析失败盖掉真正的状态码。
 */
async function readBody(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text) {
    return null
  }
  try {
    return JSON.parse(text)
  } catch {
    return { message: text.slice(0, 200) }
  }
}

export async function requestJson<T>(path: string, options: JsonRequestOptions = {}): Promise<T> {
  const { method = 'GET', query, signal, body } = options

  let response: Response
  try {
    response = await fetch(apiUrl(path, query), {
      method,
      signal,
      headers: authHeaders(
        body === undefined
          ? { Accept: 'application/json' }
          : { Accept: 'application/json', 'Content-Type': 'application/json' },
      ),
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (error) {
    throw toNetworkError(error)
  }

  const payload = await readBody(response)

  if (!response.ok) {
    throw toApiError(response.status, payload)
  }

  return payload as T
}

/**
 * 供 SSE 使用的底层请求。
 *
 * 单独暴露是因为 SSE 需要拿到未消费的 response.body，
 * 而且必须用 fetch（EventSource 无法设置 X-User-Id 请求头）。
 */
export async function openStream(
  path: string,
  options: { method?: 'GET' | 'POST'; query?: Record<string, string | number | undefined>; signal?: AbortSignal } = {},
): Promise<Response> {
  const { method = 'GET', query, signal } = options

  let response: Response
  try {
    response = await fetch(apiUrl(path, query), {
      method,
      signal,
      headers: authHeaders({ Accept: 'text/event-stream' }),
    })
  } catch (error) {
    throw toNetworkError(error)
  }

  if (!response.ok) {
    const payload = await readBody(response)
    throw toApiError(response.status, payload)
  }

  if (!response.body) {
    throw new ApiError('unknown', { status: response.status })
  }

  return response
}
