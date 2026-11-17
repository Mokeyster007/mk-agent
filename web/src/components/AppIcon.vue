<script setup lang="ts">
/**
 * 统一图标集。
 *
 * 全部为 24×24 线性图标，stroke=currentColor，stroke-width 1.6，
 * 同一套圆角与端点，避免混用不同风格图标造成廉价感。
 * 不使用 emoji 作为功能图标（emoji 在不同系统上字形差异过大）。
 */
withDefaults(
  defineProps<{
    name: keyof typeof PATHS
    size?: number
    strokeWidth?: number
  }>(),
  { size: 18, strokeWidth: 1.6 },
)

const PATHS = {
  plus: 'M12 5v14M5 12h14',
  send: 'M4.5 12.5 20 4l-6.2 16-2.3-6.4-7-1.1Z',
  stop: 'M7.5 7.5h9v9h-9z',
  'music-note': 'M9 18V6.5l9-1.6v11',
  'music-note-dot': 'M7.2 18a2.2 2.2 0 1 0 0-4.4 2.2 2.2 0 0 0 0 4.4ZM18 16.4a2.2 2.2 0 1 0 0-4.4 2.2 2.2 0 0 0 0 4.4Z',
  search: 'M10.8 17.6a6.8 6.8 0 1 0 0-13.6 6.8 6.8 0 0 0 0 13.6ZM15.8 15.8 20 20',
  list: 'M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01',
  disc: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 13.4a1.4 1.4 0 1 0 0-2.8 1.4 1.4 0 0 0 0 2.8Z',
  user: 'M12 11.6a3.9 3.9 0 1 0 0-7.8 3.9 3.9 0 0 0 0 7.8ZM4.8 20.2c.7-3.5 3.7-5.6 7.2-5.6s6.5 2.1 7.2 5.6',
  shield: 'M12 3.4 19 6v6c0 4-3 7-7 8.6C8 19 5 16 5 12V6l7-2.6Z',
  clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 7.4V12l3.2 1.9',
  'chevron-down': 'M6 9.5 12 15l6-5.5',
  'chevron-right': 'M9.5 6 15 12l-5.5 6',
  'chevron-left': 'M14.5 6 9 12l5.5 6',
  alert: 'M12 8.4v4.8M12 16.4h.01M10.6 4.2 3.1 17.4A1.6 1.6 0 0 0 4.5 19.8h15a1.6 1.6 0 0 0 1.4-2.4L13.4 4.2a1.6 1.6 0 0 0-2.8 0Z',
  check: 'M5 12.8 9.6 17.4 19 8',
  x: 'M6 6l12 12M18 6 6 18',
  refresh: 'M20 12a8 8 0 1 1-2.6-5.9M20 4.2V9h-4.8',
  info: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 11v5.4M12 7.8h.01',
  sparkle: 'M12 3.6l1.7 4.6 4.6 1.7-4.6 1.7L12 16.2l-1.7-4.6L5.7 9.9l4.6-1.7L12 3.6Z',
  sliders: 'M4 8h10M18 8h2M4 16h4M12 16h8M15 5.4v5.2M9 13.4v5.2',
  wave: 'M3 12h2.4l2.2-5.6 3 11.2 2.6-8 2 5.4 1.6-3H21',
  folder: 'M4 7.4A1.4 1.4 0 0 1 5.4 6h3.4l1.8 2.2h8A1.4 1.4 0 0 1 20 9.6v7.8A1.4 1.4 0 0 1 18.6 18.8H5.4A1.4 1.4 0 0 1 4 17.4V7.4Z',
  play: 'M8.4 5.6 18 12l-9.6 6.4V5.6Z',
  history: 'M12 7.6V12l3 1.8M20 12a8 8 0 1 1-3.2-6.4M20 4v4.4h-4.4',
  external: 'M14 5h5v5M19 5l-7.4 7.4M18 14v4.2A1.8 1.8 0 0 1 16.2 20H5.8A1.8 1.8 0 0 1 4 18.2V7.8A1.8 1.8 0 0 1 5.8 6H10',
} as const
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    :stroke-width="strokeWidth"
    stroke="currentColor"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <path :d="PATHS[name]" />
  </svg>
</template>
