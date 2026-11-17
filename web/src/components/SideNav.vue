<script setup lang="ts">
/**
 * 左侧导航。
 *
 * 三块内容，来源必须分清楚：
 * 1. 本次会话：浏览器内存里的任务（刷新即失，不跨设备）；
 * 2. 历史任务：GET /ai/runs/page 的真实服务端记录（只含自己的音乐任务）；
 * 3. 开发联调：仅在 DEV 且允许时出现，标注"不代表真实登录"。
 *
 * 只有真实接口支持时才显示入口——没有"我的歌单"接口，所以这里没有这个导航。
 */
import { computed, onMounted, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import TaskStatusBadge from './TaskStatusBadge.vue'
import {
  hasMoreHistory,
  history,
  loadHistory,
  taskStore,
} from '@/stores/taskStore'
import { session } from '@/stores/session'
import { stateMeta } from '@/utils/status'
import { formatRelative } from '@/utils/format'

const emit = defineEmits<{
  (e: 'new-task'): void
  (e: 'select', id: string): void
  (e: 'select-run', runId: string): void
  (e: 'navigate-preview'): void
  (e: 'open-library'): void
}>()

/** 模块级标记：组件重挂载不重复拉取，避免重复请求 */
let historyRequested = false

const identityDraft = ref(session.userId.value)
const identityEditing = ref(false)
const identityError = ref('')

onMounted(() => {
  if (!historyRequested) {
    historyRequested = true
    void loadHistory({ reset: true })
  }
})

const sessionTasks = computed(() => taskStore.tasks.value)

/** 服务端历史里去掉已在本次会话出现过的 runId，避免同一任务出现两次 */
const historyRecords = computed(() => {
  const localRunIds = new Set(
    sessionTasks.value.map((task) => task.runId).filter((id): id is string => !!id),
  )
  return history.records.filter((record) => !localRunIds.has(record.runId))
})

const capabilityLabel = computed(() =>
  session.capabilityMode === 'write_approval' ? '审批写' : '只读',
)

/**
 * 历史为空时的说明。
 *
 * 服务端确实有记录、只是都在本次会话里出现过时，不能写"暂无历史任务"——
 * 那会让用户以为历史接口没生效。
 */
const historyEmptyHint = computed(() =>
  history.records.length
    ? '暂无其他历史任务，本次会话的任务已列在上面'
    : '暂无历史任务',
)

function applyIdentity(): void {
  const value = identityDraft.value.trim()
  if (!value) {
    identityError.value = '身份不能为空'
    return
  }
  if (!session.updateUserId(value)) {
    identityError.value = '当前构建不允许修改身份'
    return
  }
  identityError.value = ''
  identityEditing.value = false
  historyRequested = true
  void loadHistory({ reset: true })
}

function resetIdentity(): void {
  session.resetUserId()
  identityDraft.value = session.userId.value
  identityError.value = ''
  void loadHistory({ reset: true })
}
</script>

<template>
  <nav class="nav" aria-label="主导航">
    <div class="nav__brand">
      <span class="nav__mark" aria-hidden="true">
        <AppIcon name="wave" :size="18" />
      </span>
      <div class="nav__brand-text">
        <span class="nav__name">Cadence</span>
        <span class="nav__tagline">音乐 Agent 工作台</span>
      </div>
    </div>

    <button type="button" class="nav__new" @click="emit('new-task')">
      <AppIcon name="plus" :size="15" />
      新任务
    </button>

    <!--
      真实数据入口：打开后查的是数据库里的歌单与曲目，不是任务结果。
      审批写闭环的验收就看这里——批准后刷新，能看到新歌单才算真的写进去。
    -->
    <button type="button" class="nav__new nav__new--sub" @click="emit('open-library')">
      <AppIcon name="folder" :size="15" />
      我的歌单
    </button>

    <div class="nav__mode">
      <span class="mk-eyebrow">能力模式</span>
      <div class="nav__mode-row">
        <TaskStatusBadge
          :label="capabilityLabel"
          :tone="session.capabilityMode === 'write_approval' ? 'wait' : 'neutral'"
          size="sm"
        />
        <span class="nav__mode-note">
          {{ session.capabilityMode === 'write_approval' ? '写操作需审批' : '不含写操作' }}
        </span>
      </div>
    </div>

    <div class="nav__scroll">
      <!-- 本次会话 -->
      <section v-if="sessionTasks.length" class="nav__group">
        <p class="mk-eyebrow nav__group-label">本次会话</p>
        <ul class="nav__list">
          <li v-for="task in sessionTasks" :key="task.id">
            <button
              type="button"
              class="nav__item"
              :class="{ 'nav__item--active': task.id === taskStore.activeId.value }"
              @click="emit('select', task.id)"
            >
              <span class="nav__item-top">
                <span class="nav__item-prompt">{{ task.prompt || '（历史任务）' }}</span>
              </span>
              <span class="nav__item-bottom">
                <TaskStatusBadge
                  :label="stateMeta(task.serverState).label"
                  :tone="stateMeta(task.serverState).tone"
                  size="sm"
                />
                <span class="nav__item-time">{{ formatRelative(task.createdAt) }}</span>
              </span>
            </button>
          </li>
        </ul>
        <p class="nav__hint">仅保存在当前浏览器内存中，刷新后需要从历史任务里找。</p>
      </section>

      <!-- 服务端历史 -->
      <section class="nav__group">
        <p class="mk-eyebrow nav__group-label">历史任务</p>

        <p v-if="history.loading && !history.records.length" class="nav__hint">正在读取…</p>

        <div v-else-if="history.error" class="nav__error">
          <p>{{ history.error }}</p>
          <button type="button" class="mk-btn mk-btn--sm" @click="loadHistory({ reset: true })">
            重试
          </button>
        </div>

        <p v-else-if="!historyRecords.length" class="nav__hint">{{ historyEmptyHint }}</p>

        <ul v-else class="nav__list">
          <li v-for="record in historyRecords" :key="record.runId">
            <button
              type="button"
              class="nav__item"
              :class="{ 'nav__item--active': taskStore.activeTask.value?.runId === record.runId }"
              @click="emit('select-run', record.runId)"
            >
              <span class="nav__item-top">
                <span class="nav__item-prompt">{{ record.userPrompt || '（无输入内容）' }}</span>
              </span>
              <span class="nav__item-bottom">
                <TaskStatusBadge
                  :label="stateMeta(record.state).label"
                  :tone="stateMeta(record.state).tone"
                  size="sm"
                />
                <span class="nav__item-time">{{ record.createdAt?.slice(5, 16).replace('T', ' ') }}</span>
              </span>
            </button>
          </li>
        </ul>

        <button
          v-if="hasMoreHistory()"
          type="button"
          class="mk-btn mk-btn--sm mk-btn--ghost nav__more"
          :disabled="history.loading"
          @click="loadHistory()"
        >
          {{ history.loading ? '读取中…' : '加载更多' }}
        </button>
      </section>

      <button type="button" class="nav__link" @click="emit('navigate-preview')">
        <AppIcon name="external" :size="14" />
        审批卡组件预览
      </button>
    </div>

    <!-- 开发联调：仅 DEV -->
    <div v-if="session.allowDevIdentity" class="nav__dev">
      <div class="nav__dev-head">
        <span class="mk-eyebrow">开发联调</span>
        <span class="nav__dev-badge">DEV</span>
      </div>

      <div v-if="!identityEditing" class="nav__dev-row">
        <span class="nav__dev-id mk-mono">{{ session.userId.value }}</span>
        <button type="button" class="nav__dev-edit" @click="identityEditing = true">修改</button>
      </div>

      <div v-else class="nav__dev-edit-box">
        <input
          v-model="identityDraft"
          class="mk-input nav__dev-input"
          aria-label="开发身份"
          placeholder="例如 music-user-1"
          @keydown.enter="applyIdentity"
        />
        <div class="nav__dev-actions">
          <button type="button" class="mk-btn mk-btn--sm mk-btn--primary" @click="applyIdentity">
            应用
          </button>
          <button type="button" class="mk-btn mk-btn--sm mk-btn--ghost" @click="resetIdentity">
            重置
          </button>
        </div>
      </div>

      <p v-if="identityError" class="nav__dev-error">{{ identityError }}</p>
      <p class="nav__dev-note">开发身份，不代表真实登录；权限由后端校验。</p>
    </div>

    <footer class="nav__foot">
      <span class="nav__env">
        <span class="nav__env-dot" />
        {{ session.allowDevIdentity ? '本地联调' : '正式构建' }}
      </span>
      <!-- 不写死后端地址：这里只说明前缀，具体转发目标由 Vite 代理决定 -->
      <span class="nav__env-target">接口前缀 /api</span>
    </footer>
  </nav>
</template>

<style scoped>
.nav {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--c-bg);
  border-right: 1px solid var(--c-line);
}

.nav__brand {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-5) var(--s-4) var(--s-4);
  flex: none;
}

.nav__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: linear-gradient(150deg, rgba(127, 143, 250, 0.9), rgba(127, 143, 250, 0.35));
  color: #0b0d12;
  flex: none;
}

.nav__brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.nav__name {
  font-size: var(--fs-md);
  font-weight: 600;
  letter-spacing: -0.01em;
  line-height: 1.2;
}

.nav__tagline {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  letter-spacing: 0.02em;
}

.nav__new {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  margin: 0 var(--s-4) var(--s-4);
  height: 36px;
  padding: 0 var(--s-3);
  border-radius: var(--r-sm);
  border: 1px solid var(--c-line);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--fs-base);
  font-weight: 500;
  flex: none;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}

.nav__new:hover {
  background: var(--c-surface-2);
  border-color: var(--c-line-strong);
}
.nav__new--sub {
  margin-top: calc(var(--s-3) * -1);
  font-weight: 400;
  color: var(--c-text-2);
}

.nav__mode {
  padding: 0 var(--s-4) var(--s-4);
  display: grid;
  gap: 6px;
  flex: none;
}

.nav__mode-row {
  display: flex;
  align-items: center;
  gap: var(--s-2);
}

.nav__mode-note {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.nav__scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 var(--s-3) var(--s-4);
  display: grid;
  gap: var(--s-5);
  align-content: start;
}

.nav__group {
  display: grid;
  gap: var(--s-2);
}

.nav__group-label {
  padding: 0 var(--s-2);
}

.nav__list {
  display: grid;
  gap: 2px;
}

.nav__item {
  display: grid;
  gap: 5px;
  width: 100%;
  padding: var(--s-2) var(--s-3);
  border-radius: var(--r-sm);
  border: 1px solid transparent;
  text-align: left;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}

.nav__item:hover {
  background: var(--c-surface);
}

.nav__item--active {
  background: var(--c-surface-2);
  border-color: var(--c-line);
}

.nav__item-top {
  min-width: 0;
}

.nav__item-prompt {
  display: block;
  font-size: var(--fs-sm);
  color: var(--c-text);
  line-height: 1.45;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
}

.nav__item-bottom {
  display: flex;
  align-items: center;
  gap: var(--s-2);
}

.nav__item-time {
  font-size: 10.5px;
  color: var(--c-text-3);
}

.nav__hint {
  padding: 0 var(--s-2);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  line-height: var(--lh-base);
}

.nav__error {
  padding: var(--s-2);
  display: grid;
  gap: var(--s-2);
  font-size: var(--fs-xs);
  color: var(--c-warn);
}

.nav__more {
  justify-self: start;
  margin: 0 var(--s-2);
}

.nav__link {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 var(--s-2);
  padding: var(--s-2);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  border-radius: var(--r-sm);
}

.nav__link:hover {
  color: var(--c-text-2);
  background: var(--c-surface);
}

.nav__dev {
  flex: none;
  margin: 0 var(--s-4) var(--s-3);
  padding: var(--s-3);
  border-radius: var(--r-md);
  border: 1px dashed var(--c-line-strong);
  background: var(--c-surface);
  display: grid;
  gap: 6px;
}

.nav__dev-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.nav__dev-badge {
  font-size: 9.5px;
  font-weight: 700;
  letter-spacing: 0.08em;
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--c-accent-weak);
  color: var(--c-accent-ink);
}

.nav__dev-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2);
}

.nav__dev-id {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav__dev-edit {
  font-size: var(--fs-xs);
  color: var(--c-accent-ink);
  flex: none;
}

.nav__dev-edit-box {
  display: grid;
  gap: var(--s-2);
}

.nav__dev-input {
  height: 30px;
  font-size: var(--fs-sm);
}

.nav__dev-actions {
  display: flex;
  gap: 6px;
}

.nav__dev-error {
  font-size: var(--fs-xs);
  color: var(--c-danger);
}

.nav__dev-note {
  font-size: 10.5px;
  color: var(--c-text-3);
  line-height: 1.5;
}

.nav__foot {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2);
  padding: var(--s-3) var(--s-4);
  border-top: 1px solid var(--c-line);
  font-size: 10.5px;
  color: var(--c-text-3);
}

.nav__env {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.nav__env-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--c-ok);
}

.nav__env-target {
  font-family: var(--font-mono);
  opacity: 0.75;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
