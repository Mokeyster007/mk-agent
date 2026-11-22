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
 *
 * 版面纪律：三块内容必须能被一眼扫到 —— 操作是什么 / 目标歌单 / 将写入的歌曲。
 * 每个状态都带文字，颜色只是辅助。
 */
import { computed, ref } from 'vue'
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

/** 超过这个数量就先折叠，避免一屏全是歌名 */
const COLLAPSE_AT = 8

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

/**
 * 业务扩展给出的结构化字段（当前只有售后：订单号 / 商品 / 数量 / 原因）。
 *
 * 有 details 就按"标签 值"逐条渲染，**不再**显示歌单那一块：
 * 售后卡上没有歌单，显示"未指定歌单名"会让用户以为批准的是别的东西。
 * 两者都来自服务端摘要，前端不拼接、不补字段。
 */
const details = computed(() => summary.value.details ?? [])
const hasDetails = computed(() => details.value.length > 0)

/** 音乐卡的脚注指向歌单；有 details 的业务指向自己的列表，避免指错地方。 */
const footnote = computed(() =>
  hasDetails.value
    ? '批准只表示同意执行；是否真的建单，以「我的售后」列表里的单号为准。'
    : '批准只表示同意执行；是否真的执行成功，以任务状态和最终回答为准。写库结果可在顶栏「我的歌单」里刷新核对。',
)

const expiresText = computed(() => {
  const value = props.approval?.expiresAt || props.payload.expiresAt
  return value ? formatDateTime(value) : '暂无数据'
})

const disabled = computed(() => props.busy || isPreview.value || !isPending.value)

/** 长列表折叠，但必须能展开核对 */
const songsOpen = ref(false)
const songs = computed(() => summary.value.songs)
const hiddenCount = computed(() =>
  songsOpen.value ? 0 : Math.max(0, songs.value.length - COLLAPSE_AT),
)
const visibleSongs = computed(() =>
  songsOpen.value ? songs.value : songs.value.slice(0, COLLAPSE_AT),
)
</script>

<template>
  <section class="approval" :class="{ 'approval--preview': isPreview }" data-approval-card>
    <header class="approval__head">
      <div class="approval__title">
        <span class="approval__icon" aria-hidden="true">
          <AppIcon name="shield" :size="15" />
        </span>
        <div class="approval__title-text">
          <p class="approval__eyebrow">写操作 · 需要你确认</p>
          <h3 class="approval__headline">{{ actionLabel }}</h3>
        </div>
      </div>
      <div class="approval__badges">
        <TaskStatusBadge v-if="status" :label="statusMeta.label" :tone="statusMeta.tone" size="sm" />
        <TaskStatusBadge v-else label="状态未确认" tone="warn" size="sm" />
        <span v-if="isPreview" class="mk-chip approval__preview-chip">预览</span>
      </div>
    </header>

    <!-- 1. 操作是什么（标题）+ 2. 这次要固定的关键事实 -->
    <dl v-if="hasDetails" class="approval__facts">
      <div v-for="item in details" :key="item.label" class="approval__fact">
        <dt>{{ item.label }}</dt>
        <dd>{{ item.value }}</dd>
      </div>
    </dl>
    <dl v-else class="approval__facts">
      <div class="approval__fact">
        <dt>目标歌单</dt>
        <dd>{{ targetPlaylist }}</dd>
      </div>
    </dl>

    <!-- 3. 将写入的歌曲（音乐链路；售后没有这一段） -->
    <div v-if="songs.length || summary.songCount !== null" class="approval__songs">
      <div class="approval__songs-head">
        <span class="approval__songs-label">将写入的歌曲</span>
        <span class="approval__songs-count">
          {{ summary.songCount !== null ? summary.songCount : songs.length }} 首
        </span>
        <button
          v-if="songs.length > COLLAPSE_AT"
          type="button"
          class="approval__songs-toggle"
          @click="songsOpen = !songsOpen"
        >
          {{ songsOpen ? '收起' : '展开全部' }}
        </button>
      </div>

      <ol v-if="visibleSongs.length" class="approval__song-list">
        <li v-for="(song, index) in visibleSongs" :key="`${song}-${index}`" class="approval__song">
          <span class="approval__song-index">{{ index + 1 }}</span>
          <span class="approval__song-title">{{ song }}</span>
        </li>
      </ol>
      <p v-if="hiddenCount > 0" class="approval__more">还有 {{ hiddenCount }} 首未展开</p>
      <p v-else-if="!songs.length" class="approval__more">服务端摘要没有给出歌曲标题</p>

      <p class="approval__note">摘要只提供歌曲标题，没有可靠的歌曲 ID 映射，因此这里不显示 ID。</p>
    </div>

    <p v-if="summary.text" class="approval__text">{{ summary.text }}</p>

    <div class="approval__resume">
      <span class="approval__resume-label">执行状态</span>
      <TaskStatusBadge
        v-if="props.approval"
        :label="resumeMeta.label"
        :tone="resumeMeta.tone"
        size="sm"
      />
      <span v-else class="approval__resume-none">未确认</span>
      <span v-if="props.approval && props.approval.resumeAttempts > 0" class="approval__attempts">
        已尝试恢复 {{ props.approval.resumeAttempts }} 次
      </span>
      <span class="approval__expires">有效至 {{ expiresText }}</span>
    </div>

    <p v-if="!props.approval && !isPreview" class="approval__warn">
      <AppIcon name="alert" :size="13" />
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
        {{ busy ? '提交中…' : '批准并执行' }}
      </button>
      <button type="button" class="mk-btn approval__reject" :disabled="disabled" @click="emit('reject')">
        拒绝
      </button>
      <button
        v-if="canResume"
        type="button"
        class="mk-btn"
        :disabled="busy || isPreview"
        @click="emit('resume')"
      >
        <AppIcon name="refresh" :size="13" />
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

    <p class="approval__footnote">
      {{ footnote }}
    </p>
  </section>
</template>

<style scoped>
.approval {
  border: 1px solid rgba(224, 177, 95, 0.28);
  border-radius: var(--r-md);
  background: var(--c-surface);
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

.approval__title-text {
  min-width: 0;
}

.approval__eyebrow {
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--c-warn);
}

.approval__headline {
  margin-top: 2px;
  font-size: var(--fs-base);
  font-weight: 600;
  line-height: 1.45;
  color: var(--c-text);
  overflow-wrap: anywhere;
}

.approval__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
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

/* ---- 目标歌单：单独一行，字号比正文重一档 ---- */
.approval__facts {
  display: grid;
  gap: var(--s-3);
  margin: 0;
  padding: var(--s-3) 0;
  border-top: 1px solid var(--c-line-soft);
  border-bottom: 1px solid var(--c-line-soft);
}

.approval__fact {
  min-width: 0;
}

.approval__fact dt {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  margin-bottom: 2px;
}

.approval__fact dd {
  margin: 0;
  font-size: var(--fs-base);
  color: var(--c-text);
  overflow-wrap: anywhere;
}

/* ---- 曲目列表：序号 + 歌名，紧凑但可读 ---- */
.approval__songs {
  display: grid;
  gap: var(--s-2);
}

.approval__songs-head {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
}

.approval__songs-label {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.approval__songs-count {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  font-variant-numeric: tabular-nums;
}

.approval__songs-toggle {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--c-accent-ink);
}

.approval__song-list {
  display: grid;
  gap: 1px;
}

.approval__song {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
  padding: 3px 0;
  border-bottom: 1px dashed var(--c-line-soft);
}

.approval__song:last-child {
  border-bottom: 0;
}

.approval__song-index {
  width: 16px;
  flex: none;
  text-align: right;
  font-size: 10.5px;
  color: var(--c-text-3);
  font-variant-numeric: tabular-nums;
}

.approval__song-title {
  font-size: var(--fs-sm);
  color: var(--c-text);
  overflow-wrap: anywhere;
}

.approval__more {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.approval__note {
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--c-text-3);
}

.approval__text {
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
  color: var(--c-text-2);
  overflow-wrap: anywhere;
}

.approval__resume {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.approval__expires {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}

.approval__attempts {
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
  padding-top: var(--s-1);
}

/* 拒绝必须是显眼的一等按钮，但不能看起来像"危险操作" */
.approval__reject {
  border-color: var(--c-line-strong);
  background: transparent;
  color: var(--c-text-2);
}

.approval__reject:hover:not(:disabled) {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.approval__preview-note {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.approval__footnote {
  font-size: 10.5px;
  line-height: 1.65;
  color: var(--c-text-3);
}

@media (max-width: 560px) {
  .approval__facts {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--s-2);
  }

  .approval__expires {
    margin-left: 0;
    width: 100%;
  }
}
</style>
