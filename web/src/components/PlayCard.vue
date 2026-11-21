<script setup lang="ts">
/**
 * 会话内播放卡。
 *
 * 数据只来自 `GET /playback/candidates`，也就是**会话里真实工具结果**
 * （search_songs / get_playlist_songs …）留下的曲目引用。
 * 这里不解析模型回答里的文字去猜 songId——模型可以写错，ID 必须来自数据库。
 *
 * 不可播的曲目仍然列出来并写明原因（"暂无可用音源"/"无权播放"），
 * 因为"这首为什么点不了"本身就是用户需要的信息；
 * 把它们悄悄藏掉，用户只会以为列表漏了。
 */
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import { player } from '@/stores/player'
import type { PlaybackCandidateVO } from '@/api/types'
import type { PlayerTrack } from '@/stores/player'

const props = defineProps<{
  candidates: PlaybackCandidateVO[]
  /** 来源说明，例如"来自本会话的查询结果" */
  source?: string
}>()

const playable = computed(() => props.candidates.filter((item) => item.playable))

/** 可播曲目在队列中的位置：点击第 n 首可播曲目就从第 n 首开始 */
function playAt(candidate: PlaybackCandidateVO): void {
  const queue: PlayerTrack[] = playable.value.map((item) => ({
    songId: item.songId,
    title: item.title,
    artist: item.artist,
    album: item.album,
    durationSeconds: item.durationSeconds,
    format: item.format,
  }))

  const index = queue.findIndex((track) => track.songId === candidate.songId)
  if (index < 0) {
    return
  }
  void player.playQueue(queue, index)
}

const currentId = computed(() => player.current.value?.songId ?? null)

function formatDuration(seconds: number | null): string {
  if (!seconds || seconds <= 0) {
    return ''
  }
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}:${String(s).padStart(2, '0')}`
}
</script>

<template>
  <section class="playcard" aria-label="可播放曲目">
    <header class="playcard__head">
      <span class="playcard__title">
        <AppIcon name="wave" :size="13" />
        可播放
      </span>
      <span class="playcard__note">
        {{ source ?? '来自本会话的查询结果' }} · {{ playable.length }}/{{ candidates.length }} 首可播
      </span>
    </header>

    <ol class="playcard__list">
      <li
        v-for="(item, index) in candidates"
        :key="item.songId"
        class="playcard__row"
        :class="{ 'is-current': currentId === item.songId, 'is-blocked': !item.playable }"
      >
        <button
          v-if="item.playable"
          type="button"
          class="playcard__play"
          :aria-label="`播放 ${item.title}`"
          @click="playAt(item)"
        >
          <AppIcon :name="currentId === item.songId ? 'wave' : 'play'" :size="13" />
        </button>
        <span v-else class="playcard__play is-disabled" aria-hidden="true">
          <AppIcon name="x" :size="12" />
        </span>

        <span class="playcard__index">{{ index + 1 }}</span>

        <span class="playcard__meta">
          <span class="playcard__song">{{ item.title }}</span>
          <span class="playcard__artist">{{ item.artist }}</span>
        </span>

        <span v-if="item.playable" class="playcard__tail">
          <span v-if="formatDuration(item.durationSeconds)" class="playcard__duration">
            {{ formatDuration(item.durationSeconds) }}
          </span>
          <span v-if="item.format" class="playcard__format">{{ item.format }}</span>
        </span>
        <span v-else class="playcard__reason">{{ item.unavailableReason ?? '不可播放' }}</span>
      </li>
    </ol>

    <p class="playcard__foot">
      曲库音频为项目自带的测试音（技术夹具），用于验证播放链路，不是原曲录音。
    </p>
  </section>
</template>

<style scoped>
.playcard {
  margin: var(--s-4) 0 var(--s-6);
  border: 1px solid var(--c-line);
  border-radius: var(--r-md);
  background: var(--c-bg-soft);
  overflow: hidden;
}

.playcard__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  padding: var(--s-3) var(--s-4);
  border-bottom: 1px solid var(--c-line-soft);
}

.playcard__title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.playcard__note {
  font-size: 10.5px;
  color: var(--c-text-3);
}

.playcard__list {
  margin: 0;
  padding: var(--s-1) var(--s-1);
  list-style: none;
}

.playcard__row {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  padding: 6px var(--s-2);
  border-radius: var(--r-sm);
}

.playcard__row:hover {
  background: var(--c-surface-hover);
}

.playcard__row.is-current {
  background: var(--c-accent-weak-2);
}

.playcard__row.is-blocked {
  opacity: 0.72;
}

.playcard__play {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex: none;
  border-radius: var(--r-pill);
  border: 1px solid var(--c-line);
  color: var(--c-text-2);
}

.playcard__play:hover:not(.is-disabled) {
  border-color: var(--c-accent-line);
  color: var(--c-accent);
}

.playcard__play.is-disabled {
  border-color: transparent;
  color: var(--c-text-3);
  cursor: not-allowed;
}

.playcard__index {
  width: 14px;
  flex: none;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--c-text-3);
  text-align: right;
}

.playcard__meta {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
  min-width: 0;
  flex: 1;
}

.playcard__song {
  font-size: var(--fs-xs);
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.playcard__artist {
  font-size: 10.5px;
  color: var(--c-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: none;
  max-width: 45%;
}

.playcard__tail {
  display: inline-flex;
  align-items: center;
  gap: var(--s-2);
  flex: none;
}

.playcard__duration {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--c-text-3);
}

.playcard__format {
  padding: 0 5px;
  border-radius: var(--r-pill);
  border: 1px solid var(--c-line);
  font-size: 9.5px;
  line-height: 14px;
  color: var(--c-text-3);
}

.playcard__reason {
  flex: none;
  font-size: 10.5px;
  color: var(--c-text-3);
}

.playcard__foot {
  margin: 0;
  padding: var(--s-2) var(--s-4) var(--s-3);
  border-top: 1px solid var(--c-line-soft);
  font-size: 10px;
  line-height: 1.5;
  color: var(--c-text-3);
}
</style>
