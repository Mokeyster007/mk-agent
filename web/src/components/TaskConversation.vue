<script setup lang="ts">
/**
 * 任务会话区。
 *
 * 展示顺序刻意固定：需求 → 真实状态 → 工具活动 → 审批 → 最终回答 → 错误。
 * 这样用户任何时候都能先看到"我提了什么"，再看"系统做到了哪一步"。
 *
 * 不做的事：
 * - 不做打字机动画（同步入口没有逐字流，伪造流式属于欺骗）；
 * - 不把最终回答的正则解析结果画成歌曲卡片（后端只返回文本）；
 * - 不在流结束时就标成功，成功只认 agent_run.state。
 */
import { computed, onUnmounted, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import TaskStatusBadge from './TaskStatusBadge.vue'
import ToolActivityList from './ToolActivityList.vue'
import ApprovalCard from './ApprovalCard.vue'
import MarkdownView from './MarkdownView.vue'
import ErrorNotice from './ErrorNotice.vue'
import type { SessionTask } from '@/stores/taskStore'
import { isTerminalState } from '@/api/runs'
import { stateMeta, transportMeta } from '@/utils/status'
import { formatClock } from '@/utils/format'

const props = defineProps<{ task: SessionTask }>()

const emit = defineEmits<{
  (e: 'approve'): void
  (e: 'reject'): void
  (e: 'resume'): void
  (e: 'refresh-approval'): void
  (e: 'cancel'): void
  (e: 'stop-waiting'): void
  (e: 'recheck'): void
}>()

const now = ref(Date.now())
const timer = window.setInterval(() => {
  now.value = Date.now()
}, 1000)
onUnmounted(() => window.clearInterval(timer))

const isRunning = computed(
  () => props.task.transport === 'submitting' || props.task.transport === 'streaming',
)

const serverState = computed(() => stateMeta(props.task.serverState))
const transport = computed(() => transportMeta(props.task.transport))

/**
 * 主状态：
 * - 已经拿到后端 state → 显示后端 state（这是唯一权威）；
 * - 还没拿到 → 显示传输层状态，并明确说明"尚未确认"。
 */
const primary = computed(() => {
  if (props.task.serverState) {
    return { label: serverState.value.label, tone: serverState.value.tone }
  }
  return { label: transport.value.label, tone: transport.value.tone }
})

const primaryHint = computed(() => {
  if (props.task.serverState) {
    return serverState.value.hint ?? ''
  }
  if (props.task.transport === 'aborted') {
    return '前端已停止等待，后端任务可能仍在执行。'
  }
  if (props.task.transport === 'submitting') {
    return '请求已发出，等待后端建立事件流。'
  }
  return '还没有拿到后端的任务状态。'
})

const elapsed = computed(() => {
  const end = props.task.finishedAt ?? now.value
  const millis = Math.max(0, end - props.task.createdAt)
  if (millis < 1000) return `${millis} 毫秒`
  if (millis < 60_000) return `${(millis / 1000).toFixed(1)} 秒`
  return `${Math.floor(millis / 60_000)} 分 ${Math.round((millis % 60_000) / 1000)} 秒`
})

const canCancel = computed(
  () =>
    !!props.task.runId &&
    !isTerminalState(props.task.serverState) &&
    props.task.serverState !== null &&
    props.task.transport !== 'aborted',
)

const showAnswer = computed(
  () => !!props.task.answer && props.task.serverState !== 'WAITING_APPROVAL',
)

const isHistoryOnly = computed(
  () => !!props.task.detail && !props.task.events.length && !props.task.prompt,
)

const entryLabel = computed(() => (props.task.entry === 'MUSIC_ADMIN' ? '音乐管理员' : '音乐用户'))
</script>

<template>
  <article class="task">
    <!-- 需求 -->
    <header class="task__head">
      <div class="task__meta">
        <span class="mk-chip">{{ entryLabel }}</span>
        <span class="task__time mk-mono">{{ formatClock(task.createdAt) }}</span>
        <span v-if="task.runId" class="task__runid mk-mono" :title="task.runId">
          run {{ task.runId.slice(0, 8) }}
        </span>
      </div>
      <p v-if="task.prompt" class="task__prompt">{{ task.prompt }}</p>
      <p v-else class="task__prompt task__prompt--muted">
        {{ isHistoryOnly ? '（历史任务，本地没有保存原始输入）' : '（无输入内容）' }}
      </p>
    </header>

    <!-- 状态条 -->
    <div class="task__status">
      <TaskStatusBadge :label="primary.label" :tone="primary.tone" :pulsing="isRunning" />
      <span class="task__status-hint">{{ primaryHint }}</span>
      <span class="task__elapsed mk-mono">{{ elapsed }}</span>
    </div>

    <!-- 工具活动：只渲染真实事件 -->
    <ToolActivityList v-if="task.events.length" :events="task.events" />

    <!-- 审批 -->
    <ApprovalCard
      v-if="task.approvalPayload"
      :payload="task.approvalPayload"
      :approval="task.approval"
      :busy="task.deciding"
      :error="task.approvalError"
      @approve="emit('approve')"
      @reject="emit('reject')"
      @resume="emit('resume')"
      @refresh="emit('refresh-approval')"
    />

    <!-- 最终回答 -->
    <section v-if="showAnswer" class="task__answer">
      <p class="mk-eyebrow task__answer-label">最终回答</p>
      <MarkdownView :source="task.answer" />
    </section>

    <!-- 任务级原因码 -->
    <p v-if="task.detail?.errorCode" class="task__code">
      <AppIcon name="info" :size="14" />
      {{ task.detail.errorCode }}
    </p>

    <!-- 错误 / 未确认 -->
    <ErrorNotice
      v-if="task.error"
      :title="task.error.text"
      :tone="task.error.kind === 'aborted' ? 'warn' : 'danger'"
      :action-label="task.runId ? '核对任务状态' : ''"
      :secondary-label="isRunning ? '停止等待' : ''"
      @action="emit('recheck')"
      @secondary="emit('stop-waiting')"
    />

    <ErrorNotice
      v-else-if="task.detailError"
      :title="task.detailError"
      tone="warn"
      action-label="重新核对"
      @action="emit('recheck')"
    />

    <!-- 操作 -->
    <footer class="task__actions">
      <button
        v-if="isRunning"
        type="button"
        class="mk-btn mk-btn--sm"
        @click="emit('stop-waiting')"
      >
        <AppIcon name="stop" :size="13" />
        停止等待
      </button>
      <button
        v-if="canCancel"
        type="button"
        class="mk-btn mk-btn--sm"
        @click="emit('cancel')"
      >
        取消任务
      </button>
      <button
        v-if="!isRunning && task.runId"
        type="button"
        class="mk-btn mk-btn--sm mk-btn--ghost"
        :disabled="task.verifying"
        @click="emit('recheck')"
      >
        <AppIcon name="refresh" :size="13" />
        {{ task.verifying ? '核对中…' : '核对状态' }}
      </button>
      <span v-if="isRunning" class="task__action-note">
        停止等待只关闭前端连接，不会取消后端任务。
      </span>
    </footer>
  </article>
</template>

<style scoped>
.task {
  display: grid;
  gap: var(--s-4);
  padding: var(--s-5) 0 var(--s-2);
  animation: fade var(--dur-slow) var(--ease) both;
}

@keyframes fade {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.task__head {
  display: grid;
  gap: var(--s-3);
}

.task__meta {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
}

.task__time,
.task__runid {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.task__runid {
  padding: 1px 6px;
  border-radius: var(--r-xs);
  border: 1px solid var(--c-line);
  background: var(--c-bg-soft);
}

.task__prompt {
  font-size: var(--fs-lg);
  line-height: var(--lh-tight);
  font-weight: 500;
  color: var(--c-text);
  overflow-wrap: anywhere;
}

.task__prompt--muted {
  font-size: var(--fs-base);
  font-weight: 400;
  color: var(--c-text-3);
}

.task__status {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  flex-wrap: wrap;
  padding: var(--s-2) 0;
  border-top: 1px solid var(--c-line-soft);
  border-bottom: 1px solid var(--c-line-soft);
}

.task__status-hint {
  font-size: var(--fs-sm);
  color: var(--c-text-3);
  flex: 1;
  min-width: 0;
}

.task__elapsed {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.task__answer {
  padding: var(--s-4);
  border-radius: var(--r-lg);
  border: 1px solid var(--c-line);
  background: var(--c-surface);
}

.task__answer-label {
  margin-bottom: var(--s-2);
}

.task__code {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  color: var(--c-text-3);
  font-family: var(--font-mono);
}

.task__actions {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
}

.task__action-note {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}
</style>
