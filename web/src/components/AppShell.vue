<script setup lang="ts">
/**
 * 应用骨架：侧栏 + 主工作区 + 常驻播放器。
 *
 * 布局契约（输入框不跳动的根因就在这里）：
 * 1. 外壳只有**两列**，永远不会因为"有没有任务详情"而改变列宽——
 *    详情是覆盖式抽屉，不占列、不挤压主区；
 * 2. 主区只有三行：顶栏（固定高度）+ 内容（minmax(0,1fr)）+ 播放器（auto）；
 * 3. 内容区本身**不滚动**，纵向滚动全部交给工作区里的消息区；
 * 4. 播放器没有曲目时渲染为空，第三行高度为 0，不占位置。
 *
 * 断点只有一个：<900px 侧栏变抽屉。桌面端宽度变化不影响主区结构。
 */
import AppIcon from './AppIcon.vue'
import PlaybackBar from './PlaybackBar.vue'

defineProps<{
  panelOpen: boolean
  navOpen: boolean
}>()

defineEmits<{
  (e: 'toggle-nav'): void
  (e: 'close-overlays'): void
}>()
</script>

<template>
  <div class="shell" :class="{ 'shell--nav': navOpen }">
    <div class="shell__nav" :class="{ 'shell__nav--open': navOpen }">
      <slot name="nav" />
    </div>

    <main class="shell__main">
      <header class="shell__topbar">
        <button
          type="button"
          class="shell__icon-btn shell__menu"
          aria-label="打开导航"
          @click="$emit('toggle-nav')"
        >
          <AppIcon name="list" :size="17" />
        </button>

        <div class="shell__topbar-slot">
          <slot name="topbar" />
        </div>
      </header>

      <div class="shell__content">
        <slot />
      </div>

      <!-- 常驻播放器：独立一行，不在消息滚动区里，也不浮在内容上方 -->
      <div class="shell__player">
        <PlaybackBar />
      </div>
    </main>

    <div v-if="panelOpen" class="shell__panel" role="dialog" aria-modal="true" aria-label="任务详情">
      <slot name="panel" />
    </div>

    <div
      v-if="navOpen || panelOpen"
      class="shell__scrim"
      role="presentation"
      @click="$emit('close-overlays')"
    />
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: var(--nav-w) minmax(0, 1fr);
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  background: var(--c-bg);
}

.shell__nav {
  min-width: 0;
  min-height: 0;
  height: 100%;
  background: var(--c-bg-nav);
}

.shell__main {
  display: grid;
  grid-template-rows: var(--header-h) minmax(0, 1fr) auto;
  min-width: 0;
  min-height: 0;
  height: 100%;
}

.shell__topbar {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  min-width: 0;
  padding: 0 var(--s-4);
  border-bottom: 1px solid var(--c-line);
  background: var(--c-bg);
}

.shell__topbar-slot {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--s-3);
}

.shell__icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: var(--r-sm);
  color: var(--c-text-3);
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.shell__icon-btn:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.shell__menu {
  display: none;
}

/* 内容区不滚动：滚动由工作区内部的消息区承担 */
.shell__content {
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}

/* 播放器行：没有曲目时子组件渲染为空，这一行高度为 0 */
.shell__player {
  min-width: 0;
}

/* ---- 详情：覆盖式抽屉，不占列、不挤压主区 ---- */
.shell__panel {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(var(--panel-w), 92vw);
  z-index: var(--z-drawer);
  background: var(--c-bg-soft);
  border-left: 1px solid var(--c-line);
  box-shadow: var(--shadow-lg);
  animation: panel-in var(--dur) var(--ease) both;
  overflow: hidden;
}

@keyframes panel-in {
  from {
    transform: translateX(12px);
    opacity: 0;
  }
  to {
    transform: none;
    opacity: 1;
  }
}

.shell__scrim {
  position: fixed;
  inset: 0;
  z-index: calc(var(--z-drawer) - 1);
  background: rgba(6, 8, 10, 0.58);
}

/* ---- 窄屏：侧栏变抽屉 ---- */
@media (max-width: 899px) {
  .shell {
    grid-template-columns: minmax(0, 1fr);
  }

  .shell__nav {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    width: min(292px, 86vw);
    z-index: var(--z-drawer);
    transform: translateX(-100%);
    transition: transform var(--dur) var(--ease);
    box-shadow: var(--shadow-lg);
    pointer-events: none;
  }

  .shell__nav--open {
    transform: none;
    pointer-events: auto;
  }

  .shell__menu {
    display: inline-flex;
  }

  .shell__topbar {
    padding: 0 var(--s-3);
  }
}

@media (prefers-reduced-motion: reduce) {
  .shell__nav {
    transition: none;
  }
}
</style>
