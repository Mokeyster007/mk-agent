<script setup lang="ts">
/**
 * 审批卡组件预览。
 *
 * 这是**唯一**允许出现"假审批数据"的地方，且必须满足：
 * 1. 页面顶部明确标注"预览 / 示例数据 / 不发起任何请求"；
 * 2. 组件处于 preview 模式，所有按钮禁用，不会调用 approve / reject / resume；
 * 3. 不写入任何 store，不影响真实任务；
 * 4. 说明当前生产装配为只读，因此真实页面不会出现审批卡。
 *
 * 存在意义：写能力接入时，审批界面已经过视觉与状态设计验证，
 * 但**不提前开放**任何写入口。
 */
import { useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import ApprovalCard from '@/components/ApprovalCard.vue'
import type { ApprovalRequiredPayload, ApprovalView } from '@/api/types'

const router = useRouter()

const payload: ApprovalRequiredPayload = {
  approvalId: 'preview-approval-0001',
  expiresAt: '2026-11-17T01:30:00',
  summary: {
    tool: 'create_playlist',
    toolLabel: '创建歌单',
    playlistName: '清晨慢跑',
    songCount: 3,
    songs: ['城市霓虹', '午夜电台', '沿海公路'],
    roundToolCount: 1,
    text: '即将创建歌单「清晨慢跑」，并放入 3 首歌曲。',
    details: [],
  },
}

const pendingView: ApprovalView = {
  approvalId: payload.approvalId,
  runId: 'preview-run-0001',
  agentType: 'MUSIC_USER',
  step: 3,
  primaryTool: 'create_playlist',
  toolCount: 1,
  summaryText: payload.summary.text,
  summary: payload.summary,
  status: 'PENDING',
  resumeState: 'PENDING_RESUME',
  resumeAttempts: 0,
  decidedAt: null,
  expiresAt: payload.expiresAt,
}

/** 决定已保存、恢复没起来的稳定状态：这正是需要"手动恢复"的场景 */
const decidedView: ApprovalView = {
  ...pendingView,
  status: 'APPROVED',
  resumeState: 'PENDING_RESUME',
  resumeAttempts: 2,
  decidedAt: '2026-11-17T00:58:00',
}
</script>

<template>
  <div class="preview">
    <header class="preview__bar">
      <button type="button" class="mk-btn mk-btn--sm mk-btn--ghost" @click="router.push('/')">
        <AppIcon name="chevron-left" :size="14" />
        返回工作台
      </button>
      <span class="preview__badge">预览 · 示例数据</span>
      <span class="preview__note">不发起任何请求，不影响真实任务</span>
    </header>

    <div class="preview__body">
      <h1 class="preview__title">审批卡组件预览</h1>
      <p class="preview__desc">
        当前生产装配为只读，真实页面不会出现审批卡。这里用固定示例数据展示两种状态，
        用于确认写能力接入后的界面与状态表达；按钮全部禁用，不会调用审批接口。
      </p>

      <section class="preview__case">
        <p class="mk-eyebrow">状态一 · 待确认</p>
        <ApprovalCard :payload="payload" :approval="pendingView" mode="preview" />
      </section>

      <section class="preview__case">
        <p class="mk-eyebrow">状态二 · 决定已保存、恢复未完成</p>
        <ApprovalCard
          :payload="payload"
          :approval="decidedView"
          mode="preview"
          error="恢复执行时并发许可已满，决定已保存，可以重试恢复。"
        />
      </section>

      <p class="preview__foot">
        说明：真实审批卡的内容全部来自服务端审批摘要与审批视图接口，
        前端不会根据模型聊天文本拼装审批内容，也不会允许编辑参数。
      </p>
    </div>
  </div>
</template>

<style scoped>
.preview {
  min-height: 100vh;
  background: var(--c-bg);
}

.preview__bar {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-3) var(--s-5);
  border-bottom: 1px solid var(--c-line);
  background: var(--frost);
  backdrop-filter: var(--frost-blur);
  flex-wrap: wrap;
}

.preview__badge {
  font-size: var(--fs-xs);
  font-weight: 600;
  padding: 2px 8px;
  border-radius: var(--r-pill);
  background: var(--c-accent-weak);
  color: var(--c-accent-ink);
  border: 1px solid var(--c-accent-line);
}

.preview__note {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.preview__body {
  max-width: 720px;
  margin: 0 auto;
  padding: var(--s-8) var(--s-5) var(--s-16);
  display: grid;
  gap: var(--s-6);
}

.preview__title {
  font-size: var(--fs-xl);
  font-weight: 600;
  letter-spacing: -0.02em;
}

.preview__desc {
  margin-top: calc(var(--s-4) * -1 + var(--s-2));
  font-size: var(--fs-base);
  color: var(--c-text-2);
  line-height: var(--lh-relaxed);
  max-width: 62ch;
}

.preview__case {
  display: grid;
  gap: var(--s-2);
}

.preview__foot {
  font-size: var(--fs-sm);
  color: var(--c-text-3);
  line-height: var(--lh-relaxed);
  padding-top: var(--s-4);
  border-top: 1px solid var(--c-line-soft);
}
</style>
