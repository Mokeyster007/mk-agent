<script setup lang="ts">
/**
 * 空状态。
 *
 * 三件事：说明当前真实能力、给出与能力匹配的示例、把输入焦点交回输入框。
 * 不做营销式的大标题和插画堆砌——这里是一个工作台，不是落地页。
 */
import AppIcon from './AppIcon.vue'
import { examplesFor, type ExampleTask } from '@/content/examples'

const props = defineProps<{
  mode: 'readonly' | 'write_approval'
  capabilityText: string
}>()

defineEmits<{
  (e: 'pick', prompt: string): void
}>()

const examples = (): ExampleTask[] => examplesFor(props.mode)
</script>

<template>
  <div class="empty">
    <div class="empty__mark" aria-hidden="true">
      <svg viewBox="0 0 64 64" width="52" height="52" fill="none">
        <circle cx="32" cy="32" r="21" stroke="currentColor" stroke-opacity="0.28" />
        <circle cx="32" cy="32" r="13" stroke="currentColor" stroke-opacity="0.5" />
        <circle cx="32" cy="32" r="4" fill="currentColor" fill-opacity="0.85" />
        <path
          d="M32 11v6M32 47v6M11 32h6M47 32h6"
          stroke="currentColor"
          stroke-opacity="0.4"
          stroke-linecap="round"
        />
      </svg>
    </div>

    <h1 class="empty__title">开始一个音乐任务</h1>
    <p class="empty__capability">{{ capabilityText }}</p>

    <div class="empty__examples">
      <p class="mk-eyebrow empty__label">可以试试</p>
      <ul class="empty__list">
        <li v-for="example in examples()" :key="example.title">
          <button type="button" class="example" @click="$emit('pick', example.prompt)">
            <span class="example__icon">
              <AppIcon :name="example.icon" :size="16" />
            </span>
            <span class="example__text">
              <span class="example__title">{{ example.title }}</span>
              <span class="example__prompt">{{ example.prompt }}</span>
            </span>
            <span class="example__arrow">
              <AppIcon name="chevron-right" :size="15" />
            </span>
          </button>
        </li>
      </ul>
    </div>

    <p class="empty__note">
      <AppIcon name="info" :size="14" />
      每次提交都是一个独立任务，不会沿用上一次的上下文。
    </p>
  </div>
</template>

<style scoped>
.empty {
  max-width: 620px;
  margin: 0 auto;
  padding: clamp(28px, 7vh, 72px) var(--s-2) var(--s-6);
  animation: rise var(--dur-slow) var(--ease) both;
}

@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.empty__mark {
  color: var(--c-accent);
  margin-bottom: var(--s-5);
  opacity: 0.9;
}

.empty__title {
  font-size: var(--fs-2xl);
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.25;
}

.empty__capability {
  margin-top: var(--s-3);
  max-width: 54ch;
  font-size: var(--fs-base);
  line-height: var(--lh-relaxed);
  color: var(--c-text-2);
}

.empty__examples {
  margin-top: var(--s-8);
}

.empty__label {
  margin-bottom: var(--s-3);
}

.empty__list {
  display: grid;
  gap: var(--s-2);
}

.example {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  width: 100%;
  padding: var(--s-3) var(--s-4);
  text-align: left;
  border-radius: var(--r-md);
  border: 1px solid var(--c-line);
  background: var(--c-surface);
  transition: border-color var(--dur-fast) var(--ease),
    background var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}

.example:hover {
  border-color: var(--c-line-strong);
  background: var(--c-surface-2);
}

.example:active {
  transform: translateY(1px);
}

.example__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: var(--r-sm);
  background: var(--c-accent-weak);
  color: var(--c-accent-ink);
}

.example__text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  flex: 1;
}

.example__title {
  font-size: var(--fs-base);
  font-weight: 500;
  color: var(--c-text);
}

.example__prompt {
  font-size: var(--fs-sm);
  color: var(--c-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.example__arrow {
  color: var(--c-text-3);
  display: inline-flex;
  opacity: 0;
  transform: translateX(-3px);
  transition: opacity var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}

.example:hover .example__arrow {
  opacity: 1;
  transform: none;
}

.empty__note {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: var(--s-6);
  font-size: var(--fs-sm);
  color: var(--c-text-3);
}

@media (max-width: 560px) {
  .empty__prompt {
    display: none;
  }

  .example {
    padding: var(--s-3);
  }
}
</style>
