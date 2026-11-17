<script setup lang="ts">
/**
 * 工具活动列表。
 *
 * 只渲染后端 SSE 真实推送的 tool_start / tool_result / status 事件。
 *
 * 严格约束：
 * - 不显示工具参数、不显示耗时、不显示"成功/失败"结论，
 *   因为 tool_trace 与事件里都没有这些信息；
 * - 没有事件时不占位、不生成"正在思考"这类伪造进度；
 * - 不展示模型的内部推理过程（后端也不下发）。
 */
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import type { AgentEvent } from '@/api/types'
import { formatClock } from '@/utils/format'

const props = defineProps<{ events: AgentEvent[] }>()

interface Row {
  key: string
  kind: 'tool_start' | 'tool_result' | 'status'
  label: string
  message: string
  step: number | null
  time: number
}

const rows = computed<Row[]>(() =>
  props.events
    .filter(
      (event) =>
        event.type === 'tool_start' || event.type === 'tool_result' || event.type === 'status',
    )
    .map((event, index) => ({
      key: `${event.timestamp}-${index}`,
      kind: event.type as Row['kind'],
      label:
        event.type === 'tool_start'
          ? '调用工具'
          : event.type === 'tool_result'
            ? '工具返回'
            : '状态',
      message: event.message,
      step: event.step,
      time: event.timestamp,
    })),
)

/**
 * 从事件文本里识别工具名，仅用于显示中文动作名。
 *
 * 识别失败就原样显示文本——不做模糊猜测，
 * 更不因为"看起来像"就补一个工具名上去。
 */
function iconOf(row: Row): 'wave' | 'check' | 'info' {
  if (row.kind === 'tool_start') return 'wave'
  if (row.kind === 'tool_result') return 'check'
  return 'info'
}
</script>

<template>
  <ul v-if="rows.length" class="activity">
    <li v-for="row in rows" :key="row.key" class="activity__row" :class="`activity__row--${row.kind}`">
      <span class="activity__rail" aria-hidden="true" />
      <span class="activity__icon">
        <AppIcon :name="iconOf(row)" :size="13" />
      </span>
      <span class="activity__body">
        <span class="activity__text">{{ row.message }}</span>
        <span class="activity__meta">
          <span v-if="row.step !== null">第 {{ row.step }} 步</span>
          <span v-if="row.step !== null" class="activity__sep">·</span>
          <span class="mk-mono">{{ formatClock(row.time) }}</span>
        </span>
      </span>
    </li>
  </ul>
</template>

<style scoped>
.activity {
  display: grid;
  gap: 2px;
  padding: var(--s-3) 0 var(--s-1);
}

.activity__row {
  position: relative;
  display: flex;
  gap: var(--s-3);
  padding: 5px 0;
  min-width: 0;
}

.activity__rail {
  position: absolute;
  left: 10px;
  top: 0;
  bottom: 0;
  width: 1px;
  background: var(--c-line);
}

.activity__row:first-child .activity__rail {
  top: 14px;
}

.activity__row:last-child .activity__rail {
  bottom: calc(100% - 16px);
}

.activity__icon {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 21px;
  height: 21px;
  flex: none;
  border-radius: 50%;
  border: 1px solid var(--c-line);
  background: var(--c-surface);
  color: var(--c-text-3);
  margin-top: 1px;
}

.activity__row--tool_start .activity__icon {
  color: var(--c-accent-ink);
  border-color: var(--c-accent-line);
  background: var(--c-accent-weak);
}

.activity__row--tool_result .activity__icon {
  color: var(--c-ok);
  border-color: rgba(78, 194, 160, 0.28);
  background: var(--c-ok-weak);
}

.activity__body {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  flex: 1;
}

.activity__text {
  font-size: var(--fs-sm);
  color: var(--c-text-2);
  line-height: var(--lh-base);
  overflow-wrap: anywhere;
}

.activity__meta {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 10.5px;
  color: var(--c-text-3);
}

.activity__sep {
  opacity: 0.5;
}
</style>
