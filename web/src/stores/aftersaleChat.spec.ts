/**
 * 售后对话 store 单测。
 *
 * 四条被反复强调的语义，这里逐条钉住：
 * 1. 确认卡内容只来自服务端事件，`details` 原样透传；
 * 2. 批准 → 执行 → 刷新业务数据；**拒绝 → 不刷新、不显示"已提交"**（零写入）；
 * 3. 决定已保存但恢复失败时，必须告诉用户"可以手动恢复"，而不是当成彻底失败；
 * 4. 会话建不出来时退化为单轮，但消息照样能发出去。
 */
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/errors'
import type { AgentEvent, ApprovalView } from '@/api/types'
import type { AfterSaleChatOptions } from '@/api/aftersale'
import { createAfterSaleChatStore, type AfterSaleChatDeps } from './aftersaleChat'

const APPROVAL_ID = 'ap-aftersale-1'

/** 售后确认卡：带 details，没有歌单/歌曲。 */
const APPROVAL_JSON = JSON.stringify({
  approvalId: APPROVAL_ID,
  expiresAt: '2026-11-22T10:30:00',
  summary: {
    tool: 'submit_return',
    toolLabel: '提交退货申请',
    playlistName: null,
    songCount: null,
    songs: [],
    roundToolCount: 1,
    text: '提交退货申请：订单号 SO1；退货数量 1 件。',
    details: [
      { label: '订单号', value: 'SO1' },
      { label: '商品', value: '机械键盘 K87' },
      { label: '退货数量', value: '1 件' },
      { label: '退货原因', value: '键盘按键失灵' },
    ],
  },
})

function view(overrides: Partial<ApprovalView> = {}): ApprovalView {
  return {
    approvalId: APPROVAL_ID,
    runId: 'run-1',
    agentType: 'AFTER_SALES',
    step: 2,
    primaryTool: 'submit_return',
    toolCount: 1,
    summaryText: '提交退货申请：订单号 SO1',
    summary: null,
    status: 'PENDING',
    resumeState: 'PENDING_RESUME',
    resumeAttempts: 0,
    decidedAt: null,
    expiresAt: '2026-11-22T10:30:00',
    ...overrides,
  }
}

function event(type: string, message = ''): AgentEvent {
  return { runId: 'run-1', type, message, step: null, timestamp: 0 }
}

function buildDeps(overrides: Partial<AfterSaleChatDeps> = {}): AfterSaleChatDeps {
  return {
    chat: vi.fn(async ({ onEvent }: AfterSaleChatOptions) => {
      onEvent(event('run_id'))
      onEvent(event('tool_start', '查询已签收订单'))
      onEvent(event('approval_required', APPROVAL_JSON))
    }),
    conversation: vi.fn(async () => ({ conversationId: 'conv-1' })),
    approval: vi.fn(async () => view()),
    decide: vi.fn(async (_id: string, approve: boolean, options: { onEvent: (e: AgentEvent) => void }) => {
      options.onEvent(
        event('final_answer', approve ? '已提交退货申请：RT20261122101500-00A1' : '好的，没有创建申请。'),
      )
    }),
    resume: vi.fn(async (_id: string, options: { onEvent: (e: AgentEvent) => void }) => {
      options.onEvent(event('final_answer', '已提交退货申请：RT20261122101500-00A1'))
    }),
    onExecuted: vi.fn(async () => undefined),
    ...overrides,
  }
}

describe('售后对话 store', () => {
  it('发出消息后拿到确认卡，details 原样透传，并查询真实审批状态', async () => {
    const deps = buildDeps()
    const store = createAfterSaleChatStore(deps)

    expect(await store.submit('帮我退一把键盘')).toBe(true)

    expect(store.turns.value).toHaveLength(1)
    const turn = store.turns.value[0]
    expect(turn.prompt).toBe('帮我退一把键盘')
    expect(turn.steps).toContain('查询已签收订单')
    expect(turn.approvalPayload?.approvalId).toBe(APPROVAL_ID)
    expect(turn.approvalPayload?.summary.details).toEqual([
      { label: '订单号', value: 'SO1' },
      { label: '商品', value: '机械键盘 K87' },
      { label: '退货数量', value: '1 件' },
      { label: '退货原因', value: '键盘按键失灵' },
    ])
    expect(turn.approvalPayload?.summary.songs).toEqual([])
    expect(deps.approval).toHaveBeenCalledWith(APPROVAL_ID)
    expect(turn.approval?.status).toBe('PENDING')
    // 确认前没有执行任何写操作
    expect(deps.decide).not.toHaveBeenCalled()
    expect(deps.onExecuted).not.toHaveBeenCalled()
  })

  it('会话只建一次，第二轮复用同一个 conversationId', async () => {
    const deps = buildDeps()
    const store = createAfterSaleChatStore(deps)

    await store.submit('第一句')
    await store.submit('第二句')

    expect(deps.conversation).toHaveBeenCalledTimes(1)
    const calls = (deps.chat as ReturnType<typeof vi.fn>).mock.calls
    expect(calls[0][0].conversationId).toBe('conv-1')
    expect(calls[1][0].conversationId).toBe('conv-1')
  })

  it('会话建不出来时退化为单轮，消息仍然发得出去', async () => {
    const deps = buildDeps({
      conversation: vi.fn(async () => {
        throw new ApiError('forbidden')
      }),
    })
    const store = createAfterSaleChatStore(deps)

    expect(await store.submit('帮我退一把键盘')).toBe(true)
    expect(store.conversationUnavailable.value).toBe(true)
    expect((deps.chat as ReturnType<typeof vi.fn>).mock.calls[0][0].conversationId).toBeNull()
  })

  it('批准后刷新业务数据，回答里是真实退货单号', async () => {
    const deps = buildDeps()
    const store = createAfterSaleChatStore(deps)
    await store.submit('帮我退一把键盘')
    const turn = store.turns.value[0]

    await store.decide(turn.id, true)

    expect(deps.decide).toHaveBeenCalledWith(APPROVAL_ID, true, expect.anything())
    expect(turn.answer).toContain('RT20261122101500-00A1')
    expect(deps.onExecuted).toHaveBeenCalledTimes(1)
  })

  it('拒绝确认：不刷新额度、不出现"已提交"（零写入）', async () => {
    const deps = buildDeps()
    const store = createAfterSaleChatStore(deps)
    await store.submit('帮我退一把键盘')
    const turn = store.turns.value[0]

    await store.decide(turn.id, false)

    expect(deps.decide).toHaveBeenCalledWith(APPROVAL_ID, false, expect.anything())
    expect(deps.onExecuted).not.toHaveBeenCalled()
    expect(turn.answer).not.toContain('RT')
    expect(turn.error).toBeNull()
  })

  it('决定已保存但恢复失败：提示可以手动恢复，并核对真实审批状态', async () => {
    const deps = buildDeps({
      decide: vi.fn(async () => {
        throw new ApiError('conflict', { code: 'RESUME_IN_PROGRESS' })
      }),
      approval: vi.fn(async () => view({ status: 'APPROVED', resumeState: 'FAILED' })),
    })
    const store = createAfterSaleChatStore(deps)
    await store.submit('帮我退一把键盘')
    const turn = store.turns.value[0]

    await store.decide(turn.id, true)

    expect(turn.approval?.status).toBe('APPROVED')
    expect(turn.error).toContain('决定已保存')
    expect(turn.error).toContain('手动恢复')
    expect(turn.deciding).toBe(false)
    // 批准路径即使失败也要刷新，让用户看到"到底建没建单"
    expect(deps.onExecuted).toHaveBeenCalledTimes(1)
  })

  it('提交中不接受第二条消息（一次点击一条消息）', async () => {
    const holder: { release?: () => void } = {}
    const deps = buildDeps({
      chat: vi.fn(
        () =>
          new Promise<void>((resolve) => {
            holder.release = resolve
          }),
      ),
    })
    const store = createAfterSaleChatStore(deps)

    const first = store.submit('第一句')
    // 建会话是异步的，等它把 chat 真正调起来再断言
    await vi.waitFor(() => expect(holder.release).toBeTypeOf('function'))

    expect(await store.submit('第二句')).toBe(false)
    holder.release?.()
    expect(await first).toBe(true)
    expect(store.turns.value).toHaveLength(1)
  })

  it('空白消息不发请求', async () => {
    const deps = buildDeps()
    const store = createAfterSaleChatStore(deps)

    expect(await store.submit('   ')).toBe(false)
    expect(deps.chat).not.toHaveBeenCalled()
  })

  it('手动恢复沿用原 approvalId，并刷新业务数据', async () => {
    const deps = buildDeps()
    const store = createAfterSaleChatStore(deps)
    await store.submit('帮我退一把键盘')
    const turn = store.turns.value[0]

    await store.resume(turn.id)

    expect(deps.resume).toHaveBeenCalledWith(APPROVAL_ID, expect.anything())
    expect(deps.onExecuted).toHaveBeenCalledTimes(1)
    expect(turn.answer).toContain('RT20261122101500-00A1')
  })
})
