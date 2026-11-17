import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import http from 'node:http'
import type { AddressInfo } from 'node:net'

/**
 * 真实 HTTP 上的 SSE 联调测试。
 *
 * 前面的组件测试把 runMusicChat 换成了 Mock，因此没有验证
 * "fetch + ReadableStream + 解帧"这条真实链路。
 * 这里起一个真的 HTTP 服务，按后端 SseEmitter 的格式写出帧，
 * 让前端用自己的实现去读——能证明传输层是对的。
 *
 * 仍然不是真实后端：帧内容是手写的，不涉及模型与数据库。
 */

let server: http.Server
let base = ''
/** 记录服务端实际收到的身份头，用于验证"身份走 header 而不是 URL" */
let seenUserId: string | undefined
/** 记录服务端实际收到的请求 URL */
let seenUrl = ''

beforeAll(async () => {
  server = http.createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://localhost')
    seenUserId = req.headers['x-user-id'] as string | undefined
    seenUrl = req.url ?? ''

    // 缺身份 → 401 JSON（与后端 GlobalExceptionHandler 契约一致）
    if (!req.headers['x-user-id']) {
      res.writeHead(401, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ success: false, message: '缺少用户身份' }))
      return
    }

    if (url.pathname === '/api/recovery') {
      res.writeHead(503, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ success: false, code: 'RECOVERY_IN_PROGRESS' }))
      return
    }

    if (url.pathname === '/api/stream') {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream;charset=UTF-8',
        'Cache-Control': 'no-cache',
      })
      const send = (event: string, data: unknown) => {
        res.write(`event:${event}\n`)
        res.write(`data:${JSON.stringify(data)}\n\n`)
      }
      send('run_id', { runId: 'r-1', type: 'run_id', message: '', step: null, timestamp: 1 })
      // 故意把一帧拆成两次写，验证分块边界
      res.write('event:tool_start\ndata:{"runId":"r-1","type":"tool_')
      setTimeout(() => {
        res.write('start","message":"正在搜索","step":1,"timestamp":2}\n\n')
        send('done', { runId: 'r-1', type: 'done', message: '[DONE]', step: 1, timestamp: 3 })
        res.end()
      }, 30)
      return
    }

    res.writeHead(404).end()
  })

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  const { port } = server.address() as AddressInfo
  base = `http://127.0.0.1:${port}`
})

afterAll(async () => {
  await new Promise<void>((resolve) => server.close(() => resolve()))
})

/** 让 client.ts 在导入时读到正确的 base */
async function loadSse() {
  vi.resetModules()
  vi.stubEnv('VITE_API_BASE', base)
  const mod = await import('./sse')
  vi.unstubAllEnvs()
  return mod
}

describe('SSE over fetch（真实 HTTP）', () => {
  it('按顺序收到全部帧，并能处理被切断的 JSON', async () => {
    const { streamEvents } = await loadSse()
    const frames: Array<{ event: string; data: string }> = []

    await streamEvents('/api/stream', { method: 'GET' }, (frame) => {
      frames.push({ event: frame.event, data: frame.data })
    })

    expect(frames.map((frame) => frame.event)).toEqual(['run_id', 'tool_start', 'done'])
    expect(JSON.parse(frames[1].data).message).toBe('正在搜索')
  })

  it('身份走 X-User-Id 请求头，不放进 URL', async () => {
    const { streamEvents } = await loadSse()
    seenUserId = undefined
    seenUrl = ''

    await streamEvents('/api/stream', { method: 'GET' }, () => {})

    expect(seenUserId).toBeTruthy()
    // 身份不能出现在 URL 里（会进访问日志、浏览器历史、Referer）
    expect(seenUrl).not.toContain('user')
    expect(seenUrl).toBe('/api/stream')
  })

  it('503 + RECOVERY_IN_PROGRESS 抛 recovering，而不是空流', async () => {
    const { streamEvents } = await loadSse()
    const { ApiError } = await import('./errors')

    await expect(streamEvents('/api/recovery', {}, () => {})).rejects.toMatchObject({
      kind: 'recovering',
      status: 503,
      code: 'RECOVERY_IN_PROGRESS',
    })

    try {
      await streamEvents('/api/recovery', {}, () => {})
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
    }
  })
})
