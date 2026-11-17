<script setup lang="ts">
/**
 * 右侧任务详情面板。
 *
 * 只显示"后端真的给了"的字段：
 * - 字段缺失显示「暂无数据」，Token 缺失绝不写 0；
 * - step 标为"步数"，不冒充模型调用次数；
 * - tool_trace 只有工具名，因此只画名字，不补参数、不补耗时、不补成败。
 */
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import TaskStatusBadge from './TaskStatusBadge.vue'
import type { SessionTask } from '@/stores/taskStore'
import { formatDateTime, formatDuration, formatTokens, splitTrace, UNKNOWN } from '@/utils/format'
import {
  approvalStatusMeta,
  isWriteTool,
  resumeStateMeta,
  stateMeta,
  toolLabel,
} from '@/utils/status'

const props = defineProps<{ task: SessionTask | null }>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'recheck'): void
}>()

const detail = computed(() => props.task?.detail ?? null)
const state = computed(() => stateMeta(props.task?.serverState ?? detail.value?.state ?? null))
const trace = computed(() => splitTrace(detail.value?.toolTrace ?? null))

const duration = computed(() => {
  if (detail.value?.totalCostMillis !== null && detail.value?.totalCostMillis !== undefined) {
    return formatDuration(detail.value.totalCostMillis)
  }
  return UNKNOWN
})

const hasAny = computed(() => !!props.task?.runId || !!detail.value)

const errorCode = computed(() => detail.value?.errorCode ?? null)

const approvalStatus = computed(() => approvalStatusMeta(props.task?.approval?.status ?? null))
const approvalResume = computed(() => resumeStateMeta(props.task?.approval?.resumeState ?? null))
</script>

<template>
  <aside class="panel" aria-label="任务详情">
    <header class="panel__head">
      <h2 class="panel__title">任务详情</h2>
      <button type="button" class="panel__close" aria-label="收起详情面板" @click="emit('close')">
        <AppIcon name="chevron-right" :size="16" />
      </button>
    </header>

    <div v-if="!hasAny" class="panel__empty">
      <p>提交任务后，这里会显示运行编号、状态、耗时与工具轨迹。</p>
      <p class="panel__empty-note">
        详情只来自后端任务记录，前端不会自行推算。
      </p>
    </div>

    <div v-else class="panel__body">
      <section class="panel__section">
        <p class="mk-eyebrow">运行</p>
        <dl class="kv">
          <div class="kv__row">
            <dt>运行编号</dt>
            <dd class="mk-mono mk-break">{{ task?.runId ?? UNKNOWN }}</dd>
          </div>
          <div class="kv__row">
            <dt>状态</dt>
            <dd><TaskStatusBadge :label="state.label" :tone="state.tone" size="sm" /></dd>
          </div>
          <div class="kv__row">
            <dt>入口</dt>
            <dd class="mk-mono">{{ detail?.agentType ?? task?.entry ?? UNKNOWN }}</dd>
          </div>
          <div class="kv__row">
            <dt>模型</dt>
            <dd class="mk-mono">{{ detail?.model ?? UNKNOWN }}</dd>
          </div>
        </dl>
      </section>

      <section class="panel__section">
        <p class="mk-eyebrow">时间</p>
        <dl class="kv">
          <div class="kv__row">
            <dt>开始</dt>
            <dd class="mk-mono">{{ formatDateTime(detail?.startedAt ?? null) }}</dd>
          </div>
          <div class="kv__row">
            <dt>结束</dt>
            <dd class="mk-mono">{{ formatDateTime(detail?.finishedAt ?? null) }}</dd>
          </div>
          <div class="kv__row">
            <dt>耗时</dt>
            <dd class="mk-mono">{{ duration }}</dd>
          </div>
        </dl>
      </section>

      <section class="panel__section">
        <p class="mk-eyebrow">执行规模</p>
        <dl class="kv">
          <div class="kv__row">
            <dt>步数</dt>
            <dd class="mk-mono">
              {{ detail?.currentStep ?? UNKNOWN }}
            </dd>
          </div>
          <div class="kv__row">
            <dt>工具调用次数</dt>
            <dd class="mk-mono">{{ detail?.toolCallCount ?? UNKNOWN }}</dd>
          </div>
          <div class="kv__row">
            <dt>Token 用量</dt>
            <dd class="mk-mono">{{ formatTokens(detail?.totalTokens ?? null) }}</dd>
          </div>
          <div v-if="detail?.totalTokens !== null && detail?.totalTokens !== undefined" class="kv__row">
            <dt>输入 / 输出</dt>
            <dd class="mk-mono">
              {{ formatTokens(detail?.promptTokens ?? null) }} /
              {{ formatTokens(detail?.completionTokens ?? null) }}
            </dd>
          </div>
        </dl>
        <p class="panel__note">步数是 Agent Loop 的轮次，不等于模型调用次数。</p>
      </section>

      <section class="panel__section">
        <p class="mk-eyebrow">工具轨迹</p>
        <ul v-if="trace.length" class="trace">
          <li v-for="(tool, index) in trace" :key="`${tool}-${index}`" class="trace__item">
            <span class="trace__index mk-mono">{{ index + 1 }}</span>
            <span class="trace__name">{{ toolLabel(tool) }}</span>
            <span class="trace__raw mk-mono">{{ tool }}</span>
            <span v-if="isWriteTool(tool)" class="trace__tag">写</span>
          </li>
        </ul>
        <p v-else class="panel__note">暂无数据</p>
        <p class="panel__note">轨迹只记录工具名，不含参数与结果原文。</p>
      </section>

      <section v-if="task?.approval" class="panel__section">
        <p class="mk-eyebrow">审批</p>
        <dl class="kv">
          <div class="kv__row">
            <dt>审批编号</dt>
            <dd class="mk-mono mk-break">{{ task.approval.approvalId }}</dd>
          </div>
          <div class="kv__row">
            <dt>状态</dt>
            <dd><TaskStatusBadge :label="approvalStatus.label" :tone="approvalStatus.tone" size="sm" /></dd>
          </div>
          <div class="kv__row">
            <dt>执行</dt>
            <dd><TaskStatusBadge :label="approvalResume.label" :tone="approvalResume.tone" size="sm" /></dd>
          </div>
          <div class="kv__row">
            <dt>有效期至</dt>
            <dd class="mk-mono">{{ formatDateTime(task.approval.expiresAt) }}</dd>
          </div>
        </dl>
      </section>

      <section v-if="errorCode" class="panel__section">
        <p class="mk-eyebrow">原因码</p>
        <p class="panel__code mk-mono">{{ errorCode }}</p>
        <p v-if="task && task.error?.text" class="panel__note">{{ task.error.text }}</p>
      </section>

      <button type="button" class="mk-btn mk-btn--sm mk-btn--block" @click="emit('recheck')">
        <AppIcon name="refresh" :size="13" />
        重新读取任务记录
      </button>
    </div>
  </aside>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  border-left: 1px solid var(--c-line);
  background: var(--c-bg-soft);
}

.panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2);
  height: var(--header-h);
  padding: 0 var(--s-4) 0 var(--s-5);
  border-bottom: 1px solid var(--c-line);
  flex: none;
}

.panel__title {
  font-size: var(--fs-base);
  font-weight: 600;
  letter-spacing: -0.01em;
}

.panel__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  color: var(--c-text-3);
}

.panel__close:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.panel__empty {
  padding: var(--s-6) var(--s-5);
  font-size: var(--fs-sm);
  color: var(--c-text-3);
  line-height: var(--lh-relaxed);
}

.panel__empty-note {
  margin-top: var(--s-2);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  opacity: 0.8;
}

.panel__body {
  padding: var(--s-4) var(--s-5) var(--s-8);
  overflow-y: auto;
  display: grid;
  gap: var(--s-5);
  align-content: start;
}

.panel__section {
  display: grid;
  gap: var(--s-2);
}

.kv {
  display: grid;
  gap: 6px;
  margin: 0;
}

.kv__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
  min-width: 0;
}

.kv__row dt {
  font-size: var(--fs-sm);
  color: var(--c-text-3);
  flex: none;
}

.kv__row dd {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--c-text);
  text-align: right;
  min-width: 0;
  overflow-wrap: anywhere;
}

.panel__note {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  line-height: var(--lh-base);
}

.trace {
  display: grid;
  gap: 3px;
}

.trace__item {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  padding: 4px 8px;
  border-radius: var(--r-xs);
  background: var(--c-surface);
  border: 1px solid var(--c-line-soft);
  min-width: 0;
}

.trace__index {
  font-size: 10px;
  color: var(--c-text-3);
  flex: none;
  width: 14px;
}

.trace__name {
  font-size: var(--fs-sm);
  color: var(--c-text-2);
  flex: none;
}

.trace__raw {
  font-size: 10.5px;
  color: var(--c-text-3);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: right;
}

.trace__tag {
  flex: none;
  font-size: 10px;
  padding: 0 5px;
  border-radius: 4px;
  background: var(--c-warn-weak);
  color: var(--c-warn);
}

.panel__code {
  font-size: var(--fs-sm);
  color: var(--c-warn);
  padding: 4px 8px;
  border-radius: var(--r-xs);
  background: var(--c-warn-weak);
  overflow-wrap: anywhere;
}
</style>
