import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import MarkdownView from './MarkdownView.vue'

/**
 * Markdown 安全测试。
 *
 * 模型输出是不可信内容，可能带脚本、事件属性、javascript: 链接。
 * 这三类都必须被拦住，且不能拦住正常的排版。
 */
function render(source: string) {
  return mount(MarkdownView, { props: { source } })
}

describe('MarkdownView', () => {
  it('渲染基础排版', () => {
    const wrapper = render('**粗体** 与 `代码`')
    expect(wrapper.html()).toContain('<strong>粗体</strong>')
    expect(wrapper.html()).toContain('<code>代码</code>')
  })

  it('script 标签不会被执行，也不出现在 DOM 里', () => {
    const wrapper = render('正常文本 <script>window.__pwned = 1</script>')
    expect(wrapper.find('script').exists()).toBe(false)
    expect((window as unknown as Record<string, unknown>).__pwned).toBeUndefined()
  })

  it('img onerror 被当作纯文本，不生成 img 元素', () => {
    const wrapper = render('<img src=x onerror="window.__pwned2=1">')
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('javascript: 链接不会被渲染成可点击链接', () => {
    const wrapper = render('[点我](javascript:alert(1))')

    // 安全属性是"不存在带 javascript: 的 href"，
    // 而不是"页面上看不到这串字"——它可能作为纯文本被安全地展示出来。
    const anchors = wrapper.findAll('a')
    for (const anchor of anchors) {
      expect(anchor.attributes('href') ?? '').not.toContain('javascript:')
    }
  })

  it('外链补 target=_blank 与 rel=noopener', () => {
    const wrapper = render('[文档](https://example.com/doc)')
    const anchor = wrapper.find('a')

    expect(anchor.attributes('target')).toBe('_blank')
    expect(anchor.attributes('rel')).toContain('noopener')
    expect(anchor.attributes('rel')).toContain('noreferrer')
  })

  it('保留换行（模型常按行输出清单）', () => {
    const wrapper = render('第一行\n第二行')
    expect(wrapper.html()).toContain('<br>')
  })
})
