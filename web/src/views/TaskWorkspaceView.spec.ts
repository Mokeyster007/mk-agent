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
  listConversations: vi.fn(),
  createConversation: vi.fn(),
  getConversation: vi.fn(),
  getPlaybackCandidates: vi.fn(),
}))

vi.mock('@/api/music', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/music')>()
  return { ...actual, runMusicChat: mocks.runMusicChat }
})

/**
 * 会话接口也在这里挡掉。
 *
 * 工作区现在"发消息前先确保有会话"，不挡的话测试会真的去建会话，
 * 断言就变成在测网络而不是在测界面。
 */
vi.mock('@/api/conversations', () => ({
  listConversations: mocks.listConversations,
  createConversation: mocks.createConversation,
  getConversation: mocks.getConversation,
}))

/**
 * 播放候选接口也挡掉：本文件只测布局与提交链路，
 * 不让它去真打 /playback/candidates。
 */
vi.mock('@/api/playback', () => ({
  getPlaybackCandidates: mocks.getPlaybackCandidates,
  resolvePlayback: vi.fn(),
  reportNowPlaying: vi.fn(),
}))

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
      // 顶栏的「商城」入口指向 /shop；测试 router 里必须有这条路由，
      // 否则 vue-router 会打 "No match found" 警告（不影响断言，但污染输出）。
      { path: '/shop', component: { template: '<div />' } },
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
  mocks.listConversations.mockResolvedValue([])
  mocks.createConversation.mockResolvedValue({ conversationId: 'conv-test' })
  mocks.getConversation.mockResolvedValue({
    conversationId: 'conv-test',
    title: null,
    messages: [],
    updatedAt: null,
  })
  mocks.getPlaybackCandidates.mockResolvedValue([])
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

  it('空状态给出 3 个快捷入口', async () => {
    const wrapper = await mountView()
    const examples = wrapper.findAll('.example')

    expect(examples).toHaveLength(3)
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

  it('输入区不在消息滚动容器里（结构上保证切换会话不移动输入框）', async () => {
    const wrapper = await mountView()

    expect(wrapper.find('.workspace__composer').exists()).toBe(true)
    expect(wrapper.find('.workspace__scroll .workspace__composer').exists()).toBe(false)
    expect(wrapper.find('.workspace__scroll textarea').exists()).toBe(false)
    // 输入区与消息区是同一层的两行
    expect(wrapper.find('.workspace').element.children.length).toBe(2)

    wrapper.unmount()
  })

  it('播放器不在消息滚动区与输入区里（不遮挡最后一条消息与审批按钮）', async () => {
    const wrapper = await mountView()

    expect(wrapper.find('.workspace__scroll .player').exists()).toBe(false)
    expect(wrapper.find('.workspace__composer .player').exists()).toBe(false)
    expect(wrapper.find('.shell__player').exists()).toBe(true)

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
    expect(wrapper.text()).toContain('工具 1 步')
    expect(wrapper.text()).toContain('夜航星共有 5 首歌')

    // 工具进度默认折叠成一行，展开后能看到后端推送的真实事件
    await wrapper.find('.turn__tools').trigger('click')
    expect(wrapper.text()).toContain('正在调用 search_songs')
    wrapper.unmount()
  })
})

/**
 * 输入区稳定性回归。
 *
 * 浏览器里的像素级测量见 tools/measure-composer.mjs；
 * 这里守的是**结构契约**——结构一变，像素必然变：
 * 1. 输入区不能挂在消息滚动容器里面（否则会跟着内容滚走/被挤压）；
 * 2. 工作区只有"消息区 + 输入区"两行，输入区不在消息列表或某一轮节点上；
 * 3. 切换会话不重新挂载输入框（否则焦点、草稿、高度都会重置）。
 */
describe('TaskWorkspaceView（输入区结构）', () => {
  it('输入区与消息滚动区是兄弟节点，不在滚动容器内', async () => {
    const wrapper = await mountView()
    const scroll = wrapper.find('.workspace__scroll')
    const composer = wrapper.find('.workspace__composer')

    expect(scroll.exists()).toBe(true)
    expect(composer.exists()).toBe(true)
    expect(scroll.element.contains(composer.element)).toBe(false)
    wrapper.unmount()
  })

  it('工作区只有消息区与输入区两个直接子节点', async () => {
    const wrapper = await mountView()
    const workspace = wrapper.find('.workspace')

    expect(workspace.element.children).toHaveLength(2)
    expect(workspace.element.children[0].className).toContain('workspace__stage')
    expect(workspace.element.children[1].className).toContain('workspace__composer')
    wrapper.unmount()
  })

  it('切换会话不会重新挂载输入框', async () => {
    mocks.listConversations.mockResolvedValue([
      {
        conversationId: 'conv-2',
        title: '另一段对话',
        messageCount: 2,
        lastState: 'SUCCEEDED',
        updatedAt: '2026-11-18T10:00:00',
      },
    ])
    const wrapper = await mountView()
    const before = wrapper.find('textarea').element

    const item = wrapper.findAll('.nav__item')[0]
    expect(item.exists()).toBe(true)
    await item.trigger('click')
    await flushPromises()

    expect(wrapper.find('textarea').element).toBe(before)
    wrapper.unmount()
  })
})
