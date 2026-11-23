<script setup lang="ts">
/**
 * 电商平台侧栏导航。
 *
 * 音乐模块退场后，电商为默认体验：
 * 提供导购与商城、购物车、我的订单、售后与退货、商城后台、售后处理六个清晰入口。
 */
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import { session } from '@/stores/session'

const DEMO_IDENTITIES = [
  { id: 'shop-user-1', label: 'shop-user-1（买家）' },
  { id: 'shop-admin-1', label: 'shop-admin-1（后台）' },
  { id: 'music-user-1', label: 'music-user-1（兼容买家）' },
  { id: 'music-user-2', label: 'music-user-2（兼容买家 2）' },
  { id: 'music-admin-1', label: 'music-admin-1（兼容后台）' },
] as const

const emit = defineEmits<{
  (e: 'switch-identity', userId: string): void
}>()

const currentUserId = computed(() => session.userId.value)
const canSwitch = session.allowDevIdentity

function onChange(event: Event): void {
  const next = (event.target as HTMLSelectElement).value
  if (next && next !== currentUserId.value) {
    emit('switch-identity', next)
  }
}
</script>

<template>
  <aside class="shop-nav">
    <div class="shop-nav__brand">
      <span class="shop-nav__mark" aria-hidden="true">
        <AppIcon name="folder" :size="16" />
      </span>
      <span class="shop-nav__title">MkAgent 电商</span>
      <span class="shop-nav__tag">Demo</span>
    </div>

    <nav class="shop-nav__links" aria-label="电商导航">
      <RouterLink class="shop-nav__link" to="/shop#guide">
        <AppIcon name="list" :size="14" />
        导购与选品
      </RouterLink>
      <RouterLink class="shop-nav__link" to="/shop#cart">
        <AppIcon name="folder" :size="14" />
        购物车
      </RouterLink>
      <RouterLink class="shop-nav__link" to="/shop#orders">
        <AppIcon name="clock" :size="14" />
        我的订单
      </RouterLink>
      <RouterLink class="shop-nav__link" to="/shop/aftersale">
        <AppIcon name="refresh" :size="14" />
        售后与退货
      </RouterLink>
      <RouterLink class="shop-nav__link" to="/shop/admin">
        <AppIcon name="shield" :size="14" />
        商城后台
      </RouterLink>
      <RouterLink class="shop-nav__link" to="/shop/admin/aftersale">
        <AppIcon name="check" :size="14" />
        售后处理
      </RouterLink>
    </nav>

    <div class="shop-nav__identity">
      <p class="shop-nav__label">联调身份</p>

      <select
        v-if="canSwitch"
        class="shop-nav__select"
        :value="currentUserId"
        aria-label="切换联调身份"
        @change="onChange"
      >
        <option v-for="item in DEMO_IDENTITIES" :key="item.id" :value="item.id">
          {{ item.label }}
        </option>
      </select>

      <p v-else class="shop-nav__static">{{ currentUserId }}</p>

      <p class="shop-nav__hint">
        演示联调身份（非真实登录）。角色由服务端 shop_user 表判定。
      </p>
    </div>
  </aside>
</template>

<style scoped>
.shop-nav {
  display: grid;
  grid-template-rows: auto auto 1fr;
  gap: var(--s-5);
  height: 100%;
  padding: var(--s-4) var(--s-3);
  background: var(--c-bg-nav);
}

.shop-nav__brand {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  padding: 0 var(--s-2);
  min-width: 0;
}

.shop-nav__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  flex: none;
  border-radius: var(--r-sm);
  background: var(--c-accent-weak);
  color: var(--c-accent);
}

.shop-nav__title {
  font-size: var(--fs-base);
  font-weight: 500;
  color: var(--c-text);
}

.shop-nav__tag {
  padding: 1px 6px;
  border: 1px solid var(--c-line);
  border-radius: var(--r-pill);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.shop-nav__links {
  display: grid;
  gap: 2px;
  align-content: start;
}

.shop-nav__link {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  padding: 7px var(--s-2);
  border-radius: var(--r-sm);
  color: var(--c-text-2);
  font-size: var(--fs-sm);
  text-decoration: none;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.shop-nav__link:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.shop-nav__link.router-link-exact-active {
  background: var(--c-accent-weak);
  color: var(--c-accent-ink);
}

.shop-nav__identity {
  align-self: end;
  display: grid;
  gap: var(--s-2);
  padding: var(--s-3);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-bg-soft);
}

.shop-nav__label {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  letter-spacing: 0.04em;
}

.shop-nav__select {
  width: 100%;
  padding: 6px var(--s-2);
  border: 1px solid var(--c-line);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--fs-sm);
}

.shop-nav__static {
  font-size: var(--fs-sm);
  color: var(--c-text);
  font-family: var(--font-mono);
  overflow-wrap: anywhere;
}

.shop-nav__hint {
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  color: var(--c-text-3);
}
</style>
