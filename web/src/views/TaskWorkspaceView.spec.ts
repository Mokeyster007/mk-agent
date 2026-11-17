import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { AgentEvent } from '@/api/types'

/**
 * 工作区集成测试（组件级，使用受控 Mock 响应）。
 *
 * 这里验证的是"页面在只读装配下会不会给出不该有的入口"，
 * 以及"示例点击只回填、不提交"。
 * 它不代表真实后端已经跑通——真实联调见 README 的联调步骤。
 */

const mocks = vi.hoisted(() => ({
  runMusicChat: vi.fn(),
  getRun: vi.fn(),
  pageRuns: vi.fn(),
  cancelRun: vi.fn(),
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
    pageRuns: mocks.pageRuns,
    cancelRun: mocks.cancelRun,
  }
})

const TaskWorkspaceView = (await import('./TaskWorkspaceView.vue')).default

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/preview/approval', component: { template: '<div />' } },
    ],
  })
}

async function mountView() {
  const router = makeRouter()
  await router.push('/')
  await router.isReady()
  const wrapper = mount(TaskWorkspaceView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.pageRuns.mockResolvedValue({
    pageNum: 1,
    pageSize: 20,
    total: 0,
    pages: 0,
    records: [],
  })
})

describe('TaskWorkspaceView（只读装配）', () => {
  it('展示只读能力说明，不宣称可写', async () => {
    const wrapper = await mountView()
    const text = wrapper.text()

    expect(text).toContain('只读')
    expect(text).toContain('写操作还没有开放')
    wrapper.unmount()
  })

  it('只读模式下没有任何写操作入口', async () => {
    const wrapper = await mountView()
    const buttons = wrapper.findAll('button').map((button) => button.text())
    const joined = buttons.join(' ')

    expect(joined).not.toContain('创建歌单')
    expect(joined).not.toContain('新建歌单')
    expect(joined).not.toContain('批准')
    wrapper.unmount()
  })

  it('空状态给出 4 条与能力匹配的示例', async () => {
    const wrapper = await mountView()
    const examples = wrapper.findAll('.example')

    expect(examples).toHaveLength(4)
    wrapper.unmount()
  })

  it('点击示例只回填输入框，不发起请求', async () => {
    const wrapper = await mountView()
    await wrapper.findAll('.example')[0].trigger('click')
    await flushPromises()

    const textarea = wrapper.find('textarea')
    expect((textarea.element as HTMLTextAreaElement).value.length).toBeGreaterThan(0)
    expect(mocks.runMusicChat).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('提交后进入任务视图，并显示后端返回的真实工具事件', async () => {
    mocks.runMusicChat.mockImplementation(
      async ({ onEvent }: { onEvent: (event: AgentEvent) => void }) => {
        onEvent({ runId: 'run-1', type: 'run_id', message: '', step: null, timestamp: Date.now() })
        onEvent({
          runId: 'run-1',
          type: 'tool_start',
          message: '正在调用 search_songs 搜索歌曲',
          step: 1,
          timestamp: Date.now(),
        })
        onEvent({ runId: 'run-1', type: 'done', message: '[DONE]', step: 1, timestamp: Date.now() })
      },
    )
    mocks.getRun.mockResolvedValue({
      runId: 'run-1',
      userId: 'music-user-1',
      agentType: 'MUSIC_USER',
      userPrompt: '帮我找一下夜航星的歌',
      state: 'SUCCEEDED',
      currentStep: 2,
      toolCallCount: 1,
      toolTrace: 'search_songs',
      finalAnswer: '夜航星共有 5 首歌。',
      errorMessage: null,
      errorCode: null,
      model: 'qwen-plus',
      promptTokens: 10,
      completionTokens: 20,
      totalTokens: 30,
      startedAt: '2026-11-16T02:00:00',
      finishedAt: '2026-11-16T02:00:10',
      totalCostMillis: 10000,
      createdAt: '2026-11-16T02:00:00',
    })

    const wrapper = await mountView()
    await wrapper.find('textarea').setValue('帮我找一下夜航星的歌')
    await wrapper.find('.composer__send').trigger('click')
    // 提交 → 流结束 → 查询终态是两段异步，需要多刷一次微任务队列
    await flushPromises()
    await flushPromises()

    expect(mocks.runMusicChat).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('正在调用 search_songs')
    expect(wrapper.text()).toContain('夜航星共有 5 首歌')
    wrapper.unmount()
  })
})
