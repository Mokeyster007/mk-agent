<script setup lang="ts">
/**
 * 应用骨架：三栏布局 + 移动端抽屉。
 *
 * 断点策略：
 * - ≥1180px：左导航 + 中间工作区 + 右侧详情（可收起）
 * - 900–1179px：右侧详情改为覆盖式抽屉，中间区域保持完整
 * - <900px：左导航也改为抽屉，中间单列
 *
 * 布局只负责"放哪里"，不负责取数，也不判断任何业务状态。
 */
import AppIcon from './AppIcon.vue'

defineProps<{
  panelOpen: boolean
  navOpen: boolean
  /** 右侧是否有内容可展示；没有内容时不显示开关，也不占列宽 */
  hasPanel: boolean
}>()

defineEmits<{
  (e: 'toggle-panel'): void
  (e: 'toggle-nav'): void
  (e: 'close-overlays'): void
}>()
</script>

<template>
  <div
    class="shell"
    :class="{ 'shell--panel': panelOpen && hasPanel, 'shell--nav': navOpen }"
  >
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

        <button
          v-if="hasPanel"
          type="button"
          class="shell__icon-btn"
          :aria-label="panelOpen ? '收起任务详情' : '展开任务详情'"
          :aria-expanded="panelOpen"
          @click="$emit('toggle-panel')"
        >
          <AppIcon name="sliders" :size="17" />
        </button>
      </header>

      <div class="shell__content">
        <slot />
      </div>
    </main>

    <div v-if="hasPanel" class="shell__panel" :class="{ 'shell__panel--open': panelOpen }">
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

.shell--panel {
  grid-template-columns: var(--nav-w) minmax(0, 1fr) var(--panel-w);
}

.shell__nav {
  min-width: 0;
  min-height: 0;
  height: 100%;
}

.shell__main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.shell__topbar {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  height: var(--header-h);
  padding: 0 var(--s-4);
  border-bottom: 1px solid var(--c-line);
  flex: none;
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

.shell__content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
}

.shell__panel {
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}

.shell__scrim {
  display: none;
}

/* ---- 中屏：详情变抽屉 ---- */
@media (max-width: 1179px) {
  .shell,
  .shell--panel {
    grid-template-columns: var(--nav-w) minmax(0, 1fr);
  }

  .shell__panel {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    width: min(400px, 90vw);
    z-index: var(--z-drawer);
    background: var(--c-bg-soft);
    box-shadow: var(--shadow-lg);
    transform: translateX(100%);
    transition: transform var(--dur) var(--ease);
    pointer-events: none;
  }

  .shell__panel--open {
    transform: none;
    pointer-events: auto;
  }

  .shell__scrim {
    display: block;
    position: fixed;
    inset: 0;
    z-index: calc(var(--z-drawer) - 1);
    background: rgba(6, 8, 11, 0.6);
    backdrop-filter: blur(2px);
  }
}

/* ---- 窄屏：导航也变抽屉 ---- */
@media (max-width: 899px) {
  .shell,
  .shell--panel {
    grid-template-columns: minmax(0, 1fr);
  }

  .shell__nav {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    width: min(292px, 86vw);
    z-index: var(--z-drawer);
    background: var(--c-bg);
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
</style>
