<script setup lang="ts">
/**
 * 买家页：商品列表 → 立即购买 → 我的订单 / 订单详情。
 *
 * 三条产品约束（来自本轮范围）：
 * 1. 没有购物车、优惠券、地址簿、真实支付；
 * 2. 下单**不预占、不扣库存**，所以页面上写的是"待发货"，
 *    而不是"已锁定库存"；
 * 3. 演示付款必须在页面上说清楚，不能只在文档里说。
 *
 * 页面不判断权限：能不能下单读 `session.canOrder`（服务端给的结论）。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import AppShell from '@/components/AppShell.vue'
import ShopNav from '@/components/ShopNav.vue'
import ShopOrderDetailPanel from '@/components/ShopOrderDetailPanel.vue'
import AppIcon from '@/components/AppIcon.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import { session } from '@/stores/session'
import { shop } from '@/stores/shop'
import { formatDateTime } from '@/utils/format'
import type { ShopOrderStatus } from '@/api/shop'
import type { Tone } from '@/utils/status'

const navOpen = ref(false)

/** 每个商品的购买数量，默认 1；不放进 store——它是页面级草稿，不需要跨页共享 */
const quantities = reactive<Record<number, number>>({})

const detailOpen = computed(() => shop.state.detail !== null)

const canOrder = computed(() => shop.canOrder.value)

const identityDisabled = computed(
  () => shop.state.session !== null && !shop.canOrder.value,
)

function quantityOf(productId: number): number {
  return quantities[productId] ?? 1
}

function step(productId: number, delta: number): void {
  const next = Math.min(99, Math.max(1, quantityOf(productId) + delta))
  quantities[productId] = next
}

async function onBuy(productId: number): Promise<void> {
  const order = await shop.place(productId, quantityOf(productId))
  if (order) {
    quantities[productId] = 1
  }
}

function toneOf(status: ShopOrderStatus | 'UNKNOWN'): Tone {
  switch (status) {
    case 'PENDING_SHIPMENT':
      return 'wait'
    case 'SHIPPED':
      return 'active'
    case 'SIGNED':
      return 'ok'
    default:
      return 'neutral'
  }
}

async function onSwitchIdentity(userId: string): Promise<void> {
  if (!session.updateUserId(userId)) {
    return
  }
  // 换了身份就是换了一个人：先清空，再按新身份重新读
  shop.reset()
  await Promise.all([shop.loadSession(), shop.loadProducts()])
  await shop.loadMyOrders()
}

onMounted(async () => {
  await Promise.all([shop.loadSession(), shop.loadProducts()])
  await shop.loadMyOrders()
})
</script>

<template>
  <AppShell
    :panel-open="detailOpen"
    :nav-open="navOpen"
    @toggle-nav="navOpen = !navOpen"
    @close-overlays="navOpen = false; shop.closeDetail()"
  >
    <template #nav>
      <ShopNav @switch-identity="onSwitchIdentity" />
    </template>

    <template #topbar>
      <div class="topbar">
        <h1 class="topbar__title">商城</h1>
        <span class="topbar__demo">演示付款 · 无真实资金交易</span>

        <div class="topbar__right">
          <span class="topbar__identity" :title="shop.state.session?.userId ?? '未识别'">
            {{ shop.state.session?.userId ?? '未识别身份' }}
          </span>
          <RouterLink class="topbar__link" to="/shop/admin">商城后台</RouterLink>
        </div>
      </div>
    </template>

    <div class="shop">
      <div class="shop__inner">
        <ErrorNotice
          v-if="shop.state.error"
          tone="danger"
          title="操作没有完成"
          :detail="shop.state.error"
          action-label="重新读取"
          @action="shop.clearFeedback(); shop.loadProducts(); shop.loadMyOrders()"
        />

        <p v-if="shop.state.notice" class="banner banner--ok" role="status">
          <AppIcon name="check" :size="14" />
          {{ shop.state.notice }}
        </p>

        <ErrorNotice
          v-if="identityDisabled"
          tone="warn"
          title="当前身份不能下单"
          :detail="
            shop.state.session?.identityMode === 'DISABLED'
              ? '商城身份未启用（服务端配置 mkagent.shop.demo-identity-enabled=false）。商品可以浏览，但下单、发货、签收都被服务端拒绝。'
              : '这个身份没有开通商城（shop_user 表里没有它）。可在左下角切换为 music-user-1。'
          "
        />

        <section id="products" class="section">
          <header class="section__head">
            <h2 class="section__title">商品</h2>
            <p class="section__hint">下单不锁定库存；发货时才校验并扣减。</p>
          </header>

          <p v-if="shop.state.loadingProducts && !shop.state.products.length" class="muted">
            正在读取商品…
          </p>

          <div v-else class="products">
            <article
              v-for="product in shop.state.products"
              :key="product.id"
              class="card"
              :data-sku="product.sku"
            >
              <div class="card__head">
                <h3 class="card__name">{{ product.name }}</h3>
                <span class="card__price">{{ product.priceText }}</span>
              </div>
              <p class="card__sub">{{ product.subtitle }}</p>

              <div class="card__meta">
                <span class="card__sku">{{ product.sku }}</span>
                <span
                  class="card__stock"
                  :class="{ 'card__stock--out': product.available <= 0 }"
                >
                  {{ product.available > 0 ? `可售 ${product.available} 件` : '暂时无货' }}
                </span>
              </div>

              <div class="card__actions">
                <div class="stepper" role="group" :aria-label="`${product.name} 购买数量`">
                  <button
                    type="button"
                    class="stepper__btn"
                    aria-label="减少一件"
                    :disabled="quantityOf(product.id) <= 1"
                    @click="step(product.id, -1)"
                  >
                    −
                  </button>
                  <span class="stepper__value">{{ quantityOf(product.id) }}</span>
                  <button
                    type="button"
                    class="stepper__btn"
                    aria-label="增加一件"
                    :disabled="quantityOf(product.id) >= 99"
                    @click="step(product.id, 1)"
                  >
                    +
                  </button>
                </div>

                <button
                  type="button"
                  class="mk-btn mk-btn--primary"
                  :disabled="!canOrder || shop.state.placing || !product.purchasable"
                  @click="onBuy(product.id)"
                >
                  {{ shop.state.placing ? '提交中…' : '立即购买' }}
                </button>
              </div>

              <p v-if="product.available <= 0" class="card__note">
                仍可下单，但发货时可能因库存不足失败。
              </p>
            </article>
          </div>
        </section>

        <section id="orders" class="section">
          <header class="section__head">
            <h2 class="section__title">我的订单</h2>
            <button type="button" class="link-btn" @click="shop.loadMyOrders()">刷新</button>
          </header>

          <p v-if="!canOrder" class="muted">身份不可用时无法读取订单。</p>
          <p v-else-if="shop.state.loadingOrders && !shop.state.myOrders.length" class="muted">
            正在读取订单…
          </p>
          <p v-else-if="!shop.state.myOrders.length" class="muted">
            还没有订单。选一件商品点「立即购买」。
          </p>

          <ul v-else class="orders">
            <li
              v-for="order in shop.state.myOrders"
              :key="order.orderNo"
              class="order"
              :data-order-no="order.orderNo"
            >
              <div class="order__main">
                <div class="order__title">
                  <span class="chip" :class="`chip--${toneOf(order.status)}`">
                    {{ order.statusLabel }}
                  </span>
                  <span class="order__no">{{ order.orderNo }}</span>
                </div>
                <p class="order__item">
                  {{ order.item?.productName ?? '缺少明细' }} × {{ order.item?.quantity ?? 0 }}
                  <span class="order__sku">{{ order.item?.sku }}</span>
                </p>
                <p class="order__time">下单 {{ formatDateTime(order.createdAt) }}</p>
              </div>

              <div class="order__side">
                <span class="order__amount">{{ order.totalText }}</span>
                <button
                  type="button"
                  class="mk-btn mk-btn--sm"
                  @click="shop.openOrder(order.orderNo)"
                >
                  详情
                </button>
              </div>
            </li>
          </ul>
        </section>

        <p class="foot-note">
          本页只做 A0：商品 → 下单 → 待发货 → 已发货 → 已签收。
          没有购物车、优惠券、地址簿与真实支付；售后与退货在下一阶段。
        </p>
      </div>
    </div>

    <template #panel>
      <ShopOrderDetailPanel
        v-if="shop.state.detail"
        :order="shop.state.detail"
        @close="shop.closeDetail()"
      />
    </template>
  </AppShell>
</template>

<style scoped>
.topbar {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  min-width: 0;
  width: 100%;
}

.topbar__title {
  font-size: var(--fs-base);
  font-weight: 500;
  color: var(--c-text);
  white-space: nowrap;
}

.topbar__demo {
  padding: 2px 8px;
  border-radius: var(--r-pill);
  background: var(--c-warn-weak);
  color: var(--c-warn);
  font-size: var(--fs-xs);
  white-space: nowrap;
}

.topbar__right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--s-3);
  min-width: 0;
}

.topbar__identity {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topbar__link {
  font-size: var(--fs-sm);
  color: var(--c-accent-ink);
  text-decoration: none;
  white-space: nowrap;
}

.topbar__link:hover {
  text-decoration: underline;
}

.shop {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
}

.shop__inner {
  max-width: 900px;
  margin: 0 auto;
  padding: var(--s-6) var(--s-5) var(--s-12);
  display: grid;
  gap: var(--s-5);
}

.banner {
  display: flex;
  align-items: flex-start;
  gap: var(--s-2);
  padding: var(--s-3);
  border-radius: var(--r-sm);
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
}

.banner--ok {
  background: var(--c-ok-weak);
  color: var(--c-ok);
}

.section {
  display: grid;
  gap: var(--s-3);
  scroll-margin-top: var(--s-4);
}

.section__head {
  display: flex;
  align-items: baseline;
  gap: var(--s-3);
}

.section__title {
  font-size: var(--fs-lg);
  font-weight: 500;
  color: var(--c-text);
}

.section__hint {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.products {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--s-3);
}

.card {
  display: grid;
  gap: var(--s-2);
  padding: var(--s-4);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-bg-soft);
}

.card__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-2);
}

.card__name {
  font-size: var(--fs-md);
  font-weight: 500;
  color: var(--c-text);
}

.card__price {
  font-size: var(--fs-md);
  color: var(--c-accent-ink);
  white-space: nowrap;
}

.card__sub {
  font-size: var(--fs-sm);
  color: var(--c-text-2);
  line-height: var(--lh-base);
}

.card__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.card__sku {
  font-family: var(--font-mono);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card__stock {
  white-space: nowrap;
  color: var(--c-ok);
}

.card__stock--out {
  color: var(--c-warn);
}

.card__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  margin-top: var(--s-1);
}

.card__note {
  font-size: var(--fs-xs);
  color: var(--c-warn);
}

.stepper {
  display: inline-flex;
  align-items: center;
  border: 1px solid var(--c-line);
  border-radius: var(--r-sm);
  overflow: hidden;
}

.stepper__btn {
  width: 26px;
  height: 26px;
  color: var(--c-text-2);
  font-size: var(--fs-base);
  line-height: 1;
}

.stepper__btn:hover:not(:disabled) {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.stepper__btn:disabled {
  opacity: 0.4;
}

.stepper__value {
  min-width: 26px;
  text-align: center;
  font-size: var(--fs-sm);
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.orders {
  display: grid;
  gap: var(--s-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.order {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-4);
  padding: var(--s-3) var(--s-4);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-bg-soft);
}

.order__main {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.order__title {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  min-width: 0;
}

.order__no {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order__item {
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.order__sku {
  margin-left: var(--s-2);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.order__time {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.order__side {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  flex: none;
}

.order__amount {
  font-size: var(--fs-sm);
  color: var(--c-text);
  white-space: nowrap;
}

.chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
  border-radius: var(--r-pill);
  font-size: var(--fs-xs);
  white-space: nowrap;
}

.chip--wait {
  background: var(--c-warn-weak);
  color: var(--c-warn);
}

.chip--active {
  background: var(--c-info-weak);
  color: var(--c-info);
}

.chip--ok {
  background: var(--c-ok-weak);
  color: var(--c-ok);
}

.chip--neutral {
  background: var(--c-surface-2);
  color: var(--c-text-2);
}

.muted {
  font-size: var(--fs-sm);
  color: var(--c-text-3);
}

.link-btn {
  font-size: var(--fs-sm);
  color: var(--c-accent-ink);
}

.link-btn:hover {
  text-decoration: underline;
}

.foot-note {
  margin-top: var(--s-2);
  padding-top: var(--s-4);
  border-top: 1px solid var(--c-line-soft);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  line-height: var(--lh-base);
}

@media (max-width: 720px) {
  .topbar__demo {
    display: none;
  }

  .shop__inner {
    padding: var(--s-4) var(--s-3) var(--s-10);
  }

  .order {
    flex-direction: column;
    align-items: stretch;
  }

  .order__side {
    justify-content: space-between;
  }
}
</style>
