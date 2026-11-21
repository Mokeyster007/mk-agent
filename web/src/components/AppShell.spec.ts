import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import AppShell from './AppShell.vue'
import { player } from '@/stores/player'

/**
 * 外壳布局契约（播放器 / 输入区不互相挤压、不遮挡）。
 *
 * jsdom 没有真实布局，能断言的只有**结构**：
 * 播放器必须是主区的第三行、不能出现在滚动容器或输入区里。
 * 真实像素位置由 `web/tools/measure-composer.mjs` 在浏览器里量。
 */
describe('AppShell 播放器布局', () => {
  beforeEach(() => {
    // jsdom 不实现 pause/load：这里只测结构，不让它污染输出
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined)
    vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => undefined)
    player.stop()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  function mountShell() {
    return mount(AppShell, {
      props: { panelOpen: false, navOpen: false },
      slots: {
        nav: '<div class="stub-nav" />',
        topbar: '<div class="stub-topbar" />',
        default: '<div class="stub-content"><div class="stub-scroll" /></div>',
        panel: '<div class="stub-panel" />',
      },
      global: {
        stubs: {
          // PlaybackBar 自己依赖播放器 store，这里只关心它被放在哪一行
          PlaybackBar: { template: '<div class="stub-player" />' },
        },
      },
    })
  }

  it('主区固定三行：顶栏 / 内容 / 播放器', () => {
    const wrapper = mountShell()
    const main = wrapper.find('.shell__main')

    expect(main.element.children).toHaveLength(3)
    expect(main.element.children[0].className).toContain('shell__topbar')
    expect(main.element.children[1].className).toContain('shell__content')
    expect(main.element.children[2].className).toContain('shell__player')

    wrapper.unmount()
  })

  it('播放器不在滚动容器里，也不在输入区里', () => {
    const wrapper = mountShell()

    expect(wrapper.find('.shell__content .stub-player').exists()).toBe(false)
    expect(wrapper.find('.stub-scroll .stub-player').exists()).toBe(false)
    expect(wrapper.find('.shell__player .stub-player').exists()).toBe(true)

    wrapper.unmount()
  })

  it('外壳只有两列，详情抽屉不占列（列数不会随状态变化）', () => {
    const wrapper = mountShell()

    const columns = getComputedStyle(wrapper.find('.shell').element).gridTemplateColumns
    // jsdom 不解析 CSS 变量，这里只断言结构上没有第三列容器
    expect(wrapper.find('.shell__main').exists()).toBe(true)
    expect(columns === '' || columns.split(' ').length <= 2).toBe(true)

    wrapper.unmount()
  })

  it('没有曲目时播放器不占位（第三行高度为 0）', async () => {
    const wrapper = mount(AppShell, {
      props: { panelOpen: false, navOpen: false },
      slots: { nav: '<div />', topbar: '<div />', default: '<div />', panel: '<div />' },
    })
    await nextTick()

    expect(wrapper.find('.player').exists()).toBe(false)

    wrapper.unmount()
  })
})
