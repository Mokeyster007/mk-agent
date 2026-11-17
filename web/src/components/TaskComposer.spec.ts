import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskComposer from './TaskComposer.vue'

/**
 * 输入框测试。
 *
 * 最关键的一条：**一次点击只能产生一次提交**。
 * 这是"用户点一下、任务建了两个"这类事故的唯一防线。
 */
function mountComposer(overrides: Partial<{ busy: boolean; disabled: boolean }> = {}) {
  return mount(TaskComposer, {
    props: {
      disabled: overrides.disabled ?? false,
      busy: overrides.busy ?? false,
      entry: 'MUSIC_USER' as const,
      showEntrySwitch: false,
      placeholder: '描述你的音乐需求',
    },
  })
}

describe('TaskComposer', () => {
  it('点击发送只触发一次 submit', async () => {
    const wrapper = mountComposer()
    await wrapper.find('textarea').setValue('帮我找一下夜航星的歌')

    await wrapper.find('.composer__send').trigger('click')

    const events = wrapper.emitted('submit')
    expect(events).toHaveLength(1)
    expect(events?.[0]).toEqual(['帮我找一下夜航星的歌'])
  })

  it('连续点击两次也只触发一次（第二次已无内容）', async () => {
    const wrapper = mountComposer()
    await wrapper.find('textarea').setValue('我有哪些歌单？')

    const button = wrapper.find('.composer__send')
    await button.trigger('click')
    await button.trigger('click')

    expect(wrapper.emitted('submit')).toHaveLength(1)
  })

  it('回车发送', async () => {
    const wrapper = mountComposer()
    await wrapper.find('textarea').setValue('查一下《城市霓虹》')

    await wrapper.find('textarea').trigger('keydown', { key: 'Enter' })

    expect(wrapper.emitted('submit')).toHaveLength(1)
  })

  it('Shift+Enter 换行，不发送', async () => {
    const wrapper = mountComposer()
    await wrapper.find('textarea').setValue('第一行')

    await wrapper.find('textarea').trigger('keydown', { key: 'Enter', shiftKey: true })

    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('输入法组字中的回车不算发送', async () => {
    const wrapper = mountComposer()
    const textarea = wrapper.find('textarea')
    await textarea.setValue('yehangxing')

    await textarea.trigger('compositionstart')
    await textarea.trigger('keydown', { key: 'Enter' })

    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('任务执行中按钮禁用，不产生提交', async () => {
    const wrapper = mountComposer({ busy: true })
    await wrapper.find('textarea').setValue('再查一个')

    await wrapper.find('.composer__send').trigger('click')

    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.find('.composer__send').attributes('disabled')).toBeDefined()
  })

  it('空白内容不提交', async () => {
    const wrapper = mountComposer()
    await wrapper.find('textarea').setValue('   ')

    await wrapper.find('.composer__send').trigger('click')

    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('fill() 只回填文本，不触发提交', async () => {
    const wrapper = mountComposer()

    wrapper.vm.fill('「深夜通勤」里都有哪些歌？')
    await wrapper.vm.$nextTick()

    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe(
      '「深夜通勤」里都有哪些歌？',
    )
    expect(wrapper.emitted('submit')).toBeUndefined()
  })
})
