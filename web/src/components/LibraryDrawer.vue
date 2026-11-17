<script setup lang="ts">
/**
 * "我的歌单"抽屉。
 *
 * 存在的理由：模型回答是自由文本，"已为你创建歌单「x」"这句话既不能证明
 * 歌单存在，也给不出歌单 ID。这个抽屉只做一件事——把数据库里的真实
 * 歌单与曲目查出来给用户看，因此它是审批写闭环的**验收面**：
 * 批准之后打开它、点刷新，能看到新歌单就是真的写进去了。
 *
 * 明确不做：
 * - 不提供任何写操作（新建 / 改名 / 删歌都只能由模型提请求 + 用户批准）；
 * - 不从任务结果里"推断"歌单，全部走查询接口；
 * - 不做分页（种子与演示规模远小于一屏，先不做假的复杂度）。
 */
import { computed, onBeforeUnmount, watch } from 'vue'
import AppIcon from './AppIcon.vue'
import {
  library,
  refreshPlaylists,
  togglePlaylist,
} from '@/stores/library'
import type { SongVO } from '@/api/types'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'close'): void }>()

/**
 * 每次打开都重新查一次。
 *
 * 不缓存"上次打开时的结果"：这个抽屉的全部价值就是"现在库里有什么"，
 * 用一次旧快照冒充当前状态，恰好会掩盖"批准了但没写进去"这种问题。
 */
watch(
  () => props.open,
  (open) => {
    if (open) {
      void refreshPlaylists({ resetSongs: true })
    }
  },
  { immediate: true },
)

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    emit('close')
  }
}

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

const totalSongs = computed(() =>
  library.playlists.reduce((sum, item) => sum + (item.songCount || 0), 0),
)

const refreshedAt = computed(() => {
  if (!library.loadedAt) {
    return ''
  }
  return new Date(library.loadedAt).toLocaleTimeString('zh-CN', { hour12: false })
})

function songsOf(playlistId: number): SongVO[] {
  return library.songsById[playlistId] ?? []
}

function duration(song: SongVO): string {
  if (!song.durationSeconds) {
    return '—'
  }
  const minutes = Math.floor(song.durationSeconds / 60)
  const seconds = song.durationSeconds % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}
</script>

<template>
  <div v-if="props.open" class="lib">
    <div class="lib__backdrop" @click="emit('close')" />

    <aside class="lib__sheet" role="dialog" aria-label="我的歌单">
      <header class="lib__head">
        <div class="lib__title">
          <AppIcon name="folder" :size="16" />
          <span>我的歌单</span>
        </div>
        <div class="lib__actions">
          <button
            type="button"
            class="mk-btn mk-btn--ghost mk-btn--sm"
            :disabled="library.loading"
            @click="refreshPlaylists({ resetSongs: true })"
          >
            {{ library.loading ? '刷新中…' : '刷新' }}
          </button>
          <button
            type="button"
            class="mk-btn mk-btn--ghost mk-btn--sm"
            aria-label="关闭"
            @click="emit('close')"
          >
            <AppIcon name="x" :size="14" />
          </button>
        </div>
      </header>

      <p class="lib__meta">
        <span v-if="library.playlists.length">
          {{ library.playlists.length }} 个歌单 · 共 {{ totalSongs }} 首
        </span>
        <span v-else-if="library.loadedAt">暂无歌单</span>
        <span v-if="refreshedAt" class="lib__stamp">查询于 {{ refreshedAt }}</span>
      </p>

      <div class="lib__body">
        <div v-if="library.error" class="lib__error">
          <p>{{ library.error }}</p>
          <button type="button" class="mk-btn mk-btn--sm" @click="refreshPlaylists()">
            重试
          </button>
        </div>

        <p v-else-if="library.loading && !library.playlists.length" class="lib__hint">
          正在读取…
        </p>

        <div v-else-if="!library.playlists.length" class="lib__empty">
          <p class="lib__empty-title">还没有歌单</p>
          <p class="lib__empty-text">
            在对话里描述你想建的歌单，批准后它就会出现在这里。
          </p>
        </div>

        <ul v-else class="lib__list">
          <li v-for="playlist in library.playlists" :key="playlist.id" class="lib__item">
            <button
              type="button"
              class="lib__row"
              :aria-expanded="library.expandedId === playlist.id"
              @click="togglePlaylist(playlist.id)"
            >
              <span class="lib__row-main">
                <span class="lib__name">{{ playlist.name }}</span>
                <span class="lib__sub">
                  <span class="lib__id mk-mono">#{{ playlist.id }}</span>
                  <span>{{ playlist.isPublic ? '公开' : '私有' }}</span>
                  <span>{{ playlist.songCount }} 首</span>
                </span>
              </span>
              <span class="lib__chevron" :class="{ 'is-open': library.expandedId === playlist.id }">
                <AppIcon name="chevron-right" :size="13" />
              </span>
            </button>

            <div v-if="library.expandedId === playlist.id" class="lib__songs">
              <p v-if="library.songsError" class="lib__hint lib__hint--error">
                {{ library.songsError }}
              </p>
              <p v-else-if="library.songsLoading" class="lib__hint">正在读取曲目…</p>
              <p v-else-if="!songsOf(playlist.id).length" class="lib__hint">
                这个歌单还没有歌曲
              </p>
              <ol v-else class="lib__song-list">
                <li v-for="(song, index) in songsOf(playlist.id)" :key="song.id" class="lib__song">
                  <span class="lib__song-index mk-mono">{{ index + 1 }}</span>
                  <span class="lib__song-main">
                    <span class="lib__song-title">{{ song.title }}</span>
                    <span class="lib__song-sub">
                      {{ song.artist }}
                      <template v-if="song.album"> · {{ song.album }}</template>
                    </span>
                  </span>
                  <span class="lib__song-time mk-mono">{{ duration(song) }}</span>
                </li>
              </ol>
            </div>
          </li>
        </ul>
      </div>

      <footer class="lib__foot">
        数据来自数据库实时查询，与模型回答无关。
      </footer>
    </aside>
  </div>
</template>

<style scoped>
.lib {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  justify-content: flex-end;
}

.lib__backdrop {
  position: absolute;
  inset: 0;
  background: rgba(6, 8, 11, 0.62);
  backdrop-filter: blur(1px);
}

.lib__sheet {
  position: relative;
  width: min(440px, 100vw);
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--c-bg-soft);
  border-left: 1px solid var(--c-line);
  box-shadow: -18px 0 48px rgba(0, 0, 0, 0.38);
  animation: lib-in 160ms ease-out;
}

@keyframes lib-in {
  from {
    transform: translateX(18px);
    opacity: 0.4;
  }
  to {
    transform: translateX(0);
    opacity: 1;
  }
}

.lib__head {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  padding: var(--s-4) var(--s-4) var(--s-2);
  border-bottom: 1px solid var(--c-line-soft);
}

.lib__title {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  font-size: var(--fs-md);
  font-weight: 500;
  color: var(--c-text);
}

.lib__actions {
  margin-left: auto;
  display: flex;
  gap: var(--s-1);
}

.lib__meta {
  display: flex;
  gap: var(--s-2);
  align-items: baseline;
  margin: 0;
  padding: var(--s-2) var(--s-4);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.lib__stamp {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}

.lib__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 var(--s-4) var(--s-4);
}

.lib__hint {
  margin: var(--s-3) 0;
  font-size: var(--fs-sm);
  color: var(--c-text-3);
}

.lib__hint--error {
  color: var(--c-danger);
}

.lib__error {
  margin: var(--s-3) 0;
  padding: var(--s-3);
  border: 1px solid var(--c-danger-weak);
  border-radius: var(--r-sm);
  background: var(--c-danger-weak);
  color: var(--c-text-2);
  font-size: var(--fs-sm);
}

.lib__error p {
  margin: 0 0 var(--s-2);
}

.lib__empty {
  margin-top: var(--s-8);
  text-align: center;
}

.lib__empty-title {
  margin: 0 0 var(--s-2);
  color: var(--c-text-2);
  font-size: var(--fs-base);
}

.lib__empty-text {
  margin: 0;
  color: var(--c-text-3);
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
}

.lib__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
}

.lib__item {
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  overflow: hidden;
}

.lib__row {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  width: 100%;
  padding: var(--s-3);
  background: none;
  border: 0;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.lib__row:hover {
  background: var(--c-surface-hover);
}

.lib__row-main {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.lib__name {
  font-size: var(--fs-base);
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lib__sub {
  display: flex;
  gap: var(--s-2);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.lib__id {
  color: var(--c-text-3);
}

.lib__chevron {
  margin-left: auto;
  display: inline-flex;
  color: var(--c-text-3);
  transition: transform 140ms ease;
}

.lib__chevron.is-open {
  transform: rotate(90deg);
}

.lib__songs {
  border-top: 1px solid var(--c-line-soft);
  padding: var(--s-2) var(--s-3) var(--s-3);
  background: var(--c-bg-soft);
}

.lib__song-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.lib__song {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  padding: 5px 0;
  border-bottom: 1px dashed var(--c-line-soft);
}

.lib__song:last-child {
  border-bottom: 0;
}

.lib__song-index {
  width: 18px;
  flex: none;
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  text-align: right;
}

.lib__song-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.lib__song-title {
  font-size: var(--fs-sm);
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lib__song-sub {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lib__song-time {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  font-variant-numeric: tabular-nums;
}

.lib__foot {
  flex: none;
  padding: var(--s-2) var(--s-4) var(--s-3);
  border-top: 1px solid var(--c-line-soft);
  font-size: 10.5px;
  color: var(--c-text-3);
}
</style>
