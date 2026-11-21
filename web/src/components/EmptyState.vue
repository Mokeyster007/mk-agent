<script setup lang="ts">
/**
 * 空状态。
 *
 * 只做三件事：说清当前能力、给三个能直接用的入口、把焦点留给输入框。
 * 不做营销式大标题、不做三列功能卡、不自动触发任务——
 * 这是高频使用的工作台首页，不是落地页。
 *
 * 第三个入口「查看我的歌单」是本地抽屉，不经过模型：
 * 数据本来就在库里，让模型转述一遍既慢又不可信。
 */
import AppIcon from './AppIcon.vue'
import { QUICK_ENTRIES, type QuickEntry } from '@/content/examples'

const props = defineProps<{
  mode: 'readonly' | 'write_approval'
  capabilityText: string
}>()

const emit = defineEmits<{
  (e: 'pick', prompt: string): void
  (e: 'open-library'): void
}>()

function onPick(entry: QuickEntry): void {
  if (entry.action === 'library') {
    emit('open-library')
    return
  }
  emit('pick', entry.prompt)
}
</script>

<template>
  <div class="empty">
    <span class="empty__mark" aria-hidden="true">
      <AppIcon name="wave" :size="20" />
    </span>

    <h1 class="empty__title">今天想查什么？</h1>
    <p class="empty__capability">{{ props.capabilityText }}</p>

    <div class="empty__quick">
      <button
        v-for="entry in QUICK_ENTRIES"
        :key="entry.title"
        type="button"
        class="example"
        @click="onPick(entry)"
      >
        <AppIcon :name="entry.icon" :size="14" />
        <span>{{ entry.title }}</span>
      </button>
    </div>

    <p class="empty__note">入口只填入输入框，不会自动执行；写操作仍需你在审批卡上确认。</p>
  </div>
</template>

<style scoped>
.empty {
  max-width: 560px;
  margin: 0 auto;
  padding: clamp(32px, 9vh, 96px) 0 var(--s-6);
}

.empty__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: var(--r-sm);
  background: var(--c-accent-weak);
  border: 1px solid var(--c-accent-line);
  color: var(--c-accent);
  margin-bottom: var(--s-4);
}

.empty__title {
  font-size: var(--fs-xl);
  font-weight: 600;
  letter-spacing: -0.01em;
  line-height: var(--lh-tight);
  color: var(--c-text);
}

.empty__capability {
  margin-top: var(--s-2);
  max-width: 46ch;
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
  color: var(--c-text-2);
}

.empty__quick {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin-top: var(--s-6);
}

.example {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 var(--s-3);
  border-radius: var(--r-sm);
  border: 1px solid var(--c-line);
  background: var(--c-surface);
  color: var(--c-text-2);
  font-size: var(--fs-sm);
  white-space: nowrap;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease),
    color var(--dur-fast) var(--ease);
}

.example:hover {
  background: var(--c-surface-2);
  border-color: var(--c-line-strong);
  color: var(--c-text);
}

.example:active {
  transform: translateY(1px);
}

.empty__note {
  margin-top: var(--s-5);
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  color: var(--c-text-3);
}

@media (max-width: 560px) {
  .empty__quick {
    gap: 6px;
  }

  .example {
    font-size: var(--fs-xs);
    padding: 0 var(--s-2);
  }
}
</style>
