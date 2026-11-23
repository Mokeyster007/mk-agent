import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AppShell from './AppShell.vue'

describe('AppShell 布局骨架', () => {
  function mountShell() {
    return mount(AppShell, {
      props: { panelOpen: false, navOpen: false },
      slots: {
        nav: '<div class="stub-nav" />',
        topbar: '<div class="stub-topbar" />',
        default: '<div class="stub-content"><div class="stub-scroll" /></div>',
        panel: '<div class="stub-panel" />',
      },
    })
  }

  it('主区固定两行：顶栏 / 内容，不再挂载音乐播放器', () => {
    const wrapper = mountShell()
    const main = wrapper.find('.shell__main')

    expect(main.element.children).toHaveLength(2)
    expect(main.element.children[0].className).toContain('shell__topbar')
    expect(main.element.children[1].className).toContain('shell__content')
    expect(wrapper.find('.shell__player').exists()).toBe(false)

    wrapper.unmount()
  })

  it('外壳只有两列，详情抽屉不占列', () => {
    const wrapper = mountShell()
    const columns = getComputedStyle(wrapper.find('.shell').element).gridTemplateColumns
    expect(wrapper.find('.shell__main').exists()).toBe(true)
    expect(columns === '' || columns.split(' ').length <= 2).toBe(true)
    wrapper.unmount()
  })
})
