<script setup lang="ts">
/**
 * 状态徽标。
 *
 * 只负责"把给定的 tone + label 画出来"，
 * 不做任何状态推断——推断集中在 utils/status.ts，避免同一状态在多处翻译成不同说法。
 */
import type { Tone } from '@/utils/status'

withDefaults(
  defineProps<{
    label: string
    tone?: Tone
    /** 执行中的状态点会呼吸，其余静止 */
    pulsing?: boolean
    size?: 'sm' | 'md'
  }>(),
  { tone: 'neutral', pulsing: false, size: 'md' },
)
</script>

<template>
  <span class="badge" :class="[`badge--${tone}`, `badge--${size}`]">
    <span class="badge__dot" :class="{ 'badge__dot--pulse': pulsing }" />
    {{ label }}
  </span>
</template>

<style scoped>
.badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: var(--r-pill);
  border: 1px solid var(--c-line);
  background: var(--c-surface-2);
  color: var(--c-text-2);
  font-weight: 500;
  white-space: nowrap;
}

.badge--md {
  height: 24px;
  padding: 0 10px;
  font-size: var(--fs-xs);
}

.badge--sm {
  height: 20px;
  padding: 0 8px;
  font-size: 10.5px;
}

.badge__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}

.badge__dot--pulse {
  animation: pulse 1.6s var(--ease) infinite;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.35;
    transform: scale(0.85);
  }
  50% {
    opacity: 1;
    transform: scale(1);
  }
}

.badge--active {
  color: var(--c-accent-ink);
  border-color: var(--c-accent-line);
  background: var(--c-accent-weak);
}

.badge--wait {
  color: var(--c-warn);
  border-color: rgba(224, 177, 95, 0.3);
  background: var(--c-warn-weak);
}

.badge--ok {
  color: var(--c-ok);
  border-color: rgba(116, 207, 169, 0.28);
  background: var(--c-ok-weak);
}

.badge--warn {
  color: var(--c-warn);
  border-color: rgba(224, 177, 95, 0.26);
  background: var(--c-warn-weak);
}

.badge--danger {
  color: var(--c-danger);
  border-color: rgba(226, 131, 122, 0.3);
  background: var(--c-danger-weak);
}

.badge--neutral {
  color: var(--c-text-2);
}
</style>
