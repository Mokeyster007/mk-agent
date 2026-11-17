<script setup lang="ts">
/**
 * 根组件：只做路由出口。
 *
 * 全局状态在 stores 里，样式在 styles 里，
 * 这里不放任何业务逻辑，避免根组件变成第二个"上帝对象"。
 */
</script>

<template>
  <router-view />
</template>
