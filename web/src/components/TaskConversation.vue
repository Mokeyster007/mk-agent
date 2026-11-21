<script setup lang="ts">
/**
 * 一轮对话：用户气泡 + 助手正文。
 *
 * 展示顺序固定：需求 → 真实状态 → 工具活动（折叠）→ 审批 → 最终回答 → 错误。
 *
 * 不做的事：
 * - 不做打字机动画（同步入口没有逐字流，伪造流式属于欺骗）；
 * - 不把最终回答的正则解析结果画成歌曲卡片（后端只返回文本）；
 * - 不在流结束时就标成功，成功只认 agent_run.state；
 * - 不在对话里展示 runId / token —— 那是任务详情抽屉的内容。
 */
import { computed, ref } from 'vue'
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

/**
 * 成功是默认结果，不再重复一遍"已完成"；其余状态必须显示。
 * 已经有审批卡时，卡片自己带状态，这里不再重复。
 */
const showStatus = computed(
  () =>
    !!props.task.serverState &&
    props.task.serverState !== 'SUCCEEDED' &&
    !props.task.approvalPayload,
)

const canCancel = computed(
  () =>
    !!props.task.runId &&
    !isTerminalState(props.task.serverState) &&
    props.task.serverState !== null &&
    props.task.transport !== 'aborted',
)

/**
 * 「核对状态」只在**值得核对**时出现：
 * 状态未终结、或前端已报错/未确认。已经 SUCCEEDED 的轮次再放一个核对按钮，
 * 只会把每一轮都变成一排调试按钮。
 */
const showRecheck = computed(
  () =>
    !!props.task.runId &&
    !isRunning.value &&
    (!isTerminalState(props.task.serverState) || !!props.task.error || !!props.task.detailError),
)

const showAnswer = computed(
  () => !!props.task.answer && props.task.serverState !== 'WAITING_APPROVAL',
)

const isHistoryOnly = computed(
  () => !!props.task.detail && !props.task.events.length && !props.task.prompt,
)

/** 工具活动默认折叠：它是次级信息，不该和回答抢版面；执行中自动展开 */
const toolsOpen = ref(false)

const toolSteps = computed(
  () => props.task.events.filter((event) => event.type === 'tool_start').length,
)

/** 执行中实时可见（用户需要知道"它在做什么"），结束后收成一行 */
const showTools = computed(() => toolsOpen.value || isRunning.value)

const waitingText = computed(() =>
  props.task.serverState === 'WAITING_APPROVAL'
    ? ''
    : isRunning.value
      ? '正在处理…'
      : '',
)
</script>

<template>
  <article class="turn">
    <!-- 用户：紧凑气泡，右对齐 -->
    <div v-if="task.prompt" class="turn__ask">
      <p class="turn__ask-text">{{ task.prompt }}</p>
    </div>
    <p v-else class="turn__ask-muted">
      {{ isHistoryOnly ? '（历史任务，本地没有保存原始输入）' : '（无输入内容）' }}
    </p>

    <!-- 助手：开放正文，不加卡片 -->
    <div class="turn__reply">
      <div class="turn__meta">
        <span class="turn__who">MkAgent</span>
        <span class="turn__time">{{ formatClock(task.createdAt) }}</span>
        <TaskStatusBadge v-if="showStatus" :label="primary.label" :tone="primary.tone" size="sm" />
        <button
          v-if="task.events.length"
          type="button"
          class="turn__tools"
          :aria-expanded="showTools"
          @click="toolsOpen = !toolsOpen"
        >
          <AppIcon
            name="chevron-right"
            :size="12"
            :class="['turn__tools-icon', { 'is-open': showTools }]"
          />
          工具 {{ toolSteps }} 步
        </button>
      </div>

      <p v-if="showStatus && primaryHint" class="turn__hint">{{ primaryHint }}</p>

      <ToolActivityList v-if="showTools && task.events.length" :events="task.events" />

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

      <MarkdownView v-if="showAnswer" class="turn__answer" :source="task.answer" />

      <p v-else-if="waitingText" class="turn__waiting">{{ waitingText }}</p>

      <!-- 任务级原因码 -->
      <p v-if="task.detail?.errorCode" class="turn__code">
        <AppIcon name="info" :size="13" />
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

      <!-- 操作：次级信息，不抢主视觉 -->
      <footer v-if="isRunning || canCancel || showRecheck" class="turn__actions">
        <button
          v-if="isRunning"
          type="button"
          class="mk-btn mk-btn--sm"
          @click="emit('stop-waiting')"
        >
          <AppIcon name="stop" :size="12" />
          停止等待
        </button>
        <button
          v-if="canCancel"
          type="button"
          class="mk-btn mk-btn--sm mk-btn--ghost"
          @click="emit('cancel')"
        >
          取消任务
        </button>
        <button
          v-if="showRecheck"
          type="button"
          class="mk-btn mk-btn--sm mk-btn--ghost"
          :disabled="task.verifying"
          @click="emit('recheck')"
        >
          <AppIcon name="refresh" :size="12" />
          {{ task.verifying ? '核对中…' : '核对状态' }}
        </button>
        <span v-if="isRunning" class="turn__action-note">
          停止等待只关闭前端连接，不会取消后端任务。
        </span>
      </footer>
    </div>
  </article>
</template>

<style scoped>
.turn {
  display: grid;
  gap: var(--s-3);
  padding: var(--s-6) 0;
  border-bottom: 1px solid var(--c-line-soft);
}

.turn:last-child {
  border-bottom: 0;
}

/* ---- 用户气泡 ---- */
.turn__ask {
  display: flex;
  justify-content: flex-end;
}

.turn__ask-text {
  max-width: 78%;
  padding: 9px var(--s-3);
  border-radius: var(--r-md);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--fs-base);
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.turn__ask-muted {
  text-align: right;
  font-size: var(--fs-sm);
  color: var(--c-text-3);
}

/* ---- 助手正文 ---- */
.turn__reply {
  display: grid;
  gap: var(--s-3);
  min-width: 0;
}

.turn__meta {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  min-width: 0;
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.turn__who {
  font-weight: 600;
  letter-spacing: 0.01em;
  color: var(--c-text-2);
}

.turn__time {
  font-variant-numeric: tabular-nums;
}

.turn__tools {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: auto;
  padding: 2px 6px;
  border-radius: var(--r-xs);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.turn__tools:hover {
  background: var(--c-surface-2);
  color: var(--c-text-2);
}

.turn__tools-icon {
  transition: transform var(--dur-fast) var(--ease);
}

.turn__tools-icon.is-open {
  transform: rotate(90deg);
}

.turn__hint {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  line-height: var(--lh-base);
}

.turn__answer {
  font-size: var(--fs-base);
  line-height: var(--lh-base);
  color: var(--c-text);
  overflow-wrap: anywhere;
}

.turn__waiting {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  font-size: var(--fs-sm);
  color: var(--c-text-3);
}

.turn__code {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  font-family: var(--font-mono);
}

.turn__actions {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
  margin-top: var(--s-1);
}

.turn__action-note {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

@media (max-width: 560px) {
  .turn {
    padding: var(--s-5) 0;
  }

  .turn__ask-text {
    max-width: 88%;
  }
}
</style>
