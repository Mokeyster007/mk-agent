<script setup lang="ts">
/**
 * 商城侧栏。
 *
 * 只承担导航与身份显示，不承担状态：
 * 商品数量、订单状态一律来自 store，侧栏不重复判断。
 *
 * 为什么这里有一个身份切换：
 * 本项目没有真实登录，联调身份来自 X-User-Id。
 * 商城要演示"买家下单 → 后台发货"就必须能换人，
 * 而换人这件事只能发生在**开发模式**（ALLOW_DEV_IDENTITY），
 * 正式构建里连下拉框都不渲染。
 */
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import { session } from '@/stores/session'

/**
 * 演示身份。
 *
 * 与后端 `ShopSeedInitializer` 里的三条 shop_user 记录一一对应；
 * 这里写死是刻意的——商城没有"列出所有用户"的接口，
 * 也不该有（那是用户目录，不是商城功能）。
 */
const DEMO_IDENTITIES = [
  { id: 'music-user-1', label: 'music-user-1（买家）' },
  { id: 'music-user-2', label: 'music-user-2（买家）' },
  { id: 'music-admin-1', label: 'music-admin-1（后台）' },
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
      <span class="shop-nav__title">商城</span>
      <span class="shop-nav__tag">A0</span>
    </div>

    <nav class="shop-nav__links" aria-label="商城导航">
      <a class="shop-nav__link" href="#products">
        <AppIcon name="list" :size="14" />
        商品与下单
      </a>
      <a class="shop-nav__link" href="#orders">
        <AppIcon name="clock" :size="14" />
        我的订单
      </a>
      <RouterLink class="shop-nav__link" to="/shop/aftersale">
        <AppIcon name="refresh" :size="14" />
        售后与退货
      </RouterLink>
      <RouterLink class="shop-nav__link" to="/shop/admin">
        <AppIcon name="shield" :size="14" />
        商城后台
      </RouterLink>
      <RouterLink class="shop-nav__link" to="/">
        <AppIcon name="wave" :size="14" />
        音乐工作台
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
        不是真实登录。商城角色来自 shop_user 表，与音乐角色互不推导。
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

.shop-nav__link.router-link-active {
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
