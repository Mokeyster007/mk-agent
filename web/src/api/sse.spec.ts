import { describe, expect, it } from 'vitest'
import { createSseParser } from './sse'

/**
 * SSE 解帧器测试。
 *
 * 重点覆盖两类真实风险：
 * 1. 分块边界落在协议分隔符中间（"\r\n" 被切成 "\r" + "\n"）；
 * 2. 一帧的 JSON 被拆成多个 chunk 到达。
 * 这两件事在真实网络里经常发生，处理错了会丢事件或解析失败。
 */
describe('createSseParser', () => {
  it('解析单帧：event + data', () => {
    const parser = createSseParser()
    const frames = parser.push('event:tool_start\ndata:{"type":"tool_start"}\n\n')

    expect(frames).toHaveLength(1)
    expect(frames[0].event).toBe('tool_start')
    expect(frames[0].data).toBe('{"type":"tool_start"}')
  })

  it('一次 push 里包含多帧', () => {
    const parser = createSseParser()
    const frames = parser.push('event:a\ndata:1\n\nevent:b\ndata:2\n\n')

    expect(frames.map((frame) => frame.event)).toEqual(['a', 'b'])
    expect(frames.map((frame) => frame.data)).toEqual(['1', '2'])
  })

  it('分块落在 CRLF 中间也不丢帧', () => {
    const parser = createSseParser()

    expect(parser.push('event:tool_start\r')).toHaveLength(0)
    const frames = parser.push('\ndata:{"a":1}\r\n\r\n')

    expect(frames).toHaveLength(1)
    expect(frames[0].event).toBe('tool_start')
  })

  it('JSON 被拆成多个 chunk 时能拼回来', () => {
    const parser = createSseParser()

    expect(parser.push('event:tool_start\ndata:{"type":"tool_')).toHaveLength(0)
    const frames = parser.push('start","message":"正在查询歌曲"}\n\n')

    expect(frames).toHaveLength(1)
    expect(JSON.parse(frames[0].data)).toEqual({
      type: 'tool_start',
      message: '正在查询歌曲',
    })
  })

  it('忽略注释行（心跳）', () => {
    const parser = createSseParser()
    const frames = parser.push(': keep-alive\n\nevent:done\ndata:[DONE]\n\n')

    expect(frames).toHaveLength(1)
    expect(frames[0].event).toBe('done')
  })

  it('多行 data 用换行拼接', () => {
    const parser = createSseParser()
    const frames = parser.push('event:x\ndata:line1\ndata:line2\n\n')

    expect(frames[0].data).toBe('line1\nline2')
  })

  it('没有 data 的帧被丢弃，不产生空事件', () => {
    const parser = createSseParser()
    const frames = parser.push('event:ping\n\n')

    expect(frames).toHaveLength(0)
  })

  it('流结束时 flush 出未闭合的最后一帧', () => {
    const parser = createSseParser()
    parser.push('event:final_answer\ndata:{"type":"final_answer"}')

    const frames = parser.flush()
    expect(frames).toHaveLength(1)
    expect(frames[0].event).toBe('final_answer')
  })
})
