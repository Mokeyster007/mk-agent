import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AgentEvent, AgentRunVO, ApprovalView } from '@/api/types'

/**
 * 任务状态中心测试。
 *
 * 这一层是"界面会不会骗人"的核心：
 * 一次提交只能建一个任务、流结束不等于成功、中止不等于取消、
 * 旧响应不能覆盖新任务、审批决定不能重复提交。
 */

const mocks = vi.hoisted(() => ({
  runMusicChat: vi.fn(),
  getRun: vi.fn(),
  cancelRun: vi.fn(),
  pageRuns: vi.fn(),
  getApproval: vi.fn(),
  decideApproval: vi.fn(),
  resumeApproval: vi.fn(),
}))

vi.mock('@/api/music', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/music')>()
  return { ...actual, runMusicChat: mocks.runMusicChat }
})

vi.mock('@/api/runs', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/runs')>()
  return {
    ...actual,
    getRun: mocks.getRun,
    cancelRun: mocks.cancelRun,
    pageRuns: mocks.pageRuns,
  }
})

vi.mock('@/api/approvals', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/approvals')>()
  return {
    ...actual,
    getApproval: mocks.getApproval,
    decideApproval: mocks.decideApproval,
    resumeApproval: mocks.resumeApproval,
  }
})

const { taskStore, parseApprovalPayload } = await import('./taskStore')

/**
 * 触发一次"迟到事件"。
 *
 * 用函数包一层是因为 TS 的控制流分析看不到回调里对 emit 的赋值，
 * 直接写 emit?.() 会被判定为对 null 调用。
 */
let lateEmitter: ((e: AgentEvent) => void) | null = null

function fireLateEvent(e: AgentEvent): void {
  const fn = lateEmitter as ((event: AgentEvent) => void) | null
  fn?.(e)
}

function event(type: string, message = '', runId = 'run-1'): AgentEvent {
  return { runId, type, message, step: 1, timestamp: Date.now() }
}

function runVO(overrides: Partial<AgentRunVO> = {}): AgentRunVO {
  return {
    runId: 'run-1',
    userId: 'music-user-1',
    agentType: 'MUSIC_USER',
    userPrompt: '帮我找一下夜航星的歌',
    state: 'SUCCEEDED',
    currentStep: 2,
    toolCallCount: 1,
    toolTrace: 'search_songs,terminate_task',
    finalAnswer: '夜航星共有 5 首歌。',
    errorMessage: null,
    errorCode: null,
    model: 'qwen-plus',
    promptTokens: 100,
    completionTokens: 50,
    totalTokens: 150,
    startedAt: '2026-11-16T02:00:00',
    finishedAt: '2026-11-16T02:00:10',
    totalCostMillis: 10000,
    createdAt: '2026-11-16T02:00:00',
    ...overrides,
  }
}

function approvalView(overrides: Partial<ApprovalView> = {}): ApprovalView {
  return {
    approvalId: 'ap-1',
    runId: 'run-1',
    agentType: 'MUSIC_USER',
    step: 3,
    primaryTool: 'create_playlist',
    toolCount: 1,
    summaryText: '即将创建歌单',
    summary: null,
    status: 'PENDING',
    resumeState: 'PENDING_RESUME',
    resumeAttempts: 0,
    decidedAt: null,
    expiresAt: '2026-11-17T01:30:00',
    ...overrides,
  }
}

beforeEach(() => {
  taskStore.tasks.value = []
  taskStore.activeId.value = null
  taskStore.submitting.value = false
  lateEmitter = null
  vi.clearAllMocks()
  mocks.getRun.mockResolvedValue(runVO())
})

describe('提交', () => {
  it('一次提交只发起一次请求，并只创建一个任务', async () => {
    mocks.runMusicChat.mockImplementation(async ({ onEvent }: { onEvent: (e: AgentEvent) => void }) => {
      onEvent(event('run_id'))
      onEvent(event('tool_start', '正在调用 search_songs'))
      onEvent(event('done', '[DONE]'))
    })

    await taskStore.submit('帮我找一下夜航星的歌', 'MUSIC_USER')

    expect(mocks.runMusicChat).toHaveBeenCalledTimes(1)
    expect(taskStore.tasks.value).toHaveLength(1)
  })

  it('执行中再次提交被拒绝，不产生第二个任务', async () => {
    mocks.runMusicChat.mockImplementation(() => new Promise(() => {}))
    void taskStore.submit('第一个任务', 'MUSIC_USER')

    const second = await taskStore.submit('第二个任务', 'MUSIC_USER')

    expect(second).toBe(false)
    expect(taskStore.tasks.value).toHaveLength(1)
  })

  it('空输入不提交', async () => {
    const ok = await taskStore.submit('   ', 'MUSIC_USER')
    expect(ok).toBe(false)
    expect(mocks.runMusicChat).not.toHaveBeenCalled()
  })

  it('任务落到服务端后刷新一次历史，且不因此多建任务', async () => {
    mocks.runMusicChat.mockImplementation(async ({ onEvent }: { onEvent: (e: AgentEvent) => void }) => {
      onEvent(event('run_id'))
      onEvent(event('done', '[DONE]'))
    })
    mocks.pageRuns.mockResolvedValue({
      pageNum: 1,
      pageSize: 20,
      total: 1,
      pages: 1,
      records: [runVO()],
    })

    await taskStore.submit('帮我找一下夜航星的歌', 'MUSIC_USER')

    // 历史是只读查询：刷新列表，但不会再多发一次任务请求
    expect(mocks.runMusicChat).toHaveBeenCalledTimes(1)
    expect(mocks.pageRuns).toHaveBeenCalled()
    expect(mocks.pageRuns.mock.calls[0][0]).toMatchObject({ pageNum: 1 })
    expect(taskStore.tasks.value).toHaveLength(1)
  })
})

describe('完成判定', () => {
  it('收到 done 但查询不到终态时，不标记成功', async () => {
    mocks.runMusicChat.mockImplementation(async ({ onEvent }: { onEvent: (e: AgentEvent) => void }) => {
      onEvent(event('run_id'))
      onEvent(event('done', '[DONE]'))
    })
    mocks.getRun.mockRejectedValue(new Error('network down'))

    await taskStore.submit('查一下歌单', 'MUSIC_USER')

    const task = taskStore.tasks.value[0]
    expect(task.serverState).toBeNull()
    expect(task.detailError).toBeTruthy()
    expect(task.transport).toBe('closed')
  })

  it('终态以 agent_run 为准，并用后端 final_answer 覆盖流里的文本', async () => {
    mocks.runMusicChat.mockImplementation(async ({ onEvent }: { onEvent: (e: AgentEvent) => void }) => {
      onEvent(event('run_id'))
      onEvent(event('final_answer', '流里的旧回答'))
      onEvent(event('done', '[DONE]'))
    })
    mocks.getRun.mockResolvedValue(runVO({ finalAnswer: '数据库里的最终回答' }))

    await taskStore.submit('查一下歌单', 'MUSIC_USER')

    const task = taskStore.tasks.value[0]
    expect(task.serverState).toBe('SUCCEEDED')
    expect(task.answer).toBe('数据库里的最终回答')
  })
})

describe('中止与取消', () => {
  it('前端中止显示结果未知，不写"已取消"', async () => {
    const { ApiError } = await import('@/api/errors')
    mocks.runMusicChat.mockRejectedValue(new ApiError('aborted'))

    await taskStore.submit('查一下歌单', 'MUSIC_USER')

    const task = taskStore.tasks.value[0]
    expect(task.transport).toBe('aborted')
    expect(task.error?.kind).toBe('aborted')
    expect(task.error?.text).toContain('可能仍在执行')
    expect(task.serverState).not.toBe('CANCELLED')
  })

  it('取消请求被服务端接受后，状态以查询结果为准', async () => {
    mocks.runMusicChat.mockImplementation(async ({ onEvent }: { onEvent: (e: AgentEvent) => void }) => {
      onEvent(event('run_id'))
      onEvent(event('done', '[DONE]'))
    })
    mocks.getRun.mockResolvedValueOnce(runVO({ state: 'RUNNING' }))
    mocks.cancelRun.mockResolvedValue({})

    await taskStore.submit('跑一个长任务', 'MUSIC_USER')
    const task = taskStore.tasks.value[0]
    // 流结束后仍是 RUNNING，轮询会继续；这里直接把下一次查询设成 CANCELLED
    mocks.getRun.mockResolvedValue(runVO({ state: 'CANCELLED' }))

    await taskStore.cancel(task.id)

    expect(mocks.cancelRun).toHaveBeenCalledWith('run-1')
    expect(task.serverState).toBe('CANCELLED')
  })
})

describe('陈旧响应', () => {
  it('旧任务的事件不会污染新选中的任务', async () => {
    // 旧任务一直"流着"，不结束——模拟用户中途切到别的任务
    mocks.runMusicChat.mockImplementation(({ onEvent }: { onEvent: (e: AgentEvent) => void }) => {
      lateEmitter = onEvent
      onEvent(event('run_id', '', 'run-old'))
      return new Promise<void>(() => {})
    })
    mocks.getRun.mockResolvedValue(
      runVO({ runId: 'run-new', state: 'SUCCEEDED', userPrompt: '历史任务', finalAnswer: null }),
    )

    void taskStore.submit('旧任务', 'MUSIC_USER')
    await Promise.resolve()
    await Promise.resolve()

    const oldTask = taskStore.tasks.value[0]
    expect(oldTask.runId).toBe('run-old')

    await taskStore.openHistoryRun('run-new')
    const active = taskStore.activeTask.value
    expect(active?.runId).toBe('run-new')

    // 旧任务迟到的回答：只能落在旧任务上
    fireLateEvent(event('final_answer', '旧任务迟到的回答', 'run-old'))

    expect(active?.answer).toBe('')
    expect(active?.events).toHaveLength(0)
    expect(oldTask.answer).toBe('旧任务迟到的回答')
  })

  it('已进入终态的任务不再接受迟到事件', async () => {
    mocks.runMusicChat.mockImplementation(({ onEvent }: { onEvent: (e: AgentEvent) => void }) => {
      lateEmitter = onEvent
      onEvent(event('run_id'))
      return new Promise<void>(() => {})
    })
    mocks.getRun.mockResolvedValue(runVO({ state: 'SUCCEEDED', finalAnswer: '数据库回答' }))

    void taskStore.submit('查一下', 'MUSIC_USER')
    await Promise.resolve()
    await Promise.resolve()
    const task = taskStore.tasks.value[0]

    await taskStore.recheck(task.id)
    expect(task.serverState).toBe('SUCCEEDED')

    fireLateEvent(event('final_answer', '迟到的覆盖文本'))
    expect(task.answer).toBe('数据库回答')
  })
})

describe('审批', () => {
  async function submitWithApproval(): Promise<void> {
    const payload = JSON.stringify({
      approvalId: 'ap-1',
      expiresAt: '2026-11-17T01:30:00',
      summary: {
        tool: 'create_playlist',
        toolLabel: '创建歌单',
        playlistName: '清晨慢跑',
        songCount: 1,
        songs: ['城市霓虹'],
        roundToolCount: 1,
        text: '即将创建歌单「清晨慢跑」',
      },
    })
    mocks.runMusicChat.mockImplementation(async ({ onEvent }: { onEvent: (e: AgentEvent) => void }) => {
      onEvent(event('run_id'))
      onEvent(event('approval_required', payload))
      onEvent(event('done', '[DONE]'))
    })
    mocks.getRun.mockResolvedValue(runVO({ state: 'WAITING_APPROVAL', finalAnswer: null }))
    mocks.getApproval.mockResolvedValue(approvalView())

    await taskStore.submit('帮我建一个歌单', 'MUSIC_USER')
  }

  it('收到 approval_required 后以审批接口校准状态', async () => {
    await submitWithApproval()
    const task = taskStore.tasks.value[0]

    expect(task.approvalPayload?.approvalId).toBe('ap-1')
    expect(task.approval?.status).toBe('PENDING')
    expect(task.serverState).toBe('WAITING_APPROVAL')
    expect(mocks.getApproval).toHaveBeenCalledWith('ap-1')
  })

  it('重复点击批准只提交一次决定', async () => {
    await submitWithApproval()
    const task = taskStore.tasks.value[0]
    mocks.decideApproval.mockImplementation(() => new Promise(() => {}))

    void taskStore.decide(task.id, true)
    void taskStore.decide(task.id, true)

    expect(mocks.decideApproval).toHaveBeenCalledTimes(1)
    expect(task.deciding).toBe(true)
  })

  it('决定提交失败但审批已变更时，提示决定已保存', async () => {
    await submitWithApproval()
    const task = taskStore.tasks.value[0]

    const { ApiError } = await import('@/api/errors')
    mocks.decideApproval.mockRejectedValue(
      new ApiError('rate_limited', { status: 429, code: 'RESUME_CONCURRENCY_LIMIT' }),
    )
    mocks.getApproval.mockResolvedValue(
      approvalView({ status: 'APPROVED', resumeState: 'PENDING_RESUME', resumeAttempts: 1 }),
    )

    await taskStore.decide(task.id, true)

    expect(task.approval?.status).toBe('APPROVED')
    expect(task.error?.text).toContain('决定已保存')
    expect(task.error?.text).toContain('手动恢复')
  })

  it('决定根本没落库时，不谎称已保存', async () => {
    await submitWithApproval()
    const task = taskStore.tasks.value[0]

    const { ApiError } = await import('@/api/errors')
    mocks.decideApproval.mockRejectedValue(
      new ApiError('conflict', { status: 409, code: 'APPROVAL_EXPIRED' }),
    )
    mocks.getApproval.mockResolvedValue(approvalView({ status: 'EXPIRED', resumeState: 'FAILED' }))

    await taskStore.decide(task.id, true)

    expect(task.error?.text).not.toContain('决定已保存')
    expect(task.error?.text).toContain('过期')
  })
})

describe('parseApprovalPayload', () => {
  it('解析后端事件 JSON', () => {
    const payload = parseApprovalPayload(
      JSON.stringify({
        approvalId: 'ap-9',
        expiresAt: '2026-11-17T01:30:00',
        summary: { tool: 'create_playlist', toolLabel: '创建歌单', roundToolCount: 1, songs: [] },
      }),
    )

    expect(payload?.approvalId).toBe('ap-9')
    expect(payload?.summary.toolLabel).toBe('创建歌单')
  })

  it('缺少 approvalId 时返回 null，不构造半个审批', () => {
    expect(parseApprovalPayload('{"summary":{}}')).toBeNull()
    expect(parseApprovalPayload('not json')).toBeNull()
  })
})
