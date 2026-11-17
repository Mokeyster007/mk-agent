<script setup lang="ts">
/**
 * 审批卡。
 *
 * 数据来源纪律：
 * - 可执行内容（工具、歌单名、歌曲、有效期）**全部来自服务端**：
 *   初始来自 approval_required 事件，随后用 GET /ai/approvals/{id} 校准；
 * - 绝不根据模型聊天文本拼审批内容，也绝不编辑任何参数；
 * - 前端不生成幂等键、不传参数，决定接口只收 approvalId。
 *
 * 语义纪律：
 * - "批准"只是保存了决定，不代表业务执行成功；
 * - 用户拒绝不是系统故障；
 * - 决定已保存但恢复失败时，保留决定并给出"手动恢复"。
 */
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import TaskStatusBadge from './TaskStatusBadge.vue'
import type { ApprovalRequiredPayload, ApprovalView } from '@/api/types'
import { approvalStatusMeta, resumeStateMeta, toolLabel } from '@/utils/status'
import { formatDateTime } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    payload: ApprovalRequiredPayload
    approval: ApprovalView | null
    /** live = 真实审批；preview = 组件预览，不触发任何请求 */
    mode?: 'live' | 'preview'
    busy?: boolean
    error?: string | null
  }>(),
  { mode: 'live', busy: false, error: null },
)

const emit = defineEmits<{
  (e: 'approve'): void
  (e: 'reject'): void
  (e: 'resume'): void
  (e: 'refresh'): void
}>()

const isPreview = computed(() => props.mode === 'preview')

/** 审批真实状态：以查询接口为准，查不到时不假装是 PENDING */
const status = computed(() => props.approval?.status ?? null)
const statusMeta = computed(() => approvalStatusMeta(status.value))
const resumeMeta = computed(() => resumeStateMeta(props.approval?.resumeState ?? null))

const isPending = computed(() => status.value === 'PENDING')
/** 决定已保存但执行没接上：这是"可以重试恢复"的稳定状态，不是失败 */
const canResume = computed(() => {
  const approval = props.approval
  if (!approval) return false
  const decided = approval.status === 'APPROVED' || approval.status === 'REJECTED'
  return decided && approval.resumeState !== 'FINISHED' && approval.resumeState !== 'RUNNING'
})

const summary = computed(() => props.payload.summary)
const targetPlaylist = computed(() => summary.value.playlistName || '未指定歌单名')
const actionLabel = computed(() => summary.value.toolLabel || toolLabel(summary.value.tool))

const expiresText = computed(() => {
  const value = props.approval?.expiresAt || props.payload.expiresAt
  return value ? formatDateTime(value) : '暂无数据'
})

const disabled = computed(() => props.busy || isPreview.value || !isPending.value)
</script>

<template>
  <section class="approval" :class="{ 'approval--preview': isPreview }" data-approval-card>
    <header class="approval__head">
      <div class="approval__title">
        <span class="approval__icon"><AppIcon name="shield" :size="16" /></span>
        <div>
          <h3>需要你确认这次写操作</h3>
          <p class="approval__sub">
            批准后才会执行；批准本身不等于执行成功。
          </p>
        </div>
      </div>
      <div class="approval__badges">
        <TaskStatusBadge v-if="status" :label="statusMeta.label" :tone="statusMeta.tone" />
        <TaskStatusBadge
          v-else
          label="状态未确认"
          tone="warn"
        />
        <span v-if="isPreview" class="mk-chip approval__preview-chip">预览</span>
      </div>
    </header>

    <dl class="approval__grid">
      <div class="approval__item">
        <dt>将执行的操作</dt>
        <dd>{{ actionLabel }}</dd>
      </div>
      <div class="approval__item">
        <dt>目标歌单</dt>
        <dd>{{ targetPlaylist }}</dd>
      </div>
      <div class="approval__item">
        <dt>涉及歌曲</dt>
        <dd>
          <template v-if="summary.songCount !== null">
            {{ summary.songCount }} 首
          </template>
          <template v-else>暂无数据</template>
        </dd>
      </div>
      <div class="approval__item">
        <dt>本轮工具数</dt>
        <dd>{{ summary.roundToolCount }}</dd>
      </div>
      <div class="approval__item approval__item--wide">
        <dt>有效期至</dt>
        <dd class="mk-mono">{{ expiresText }}</dd>
      </div>
    </dl>

    <div v-if="summary.songs.length" class="approval__songs">
      <p class="mk-eyebrow">歌曲清单（服务端摘要，按标题截断）</p>
      <ul>
        <li v-for="song in summary.songs" :key="song">{{ song }}</li>
      </ul>
      <p class="approval__note">
        摘要只提供歌曲标题，没有可靠的歌曲 ID 映射，因此这里不显示 ID。
      </p>
    </div>

    <p v-if="summary.text" class="approval__text">{{ summary.text }}</p>

    <div v-if="props.approval" class="approval__resume">
      <span class="approval__resume-label">执行状态</span>
      <TaskStatusBadge :label="resumeMeta.label" :tone="resumeMeta.tone" size="sm" />
      <span v-if="props.approval.resumeAttempts > 0" class="approval__attempts">
        已尝试恢复 {{ props.approval.resumeAttempts }} 次
      </span>
    </div>

    <p v-if="!props.approval && !isPreview" class="approval__warn">
      <AppIcon name="alert" :size="14" />
      还没有确认到审批的当前状态，建议先刷新再提交决定。
    </p>

    <p v-if="error" class="approval__error">{{ error }}</p>

    <footer class="approval__actions">
      <button
        type="button"
        class="mk-btn mk-btn--primary"
        :disabled="disabled"
        @click="emit('approve')"
      >
        {{ busy ? '提交中…' : '批准并继续' }}
      </button>
      <button type="button" class="mk-btn" :disabled="disabled" @click="emit('reject')">
        拒绝
      </button>
      <button
        v-if="canResume"
        type="button"
        class="mk-btn"
        :disabled="busy || isPreview"
        @click="emit('resume')"
      >
        <AppIcon name="refresh" :size="14" />
        手动恢复
      </button>
      <button
        type="button"
        class="mk-btn mk-btn--ghost"
        :disabled="busy || isPreview"
        @click="emit('refresh')"
      >
        刷新状态
      </button>
      <span v-if="isPreview" class="approval__preview-note">
        预览模式：按钮不发起任何请求
      </span>
    </footer>

    <!-- 窄屏上卡片头部的说明会被滚出视口，这里把最关键的一句放在按钮旁边 -->
    <p class="approval__footnote">
      批准只表示同意执行；是否真的执行成功，以任务状态和最终回答为准。
      写库结果可在左侧「我的歌单」刷新核对。
    </p>
  </section>
</template>

<style scoped>
.approval {
  border: 1px solid rgba(226, 180, 94, 0.32);
  border-radius: var(--r-lg);
  background: linear-gradient(180deg, rgba(226, 180, 94, 0.06), transparent 42%), var(--c-surface);
  padding: var(--s-4);
  display: grid;
  gap: var(--s-3);
}

.approval--preview {
  border-color: var(--c-line-strong);
  border-style: dashed;
}

.approval__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-3);
  flex-wrap: wrap;
}

.approval__title {
  display: flex;
  gap: var(--s-3);
  min-width: 0;
}

.approval__title h3 {
  font-size: var(--fs-base);
  font-weight: 600;
  line-height: var(--lh-tight);
}

.approval__sub {
  margin-top: 3px;
  font-size: var(--fs-sm);
  color: var(--c-text-3);
}

.approval__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: none;
  border-radius: var(--r-sm);
  background: var(--c-warn-weak);
  color: var(--c-warn);
}

.approval__badges {
  display: flex;
  align-items: center;
  gap: var(--s-2);
}

.approval__preview-chip {
  border-color: var(--c-accent-line);
  color: var(--c-accent-ink);
  background: var(--c-accent-weak);
}

.approval__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s-3);
  margin: 0;
}

.approval__item {
  min-width: 0;
}

.approval__item--wide {
  grid-column: 1 / -1;
}

.approval__item dt {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  margin-bottom: 2px;
}

.approval__item dd {
  margin: 0;
  font-size: var(--fs-base);
  color: var(--c-text);
  overflow-wrap: anywhere;
}

.approval__songs {
  border-top: 1px solid var(--c-line-soft);
  padding-top: var(--s-3);
}

.approval__songs ul {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: var(--s-2);
}

.approval__songs li {
  padding: 2px 9px;
  border-radius: var(--r-pill);
  border: 1px solid var(--c-line);
  background: var(--c-bg-soft);
  font-size: var(--fs-sm);
  color: var(--c-text-2);
}

.approval__note {
  margin-top: var(--s-2);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.approval__text {
  font-size: var(--fs-sm);
  color: var(--c-text-2);
  padding: var(--s-2) var(--s-3);
  border-radius: var(--r-sm);
  background: var(--c-bg-soft);
  border: 1px solid var(--c-line-soft);
  overflow-wrap: anywhere;
}

.approval__resume {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
  font-size: var(--fs-sm);
  color: var(--c-text-3);
}

.approval__resume-label {
  font-size: var(--fs-xs);
}

.approval__attempts {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.approval__warn {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  color: var(--c-warn);
}

.approval__error {
  font-size: var(--fs-sm);
  color: var(--c-danger);
  overflow-wrap: anywhere;
}

.approval__actions {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
  border-top: 1px solid var(--c-line-soft);
  padding-top: var(--s-3);
}

.approval__preview-note {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.approval__footnote {
  margin-top: var(--s-2);
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--c-text-3);
}

@media (max-width: 560px) {
  .approval__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
