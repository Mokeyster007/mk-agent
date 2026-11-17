/**
 * SSE 客户端（基于 fetch，不是 EventSource）。
 *
 * 为什么不用 EventSource：
 * 后端所有入口都要求 X-User-Id 请求头，而 EventSource 无法设置自定义请求头。
 * 把身份塞进 query 参数是不可接受的（会进访问日志、浏览器历史、Referer），
 * 所以这里用 fetch + ReadableStream 自己解帧。
 *
 * 协议：Spring 的 SseEmitter 写出
 *   event:<name>\n
 *   data:<json>\n
 *   \n
 * 因此按空行分帧，字段用 ":" 切分，data 支持多行拼接。
 */
import { openStream } from './client'

export interface SseFrame {
  /** SSE event name，后端保证与 AgentEvent.type 一致 */
  event: string
  /** 原始 data 文本（后端是 AgentEvent 的 JSON） */
  data: string
  id: string | null
}

export interface SseParser {
  push(chunk: string): SseFrame[]
  flush(): SseFrame[]
}

function parseBlock(raw: string): SseFrame | null {
  const lines = raw.split('\n')
  let event = 'message'
  let id: string | null = null
  const dataLines: string[] = []

  for (const line of lines) {
    if (!line || line.startsWith(':')) {
      // 空行或注释（心跳），忽略
      continue
    }
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }

    switch (field) {
      case 'event':
        event = value || 'message'
        break
      case 'data':
        dataLines.push(value)
        break
      case 'id':
        id = value
        break
      default:
        // retry 等字段当前用不到
        break
    }
  }

  if (dataLines.length === 0) {
    return null
  }
  return { event, data: dataLines.join('\n'), id }
}

/**
 * 增量解帧器。
 *
 * 单独抽出来是为了能脱离网络做单元测试：
 * 分块边界可能落在 "\r\n" 中间，也可能落在 JSON 中间，
 * 这两种情况都必须正确。
 */
export function createSseParser(): SseParser {
  let buffer = ''
  let pendingCr = false

  return {
    push(chunk: string): SseFrame[] {
      let text = chunk
      if (pendingCr) {
        text = `\r${text}`
        pendingCr = false
      }
      if (text.endsWith('\r')) {
        text = text.slice(0, -1)
        pendingCr = true
      }
      buffer += text
      // 统一换行，兼容 \r\n 与 \r
      buffer = buffer.replace(/\r\n?/g, '\n')

      const frames: SseFrame[] = []
      let index = buffer.indexOf('\n\n')
      while (index >= 0) {
        const raw = buffer.slice(0, index)
        buffer = buffer.slice(index + 2)
        const frame = parseBlock(raw)
        if (frame) {
          frames.push(frame)
        }
        index = buffer.indexOf('\n\n')
      }
      return frames
    },

    flush(): SseFrame[] {
      if (pendingCr) {
        buffer += '\r'
        pendingCr = false
      }
      const raw = buffer
      buffer = ''
      const frame = raw.trim() ? parseBlock(raw.replace(/\r\n?/g, '\n')) : null
      return frame ? [frame] : []
    },
  }
}

export interface StreamEventsOptions {
  method?: 'GET' | 'POST'
  query?: Record<string, string | number | undefined>
  signal?: AbortSignal
}

/**
 * 打开一条 SSE 流并逐帧回调。
 *
 * 注意：
 * - 连接结束（收到 done 或流关闭）**不代表业务成功**，
 *   调用方必须另外查询 agent_run 才能确认终态；
 * - 主动 abort 只停止前端读取，不会取消后端任务，
 *   因此这里不把 abort 翻译成"任务已取消"。
 */
export async function streamEvents(
  path: string,
  options: StreamEventsOptions,
  onFrame: (frame: SseFrame) => void,
): Promise<void> {
  const response = await openStream(path, options)
  const reader = response.body!.getReader()
  const decoder = new TextDecoder()
  const parser = createSseParser()

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      const text = decoder.decode(value, { stream: true })
      for (const frame of parser.push(text)) {
        onFrame(frame)
      }
    }
    const tail = decoder.decode()
    if (tail) {
      for (const frame of parser.push(tail)) {
        onFrame(frame)
      }
    }
    for (const frame of parser.flush()) {
      onFrame(frame)
    }
  } finally {
    // 提前 break（例如收到 done）时必须释放底层连接
    try {
      reader.releaseLock()
    } catch {
      // 已释放
    }
  }
}
