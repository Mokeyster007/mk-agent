<script setup lang="ts">
/**
 * 商城后台（演示）：按状态看订单、发货、标记签收、核对库存与出库流水。
 *
 * 三点必须说清楚：
 * 1. 这里是**演示后台**，没有仓储系统、没有物流单号、没有承运商；
 *    "发货"替代的是仓库出库这个动作，不是"已交给快递"；
 * 2. 权限由服务端判定（`/shop/session` 的 canOperate）。页面隐藏按钮只是
 *    不让人白点一次，真正拦住越权的是后端 403；
 * 3. 重复点发货不会重复扣库存——后端返回 applied=false，页面如实显示。
 */
import { computed, onMounted, ref } from 'vue'
import AppShell from '@/components/AppShell.vue'
import ShopNav from '@/components/ShopNav.vue'
import ShopOrderDetailPanel from '@/components/ShopOrderDetailPanel.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import AppIcon from '@/components/AppIcon.vue'
import { session } from '@/stores/session'
import { shop } from '@/stores/shop'
import { formatDateTime } from '@/utils/format'
import type { ShopOrderStatus } from '@/api/shop'
import type { Tone } from '@/utils/status'

const navOpen = ref(false)

const TABS: { value: ShopOrderStatus | null; label: string }[] = [
  { value: null, label: '全部' },
  { value: 'PENDING_SHIPMENT', label: '待发货' },
  { value: 'SHIPPED', label: '已发货' },
  { value: 'SIGNED', label: '已签收' },
]

const detailOpen = computed(() => shop.state.detail !== null)

const canOperate = computed(() => shop.canOperate.value)

const identityDisabled = computed(
  () => shop.state.session !== null && shop.state.session.identityMode === 'DISABLED',
)

const totalPages = computed(() =>
  Math.max(1, Math.ceil(shop.state.adminTotal / 20)),
)

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

async function loadAll(): Promise<void> {
  await shop.loadSession()
  if (!shop.canOperate.value) {
    return
  }
  await Promise.all([shop.loadAdminOrders(shop.state.adminStatus), shop.loadInventory()])
}

async function onSwitchIdentity(userId: string): Promise<void> {
  if (!session.updateUserId(userId, { probeMusicCapabilities: false })) {
    return
  }
  shop.reset()
  await loadAll()
}

function balanceOf(sku: string): number | null {
  const row = shop.state.inventory?.balances.find((item) => item.sku === sku)
  return row ? row.available : null
}

onMounted(loadAll)
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
        <h1 class="topbar__title">商城后台</h1>
        <span class="topbar__demo">演示发货 · 无真实物流</span>

        <div class="topbar__right">
          <span class="topbar__identity">{{ shop.state.session?.userId ?? '未识别身份' }}</span>
          <RouterLink class="topbar__link" to="/shop">返回商城</RouterLink>
        </div>
      </div>
    </template>

    <div class="admin">
      <div class="admin__inner">
        <ErrorNotice
          v-if="shop.state.error"
          tone="danger"
          title="操作没有完成"
          :detail="shop.state.error"
          action-label="刷新订单"
          @action="shop.clearFeedback(); loadAll()"
        />

        <p v-if="shop.state.notice" class="banner banner--ok" role="status">
          <AppIcon name="check" :size="14" />
          {{ shop.state.notice }}
        </p>

        <ErrorNotice
          v-if="!canOperate"
          tone="warn"
          title="当前身份不能使用后台"
          :detail="
            identityDisabled
              ? '商城身份未启用（服务端配置 mkagent.shop.demo-identity-enabled=false），后台写操作一律被拒绝。'
              : '这个身份不是商城后台人员。可在左下角把联调身份切换为 music-admin-1。'
          "
        />

        <template v-else>
          <section class="section">
            <header class="section__head">
              <h2 class="section__title">订单</h2>
              <div class="tabs" role="tablist" aria-label="按状态筛选">
                <button
                  v-for="tab in TABS"
                  :key="tab.label"
                  type="button"
                  role="tab"
                  class="tabs__item"
                  :class="{ 'tabs__item--on': shop.state.adminStatus === tab.value }"
                  :aria-selected="shop.state.adminStatus === tab.value"
                  @click="shop.loadAdminOrders(tab.value)"
                >
                  {{ tab.label }}
                </button>
              </div>
              <button type="button" class="link-btn" @click="loadAll()">刷新</button>
            </header>

            <p v-if="shop.state.loadingAdmin && !shop.state.adminOrders.length" class="muted">
              正在读取订单…
            </p>
            <p v-else-if="!shop.state.adminOrders.length" class="muted">
              该状态下没有订单。买家在商城页下单后会出现在这里。
            </p>

            <ul v-else class="rows">
              <li
                v-for="order in shop.state.adminOrders"
                :key="order.orderNo"
                class="row"
                :data-order-no="order.orderNo"
              >
                <div class="row__grid">
                  <div class="row__cell row__cell--no">
                    <button
                      type="button"
                      class="row__no"
                      @click="shop.openOrder(order.orderNo, true)"
                    >
                      {{ order.orderNo }}
                    </button>
                    <span class="row__time">{{ formatDateTime(order.createdAt) }}</span>
                  </div>

                  <div class="row__cell">
                    <p class="row__product">{{ order.item?.productName ?? '缺少明细' }}</p>
                    <p class="row__sku">
                      {{ order.item?.sku }} × {{ order.item?.quantity ?? 0 }}
                    </p>
                  </div>

                  <div class="row__cell row__cell--buyer">
                    <p class="row__buyer">{{ order.buyerId }}</p>
                    <p class="row__amount">{{ order.totalText }}</p>
                  </div>

                  <div class="row__cell row__cell--status">
                    <span class="chip" :class="`chip--${toneOf(order.status)}`">
                      {{ order.statusLabel }}
                    </span>
                  </div>
                </div>

                <div class="row__actions">
                  <button
                    type="button"
                    class="mk-btn mk-btn--sm mk-btn--primary"
                    :disabled="!order.canShip || shop.state.busyOrderNo === order.orderNo"
                    @click="shop.ship(order.orderNo)"
                  >
                    {{ shop.state.busyOrderNo === order.orderNo ? '处理中…' : '发货' }}
                  </button>
                  <button
                    type="button"
                    class="mk-btn mk-btn--sm"
                    :disabled="!order.canSign || shop.state.busyOrderNo === order.orderNo"
                    @click="shop.sign(order.orderNo)"
                  >
                    标记签收
                  </button>
                  <button
                    type="button"
                    class="link-btn"
                    @click="shop.openOrder(order.orderNo, true)"
                  >
                    详情
                  </button>
                </div>
              </li>
            </ul>

            <div v-if="totalPages > 1" class="pager">
              <button
                type="button"
                class="mk-btn mk-btn--sm"
                :disabled="shop.state.adminPage <= 1"
                @click="shop.goAdminPage(shop.state.adminPage - 1)"
              >
                上一页
              </button>
              <span class="pager__info">
                第 {{ shop.state.adminPage }} / {{ totalPages }} 页 · 共
                {{ shop.state.adminTotal }} 单
              </span>
              <button
                type="button"
                class="mk-btn mk-btn--sm"
                :disabled="shop.state.adminPage >= totalPages"
                @click="shop.goAdminPage(shop.state.adminPage + 1)"
              >
                下一页
              </button>
            </div>
          </section>

          <section class="section">
            <header class="section__head">
              <h2 class="section__title">库存核对</h2>
              <p class="section__hint">余额与流水必须对得上：出库为负，签收不再变动。</p>
            </header>

            <div class="inventory">
              <table class="table">
                <thead>
                  <tr>
                    <th scope="col">SKU</th>
                    <th scope="col">商品</th>
                    <th scope="col" class="table__num">可售</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="row in shop.state.inventory?.balances ?? []"
                    :key="row.sku"
                    :data-sku="row.sku"
                  >
                    <td class="table__mono">{{ row.sku }}</td>
                    <td>{{ row.productName ?? '—' }}</td>
                    <td class="table__num">{{ balanceOf(row.sku) ?? '—' }}</td>
                  </tr>
                </tbody>
              </table>

              <div class="moves">
                <h3 class="moves__title">最近出库流水</h3>
                <p v-if="!shop.state.inventory?.movements.length" class="muted">
                  还没有任何流水。
                </p>
                <ul v-else class="moves__list">
                  <li
                    v-for="move in shop.state.inventory?.movements ?? []"
                    :key="move.id"
                    class="moves__item"
                  >
                    <span class="moves__delta">{{ move.quantityDelta }}</span>
                    <div class="moves__body">
                      <p class="moves__sku">{{ move.sku }}</p>
                      <p class="moves__meta">
                        {{ move.type }} · {{ move.operatorId ?? '未知' }} ·
                        {{ formatDateTime(move.createdAt) }}
                      </p>
                    </div>
                  </li>
                </ul>
              </div>
            </div>
          </section>

          <p class="foot-note">
            发货在同一个数据库事务内完成：校验状态 → 扣减可售库存 → 写一笔出库流水 → 更新订单状态。
            库存不足时整体回滚，订单保持待发货。重复发货不会重复扣减。
          </p>
        </template>
      </div>
    </div>

    <template #panel>
      <ShopOrderDetailPanel
        v-if="shop.state.detail"
        :order="shop.state.detail"
        show-actions
        :busy="shop.state.busyOrderNo === shop.state.detail.orderNo"
        @close="shop.closeDetail()"
        @ship="shop.ship(shop.state.detail.orderNo)"
        @sign="shop.sign(shop.state.detail.orderNo)"
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

.admin {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
}

.admin__inner {
  max-width: 1000px;
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
}

.section__head {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  flex-wrap: wrap;
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

.tabs {
  display: inline-flex;
  border: 1px solid var(--c-line);
  border-radius: var(--r-sm);
  overflow: hidden;
}

.tabs__item {
  padding: 4px 10px;
  font-size: var(--fs-sm);
  color: var(--c-text-3);
  border-right: 1px solid var(--c-line);
}

.tabs__item:last-child {
  border-right: none;
}

.tabs__item:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.tabs__item--on {
  background: var(--c-accent-weak);
  color: var(--c-accent-ink);
}

.rows {
  display: grid;
  gap: var(--s-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-4);
  padding: var(--s-3) var(--s-4);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-bg-soft);
}

.row__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1.2fr) minmax(0, 0.9fr) auto;
  gap: var(--s-3);
  align-items: center;
  min-width: 0;
  flex: 1;
}

.row__cell {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.row__no {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--c-accent-ink);
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row__no:hover {
  text-decoration: underline;
}

.row__time,
.row__sku,
.row__amount {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.row__product {
  font-size: var(--fs-sm);
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row__sku {
  font-family: var(--font-mono);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row__buyer {
  font-size: var(--fs-sm);
  color: var(--c-text-2);
  font-family: var(--font-mono);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row__cell--status {
  justify-items: end;
}

.row__actions {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex: none;
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

.inventory {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--s-4);
  align-items: start;
}

.table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}

.table th {
  text-align: left;
  padding: var(--s-2);
  border-bottom: 1px solid var(--c-line);
  font-size: var(--fs-xs);
  font-weight: 400;
  color: var(--c-text-3);
}

.table td {
  padding: var(--s-2);
  border-bottom: 1px solid var(--c-line-soft);
  color: var(--c-text);
}

.table__mono {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--c-text-2);
}

.table__num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.moves__title {
  font-size: var(--fs-xs);
  font-weight: 500;
  color: var(--c-text-3);
  margin-bottom: var(--s-2);
}

.moves__list {
  display: grid;
  gap: var(--s-2);
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 260px;
  overflow-y: auto;
}

.moves__item {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-2) var(--s-3);
  border-radius: var(--r-sm);
  background: var(--c-bg-soft);
}

.moves__delta {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  color: var(--c-warn);
  min-width: 32px;
}

.moves__sku {
  font-size: var(--fs-sm);
  color: var(--c-text);
  font-family: var(--font-mono);
}

.moves__meta {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.pager {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  justify-content: center;
}

.pager__info {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
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
  padding-top: var(--s-4);
  border-top: 1px solid var(--c-line-soft);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  line-height: var(--lh-base);
}

@media (max-width: 860px) {
  .inventory {
    grid-template-columns: minmax(0, 1fr);
  }

  .row {
    flex-direction: column;
    align-items: stretch;
  }

  .row__grid {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .row__cell--buyer {
    display: none;
  }

  .row__actions {
    justify-content: flex-end;
  }
}
</style>
