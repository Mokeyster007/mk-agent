<script setup lang="ts">
/**
 * 主工作区。
 *
 * 结构契约（输入框稳定的关键）：
 *   工作区 = 消息区（唯一纵向滚动容器）+ 输入区（固定底边，不随消息高度移动）
 * 输入组件挂在**会话工作区**上，不挂在消息列表 / 某一轮 run 的节点上，
 * 因此切换会话只更新内容，不换父容器、不重新挂载。
 *
 * 所有"是否成功""是否取消"的判断都在 store / api 层，这里不重复推断。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import SideNav from '@/components/SideNav.vue'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import TaskConversation from '@/components/TaskConversation.vue'
import TaskComposer from '@/components/TaskComposer.vue'
import TaskDetailPanel from '@/components/TaskDetailPanel.vue'
import LibraryDrawer from '@/components/LibraryDrawer.vue'
import MusicAccountDrawer from '@/components/MusicAccountDrawer.vue'
import PlayCard from '@/components/PlayCard.vue'
import { ApiError } from '@/api/errors'
import { getPlaybackCandidates } from '@/api/playback'
import type { PlaybackCandidateVO } from '@/api/types'
import { session } from '@/stores/session'
import { refreshPlaylists } from '@/stores/library'
import {
  activeConversationId,
  clearActiveConversation,
  conversations,
  ensureConversation,
  newConversation,
  openConversation,
  refreshConversations,
} from '@/stores/conversation'
import { taskStore } from '@/stores/taskStore'
import { isTerminalState } from '@/api/runs'
import { READ_ONLY_CAPABILITY_TEXT, WRITE_CAPABILITY_TEXT } from '@/content/examples'
import { stateMeta } from '@/utils/status'

const router = useRouter()

const navOpen = ref(false)
const libraryOpen = ref(false)
const accountOpen = ref(false)
const panelOpen = ref(false)
const composer = ref<InstanceType<typeof TaskComposer> | null>(null)
const scroller = ref<HTMLElement | null>(null)
const libraryTrigger = ref<HTMLButtonElement | null>(null)

/**
 * 当前会话的全部轮次（时间正序）。
 *
 * 一轮 = 一个 run = 一条回答（可能带一张审批卡）。
 * 这里**不按 activeId 过滤**：会话里每一轮都要同时可见，
 * 只有"当前选中的那一轮"才进右侧详情抽屉。
 */
const turns = computed(() =>
  taskStore.tasks.value
    .filter((task) => task.conversationId === activeConversationId.value)
    .slice()
    .sort((a, b) => a.createdAt - b.createdAt),
)

/**
 * 详情抽屉跟随谁：
 * - 从「独立任务」列表点进来的 run（conversationId 为 null）→ 看它自己；
 * - 其余情况 → 跟随当前会话的最后一轮（最新状态最有用）。
 */
const activeTask = computed(() => {
  const selected = taskStore.activeTask.value
  if (selected?.runId && selected.conversationId === null) {
    return selected
  }
  return turns.value[turns.value.length - 1] ?? null
})

const tasks = taskStore.tasks

const hasPanel = computed(() => !!activeTask.value?.runId)

/** 会话里还有执行中/待审批的轮次：不允许再发第二条 */
const busyTurn = computed(() =>
  turns.value.find(
    (task) =>
      task.transport === 'streaming' ||
      task.transport === 'submitting' ||
      (!!task.serverState && !isTerminalState(task.serverState)),
  ),
)

const composerBlocked = computed(() => !!busyTurn.value)

const blockHint = computed(() => {
  const task = busyTurn.value
  if (!task) {
    return ''
  }
  if (task.serverState === 'WAITING_APPROVAL') {
    return '这个会话有一条写操作在等你确认：先在上面批准或拒绝，再继续。'
  }
  return '这个会话还有任务在执行：等它结束再发下一条，或新建一个会话。'
})

const capabilityText = computed(() =>
  session.capabilityMode === 'write_approval' ? WRITE_CAPABILITY_TEXT : READ_ONLY_CAPABILITY_TEXT,
)

/** 顶栏只写面向用户的准确文案，不出现 runId / epoch / token */
const capabilityLabel = computed(() =>
  session.capabilityMode === 'write_approval' ? '写入需确认' : '仅查询',
)

const capabilityTitle = computed(() =>
  session.capabilityMode === 'write_approval'
    ? '写操作会先暂停并生成审批卡，需要你确认后才执行'
    : '当前装配没有写工具，只能查询',
)

const placeholder = computed(() =>
  session.entry.value === 'MUSIC_ADMIN'
    ? '向音乐管理员 Agent 提问，例如：最近 30 天播放最多的歌曲有哪些？'
    : '描述你的音乐需求，例如：帮我找一下夜航星的歌',
)

const topbarTitle = computed(() => {
  const current = conversations.list.find(
    (item) => item.conversationId === activeConversationId.value,
  )
  if (current?.title) {
    return current.title
  }
  return turns.value[0]?.prompt || '新会话'
})

const topbarState = computed(() => stateMeta(activeTask.value?.serverState ?? null))

/* ==================== 滚动：只在底部附近才跟随 ==================== */

const atBottom = ref(true)
/** 离底部足够远才算"在读历史"，避免按钮在阈值附近闪烁 */
const farFromBottom = ref(false)
/** 与底部的距离小于这个值，就认为用户仍在底部（继续跟随） */
const FOLLOW_THRESHOLD = 80
/** 超过这个距离才显示「回到最新」 */
const JUMP_THRESHOLD = 240

const showJump = computed(() => farFromBottom.value && turns.value.length > 0)

function measureBottom(): void {
  const el = scroller.value
  if (!el) {
    return
  }
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight
  atBottom.value = distance <= FOLLOW_THRESHOLD
  farFromBottom.value = distance > JUMP_THRESHOLD
}

function onScroll(): void {
  measureBottom()
}

/**
 * 滚到底部。
 *
 * 刻意不用 scrollTo({behavior:'smooth'})：动画在截图/无头环境下等于"没滚动"，
 * 而且平滑滚动会把"跟随"变成不可控的异步行为。
 */
function scrollToBottom(force = false): void {
  if (!force && !atBottom.value) {
    return
  }
  void nextTick(() => {
    const el = scroller.value
    if (!el) {
      return
    }
    el.scrollTop = el.scrollHeight
    atBottom.value = true
    farFromBottom.value = false
  })
}

/** 切换会话 / 首次加载：一定落到底部（最新一轮） */
function resetToLatest(): void {
  atBottom.value = true
  farFromBottom.value = false
  void nextTick(() => {
    const el = scroller.value
    if (!el) {
      return
    }
    el.scrollTop = el.scrollHeight
  })
}

/**
 * 内容变化后跟随。
 *
 * 只跟随"当前会话"的轮次数与最后一轮回答长度：
 * 别的会话的流式事件不会进入 turns，也就不会把当前视图拉走。
 */
const contentSignature = computed(() => {
  const last = turns.value[turns.value.length - 1]
  return `${turns.value.length}:${last?.answer.length ?? 0}:${last?.approvalPayload?.approvalId ?? ''}`
})

watch(contentSignature, () => {
  if (atBottom.value) {
    scrollToBottom(true)
  }
})

watch(activeConversationId, () => {
  resetToLatest()
})

/* ==================== 播放卡：会话真实引用 → 可点击曲目 ==================== */

const playbackCandidates = ref<PlaybackCandidateVO[]>([])
let candidatesController: AbortController | null = null
let candidatesTimer: ReturnType<typeof setTimeout> | null = null

/**
 * 拉取当前会话的可播放候选。
 *
 * 数据源是会话已存的业务引用（真实工具结果），不是模型回答文本。
 * 只在"这一轮已经有结果"时拉，避免流式过程中每个 token 都请求一次。
 */
async function loadCandidates(): Promise<void> {
  const conversationId = activeConversationId.value
  if (!conversationId) {
    playbackCandidates.value = []
    return
  }

  candidatesController?.abort()
  candidatesController = new AbortController()

  try {
    const list = await getPlaybackCandidates(conversationId, candidatesController.signal)
    // 期间用户切了会话：旧结果不能写进新会话
    if (activeConversationId.value === conversationId) {
      playbackCandidates.value = list
    }
  } catch (error) {
    if (error instanceof ApiError && error.kind === 'aborted') {
      return
    }
    playbackCandidates.value = []
  }
}

function scheduleCandidates(): void {
  if (candidatesTimer) {
    clearTimeout(candidatesTimer)
  }
  candidatesTimer = setTimeout(() => {
    candidatesTimer = null
    void loadCandidates()
  }, 350)
}

/** 只在轮次数 / 末轮状态变化时重新拉（不跟随流式文本长度） */
const candidatesKey = computed(() => {
  const last = turns.value[turns.value.length - 1]
  return `${activeConversationId.value ?? ''}:${turns.value.length}:${last?.serverState ?? ''}:${last?.transport ?? ''}`
})

watch(candidatesKey, () => {
  scheduleCandidates()
})

onMounted(() => {
  void session.loadCapabilities()

  const prefill = new URLSearchParams(window.location.search).get('q')
  if (prefill && !tasks.value.length) {
    composer.value?.fill(prefill)
  }

  /*
   * 刷新后回到同一个会话：会话 id 存在 localStorage，
   * 消息从服务端重新拉（不是前端内存），所以刷新不丢。
   */
  void refreshConversations()
  if (activeConversationId.value) {
    void openConversation(activeConversationId.value).then(() => {
      resetToLatest()
      scheduleCandidates()
    })
  } else {
    scheduleCandidates()
  }
})

/**
 * 提交一条消息。
 *
 * 有会话就追加到会话；没有就**先建会话**再发——
 * 静默退化成独立任务等于又回到"每发一条就新开一个窗口"。
 */
async function onSubmit(prompt: string): Promise<void> {
  if (composerBlocked.value) {
    return
  }
  let conversationId: string
  try {
    conversationId = await ensureConversation()
  } catch (error) {
    composer.value?.fill(prompt)
    window.alert(`无法创建会话：${String(error)}`)
    return
  }

  const started = await taskStore.submit(prompt, session.entry.value, conversationId)
  if (started) {
    navOpen.value = false
    atBottom.value = true
    scrollToBottom(true)
    void refreshConversations()
  }
}

function onPick(prompt: string): void {
  composer.value?.fill(prompt)
}

async function onSelectConversation(conversationId: string): Promise<void> {
  navOpen.value = false
  await openConversation(conversationId)
  resetToLatest()
}

/** 新建会话：只在用户明确点这个按钮时创建 */
async function onNewConversation(): Promise<void> {
  navOpen.value = false
  try {
    await newConversation()
  } catch {
    // 建不出来就退到"未开始"状态，用户仍可在下一条消息里重试
    clearActiveConversation()
  }
  resetToLatest()
  composer.value?.fill('')
}

/**
 * 打开一条独立任务（老的入口）。
 *
 * 它不是一段对话，因此不进消息流：直接把详情抽屉打开，
 * 看这次提交的状态、耗时与工具轨迹。
 */
async function onSelectRun(runId: string): Promise<void> {
  await taskStore.openHistoryRun(runId)
  navOpen.value = false
  if (activeTask.value?.runId === runId) {
    panelOpen.value = true
  }
}

/**
 * 审批通过后自动刷新"我的歌单"缓存。
 *
 * 抽屉可能正开着：批准后数据库里多了歌单，界面却还是旧列表，
 * 用户会以为没写成功。只在 APPROVED 时刷，拒绝不动数据。
 */
watch(
  () => turns.value.map((task) => task.approval?.status ?? '').join('|'),
  (statuses) => {
    if (statuses.split('|').includes('APPROVED')) {
      void refreshPlaylists({ resetSongs: true })
    }
  },
)

function onOpenLibrary(): void {
  libraryOpen.value = true
  navOpen.value = false
}

/** 关闭后焦点回到触发按钮，键盘用户不会被丢到页首 */
function onCloseLibrary(): void {
  libraryOpen.value = false
  void nextTick(() => libraryTrigger.value?.focus())
}

function closeOverlays(): void {
  navOpen.value = false
  panelOpen.value = false
}

function togglePanel(): void {
  panelOpen.value = !panelOpen.value
  /*
   * 会话回填出来的轮次只有状态，没有 run 详情（token / 工具轨迹）。
   * 打开抽屉时补查一次，否则整页都是"暂无数据"。
   */
  const task = activeTask.value
  if (panelOpen.value && task?.runId && !task.detail) {
    void taskStore.recheck(task.id)
  }
}

function toggleNav(): void {
  navOpen.value = !navOpen.value
}

/*
 * 审批与任务操作：都按**轮次 id** 派发。
 * 会话里可能同时存在多张卡（历史轮次的卡只读展示），
 * 所以不能依赖"当前选中的那个任务"。
 */
function onApprove(taskId: string): void {
  void taskStore.decide(taskId, true)
}

function onReject(taskId: string): void {
  void taskStore.decide(taskId, false)
}

function onResume(taskId: string): void {
  void taskStore.resume(taskId)
}

function onRefreshApproval(taskId: string): void {
  void taskStore.refreshApproval(taskId)
}

function onCancel(taskId: string): void {
  void taskStore.cancel(taskId)
}

function onRecheck(taskId: string): void {
  void taskStore.recheck(taskId)
}
</script>

<template>
  <AppShell
    :panel-open="panelOpen && hasPanel"
    :nav-open="navOpen"
    @toggle-nav="toggleNav"
    @close-overlays="closeOverlays"
  >
    <template #nav>
      <SideNav
        @new-conversation="onNewConversation"
        @select-conversation="onSelectConversation"
        @select-run="onSelectRun"
        @navigate-preview="router.push('/preview/approval')"
      />
    </template>

    <template #topbar>
      <div class="topbar">
        <h1 class="topbar__title" :title="topbarTitle">{{ topbarTitle }}</h1>
        <span v-if="activeTask?.serverState" class="topbar__state">
          {{ topbarState.label }}
        </span>

        <div class="topbar__right">
          <button
            ref="libraryTrigger"
            type="button"
            class="topbar__btn"
            aria-label="打开我的歌单"
            @click="onOpenLibrary"
          >
            <AppIcon name="folder" :size="14" />
            <span class="topbar__btn-label">我的歌单</span>
          </button>
          <button
            type="button"
            class="topbar__btn"
            aria-label="打开音乐账号"
            @click="accountOpen = true"
          >
            <AppIcon name="user" :size="14" />
            <span class="topbar__btn-label">音乐账号</span>
          </button>
          <!--
            商城入口：独立路由，不占用音乐工作区的任何结构。
            音乐与商城共用同一个外壳与联调身份，但业务与数据完全分开。
          -->
          <RouterLink class="topbar__btn" to="/shop" aria-label="打开商城">
            <AppIcon name="list" :size="14" />
            <span class="topbar__btn-label">商城</span>
          </RouterLink>
          <span class="topbar__cap" :title="capabilityTitle">{{ capabilityLabel }}</span>
          <button
            v-if="hasPanel"
            type="button"
            class="topbar__icon"
            :aria-label="panelOpen ? '收起任务详情' : '展开任务详情'"
            :aria-expanded="panelOpen"
            @click="togglePanel"
          >
            <AppIcon name="sliders" :size="16" />
          </button>
        </div>
      </div>
    </template>

    <div class="workspace">
      <div class="workspace__stage">
        <div ref="scroller" class="workspace__scroll" @scroll.passive="onScroll">
          <div class="workspace__inner">
            <EmptyState
              v-if="!turns.length"
              :mode="session.capabilityMode"
              :capability-text="capabilityText"
              @pick="onPick"
              @open-library="onOpenLibrary"
            />

            <div v-else class="turns">
              <TaskConversation
                v-for="turn in turns"
                :key="turn.id"
                :task="turn"
                @approve="onApprove(turn.id)"
                @reject="onReject(turn.id)"
                @resume="onResume(turn.id)"
                @refresh-approval="onRefreshApproval(turn.id)"
                @cancel="onCancel(turn.id)"
                @stop-waiting="taskStore.abortWaiting()"
                @recheck="onRecheck(turn.id)"
              />
            </div>

            <!--
              播放卡：只在会话里有真实工具引用时出现。
              它跟着内容滚动，不是浮层，因此不会压住输入区或审批按钮。
            -->
            <PlayCard
              v-if="turns.length && playbackCandidates.length"
              :candidates="playbackCandidates"
            />
          </div>
        </div>

        <Transition name="jump">
          <button v-if="showJump" type="button" class="workspace__jump" @click="scrollToBottom(true)">
            <AppIcon name="chevron-down" :size="13" />
            回到最新
          </button>
        </Transition>
      </div>

      <div class="workspace__composer">
        <div class="workspace__composer-inner">
          <p v-if="blockHint" class="workspace__block" role="status">{{ blockHint }}</p>
          <TaskComposer
            ref="composer"
            :disabled="false"
            :busy="taskStore.isBusy.value || composerBlocked"
            :entry="session.entry.value"
            :show-entry-switch="session.canSwitchEntry"
            :placeholder="placeholder"
            @submit="onSubmit"
            @entry="session.setEntry"
          />
          <p class="workspace__disclaimer">
            回答由模型生成，业务结果以任务记录为准。同一会话内模型能看到最近几轮对话与已确认的歌曲/歌单；
            换会话则互不可见。
          </p>
        </div>
      </div>
    </div>

    <template #panel>
      <TaskDetailPanel
        :task="activeTask"
        @close="panelOpen = false"
        @recheck="activeTask && onRecheck(activeTask.id)"
      />
    </template>
  </AppShell>

  <LibraryDrawer :open="libraryOpen" @close="onCloseLibrary" />
  <MusicAccountDrawer :open="accountOpen" @close="accountOpen = false" />
</template>

<style scoped>
/**
 * 工作区 = 消息区（唯一滚动容器）+ 输入区（固定底边）。
 *
 * 用 grid 而不是 flex：行高分配是显式的，
 * 输入区不会因为消息内容多少而改变位置。
 */
.workspace {
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.workspace__stage {
  position: relative;
  min-height: 0;
  min-width: 0;
}

.workspace__scroll {
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
  /* 滚动条出现/消失时不再改变内容宽度，中心线不会左右跳 */
  scrollbar-gutter: stable;
}

.workspace__inner {
  width: 100%;
  max-width: var(--center-max);
  margin: 0 auto;
  padding: 0 var(--s-5);
  min-width: 0;
}

/* 会话是追加式列表：轮次之间用分隔线，不套卡片 */
.turns {
  display: grid;
  padding-bottom: var(--s-6);
}

/**
 * 「回到最新」贴着右下角。
 *
 * 不居中：正文左对齐，行尾大多是空白，靠右的胶囊几乎不会压到字；
 * 也不放进输入区——那会让输入框随它的出现而位移。
 */
.workspace__jump {
  position: absolute;
  right: var(--s-4);
  bottom: var(--s-3);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 var(--s-3);
  border-radius: var(--r-pill);
  border: 1px solid var(--c-line-strong);
  background: var(--c-surface-2);
  color: var(--c-text-2);
  font-size: var(--fs-xs);
  box-shadow: var(--shadow-md);
}

.workspace__jump:hover {
  color: var(--c-text);
  border-color: var(--c-text-3);
}

.jump-enter-active,
.jump-leave-active {
  transition: opacity var(--dur-fast) var(--ease);
}

.jump-enter-from,
.jump-leave-to {
  opacity: 0;
}

.workspace__block {
  margin-bottom: var(--s-2);
  padding: var(--s-2) var(--s-3);
  border-radius: var(--r-sm);
  border: 1px solid rgba(224, 177, 95, 0.24);
  background: var(--c-warn-weak);
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  line-height: var(--lh-base);
}

.workspace__composer {
  width: 100%;
  background: var(--c-bg);
  border-top: 1px solid var(--c-line);
  padding: var(--s-3) 0 var(--s-2);
  /* 全面屏底部安全区：有 env() 时取较大值，没有时退回 8px */
  padding-bottom: max(var(--s-2), env(safe-area-inset-bottom));
}

.workspace__composer-inner {
  max-width: var(--center-max);
  margin: 0 auto;
  padding: 0 var(--s-5);
}

.workspace__disclaimer {
  margin-top: var(--s-2);
  text-align: center;
  font-size: 10.5px;
  color: var(--c-text-3);
  line-height: 1.55;
}

/* ---- 顶栏 ---- */
.topbar {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  min-width: 0;
  width: 100%;
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

.topbar__state {
  flex: none;
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.topbar__right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex: none;
}

.topbar__btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 var(--s-2);
  border-radius: var(--r-sm);
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  /* 商城入口是 RouterLink（<a>），需要显式去掉下划线 */
  text-decoration: none;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.topbar__btn:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.topbar__cap {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 var(--s-2);
  border-radius: var(--r-pill);
  border: 1px solid var(--c-line);
  background: var(--c-surface);
  font-size: 10.5px;
  letter-spacing: 0.02em;
  color: var(--c-text-2);
  white-space: nowrap;
}

.topbar__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  color: var(--c-text-3);
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.topbar__icon:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
}

@media (max-width: 899px) {
  .workspace__inner,
  .workspace__composer-inner {
    padding: 0 var(--s-4);
  }

  .topbar__state {
    display: none;
  }
}

@media (max-width: 560px) {
  .topbar__btn-label {
    display: none;
  }
}
</style>
