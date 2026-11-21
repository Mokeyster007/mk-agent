<script setup lang="ts">
/**
 * 左侧导航。
 *
 * 三块内容，来源必须分清楚：
 * 1. 会话：GET /ai/conversations 的真实服务端记录（跨刷新一致），这是**聊天历史**；
 * 2. 独立任务：GET /ai/runs/page 的单次提交记录，老的入口，折叠在下方，
 *    不与会话混排——一次提交不是一段对话；
 * 3. 开发联调：仅在 DEV 且允许时出现，标注"不代表真实登录"。
 *
 * 侧栏只承担导航，不承担状态展示：能力模式、歌单入口都在主区顶栏。
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
import {
  activeConversationId,
  conversations,
  refreshConversations,
} from '@/stores/conversation'
import { session } from '@/stores/session'
import { stateMeta } from '@/utils/status'
import { formatRelative } from '@/utils/format'

const emit = defineEmits<{
  (e: 'new-conversation'): void
  (e: 'select-conversation', conversationId: string): void
  (e: 'select-run', runId: string): void
  (e: 'navigate-preview'): void
}>()

/** 模块级标记：组件重挂载不重复拉取，避免重复请求 */
let historyRequested = false
let conversationsRequested = false

const identityDraft = ref(session.userId.value)
const identityEditing = ref(false)
const identityError = ref('')

onMounted(() => {
  if (!conversationsRequested) {
    conversationsRequested = true
    void refreshConversations()
  }
  // 独立任务列表默认折叠，不抢首屏——但它仍然是真实数据，不删功能
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

/**
 * 会话时间。
 *
 * updatedAt 是 ISO 字符串，formatRelative 收的是毫秒数；
 * 解析失败时返回空串而不是 "Invalid Date"。
 */
function relativeTime(value: string | null): string {
  if (!value) {
    return ''
  }
  const at = Date.parse(value)
  return Number.isNaN(at) ? '' : formatRelative(at)
}

const historyEmptyHint = computed(() =>
  history.records.length
    ? '暂无其他独立任务，本次会话的任务已列在上面'
    : '暂无独立任务',
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
        <AppIcon name="wave" :size="17" />
      </span>
      <span class="nav__brand-text">
        <span class="nav__name">MkAgent</span>
        <span class="nav__tagline">音乐工作台</span>
      </span>
    </div>

    <button type="button" class="nav__new" @click="emit('new-conversation')">
      <AppIcon name="plus" :size="15" />
      新建会话
    </button>

    <div class="nav__scroll">
      <section class="nav__group" aria-labelledby="nav-conv-label">
        <p id="nav-conv-label" class="nav__group-label">会话</p>

        <p v-if="conversations.loading && !conversations.list.length" class="nav__hint">
          正在读取…
        </p>

        <div v-else-if="conversations.error" class="nav__error">
          <p>{{ conversations.error }}</p>
          <button type="button" class="mk-btn mk-btn--sm" @click="refreshConversations()">
            重试
          </button>
        </div>

        <p v-else-if="!conversations.list.length" class="nav__hint">
          还没有会话。发一条消息会自动建一个，之后可以在同一个会话里接着说。
        </p>

        <ul v-else class="nav__list">
          <li v-for="item in conversations.list" :key="item.conversationId">
            <button
              type="button"
              class="nav__item"
              :class="{
                'nav__item--active': item.conversationId === activeConversationId,
              }"
              :aria-current="item.conversationId === activeConversationId ? 'true' : undefined"
              @click="emit('select-conversation', item.conversationId)"
            >
              <span class="nav__item-title" :title="item.title || '新会话'">
                {{ item.title || '新会话' }}
              </span>
              <span class="nav__item-meta">
                <TaskStatusBadge
                  v-if="item.lastState"
                  :label="stateMeta(item.lastState).label"
                  :tone="stateMeta(item.lastState).tone"
                  size="sm"
                />
                <span class="nav__item-time">{{ relativeTime(item.updatedAt) }}</span>
              </span>
            </button>
          </li>
        </ul>
      </section>

      <!-- 独立任务：折叠，不与会话混排 -->
      <details class="nav__group nav__fold">
        <summary class="nav__fold-head">
          <span class="nav__group-label">独立任务</span>
          <AppIcon name="chevron-right" :size="13" class="nav__fold-chevron" />
        </summary>

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
              class="nav__item nav__item--compact"
              :class="{ 'nav__item--active': taskStore.activeTask.value?.runId === record.runId }"
              @click="emit('select-run', record.runId)"
            >
              <span class="nav__item-title" :title="record.userPrompt || ''">
                {{ record.userPrompt || '（无输入内容）' }}
              </span>
              <span class="nav__item-meta">
                <TaskStatusBadge
                  :label="stateMeta(record.state).label"
                  :tone="stateMeta(record.state).tone"
                  size="sm"
                />
                <span class="nav__item-time">
                  {{ record.createdAt?.slice(5, 16).replace('T', ' ') }}
                </span>
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
      </details>

      <button type="button" class="nav__link" @click="emit('navigate-preview')">
        <AppIcon name="external" :size="13" />
        审批卡组件预览
      </button>
    </div>

    <!-- 当前用户（开发联调时可改，正式构建只读） -->
    <footer class="nav__foot">
      <div class="nav__user">
        <span class="nav__avatar" aria-hidden="true">
          <AppIcon name="user" :size="14" />
        </span>
        <span class="nav__user-main">
          <span class="nav__user-label">当前用户</span>
          <span class="nav__user-id" :title="session.userId.value">{{ session.userId.value }}</span>
        </span>
        <button
          v-if="session.allowDevIdentity && !identityEditing"
          type="button"
          class="nav__user-edit"
          @click="identityEditing = true"
        >
          修改
        </button>
      </div>

      <div v-if="identityEditing" class="nav__edit">
        <input
          v-model="identityDraft"
          class="mk-input nav__edit-input"
          aria-label="开发身份"
          placeholder="例如 music-user-1"
          @keydown.enter="applyIdentity"
        />
        <div class="nav__edit-actions">
          <button type="button" class="mk-btn mk-btn--sm mk-btn--primary" @click="applyIdentity">
            应用
          </button>
          <button type="button" class="mk-btn mk-btn--sm mk-btn--ghost" @click="resetIdentity">
            重置
          </button>
        </div>
        <p class="nav__edit-note">开发身份，不代表真实登录；权限由后端校验。</p>
      </div>
      <p v-if="identityError" class="nav__edit-error">{{ identityError }}</p>
    </footer>
  </nav>
</template>

<style scoped>
.nav {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  height: 100%;
  min-height: 0;
  border-right: 1px solid var(--c-line);
  background: var(--c-bg-nav);
}

.nav__brand {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-5) var(--s-4) var(--s-4);
  min-width: 0;
}

.nav__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: var(--r-sm);
  background: var(--c-accent-weak);
  border: 1px solid var(--c-accent-line);
  color: var(--c-accent);
}

.nav__brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.25;
}

.nav__name {
  font-size: var(--fs-base);
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--c-text);
}

.nav__tagline {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  letter-spacing: 0.04em;
}

.nav__new {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--s-2);
  margin: 0 var(--s-4) var(--s-4);
  height: 36px;
  border-radius: var(--r-sm);
  border: 1px solid var(--c-accent-line);
  background: var(--c-accent-weak-2);
  color: var(--c-accent-ink);
  font-size: var(--fs-sm);
  font-weight: 500;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}

.nav__new:hover {
  background: var(--c-accent-weak);
  border-color: var(--c-accent);
}

.nav__scroll {
  min-height: 0;
  overflow-y: auto;
  padding: 0 var(--s-3) var(--s-4);
  display: grid;
  gap: var(--s-4);
  align-content: start;
}

.nav__group {
  display: grid;
  gap: var(--s-1);
  min-width: 0;
}

.nav__group-label {
  padding: 0 var(--s-2) var(--s-1);
  font-size: var(--fs-xs);
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--c-text-3);
}

.nav__list {
  display: grid;
  gap: 1px;
  min-width: 0;
}

.nav__item {
  position: relative;
  display: grid;
  gap: 4px;
  width: 100%;
  padding: var(--s-2) var(--s-3);
  border-radius: var(--r-sm);
  text-align: left;
  min-width: 0;
  transition: background var(--dur-fast) var(--ease);
}

.nav__item:hover {
  background: var(--c-surface-hover);
}

/* 选中态：底色 + 左侧 2px 强调条，不靠加粗字重 */
.nav__item--active {
  background: var(--c-surface);
}

.nav__item--active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 2px;
  border-radius: var(--r-pill);
  background: var(--c-accent);
}

.nav__item-title {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: var(--fs-sm);
  line-height: 1.5;
  color: var(--c-text);
  overflow-wrap: anywhere;
}

.nav__item--active .nav__item-title {
  color: var(--c-text);
}

.nav__item--compact .nav__item-title {
  -webkit-line-clamp: 1;
  line-clamp: 1;
  color: var(--c-text-2);
  font-size: var(--fs-xs);
}

.nav__item-meta {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  min-width: 0;
}

.nav__item-time {
  font-size: 10.5px;
  color: var(--c-text-3);
  white-space: nowrap;
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
  margin: var(--s-1) var(--s-2) 0;
}

/* ---- 折叠区 ---- */
.nav__fold {
  border-top: 1px solid var(--c-line-soft);
  padding-top: var(--s-2);
}

.nav__fold-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2);
  padding: 0 var(--s-2);
  cursor: pointer;
  list-style: none;
}

.nav__fold-head::-webkit-details-marker {
  display: none;
}

.nav__fold-chevron {
  color: var(--c-text-3);
  transition: transform var(--dur-fast) var(--ease);
}

.nav__fold[open] .nav__fold-chevron {
  transform: rotate(90deg);
}

.nav__fold[open] .nav__fold-head {
  margin-bottom: var(--s-1);
}

.nav__link {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 var(--s-2);
  padding: var(--s-2);
  border-radius: var(--r-sm);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.nav__link:hover {
  color: var(--c-text-2);
  background: var(--c-surface-hover);
}

/* ---- 当前用户 ---- */
.nav__foot {
  border-top: 1px solid var(--c-line);
  padding: var(--s-3) var(--s-3) var(--s-3);
  display: grid;
  gap: var(--s-2);
}

.nav__user {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  min-width: 0;
}

.nav__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  flex: none;
  border-radius: 50%;
  background: var(--c-surface-2);
  color: var(--c-text-2);
}

.nav__user-main {
  display: grid;
  min-width: 0;
  line-height: 1.3;
}

.nav__user-label {
  font-size: 10.5px;
  color: var(--c-text-3);
}

.nav__user-id {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav__user-edit {
  margin-left: auto;
  flex: none;
  font-size: var(--fs-xs);
  color: var(--c-accent-ink);
}

.nav__edit {
  display: grid;
  gap: var(--s-2);
}

.nav__edit-input {
  height: 30px;
  font-size: var(--fs-sm);
}

.nav__edit-actions {
  display: flex;
  gap: 6px;
}

.nav__edit-note,
.nav__edit-error {
  font-size: 10.5px;
  line-height: 1.5;
  color: var(--c-text-3);
}

.nav__edit-error {
  color: var(--c-danger);
}
</style>
