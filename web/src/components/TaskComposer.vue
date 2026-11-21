<script setup lang="ts">
/**
 * 任务输入框。
 *
 * 只负责"收集一句话 + 发出一次提交意图"，不做请求。
 * 防重复提交有三层：
 * 1. 组件内 submitting 锁（点击后立即置位，不等父组件）；
 * 2. 按钮 disabled；
 * 3. store 里的 isBusy 守卫。
 *
 * 输入法：组字中的回车是选词，不是发送（isComposing + composition 事件双保险）。
 * 高度：随内容向上扩展，到上限后在 textarea 内部滚动，不影响底部位置。
 */
import { nextTick, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import type { MusicEntry } from '@/api/types'

const props = defineProps<{
  disabled: boolean
  busy: boolean
  entry: MusicEntry
  showEntrySwitch: boolean
  placeholder: string
}>()

const emit = defineEmits<{
  (e: 'submit', prompt: string): void
  (e: 'entry', value: MusicEntry): void
}>()

/** 与 CSS 的 --composer-max-h 保持一致 */
const MAX_HEIGHT = 200

const text = ref('')
const composing = ref(false)
const submitting = ref(false)
const textarea = ref<HTMLTextAreaElement | null>(null)

function resize(): void {
  const el = textarea.value
  if (!el) {
    return
  }
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, MAX_HEIGHT)}px`
}

function send(): void {
  const value = text.value.trim()
  if (!value || props.busy || props.disabled || submitting.value) {
    return
  }
  submitting.value = true
  emit('submit', value)
  text.value = ''
  void nextTick(() => {
    resize()
    submitting.value = false
  })
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.shiftKey) {
    return
  }
  // 输入法组字期间的回车用于选词，不能当提交
  if (composing.value || event.isComposing) {
    return
  }
  event.preventDefault()
  send()
}

function onInput(): void {
  resize()
}

/** 供空状态示例回填 */
function fill(prompt: string): void {
  text.value = prompt
  void nextTick(() => {
    resize()
    textarea.value?.focus()
  })
}

defineExpose({ fill })
</script>

<template>
  <div class="composer" :class="{ 'composer--busy': busy }">
    <div v-if="showEntrySwitch" class="composer__entry">
      <span class="composer__entry-label">入口</span>
      <div class="segmented" role="group" aria-label="Agent 入口">
        <button
          type="button"
          class="segmented__item"
          :class="{ 'segmented__item--on': entry === 'MUSIC_USER' }"
          :aria-pressed="entry === 'MUSIC_USER'"
          @click="emit('entry', 'MUSIC_USER')"
        >
          音乐用户
        </button>
        <button
          type="button"
          class="segmented__item"
          :class="{ 'segmented__item--on': entry === 'MUSIC_ADMIN' }"
          :aria-pressed="entry === 'MUSIC_ADMIN'"
          @click="emit('entry', 'MUSIC_ADMIN')"
        >
          音乐管理员
        </button>
      </div>
      <span class="composer__entry-hint">开发联调，权限仍由后端校验</span>
    </div>

    <div class="composer__box">
      <textarea
        ref="textarea"
        v-model="text"
        class="composer__input"
        rows="1"
        :placeholder="placeholder"
        :disabled="disabled"
        aria-label="任务描述"
        @input="onInput"
        @keydown="onKeydown"
        @compositionstart="composing = true"
        @compositionend="composing = false"
      />
      <div class="composer__bar">
        <span class="composer__hint">
          <kbd>Enter</kbd> 发送 · <kbd>Shift</kbd>+<kbd>Enter</kbd> 换行
        </span>
        <button
          type="button"
          class="composer__send"
          :disabled="!text.trim() || busy || disabled || submitting"
          :aria-label="busy ? '任务执行中' : '发送任务'"
          @click="send"
        >
          <AppIcon :name="busy ? 'clock' : 'send'" :size="15" />
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.composer {
  width: 100%;
}

.composer__entry {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  margin-bottom: var(--s-2);
  flex-wrap: wrap;
}

.composer__entry-label {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.composer__entry-hint {
  font-size: 10.5px;
  color: var(--c-text-3);
}

.segmented {
  display: inline-flex;
  padding: 2px;
  border-radius: var(--r-sm);
  background: var(--c-bg-soft);
  border: 1px solid var(--c-line);
}

.segmented__item {
  height: 22px;
  padding: 0 9px;
  border-radius: 6px;
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.segmented__item:hover {
  color: var(--c-text-2);
}

.segmented__item--on {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.composer__box {
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-surface);
  padding: var(--s-2) var(--s-3) var(--s-2);
  transition: border-color var(--dur) var(--ease), background var(--dur) var(--ease);
}

.composer__box:focus-within {
  border-color: var(--c-accent-line);
  background: var(--c-surface-2);
}

.composer--busy .composer__box {
  border-color: var(--c-line-strong);
}

.composer__input {
  display: block;
  width: 100%;
  min-height: 26px;
  max-height: var(--composer-max-h);
  resize: none;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--c-text);
  font-size: var(--fs-base);
  line-height: var(--lh-base);
  padding: 4px 0 0;
  overflow-y: auto;
}

.composer__input::placeholder {
  color: var(--c-text-3);
}

.composer__input:disabled {
  opacity: 0.6;
}

.composer__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  margin-top: var(--s-1);
}

.composer__hint {
  font-size: 10.5px;
  color: var(--c-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

kbd {
  font-family: var(--font-sans);
  font-size: 10px;
  padding: 1px 4px;
  border-radius: 4px;
  border: 1px solid var(--c-line);
  background: var(--c-bg-soft);
  color: var(--c-text-3);
}

.composer__send {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: var(--r-sm);
  background: var(--c-accent);
  color: var(--c-text-inverse);
  transition: background var(--dur-fast) var(--ease), opacity var(--dur-fast) var(--ease);
}

.composer__send:hover:not(:disabled) {
  background: var(--c-accent-strong);
}

.composer__send:disabled {
  background: var(--c-surface-3);
  color: var(--c-text-3);
}

@media (max-width: 560px) {
  .composer__hint {
    display: none;
  }

  .composer__entry-hint {
    display: none;
  }

  .composer__input {
    font-size: 16px; /* 防止 iOS 聚焦时自动放大页面 */
  }

  .composer__bar {
    justify-content: flex-end;
  }
}
</style>
