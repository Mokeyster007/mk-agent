<script setup lang="ts">
/**
 * 错误提示。
 *
 * 三条规则：
 * 1. 只显示已经脱敏的文案（由 api/errors.ts 生成），不渲染后端原始 message 里的堆栈；
 * 2. "可重试"要区分对象：状态查询可以重试，执行类请求不自动重试；
 * 3. 不把错误伪装成成功，也不把网络错误说成服务端失败。
 */
import AppIcon from './AppIcon.vue'
import type { Tone } from '@/utils/status'

withDefaults(
  defineProps<{
    title: string
    detail?: string
    tone?: Tone
    /** 可选的恢复动作文案；为空则不显示按钮 */
    actionLabel?: string
    secondaryLabel?: string
  }>(),
  { detail: '', tone: 'warn', actionLabel: '', secondaryLabel: '' },
)

defineEmits<{
  (e: 'action'): void
  (e: 'secondary'): void
}>()
</script>

<template>
  <div class="notice" :class="`notice--${tone}`" role="alert">
    <span class="notice__icon">
      <AppIcon :name="tone === 'danger' ? 'alert' : 'info'" :size="16" />
    </span>
    <div class="notice__body">
      <p class="notice__title">{{ title }}</p>
      <p v-if="detail" class="notice__detail">{{ detail }}</p>
      <div v-if="actionLabel || secondaryLabel" class="notice__actions">
        <button v-if="actionLabel" type="button" class="mk-btn mk-btn--sm" @click="$emit('action')">
          {{ actionLabel }}
        </button>
        <button
          v-if="secondaryLabel"
          type="button"
          class="mk-btn mk-btn--sm mk-btn--ghost"
          @click="$emit('secondary')"
        >
          {{ secondaryLabel }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.notice {
  display: flex;
  gap: var(--s-3);
  padding: var(--s-3) var(--s-4);
  border-radius: var(--r-md);
  border: 1px solid var(--c-line);
  background: var(--c-surface-2);
}

.notice--warn {
  border-color: rgba(226, 180, 94, 0.26);
  background: rgba(226, 180, 94, 0.07);
}

.notice--danger {
  border-color: rgba(226, 121, 111, 0.3);
  background: rgba(226, 121, 111, 0.07);
}

.notice--neutral {
  border-color: var(--c-line);
}

.notice--ok {
  border-color: rgba(78, 194, 160, 0.26);
  background: rgba(78, 194, 160, 0.06);
}

.notice__icon {
  flex: none;
  margin-top: 2px;
  color: var(--c-warn);
  display: inline-flex;
}

.notice--danger .notice__icon {
  color: var(--c-danger);
}

.notice--neutral .notice__icon {
  color: var(--c-text-3);
}

.notice--ok .notice__icon {
  color: var(--c-ok);
}

.notice__body {
  min-width: 0;
  flex: 1;
}

.notice__title {
  font-size: var(--fs-base);
  font-weight: 500;
  color: var(--c-text);
  line-height: var(--lh-tight);
}

.notice__detail {
  margin-top: 4px;
  font-size: var(--fs-sm);
  color: var(--c-text-2);
  line-height: var(--lh-base);
  overflow-wrap: anywhere;
}

.notice__actions {
  display: flex;
  gap: var(--s-2);
  margin-top: var(--s-3);
  flex-wrap: wrap;
}
</style>
