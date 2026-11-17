<script setup lang="ts">
/**
 * 主工作区。
 *
 * 只做编排：把 store 的状态映射到界面，把界面事件转成 store 动作。
 * 所有"是否成功""是否取消"的判断都在 store / api 层，这里不重复推断。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import SideNav from '@/components/SideNav.vue'
import EmptyState from '@/components/EmptyState.vue'
import TaskConversation from '@/components/TaskConversation.vue'
import TaskComposer from '@/components/TaskComposer.vue'
import TaskDetailPanel from '@/components/TaskDetailPanel.vue'
import TaskStatusBadge from '@/components/TaskStatusBadge.vue'
import LibraryDrawer from '@/components/LibraryDrawer.vue'
import { session } from '@/stores/session'
import { refreshPlaylists } from '@/stores/library'
import { taskStore } from '@/stores/taskStore'
import { READ_ONLY_CAPABILITY_TEXT, WRITE_CAPABILITY_TEXT } from '@/content/examples'
import { stateMeta } from '@/utils/status'

const router = useRouter()

const panelOpen = ref(window.innerWidth >= 1180)
const libraryOpen = ref(false)
const navOpen = ref(false)
const composer = ref<InstanceType<typeof TaskComposer> | null>(null)
const scroller = ref<HTMLElement | null>(null)

const activeTask = taskStore.activeTask
const tasks = taskStore.tasks

const hasPanel = computed(() => !!activeTask.value?.runId)

const capabilityText = computed(() =>
  session.capabilityMode === 'write_approval' ? WRITE_CAPABILITY_TEXT : READ_ONLY_CAPABILITY_TEXT,
)

const placeholder = computed(() =>
  session.entry.value === 'MUSIC_ADMIN'
    ? '向音乐管理员 Agent 提问，例如：最近 30 天播放最多的歌曲有哪些？'
    : '描述你的音乐需求，例如：帮我找一下夜航星的歌',
)

const topbarTitle = computed(() => {
  if (!activeTask.value) {
    return '新任务'
  }
  return activeTask.value.prompt || '历史任务'
})

const topbarState = computed(() => stateMeta(activeTask.value?.serverState ?? null))

function scrollToTop(): void {
  void nextTick(() => {
    const el = scroller.value
    if (!el) {
      return
    }
    // 部分环境（含 jsdom）没有实现 scrollTo，降级为直接设置 scrollTop
    if (typeof el.scrollTo === 'function') {
      el.scrollTo({ top: 0 })
    } else {
      el.scrollTop = 0
    }
  })
}

watch(
  () => taskStore.activeId.value,
  () => scrollToTop(),
)

/**
 * 审批卡出现时把滚动区滚到底部。
 *
 * 审批是任务里唯一"必须由人操作"的一步，而卡片通常比可视区高。
 * 用 scrollIntoView 对齐顶部反而会把"批准/拒绝"按钮留在折叠线以下，
 * 所以这里直接滚到底：卡片在内容末尾，底部就是按钮所在位置。
 *
 * 两处细节是踩过坑的：
 * 1. 用直接赋值 scrollTop 而不是 scrollTo({...})——后者会受 CSS
 *    scroll-behavior 影响变成异步动画，截图/无动画环境下看起来"没滚动"；
 * 2. 卡片挂载后布局还要一帧才稳定，scrollHeight 在第一次回调里可能仍是旧值，
 *    因此再补一次延后调用，而不是只滚一次。
 */
function revealApprovalCard(): void {
  const container = scroller.value
  if (!container) {
    return
  }
  const card = container.querySelector<HTMLElement>('[data-approval-card]')
  if (!card) {
    return
  }
  // 只补齐差量，避免用户已经手动往上翻时被强行拉到底
  const overflow = card.getBoundingClientRect().bottom - container.getBoundingClientRect().bottom
  if (overflow > -8) {
    container.scrollTop = container.scrollTop + overflow + 12
  }
}

watch(
  () => activeTask.value?.approvalPayload?.approvalId,
  async (approvalId) => {
    if (!approvalId) {
      return
    }
    await nextTick()
    revealApprovalCard()
    // 布局稳定后再补一次（字体/换行在首帧之后才定型）
    if (typeof requestAnimationFrame === 'function') {
      requestAnimationFrame(() => revealApprovalCard())
    }
    window.setTimeout(revealApprovalCard, 160)
  },
)

onMounted(() => {
  /*
   * 用后端真实装配校正"只读 / 审批写"文案。
   * 失败时保留构建期默认值，不挡用户使用。
   */
  void session.loadCapabilities()

  // 只读一次 URL 参数，避免刷新后重复提交
  const prefill = new URLSearchParams(window.location.search).get('q')
  if (prefill && !tasks.value.length) {
    composer.value?.fill(prefill)
  }
})

async function onSubmit(prompt: string): Promise<void> {
  const started = await taskStore.submit(prompt, session.entry.value)
  if (started) {
    navOpen.value = false
    scrollToTop()
  }
}

function onPick(prompt: string): void {
  composer.value?.fill(prompt)
}

function onSelect(id: string): void {
  taskStore.select(id)
  navOpen.value = false
}

async function onSelectRun(runId: string): Promise<void> {
  await taskStore.openHistoryRun(runId)
  navOpen.value = false
  scrollToTop()
}

/**
 * 审批通过后自动刷新"我的歌单"缓存。
 *
 * 抽屉可能正开着：批准后数据库里多了歌单，界面却还是旧列表，
 * 用户会以为没写成功。只在 APPROVED 时刷，拒绝不动数据。
 */
watch(
  () => activeTask.value?.approval?.status,
  (status) => {
    if (status === 'APPROVED') {
      void refreshPlaylists({ resetSongs: true })
    }
  },
)

function onOpenLibrary(): void {
  libraryOpen.value = true
  navOpen.value = false
}

function onNewTask(): void {
  taskStore.startNew()
  navOpen.value = false
  scrollToTop()
}

function closeNav(): void {
  navOpen.value = false
  // 窄屏下详情也是抽屉，点遮罩应一并收起，否则会留下一个"关不掉"的浮层
  panelOpen.value = false
}

function togglePanel(): void {
  panelOpen.value = !panelOpen.value
}

function toggleNav(): void {
  navOpen.value = !navOpen.value
}

/* 审批与任务操作：统一在这里判空，模板里不再出现可能的 null 访问 */
function onApprove(): void {
  if (activeTask.value) {
    void taskStore.decide(activeTask.value.id, true)
  }
}

function onReject(): void {
  if (activeTask.value) {
    void taskStore.decide(activeTask.value.id, false)
  }
}

function onResume(): void {
  if (activeTask.value) {
    void taskStore.resume(activeTask.value.id)
  }
}

function onRefreshApproval(): void {
  if (activeTask.value) {
    void taskStore.refreshApproval(activeTask.value.id)
  }
}

function onCancel(): void {
  if (activeTask.value) {
    void taskStore.cancel(activeTask.value.id)
  }
}

function onRecheck(): void {
  if (activeTask.value) {
    void taskStore.recheck(activeTask.value.id)
  }
}

</script>

<template>
  <AppShell
    :panel-open="panelOpen"
    :nav-open="navOpen"
    :has-panel="hasPanel"
    @toggle-panel="togglePanel"
    @toggle-nav="toggleNav"
    @close-overlays="closeNav"
  >
    <template #nav>
      <SideNav
        @new-task="onNewTask"
        @select="onSelect"
        @select-run="onSelectRun"
        @navigate-preview="router.push('/preview/approval')"
        @open-library="onOpenLibrary"
      />
    </template>

    <template #topbar>
      <div class="topbar">
        <span class="topbar__title">{{ topbarTitle }}</span>
        <TaskStatusBadge
          v-if="activeTask?.serverState"
          :label="topbarState.label"
          :tone="topbarState.tone"
          size="sm"
        />
        <span class="topbar__mode">
          {{ session.capabilityMode === 'write_approval' ? '审批写' : '只读' }}
        </span>
      </div>
    </template>

    <div class="workspace">
      <div ref="scroller" class="workspace__scroll">
        <div class="workspace__inner">
          <EmptyState
            v-if="!activeTask"
            :mode="session.capabilityMode"
            :capability-text="capabilityText"
            @pick="onPick"
          />

          <TaskConversation
            v-else-if="activeTask"
            :task="activeTask"
            @approve="onApprove"
            @reject="onReject"
            @resume="onResume"
            @refresh-approval="onRefreshApproval"
            @cancel="onCancel"
            @stop-waiting="taskStore.abortWaiting()"
            @recheck="onRecheck"
          />

          <div class="workspace__spacer" />
        </div>
      </div>

      <div class="workspace__composer">
        <div class="workspace__composer-inner">
          <TaskComposer
            ref="composer"
            :disabled="false"
            :busy="taskStore.isBusy.value"
            :entry="session.entry.value"
            :show-entry-switch="session.canSwitchEntry"
            :placeholder="placeholder"
            @submit="onSubmit"
            @entry="session.setEntry"
          />
          <p class="workspace__disclaimer">
            回答由模型生成，业务结果以任务记录为准。每次提交为独立任务，不共享上下文。
          </p>
        </div>
      </div>
    </div>

    <template #panel>
      <TaskDetailPanel
        :task="activeTask"
        @close="panelOpen = false"
        @recheck="onRecheck"
      />
    </template>
  </AppShell>

  <LibraryDrawer :open="libraryOpen" @close="libraryOpen = false" />
</template>

<style scoped>
/**
 * 结构：外层固定高度 flex 列 → 中间独立滚动区 → 底部固定输入区。
 *
 * 这里刻意**不用** sticky 输入框：sticky 会让输入框盖在内容上，
 * 审批按钮、任务操作按钮会被压在下面点不到（截图里确认过）。
 * 用独立滚动区后，任何内容都能完整滚到输入框上方。
 */
.workspace {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.workspace__scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
}

.workspace__inner {
  width: 100%;
  max-width: var(--center-max);
  margin: 0 auto;
  padding: 0 var(--s-6);
  min-width: 0;
}

.workspace__spacer {
  height: var(--s-6);
}

.workspace__composer {
  flex: none;
  width: 100%;
  background: var(--c-bg);
  border-top: 1px solid var(--c-line-soft);
  padding: var(--s-3) 0 var(--s-3);
}

.workspace__composer-inner {
  max-width: var(--center-max);
  margin: 0 auto;
  padding: 0 var(--s-6);
}

.workspace__disclaimer {
  margin-top: var(--s-2);
  text-align: center;
  font-size: 10.5px;
  color: var(--c-text-3);
  line-height: 1.5;
}

.topbar {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  min-width: 0;
}

.topbar__title {
  font-size: var(--fs-base);
  font-weight: 500;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.topbar__mode {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  letter-spacing: 0.04em;
  flex: none;
}

@media (max-width: 899px) {
  .workspace__inner,
  .workspace__composer-inner {
    padding: 0 var(--s-4);
  }
}
</style>
