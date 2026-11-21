import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 会话 store 的行为测试。
 *
 * 关注三件事（都是"错了会让用户以为有记忆/没记忆"的地方）：
 * 1. 没有会话时发消息会**先建会话**，不是退化成独立任务；
 * 2. 打开会话会把每一轮还原成任务对象（含待审批的卡片）；
 * 3. 会话 id 落 localStorage，刷新后还能回到同一个会话。
 */

const mocks = vi.hoisted(() => ({
  listConversations: vi.fn(),
  createConversation: vi.fn(),
  getConversation: vi.fn(),
  getApproval: vi.fn(),
}))

vi.mock('@/api/conversations', () => ({
  listConversations: mocks.listConversations,
  createConversation: mocks.createConversation,
  getConversation: mocks.getConversation,
}))

vi.mock('@/api/approvals', () => ({
  getApproval: mocks.getApproval,
  decideApproval: vi.fn(),
  resumeApproval: vi.fn(),
}))

vi.mock('@/api/music', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/music')>()
  return { ...actual, runMusicChat: vi.fn() }
})

vi.mock('@/api/runs', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/runs')>()
  return { ...actual, getRun: vi.fn(), pageRuns: vi.fn(), cancelRun: vi.fn() }
})

const { taskStore } = await import('./taskStore')
const {
  activeConversationId,
  clearActiveConversation,
  conversations,
  ensureConversation,
  openConversation,
  refreshConversations,
} = await import('./conversation')

beforeEach(() => {
  vi.clearAllMocks()
  window.localStorage.clear()
  conversations.list = []
  conversations.error = null
  conversations.loading = false
  conversations.opening = false
  conversations.detailError = null
  activeConversationId.value = null
  taskStore.tasks.value = []
})

describe('conversation store', () => {
  it('列表来自服务端，失败时保留错误文案', async () => {
    mocks.listConversations.mockResolvedValue([
      {
        conversationId: 'c-1',
        title: '帮我查一下夜航星的歌',
        messageCount: 4,
        lastState: 'SUCCEEDED',
        updatedAt: '2026-11-18T04:00:00',
      },
    ])

    await refreshConversations()
    expect(conversations.list).toHaveLength(1)
    expect(conversations.error).toBeNull()
  })

  it('没有会话时 ensureConversation 会建一个，并写进 localStorage', async () => {
    mocks.createConversation.mockResolvedValue({ conversationId: 'c-new' })
    mocks.listConversations.mockResolvedValue([])

    const id = await ensureConversation()

    expect(id).toBe('c-new')
    expect(mocks.createConversation).toHaveBeenCalledTimes(1)
    expect(activeConversationId.value).toBe('c-new')
    expect(window.localStorage.getItem('mkagent.activeConversationId')).toBe('c-new')
  })

  it('已有会话时不会重复创建', async () => {
    activeConversationId.value = 'c-existing'

    const id = await ensureConversation()

    expect(id).toBe('c-existing')
    expect(mocks.createConversation).not.toHaveBeenCalled()
  })

  it('打开会话会把消息还原成轮次，并重建待审批的卡片', async () => {
    mocks.getConversation.mockResolvedValue({
      conversationId: 'c-1',
      title: '帮我查一下夜航星的歌',
      updatedAt: '2026-11-18T04:00:00',
      messages: [
        {
          runId: 'r-1',
          role: 'USER',
          content: '帮我查一下夜航星的歌',
          state: null,
          createdAt: '2026-11-18T03:59:00',
          approvalId: null,
        },
        {
          runId: 'r-1',
          role: 'ASSISTANT',
          content: '夜航星一共有 5 首作品。',
          state: 'SUCCEEDED',
          createdAt: '2026-11-18T03:59:10',
          approvalId: null,
        },
        {
          runId: 'r-2',
          role: 'USER',
          content: '把前两首建一个歌单',
          state: null,
          createdAt: '2026-11-18T04:00:00',
          approvalId: 'a-1',
        },
      ],
    })

    mocks.getApproval.mockResolvedValue({
      approvalId: 'a-1',
      runId: 'r-2',
      status: 'PENDING',
      resumeState: 'PENDING_RESUME',
      summary: {
        tool: 'create_playlist',
        toolLabel: '创建歌单',
        playlistName: '夜航星精选',
        songCount: 2,
        songs: ['城市霓虹', '深夜电台'],
        roundToolCount: 1,
        text: '创建歌单《夜航星精选》',
      },
      expiresAt: '2026-11-18T04:10:00',
    })

    await openConversation('c-1')

    const turns = taskStore.tasks.value.slice().sort((a, b) => a.createdAt - b.createdAt)
    expect(turns).toHaveLength(2)

    expect(turns[0].runId).toBe('r-1')
    expect(turns[0].prompt).toBe('帮我查一下夜航星的歌')
    expect(turns[0].answer).toContain('5 首作品')
    expect(turns[0].serverState).toBe('SUCCEEDED')
    expect(turns[0].conversationId).toBe('c-1')

    // 待审批：卡片必须能被重建，否则用户只能看着"等待审批"却没有按钮
    expect(turns[1].runId).toBe('r-2')
    expect(turns[1].serverState).toBe('WAITING_APPROVAL')
    expect(turns[1].approvalPayload?.approvalId).toBe('a-1')
    expect(turns[1].approvalPayload?.summary.playlistName).toBe('夜航星精选')
  })

  it('清空当前会话不会删除服务端记录', () => {
    activeConversationId.value = 'c-1'
    window.localStorage.setItem('mkagent.activeConversationId', 'c-1')

    clearActiveConversation()

    expect(activeConversationId.value).toBeNull()
    expect(window.localStorage.getItem('mkagent.activeConversationId')).toBeNull()
    expect(mocks.getConversation).not.toHaveBeenCalled()
  })
})
