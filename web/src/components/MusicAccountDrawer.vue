<script setup lang="ts">
/**
 * 「音乐账号」抽屉。
 *
 * 只做三件事：展示来源与连接状态、切换来源过滤、只读展示获准的数据。
 *
 * 明确的边界：
 * - 不提供"保存到我的歌单"或任何写回平台的动作；
 * - 不按歌名去重：同名歌曲按来源分别展示（身份是 provider + providerTrackId）；
 * - 外部曲目没有本地 songId，也不自动导入曲库，因此不提供播放按钮；
 * - 模拟来源必须标"模拟"，不能显示成"已接网易云 / QQ 音乐"。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
import { providers } from '@/stores/providers'
import type { ProviderTrackKind } from '@/api/providers'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const state = providers.state
const closeBtn = ref<HTMLButtonElement | null>(null)
const query = ref('')

const KINDS: Array<{ key: ProviderTrackKind; label: string }> = [
  { key: 'LIKED', label: '我喜欢的' },
  { key: 'RECENT', label: '最近播放' },
  { key: 'PLAYLISTS', label: '歌单' },
  { key: 'SEARCH', label: '搜索' },
]

const CAP_LABEL: Record<string, string> = {
  CATALOG_SEARCH: '目录搜索',
  READ_PLAYLISTS: '读歌单',
  READ_LIKED: '读喜欢',
  READ_RECENT: '读最近播放',
  PLAYBACK: '站内播放',
}

const STATUS_LABEL: Record<string, string> = {
  OK: '正常',
  TIMEOUT: '超时',
  ERROR: '读取失败',
  PERMISSION_DENIED: '权限不足',
  EXPIRED: '授权已过期',
  REVOKED: '授权已撤销',
  UNKNOWN_PROVIDER: '来源已下线',
}

/** 按来源分组：不在前端按歌名合并 */
const groupedTracks = computed(() => {
  const groups = new Map<string, { name: string; items: typeof state.tracks }>()
  for (const track of state.tracks) {
    const key = track.provider
    if (!groups.has(key)) {
      groups.set(key, {
        name: providerName(key),
        items: [],
      })
    }
    groups.get(key)!.items.push(track)
  }
  return [...groups.entries()].map(([provider, group]) => ({ provider, ...group }))
})

const groupedPlaylists = computed(() => {
  const groups = new Map<string, { name: string; items: typeof state.playlists }>()
  for (const playlist of state.playlists) {
    const key = playlist.provider
    if (!groups.has(key)) {
      groups.set(key, { name: providerName(key), items: [] })
    }
    groups.get(key)!.items.push(playlist)
  }
  return [...groups.entries()].map(([provider, group]) => ({ provider, ...group }))
})

function providerName(provider: string): string {
  return state.catalog.find((item) => item.provider === provider)?.displayName ?? provider
}

function statusTone(status: string): string {
  if (status === 'OK') {
    return 'ok'
  }
  if (status === 'TIMEOUT' || status === 'ERROR' || status === 'UNKNOWN_PROVIDER') {
    return 'danger'
  }
  return 'warn'
}

function formatDuration(seconds: number | null): string {
  if (!seconds) {
    return '—'
  }
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

function trackKey(provider: string, id: string): string {
  return `${provider}:${id}`
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    emit('close')
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) {
      return
    }
    void nextTick(() => closeBtn.value?.focus())
    void providers.open()
  },
  { immediate: true },
)

watch(
  () => props.open,
  (open) => {
    if (typeof window === 'undefined') {
      return
    }
    if (open) {
      window.addEventListener('keydown', onKeydown)
    } else {
      window.removeEventListener('keydown', onKeydown)
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('keydown', onKeydown)
  }
})

async function search(): Promise<void> {
  if (state.kind !== 'SEARCH') {
    await providers.setKind('SEARCH')
  } else {
    await providers.reload()
  }
}
</script>

<template>
  <div v-if="props.open" class="acct">
    <div class="acct__backdrop" @click="emit('close')" />

    <aside class="acct__sheet" role="dialog" aria-label="音乐账号">
      <header class="acct__head">
        <div class="acct__title">
          <AppIcon name="user" :size="16" />
          <span>音乐账号</span>
          <span class="acct__count">{{ providers.connectedCount.value }} 个来源已连接</span>
        </div>
        <div class="acct__actions">
          <button
            type="button"
            class="mk-btn mk-btn--ghost mk-btn--sm"
            :disabled="state.loading"
            @click="providers.open()"
          >
            {{ state.loading ? '读取中…' : '刷新' }}
          </button>
          <button
            ref="closeBtn"
            type="button"
            class="mk-btn mk-btn--ghost mk-btn--sm"
            aria-label="关闭"
            @click="emit('close')"
          >
            <AppIcon name="x" :size="14" />
          </button>
        </div>
      </header>

      <div class="acct__body">
        <p v-if="state.catalogError" class="acct__error">{{ state.catalogError }}</p>

        <!-- 已连接 -->
        <section class="acct__section" aria-labelledby="acct-connected">
          <p id="acct-connected" class="acct__label">已连接</p>

          <p v-if="!providers.connections.value.length" class="acct__hint">
            还没有连接任何音乐账号。未连接时，本地曲库、播放器与歌单都可以照常使用。
          </p>

          <ul v-else class="acct__chips">
            <li
              v-for="item in providers.connections.value"
              :key="item.provider"
              class="acct__chip"
              :class="{ 'is-simulated': item.simulated }"
            >
              <span class="acct__chip-name">{{ item.displayName }}</span>
              <span v-if="item.simulated" class="acct__badge">模拟</span>
              <span class="acct__chip-state" :class="`is-${statusTone(item.status ?? 'OK')}`">
                {{ STATUS_LABEL[item.status ?? 'OK'] ?? item.status }}
              </span>
              <button
                type="button"
                class="acct__chip-btn"
                :disabled="state.busyProvider === item.provider"
                @click="providers.disconnect(item.provider)"
              >
                断开
              </button>
            </li>
          </ul>
        </section>

        <!-- 可连接 -->
        <section v-if="providers.available.value.length" class="acct__section" aria-labelledby="acct-available">
          <p id="acct-available" class="acct__label">可连接</p>

          <ul class="acct__list">
            <li v-for="item in providers.available.value" :key="item.provider" class="acct__row">
              <div class="acct__row-main">
                <span class="acct__row-name">
                  {{ item.displayName }}
                  <span v-if="item.simulated" class="acct__badge">模拟</span>
                </span>
                <span class="acct__caps">
                  <span v-for="cap in item.capabilities" :key="cap" class="acct__cap">
                    {{ CAP_LABEL[cap] ?? cap }}
                  </span>
                </span>
                <span v-if="!item.configured" class="acct__row-note">
                  凭据未配置：只能查看能力，不能绑定
                </span>
              </div>
              <button
                type="button"
                class="mk-btn mk-btn--sm"
                :disabled="state.busyProvider === item.provider"
                @click="providers.connect(item.provider)"
              >
                连接
              </button>
            </li>
          </ul>
        </section>

        <!-- 过滤 + 类别 -->
        <section class="acct__section" aria-labelledby="acct-data">
          <div class="acct__toolbar">
            <p id="acct-data" class="acct__label">只读数据</p>

            <label class="acct__filter">
              <span class="acct__filter-label">来源</span>
              <select
                class="acct__select"
                :value="state.filter ?? ''"
                @change="providers.setFilter(($event.target as HTMLSelectElement).value || null)"
              >
                <option value="">全部已连接来源</option>
                <option
                  v-for="item in providers.connections.value"
                  :key="item.provider"
                  :value="item.provider"
                >
                  {{ item.displayName }}
                </option>
              </select>
            </label>
          </div>

          <div class="acct__tabs" role="tablist" aria-label="数据类型">
            <button
              v-for="kind in KINDS"
              :key="kind.key"
              type="button"
              role="tab"
              class="acct__tab"
              :class="{ 'is-active': state.kind === kind.key }"
              :aria-selected="state.kind === kind.key"
              @click="providers.setKind(kind.key)"
            >
              {{ kind.label }}
            </button>
          </div>

          <form v-if="state.kind === 'SEARCH'" class="acct__search" @submit.prevent="search">
            <input
              v-model="query"
              class="mk-input acct__search-input"
              type="search"
              placeholder="搜索曲目或歌手"
              aria-label="搜索外部曲目"
            />
            <button type="submit" class="mk-btn mk-btn--sm" :disabled="!query.trim()">
              搜索
            </button>
          </form>

          <!-- 来源状态：一个来源失败只影响它自己 -->
          <ul v-if="state.sources.length" class="acct__sources">
            <li
              v-for="source in state.sources"
              :key="source.provider"
              class="acct__source"
              :class="`is-${statusTone(source.status)}`"
            >
              <span class="acct__source-name">
                {{ source.displayName }}
                <span v-if="source.simulated" class="acct__badge">模拟</span>
              </span>
              <span class="acct__source-status">
                {{ STATUS_LABEL[source.status] ?? source.status }}
              </span>
              <span v-if="source.message" class="acct__source-msg">{{ source.message }}</span>
            </li>
          </ul>

          <p v-if="state.error" class="acct__error">{{ state.error }}</p>
          <p v-else-if="state.loading" class="acct__hint">正在读取…</p>

          <div v-else-if="!providers.connections.value.length" class="acct__empty">
            <p class="acct__empty-title">没有可读的来源</p>
            <p class="acct__empty-text">连接一个音乐账号后，这里会显示它授权的歌单与收藏。</p>
          </div>

          <div v-else-if="state.kind === 'PLAYLISTS'" class="acct__groups">
            <div v-for="group in groupedPlaylists" :key="group.provider" class="acct__group">
              <p class="acct__group-head">{{ group.name }}</p>
              <ul class="acct__tracks">
                <li v-for="item in group.items" :key="item.providerPlaylistId" class="acct__track">
                  <span class="acct__track-title">{{ item.name }}</span>
                  <span class="acct__track-sub">
                    <span class="acct__source-tag">{{ group.name }}</span>
                    <span>{{ item.trackCount ?? '—' }} 首</span>
                  </span>
                </li>
              </ul>
            </div>
            <p v-if="!groupedPlaylists.length" class="acct__hint">没有读到歌单</p>
          </div>

          <div v-else class="acct__groups">
            <div v-for="group in groupedTracks" :key="group.provider" class="acct__group">
              <p class="acct__group-head">{{ group.name }}</p>
              <ul class="acct__tracks">
                <li
                  v-for="track in group.items"
                  :key="trackKey(track.provider, track.providerTrackId)"
                  class="acct__track"
                >
                  <span class="acct__track-main">
                    <span class="acct__track-title">
                      {{ track.title }}
                      <span v-if="track.version" class="acct__version">{{ track.version }}</span>
                    </span>
                    <span class="acct__track-sub">
                      <span>{{ track.artist }}</span>
                      <span class="acct__source-tag">{{ group.name }}</span>
                      <span>{{ formatDuration(track.durationSeconds) }}</span>
                    </span>
                  </span>
                  <span
                    class="acct__playable"
                    :class="track.playable ? 'is-ok' : 'is-blocked'"
                    :title="track.playableReason ?? ''"
                  >
                    {{ track.playable ? '可播放' : '不可播放' }}
                  </span>
                </li>
              </ul>
            </div>
            <p v-if="!groupedTracks.length" class="acct__hint">这个来源没有可展示的曲目</p>
          </div>

          <div v-if="state.page > 1 || providers.hasMore.value" class="acct__pager">
            <button
              type="button"
              class="mk-btn mk-btn--sm mk-btn--ghost"
              :disabled="state.page <= 1 || state.loading"
              @click="providers.prevPage()"
            >
              上一页
            </button>
            <span class="acct__page">第 {{ state.page }} 页</span>
            <button
              type="button"
              class="mk-btn mk-btn--sm mk-btn--ghost"
              :disabled="!providers.hasMore.value || state.loading"
              @click="providers.nextPage()"
            >
              下一页
            </button>
          </div>
        </section>

        <p class="acct__foot">
          只读展示：不写回平台、不导入本地曲库、不把第三方数据发送给模型。
          标有「模拟」的来源没有连接任何真实平台。
        </p>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.acct {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal, 40);
}

.acct__backdrop {
  position: absolute;
  inset: 0;
  background: rgb(0 0 0 / 42%);
}

.acct__sheet {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(480px, 100vw);
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  background: var(--c-bg);
  border-left: 1px solid var(--c-line-strong);
  box-shadow: var(--shadow-lg);
}

.acct__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  padding: var(--s-3) var(--s-4);
  border-bottom: 1px solid var(--c-line);
}

.acct__title {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.acct__count {
  font-size: 11px;
  color: var(--c-text-3);
}

.acct__actions {
  display: flex;
  gap: var(--s-1);
}

.acct__body {
  min-height: 0;
  overflow-y: auto;
  padding: var(--s-4);
  display: grid;
  gap: var(--s-4);
  align-content: start;
}

.acct__section {
  display: grid;
  gap: var(--s-2);
}

.acct__label {
  margin: 0;
  font-size: 11px;
  letter-spacing: 0.04em;
  color: var(--c-text-3);
  text-transform: uppercase;
}

.acct__hint {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  line-height: 1.6;
}

.acct__error {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--c-danger);
}

.acct__empty {
  display: grid;
  gap: 4px;
  padding: var(--s-4);
  border: 1px dashed var(--c-line-strong);
  border-radius: var(--r-md);
}

.acct__empty-title {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.acct__empty-text {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.acct__chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.acct__chip {
  display: inline-flex;
  align-items: center;
  gap: var(--s-2);
  padding: 4px var(--s-2) 4px var(--s-3);
  border: 1px solid var(--c-line-strong);
  border-radius: var(--r-pill);
  background: var(--c-surface);
  font-size: var(--fs-xs);
  color: var(--c-text);
}

.acct__chip.is-simulated {
  border-style: dashed;
}

.acct__chip-name {
  white-space: nowrap;
}

.acct__badge {
  padding: 0 5px;
  border-radius: var(--r-pill);
  border: 1px solid var(--c-line);
  font-size: 10px;
  line-height: 15px;
  color: var(--c-text-3);
}

.acct__chip-state {
  font-size: 10px;
}

.acct__chip-state.is-ok {
  color: var(--c-text-3);
}

.acct__chip-state.is-warn {
  color: var(--c-warn);
}

.acct__chip-state.is-danger {
  color: var(--c-danger);
}

.acct__chip-btn {
  font-size: 10.5px;
  color: var(--c-text-3);
  text-decoration: underline;
}

.acct__chip-btn:hover {
  color: var(--c-danger);
}

.acct__list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: var(--s-1);
}

.acct__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  padding: var(--s-2) var(--s-3);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
}

.acct__row-main {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.acct__row-name {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.acct__row-note {
  font-size: 10.5px;
  color: var(--c-warn);
}

.acct__caps {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.acct__cap {
  font-size: 10px;
  color: var(--c-text-3);
  border: 1px solid var(--c-line);
  border-radius: var(--r-pill);
  padding: 0 6px;
  line-height: 16px;
}

.acct__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
}

.acct__filter {
  display: inline-flex;
  align-items: center;
  gap: var(--s-2);
  font-size: 11px;
  color: var(--c-text-3);
}

.acct__select {
  background: var(--c-surface);
  color: var(--c-text);
  border: 1px solid var(--c-line-strong);
  border-radius: var(--r-sm);
  padding: 3px 6px;
  font-size: 11px;
  max-width: 190px;
}

.acct__tabs {
  display: flex;
  gap: 2px;
  padding: 2px;
  background: var(--c-surface-2);
  border-radius: var(--r-sm);
}

.acct__tab {
  flex: 1;
  padding: 5px 0;
  border-radius: var(--r-sm);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.acct__tab.is-active {
  background: var(--c-surface);
  color: var(--c-text);
}

.acct__search {
  display: flex;
  gap: var(--s-2);
}

.acct__search-input {
  flex: 1;
  min-width: 0;
}

.acct__sources {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 2px;
}

.acct__source {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
  padding: 4px var(--s-2);
  border-radius: var(--r-sm);
  font-size: 10.5px;
  background: var(--c-surface-2);
  color: var(--c-text-3);
}

.acct__source.is-danger {
  color: var(--c-danger);
}

.acct__source.is-warn {
  color: var(--c-warn);
}

.acct__source-name {
  color: var(--c-text-2);
  white-space: nowrap;
}

.acct__source-status {
  white-space: nowrap;
}

.acct__source-msg {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.acct__groups {
  display: grid;
  gap: var(--s-3);
}

.acct__group {
  display: grid;
  gap: 4px;
}

.acct__group-head {
  margin: 0;
  font-size: 11px;
  color: var(--c-text-3);
}

.acct__tracks {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 1px;
}

.acct__track {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  padding: 6px var(--s-2);
  border-radius: var(--r-sm);
}

.acct__track:hover {
  background: var(--c-surface-2);
}

.acct__track-main {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.acct__track-title {
  font-size: var(--fs-xs);
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.acct__version {
  margin-left: 4px;
  font-size: 10px;
  color: var(--c-text-3);
  border: 1px solid var(--c-line);
  border-radius: var(--r-pill);
  padding: 0 5px;
}

.acct__track-sub {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  font-size: 10px;
  color: var(--c-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.acct__source-tag {
  color: var(--c-text-3);
}

.acct__playable {
  flex: none;
  font-size: 10px;
  white-space: nowrap;
}

.acct__playable.is-ok {
  color: var(--c-text-3);
}

.acct__playable.is-blocked {
  color: var(--c-text-3);
  opacity: 0.7;
}

.acct__pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--s-3);
}

.acct__page {
  font-size: 11px;
  color: var(--c-text-3);
}

.acct__foot {
  margin: 0;
  font-size: 10.5px;
  line-height: 1.7;
  color: var(--c-text-3);
  border-top: 1px solid var(--c-line-soft);
  padding-top: var(--s-3);
}
</style>
