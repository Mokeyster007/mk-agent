<script setup lang="ts">
/**
 * 安全 Markdown 渲染。
 *
 * 三道防线：
 * 1. markdown-it 关掉 html，原始 HTML 一律当纯文本，不解析；
 * 2. DOMPurify 再做一次白名单过滤（标签、属性都收窄）；
 * 3. 外链统一加 target=_blank + rel=noopener noreferrer nofollow，
 *    防止 window.opener 反向控制与权重传递。
 *
 * 模型输出属于不可信内容：它可能带 <script>、onerror、javascript: 链接。
 */
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const props = defineProps<{ source: string }>()

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  typographer: false,
})

const defaultLinkOpen =
  md.renderer.rules.link_open ??
  ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  token.attrSet('target', '_blank')
  token.attrSet('rel', 'noopener noreferrer nofollow')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

const ALLOWED_TAGS = [
  'p',
  'br',
  'strong',
  'em',
  'del',
  'code',
  'pre',
  'blockquote',
  'ul',
  'ol',
  'li',
  'a',
  'hr',
  'h1',
  'h2',
  'h3',
  'h4',
  'h5',
  'h6',
  'table',
  'thead',
  'tbody',
  'tr',
  'th',
  'td',
]

const html = computed(() => {
  const raw = md.render(props.source ?? '')
  return DOMPurify.sanitize(raw, {
    ALLOWED_TAGS,
    ALLOWED_ATTR: ['href', 'title', 'target', 'rel'],
    ALLOW_DATA_ATTR: false,
    FORBID_TAGS: ['style', 'script', 'iframe', 'object', 'embed', 'form', 'input', 'svg'],
    FORBID_ATTR: ['style', 'onerror', 'onload', 'onclick'],
  })
})
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div class="markdown" v-html="html" />
</template>

<style scoped>
.markdown {
  font-size: var(--fs-md);
  line-height: var(--lh-relaxed);
  color: var(--c-text);
  overflow-wrap: anywhere;
}

.markdown :deep(p) {
  margin: 0 0 var(--s-3);
}

.markdown :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown :deep(ul),
.markdown :deep(ol) {
  margin: 0 0 var(--s-3);
  padding-left: var(--s-5);
}

.markdown :deep(ul) {
  list-style: disc;
}

.markdown :deep(ol) {
  list-style: decimal;
}

.markdown :deep(li) {
  margin: 2px 0;
}

.markdown :deep(li::marker) {
  color: var(--c-text-3);
}

.markdown :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.9em;
  padding: 1px 5px;
  border-radius: var(--r-xs);
  background: var(--c-surface-3);
  color: var(--c-text);
}

.markdown :deep(pre) {
  margin: 0 0 var(--s-3);
  padding: var(--s-3) var(--s-4);
  border-radius: var(--r-sm);
  border: 1px solid var(--c-line);
  background: var(--c-bg-soft);
  overflow-x: auto;
}

.markdown :deep(pre code) {
  padding: 0;
  background: none;
}

.markdown :deep(blockquote) {
  margin: 0 0 var(--s-3);
  padding: 2px 0 2px var(--s-4);
  border-left: 2px solid var(--c-line-strong);
  color: var(--c-text-2);
}

.markdown :deep(a) {
  color: var(--c-accent-ink);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.markdown :deep(h1),
.markdown :deep(h2),
.markdown :deep(h3),
.markdown :deep(h4) {
  margin: var(--s-4) 0 var(--s-2);
  font-size: var(--fs-md);
  font-weight: 600;
  line-height: var(--lh-tight);
}

.markdown :deep(h1:first-child),
.markdown :deep(h2:first-child),
.markdown :deep(h3:first-child) {
  margin-top: 0;
}

.markdown :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 0 0 var(--s-3);
  font-size: var(--fs-sm);
}

.markdown :deep(th),
.markdown :deep(td) {
  border: 1px solid var(--c-line);
  padding: 6px 10px;
  text-align: left;
}

.markdown :deep(th) {
  background: var(--c-surface-2);
  font-weight: 600;
}

.markdown :deep(hr) {
  border: 0;
  height: 1px;
  background: var(--c-line);
  margin: var(--s-4) 0;
}
</style>
