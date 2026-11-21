<script setup lang="ts">
/**
 * 订单详情抽屉（买家页与后台页共用）。
 *
 * 为什么共用：两边的"订单长什么样"必须一致。
 * 各写一份的话，后台看到的字段和买家看到的迟早会分叉，
 * 到时候"用户说他看到的是已发货"就变成一句无法核对的话。
 *
 * 操作按钮由 `showActions` 控制：买家页不传，后台页传 true。
 * 但真正的权限判断在服务端——按钮只是不让人白点一次。
 */
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import type { ShopOrderVO } from '@/api/shop'
import { formatDateTime } from '@/utils/format'
import type { Tone } from '@/utils/status'

const props = withDefaults(
  defineProps<{
    order: ShopOrderVO
    busy?: boolean
    showActions?: boolean
  }>(),
  { busy: false, showActions: false },
)

defineEmits<{
  (e: 'close'): void
  (e: 'ship'): void
  (e: 'sign'): void
}>()

const tone = computed<Tone>(() => {
  switch (props.order.status) {
    case 'PENDING_SHIPMENT':
      return 'wait'
    case 'SHIPPED':
      return 'active'
    case 'SIGNED':
      return 'ok'
    default:
      return 'neutral'
  }
})

const movements = computed(() => props.order.movements ?? [])
</script>

<template>
  <div class="panel">
    <header class="panel__head">
      <div class="panel__title">
        <p class="panel__eyebrow">订单</p>
        <h2 class="panel__order-no">{{ order.orderNo }}</h2>
      </div>
      <button type="button" class="panel__close" aria-label="关闭订单详情" @click="$emit('close')">
        <AppIcon name="x" :size="16" />
      </button>
    </header>

    <div class="panel__scroll">
      <div class="panel__row">
        <span class="chip" :class="`chip--${tone}`">{{ order.statusLabel }}</span>
        <span class="panel__amount">{{ order.totalText }}</span>
      </div>

      <p v-if="order.paymentMode === 'DEMO'" class="panel__demo">
        演示付款：本单没有发生真实资金交易。
      </p>

      <section class="panel__section">
        <h3 class="panel__h3">商品明细</h3>
        <div v-if="order.item" class="line">
          <div class="line__main">
            <p class="line__name">{{ order.item.productName }}</p>
            <p class="line__sku">{{ order.item.sku }}</p>
          </div>
          <div class="line__calc">
            {{ order.item.unitPriceText }} × {{ order.item.quantity }}
            <strong>{{ order.item.lineText }}</strong>
          </div>
        </div>
        <p v-else class="panel__muted">缺少明细</p>
      </section>

      <section class="panel__section">
        <h3 class="panel__h3">流转记录</h3>
        <ul class="steps">
          <li class="steps__item">
            <span class="steps__dot" />
            <div>
              <p class="steps__label">下单</p>
              <p class="steps__meta">{{ formatDateTime(order.createdAt) }} · {{ order.buyerId }}</p>
            </div>
          </li>
          <li class="steps__item" :class="{ 'steps__item--idle': !order.shippedAt }">
            <span class="steps__dot" />
            <div>
              <p class="steps__label">发货</p>
              <p class="steps__meta">
                <template v-if="order.shippedAt">
                  {{ formatDateTime(order.shippedAt) }} · {{ order.shippedBy ?? '未知操作人' }}
                </template>
                <template v-else>尚未发货（库存未扣减）</template>
              </p>
            </div>
          </li>
          <li class="steps__item" :class="{ 'steps__item--idle': !order.signedAt }">
            <span class="steps__dot" />
            <div>
              <p class="steps__label">签收</p>
              <p class="steps__meta">
                <template v-if="order.signedAt">
                  {{ formatDateTime(order.signedAt) }} · {{ order.signedBy ?? '未知操作人' }}
                </template>
                <template v-else>尚未签收</template>
              </p>
            </div>
          </li>
        </ul>
      </section>

      <section class="panel__section">
        <h3 class="panel__h3">出库流水</h3>
        <p v-if="!movements.length" class="panel__muted">暂无出库记录（发货后才会产生）</p>
        <ul v-else class="moves">
          <li v-for="move in movements" :key="move.id" class="moves__item">
            <span class="moves__delta" :class="{ 'moves__delta--out': move.quantityDelta < 0 }">
              {{ move.quantityDelta > 0 ? '+' : '' }}{{ move.quantityDelta }}
            </span>
            <div class="moves__body">
              <p class="moves__sku">{{ move.sku }}</p>
              <p class="moves__meta">
                {{ move.type }} · {{ move.operatorId ?? '未知操作人' }} ·
                {{ formatDateTime(move.createdAt) }}
              </p>
            </div>
          </li>
        </ul>
      </section>
    </div>

    <footer v-if="showActions" class="panel__foot">
      <button
        type="button"
        class="mk-btn mk-btn--primary"
        :disabled="!order.canShip || busy"
        @click="$emit('ship')"
      >
        发货（扣库存）
      </button>
      <button
        type="button"
        class="mk-btn"
        :disabled="!order.canSign || busy"
        @click="$emit('sign')"
      >
        标记签收
      </button>
      <p v-if="!order.canShip && !order.canSign" class="panel__muted panel__muted--foot">
        该订单已签收，没有可执行的操作。
      </p>
    </footer>
  </div>
</template>

<style scoped>
.panel {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  height: 100%;
  min-height: 0;
}

.panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-3);
  padding: var(--s-4);
  border-bottom: 1px solid var(--c-line);
}

.panel__eyebrow {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  letter-spacing: 0.06em;
}

.panel__order-no {
  margin-top: 2px;
  font-size: var(--fs-md);
  font-weight: 500;
  font-family: var(--font-mono);
  color: var(--c-text);
  overflow-wrap: anywhere;
}

.panel__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: none;
  border-radius: var(--r-sm);
  color: var(--c-text-3);
}

.panel__close:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.panel__scroll {
  min-height: 0;
  overflow-y: auto;
  padding: var(--s-4);
  display: grid;
  gap: var(--s-5);
  align-content: start;
}

.panel__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
}

.panel__amount {
  font-size: var(--fs-lg);
  color: var(--c-text);
}

.panel__demo {
  padding: var(--s-2) var(--s-3);
  border-radius: var(--r-sm);
  background: var(--c-warn-weak);
  color: var(--c-warn);
  font-size: var(--fs-sm);
}

.panel__section {
  display: grid;
  gap: var(--s-2);
}

.panel__h3 {
  font-size: var(--fs-xs);
  font-weight: 500;
  color: var(--c-text-3);
  letter-spacing: 0.06em;
}

.panel__muted {
  font-size: var(--fs-sm);
  color: var(--c-text-3);
}

.panel__muted--foot {
  margin-top: var(--s-2);
}

.chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
  border-radius: var(--r-pill);
  font-size: var(--fs-xs);
  border: 1px solid transparent;
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

.line {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-3);
  padding: var(--s-3);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-sm);
  background: var(--c-bg-soft);
}

.line__name {
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.line__sku {
  margin-top: 2px;
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  font-family: var(--font-mono);
}

.line__calc {
  display: grid;
  gap: 2px;
  justify-items: end;
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  white-space: nowrap;
}

.line__calc strong {
  font-size: var(--fs-sm);
  font-weight: 500;
  color: var(--c-text);
}

.steps {
  display: grid;
  gap: var(--s-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.steps__item {
  display: flex;
  gap: var(--s-3);
}

.steps__dot {
  width: 7px;
  height: 7px;
  margin-top: 6px;
  flex: none;
  border-radius: 50%;
  background: var(--c-accent);
}

.steps__item--idle .steps__dot {
  background: var(--c-line-strong);
}

.steps__label {
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.steps__meta {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  line-height: var(--lh-base);
}

.moves {
  display: grid;
  gap: var(--s-2);
  margin: 0;
  padding: 0;
  list-style: none;
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
  color: var(--c-ok);
  min-width: 34px;
}

.moves__delta--out {
  color: var(--c-warn);
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

.panel__foot {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
  padding: var(--s-4);
  border-top: 1px solid var(--c-line);
  background: var(--c-bg-soft);
}
</style>
