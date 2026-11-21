<script setup lang="ts">
/**
 * 常驻播放器：全站唯一，挂在 AppShell 的独立一行。
 *
 * 三条布局约束（都是为了不破坏聊天区）：
 * 1. 它**不在**消息滚动容器里，也不浮在内容上方——不会遮住最后一条消息或审批按钮；
 * 2. 高度固定，输入区在它上面，播放器的出现/消失不会改变消息区宽度；
 * 3. 窄屏改成两行（信息 + 控制），进度条占满第二行，不做悬浮迷你条。
 *
 * 状态全部来自 `player.state`：组件只负责显示与派发指令，
 * 不在本地推断"是不是在播"——浏览器事件才是唯一事实来源。
 */
import { computed, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import EqPanel from './EqPanel.vue'
import { player } from '@/stores/player'
import type { PlayerTrack } from '@/stores/player'

const state = player.state
const current = player.current

const popover = ref<'queue' | 'eq' | null>(null)
const queueTrigger = ref<HTMLButtonElement | null>(null)
const eqTrigger = ref<HTMLButtonElement | null>(null)

/** 拖动进度条时先显示本地值，避免 timeupdate 把滑块拽回去 */
const scrubbing = ref(false)
const scrubValue = ref(0)

const title = computed(() => current.value?.title ?? '未在播放')
const artist = computed(() => current.value?.artist ?? '')
const format = computed(() => current.value?.format ?? '')

const displayTime = computed(() => (scrubbing.value ? scrubValue.value : state.currentTime))

const progressMax = computed(() => (state.duration > 0 ? state.duration : 0))

const statusText = computed(() => {
  if (state.status === 'error' && state.error) {
    return state.error.message
  }
  if (state.autoplayBlocked) {
    return '浏览器已阻止自动播放，点击播放继续'
  }
  if (state.status === 'loading') {
    return '正在解析音源…'
  }
  if (state.status === 'ended') {
    return '已播完，可点击上一首或重新播放'
  }
  if (state.status === 'paused') {
    return '已暂停'
  }
  return ''
})

const isError = computed(() => state.status === 'error')
const busy = computed(() => state.status === 'loading')

const playLabel = computed(() => {
  if (state.status === 'playing') {
    return '暂停'
  }
  if (isError.value) {
    return '重试'
  }
  return '播放'
})

const queueCount = computed(() => state.queue.length)

function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) {
    return '0:00'
  }
  const total = Math.floor(seconds)
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

function onScrub(event: Event): void {
  scrubbing.value = true
  scrubValue.value = Number((event.target as HTMLInputElement).value)
}

function onSeekCommit(event: Event): void {
  const value = Number((event.target as HTMLInputElement).value)
  player.seek(value)
  scrubbing.value = false
}

function togglePopover(which: 'queue' | 'eq'): void {
  popover.value = popover.value === which ? null : which
}

function closePopover(): void {
  const which = popover.value
  popover.value = null
  if (which === 'queue') {
    queueTrigger.value?.focus()
  } else if (which === 'eq') {
    eqTrigger.value?.focus()
  }
}

function playFromQueue(index: number): void {
  void player.jumpTo(index)
}

function onEqChange(patch: Record<string, number | boolean>): void {
  player.setEq(patch)
}

const tracks = computed<PlayerTrack[]>(() => state.queue)

/**
 * 续播卡：只在"没有曲目在播放器里"且本地有未过期快照时出现。
 *
 * 它**不是**正在播放：不自动播放、不上报 PLAYING，
 * 只有用户点「继续播放」才重新解析音源并 seek 到上次的位置。
 */
const resume = computed(() => state.resume)

const resumeAgo = computed(() => {
  const snapshot = resume.value
  if (!snapshot) {
    return ''
  }
  const minutes = Math.max(0, Math.round((Date.now() - snapshot.updatedAt) / 60_000))
  if (minutes < 1) {
    return '刚刚'
  }
  if (minutes < 60) {
    return `${minutes} 分钟前`
  }
  const hours = Math.round(minutes / 60)
  return `${hours} 小时前`
})

const resuming = ref(false)

async function onResume(): Promise<void> {
  if (resuming.value) {
    return
  }
  resuming.value = true
  try {
    await player.resumeLast()
  } finally {
    resuming.value = false
  }
}
</script>

<template>
  <div v-if="player.hasTrack.value" class="player" role="region" aria-label="播放器">
    <!-- 1. 曲目信息 -->
    <div class="player__track">
      <span class="player__disc" :class="{ 'is-spinning': state.status === 'playing' }">
        <AppIcon name="disc" :size="16" />
      </span>
      <div class="player__meta">
        <p class="player__title" :title="title">{{ title }}</p>
        <p class="player__sub" :class="{ 'is-error': isError, 'is-hint': state.autoplayBlocked }">
          <template v-if="statusText">{{ statusText }}</template>
          <template v-else>
            <span class="player__artist">{{ artist }}</span>
            <span v-if="format" class="player__format">{{ format }}</span>
          </template>
        </p>
      </div>
    </div>

    <!-- 2. 传输控制 -->
    <div class="player__controls">
      <button
        type="button"
        class="player__btn"
        aria-label="上一首"
        :disabled="busy"
        @click="player.previous()"
      >
        <AppIcon name="skip-back" :size="15" />
      </button>

      <button
        type="button"
        class="player__btn player__btn--main"
        :class="{ 'is-attention': state.autoplayBlocked }"
        :aria-label="playLabel"
        :disabled="busy"
        @click="player.toggle()"
      >
        <AppIcon :name="state.status === 'playing' ? 'pause' : 'play'" :size="16" />
      </button>

      <button
        type="button"
        class="player__btn"
        aria-label="下一首"
        :disabled="busy"
        @click="player.next()"
      >
        <AppIcon name="skip-forward" :size="15" />
      </button>
    </div>

    <!-- 3. 进度 -->
    <div class="player__progress">
      <span class="player__time">{{ formatTime(displayTime) }}</span>
      <input
        class="player__range"
        type="range"
        min="0"
        :max="progressMax"
        step="0.1"
        :value="displayTime"
        :disabled="!player.canSeek.value"
        aria-label="播放进度"
        @input="onScrub"
        @change="onSeekCommit"
      />
      <span class="player__time">{{ formatTime(state.duration) }}</span>
    </div>

    <!-- 4. 次要操作 -->
    <div class="player__actions">
      <button
        ref="eqTrigger"
        type="button"
        class="player__btn player__btn--icon"
        :class="{ 'is-open': popover === 'eq', 'is-active': state.eq.enabled }"
        aria-label="调音"
        :aria-expanded="popover === 'eq'"
        @click="togglePopover('eq')"
      >
        <AppIcon name="sliders" :size="15" />
      </button>

      <button
        ref="queueTrigger"
        type="button"
        class="player__btn player__btn--icon player__queue-btn"
        :class="{ 'is-open': popover === 'queue' }"
        :aria-label="`播放队列，共 ${queueCount} 首`"
        :aria-expanded="popover === 'queue'"
        @click="togglePopover('queue')"
      >
        <AppIcon name="list" :size="15" />
        <span class="player__count">{{ queueCount }}</span>
      </button>

      <button
        type="button"
        class="player__btn player__btn--icon"
        aria-label="关闭播放器"
        @click="player.stop()"
      >
        <AppIcon name="x" :size="14" />
      </button>
    </div>

    <!-- 浮层：队列 / 调音 -->
    <div v-if="popover" class="player__scrim" role="presentation" @click="closePopover" />

    <Transition name="pop">
      <div v-if="popover === 'queue'" class="player__pop player__pop--queue" role="dialog" aria-label="播放队列">
        <header class="player__pop-head">
          <span>播放队列</span>
          <span class="player__pop-note">共 {{ queueCount }} 首</span>
        </header>
        <ol class="player__queue">
          <li
            v-for="(track, index) in tracks"
            :key="`${track.songId}-${index}`"
            class="player__queue-item"
            :class="{ 'is-current': index === state.index }"
          >
            <button type="button" class="player__queue-btn-row" @click="playFromQueue(index)">
              <span class="player__queue-index">{{ index === state.index ? '▶' : index + 1 }}</span>
              <span class="player__queue-meta">
                <span class="player__queue-title">{{ track.title }}</span>
                <span class="player__queue-artist">{{ track.artist }}</span>
              </span>
              <span class="player__queue-format">{{ track.format ?? '' }}</span>
            </button>
          </li>
        </ol>
      </div>
    </Transition>

    <Transition name="pop">
      <div v-if="popover === 'eq'" class="player__pop player__pop--eq" role="dialog" aria-label="调音">
        <EqPanel
          :settings="state.eq"
          :available="player.eqAvailable.value"
          :supported="state.eqSupported"
          @change="onEqChange"
          @bypass="player.toggleEqBypass()"
          @reset="player.resetEq()"
        />
      </div>
    </Transition>
  </div>

  <!--
    续播卡：刷新/重开页面后显示"上次听到这里"。
    刻意不做成"自动接着放"——浏览器会拦，而且用户重开页面时不一定想立刻出声。
  -->
  <div
    v-else-if="resume"
    class="player player--resume"
    role="region"
    aria-label="续播"
  >
    <div class="player__track">
      <span class="player__disc">
        <AppIcon name="disc" :size="16" />
      </span>
      <div class="player__meta">
        <p class="player__title" :title="resume.title">{{ resume.title }}</p>
        <p class="player__sub">
          <span class="player__artist">{{ resume.artist }}</span>
          <span class="player__format">上次听到 {{ formatTime(resume.positionSeconds) }}</span>
        </p>
      </div>
    </div>

    <div class="player__resume-action">
      <span class="player__resume-ago">{{ resumeAgo }}</span>
      <button
        type="button"
        class="player__resume-btn"
        :disabled="resuming"
        @click="onResume"
      >
        <AppIcon name="play" :size="14" />
        {{ resuming ? '正在解析…' : '继续播放' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.player {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(180px, 320px) auto;
  align-items: center;
  gap: var(--s-4);
  height: 60px;
  padding: 0 var(--s-4);
  border-top: 1px solid var(--c-line);
  background: var(--c-bg-soft);
}

/* ---- 续播卡 ---- */
.player--resume {
  grid-template-columns: minmax(0, 1fr) auto;
  border-top-color: var(--c-line-soft);
}

.player__resume-action {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  min-width: 0;
}

.player__resume-ago {
  font-size: 11px;
  color: var(--c-text-3);
  white-space: nowrap;
}

.player__resume-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--s-1);
  height: 30px;
  padding: 0 var(--s-3);
  border-radius: var(--r-pill);
  border: 1px solid var(--c-line-strong);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--fs-xs);
  white-space: nowrap;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.player__resume-btn:hover:not(:disabled) {
  background: var(--c-accent);
  border-color: var(--c-accent);
  color: var(--c-text-inverse);
}

.player__resume-btn:disabled {
  opacity: 0.55;
  cursor: default;
}

/* ---- 曲目 ---- */
.player__track {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  min-width: 0;
}

.player__disc {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-text-3);
}

.player__disc.is-spinning {
  color: var(--c-accent);
  background: var(--c-accent-weak-2);
}

.player__meta {
  min-width: 0;
  display: grid;
  gap: 1px;
}

.player__title {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.player__sub {
  margin: 0;
  display: flex;
  align-items: center;
  gap: var(--s-2);
  font-size: 11px;
  color: var(--c-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.player__sub.is-error {
  color: var(--c-danger);
}

.player__sub.is-hint {
  color: var(--c-warn);
}

.player__artist {
  overflow: hidden;
  text-overflow: ellipsis;
}

.player__format {
  flex: none;
  padding: 0 5px;
  border-radius: var(--r-pill);
  border: 1px solid var(--c-line);
  font-size: 10px;
  line-height: 15px;
  color: var(--c-text-3);
}

/* ---- 控制 ---- */
.player__controls {
  display: flex;
  align-items: center;
  gap: var(--s-1);
}

.player__btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: var(--r-sm);
  color: var(--c-text-2);
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.player__btn:hover:not(:disabled) {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.player__btn:disabled {
  opacity: 0.4;
  cursor: default;
}

.player__btn--main {
  width: 34px;
  height: 34px;
  border-radius: var(--r-pill);
  background: var(--c-surface);
  color: var(--c-text);
}

.player__btn--main:hover:not(:disabled) {
  background: var(--c-surface-3);
}

.player__btn--main.is-attention {
  background: var(--c-accent);
  color: var(--c-text-inverse);
}

.player__btn--icon {
  width: 28px;
  height: 28px;
}

.player__btn--icon.is-open,
.player__btn--icon.is-active {
  background: var(--c-surface-2);
  color: var(--c-accent);
}

/* ---- 进度 ---- */
.player__progress {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  min-width: 0;
}

.player__time {
  flex: none;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--c-text-3);
  font-variant-numeric: tabular-nums;
}

.player__range {
  flex: 1;
  min-width: 0;
  height: 4px;
  accent-color: var(--c-accent);
  cursor: pointer;
}

.player__range:disabled {
  cursor: default;
  opacity: 0.5;
}

/* ---- 次要操作 ---- */
.player__actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.player__queue-btn {
  gap: 2px;
  width: auto;
  min-width: 28px;
  padding: 0 5px;
}

.player__count {
  font-size: 10px;
  font-family: var(--font-mono);
  color: var(--c-text-3);
}

/* ---- 浮层 ---- */
.player__scrim {
  position: fixed;
  inset: 0;
  z-index: var(--z-overlay);
  background: transparent;
}

.player__pop {
  position: absolute;
  right: var(--s-4);
  bottom: calc(100% + var(--s-2));
  z-index: calc(var(--z-overlay) + 1);
  border-radius: var(--r-md);
  border: 1px solid var(--c-line-strong);
  background: var(--c-surface);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}

.player__pop--queue {
  width: 300px;
  max-height: 320px;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
}

.player__pop-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--s-3);
  border-bottom: 1px solid var(--c-line-soft);
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.player__pop-note {
  font-size: 10.5px;
  color: var(--c-text-3);
}

.player__queue {
  margin: 0;
  padding: var(--s-1);
  list-style: none;
  overflow-y: auto;
}

.player__queue-item.is-current {
  background: var(--c-accent-weak-2);
  border-radius: var(--r-sm);
}

.player__queue-btn-row {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  width: 100%;
  padding: 6px var(--s-2);
  text-align: left;
  border-radius: var(--r-sm);
  color: var(--c-text-2);
}

.player__queue-btn-row:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.player__queue-index {
  flex: none;
  width: 16px;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--c-text-3);
  text-align: center;
}

.player__queue-meta {
  display: grid;
  min-width: 0;
  gap: 1px;
}

.player__queue-title {
  font-size: var(--fs-xs);
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.player__queue-artist {
  font-size: 10px;
  color: var(--c-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.player__queue-format {
  margin-left: auto;
  flex: none;
  font-size: 10px;
  color: var(--c-text-3);
}

.pop-enter-active,
.pop-leave-active {
  transition: opacity var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}

.pop-enter-from,
.pop-leave-to {
  opacity: 0;
  transform: translateY(4px);
}

/* ---- 窄屏：两行 ---- */
@media (max-width: 899px) {
  .player {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-rows: auto auto;
    gap: var(--s-1) var(--s-3);
    height: auto;
    padding: var(--s-2) var(--s-3) var(--s-3);
  }

  .player__track {
    grid-column: 1;
  }

  .player__controls {
    grid-column: 2;
    grid-row: 1;
  }

  .player__progress {
    grid-column: 1 / -1;
    grid-row: 2;
  }

  .player__actions {
    grid-column: 1 / -1;
    grid-row: 3;
    justify-content: flex-end;
    border-top: 1px solid var(--c-line-soft);
    padding-top: var(--s-2);
  }

  .player__pop {
    right: var(--s-2);
    left: var(--s-2);
    width: auto;
  }
}

@media (prefers-reduced-motion: reduce) {
  .pop-enter-active,
  .pop-leave-active {
    transition: none;
  }
}
</style>
