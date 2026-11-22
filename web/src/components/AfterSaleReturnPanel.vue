<script setup lang="ts">
/**
 * 买家售后详情抽屉。
 *
 * 只显示服务端返回的事实：
 * - 退货单号、状态、来源（普通表单 / 售后 Agent）、数量、金额、原因；
 * - 客服决策（谁在什么时候、批准还是驳回、驳回原因）——B1 起有；
 * - 处理记录（提交 → 决策，一个动作一条）。
 *
 * 不显示、也不推断"已退款 / 已入库"：B1 只做到批准与驳回，
 * 实物仍在买家手里，退款要等仓库验收之后。
 */
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import { afterSaleStatusTone } from '@/stores/aftersale'
import type { AfterSaleReturnVO } from '@/api/aftersale'
import { formatDateTime } from '@/utils/format'

const props = defineProps<{ value: AfterSaleReturnVO }>()

const emit = defineEmits<{ (e: 'close'): void }>()

const tone = computed(() => afterSaleStatusTone(props.value.status))
</script>

<template>
  <aside class="panel" data-return-detail>
    <header class="panel__head">
      <div class="panel__title">
        <span class="panel__no">{{ value.returnNo }}</span>
        <span class="panel__status" :class="`panel__status--${tone}`">
          {{ value.statusLabel }}
        </span>
      </div>
      <button type="button" class="panel__close" aria-label="关闭详情" @click="emit('close')">
        <AppIcon name="x" :size="14" />
      </button>
    </header>

    <dl class="facts">
      <div class="fact">
        <dt>原订单号</dt>
        <dd class="mono">{{ value.orderNo }}</dd>
      </div>
      <div class="fact">
        <dt>商品</dt>
        <dd>{{ value.productName }}<span class="mono fact__sku">{{ value.sku }}</span></dd>
      </div>
      <div class="fact">
        <dt>退货数量</dt>
        <dd>{{ value.quantity }} 件</dd>
      </div>
      <div class="fact">
        <dt>单价</dt>
        <dd>{{ value.unitPriceText }}</dd>
      </div>
      <div class="fact">
        <dt>预计退款</dt>
        <dd>{{ value.refundText }}</dd>
      </div>
      <div class="fact">
        <dt>提交来源</dt>
        <dd>{{ value.sourceLabel }}</dd>
      </div>
      <div class="fact fact--wide">
        <dt>退货原因</dt>
        <dd>{{ value.reason }}</dd>
      </div>
      <div class="fact">
        <dt>提交时间</dt>
        <dd>{{ formatDateTime(value.createdAt) }}</dd>
      </div>
    </dl>

    <section v-if="value.decision" class="decision" data-decision>
      <h3 class="events__title">客服处理结果</h3>
      <p class="decision__line">
        <span class="decision__kind" :class="`decision__kind--${tone}`">
          {{ value.decision.label }}
        </span>
        <span class="decision__when">{{ formatDateTime(value.decision.decidedAt) }}</span>
      </p>
      <p v-if="value.decision.reason" class="decision__reason">
        驳回原因：{{ value.decision.reason }}
      </p>
      <p v-else class="decision__reason decision__reason--muted">
        已批准：这 {{ value.quantity }} 件仍计入已占用额度，等仓库验收（不在本轮范围）。
      </p>
    </section>

    <section class="events">
      <h3 class="events__title">处理记录</h3>
      <p v-if="!value.events || !value.events.length" class="events__empty">暂无记录。</p>
      <ol v-else class="events__list">
        <li v-for="(event, index) in value.events" :key="`${event.type}-${index}`" class="event">
          <span class="event__dot" aria-hidden="true" />
          <div class="event__body">
            <p class="event__label">{{ event.label }}</p>
            <p class="event__meta">
              {{ formatDateTime(event.createdAt) }}
              <span v-if="event.actorId" class="mono">· {{ event.actorId }}</span>
            </p>
            <p v-if="event.note" class="event__note">{{ event.note }}</p>
          </div>
        </li>
      </ol>
    </section>

    <p class="foot">
      批准只代表"同意退"，实物仍在买家手里；驳回会在同一事务里释放本单占用的额度。
      可售库存与出库流水在两种情况下都没有变化。仓库验收、退货入库与退款不在本轮范围。
    </p>
  </aside>
</template>

<style scoped>
.panel {
  display: grid;
  gap: var(--s-4);
  height: 100%;
  overflow-y: auto;
  padding: var(--s-4);
}

.panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-3);
}

.panel__title {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.panel__no {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  color: var(--c-text);
  overflow-wrap: anywhere;
}

.panel__status {
  font-size: var(--fs-xs);
}

.panel__status--wait {
  color: var(--c-warn);
}

.panel__status--ok {
  color: var(--c-ok);
}

.panel__status--neutral {
  color: var(--c-text-3);
}

.panel__close {
  flex: none;
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-sm);
  color: var(--c-text-3);
}

.panel__close:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s-3);
  margin: 0;
  padding: var(--s-3) 0;
  border-top: 1px solid var(--c-line-soft);
  border-bottom: 1px solid var(--c-line-soft);
}

.fact {
  min-width: 0;
}

.fact--wide {
  grid-column: 1 / -1;
}

.fact dt {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  margin-bottom: 2px;
}

.fact dd {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--c-text);
  overflow-wrap: anywhere;
}

.fact__sku {
  margin-left: var(--s-2);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.mono {
  font-family: var(--font-mono);
}

.events {
  display: grid;
  gap: var(--s-2);
}

.decision {
  display: grid;
  gap: 4px;
  padding: var(--s-3);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-bg-soft);
}

.decision__line {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.decision__kind--ok {
  color: var(--c-ok);
}

.decision__kind--neutral {
  color: var(--c-warn);
}

.decision__when {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.decision__reason {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  overflow-wrap: anywhere;
}

.decision__reason--muted {
  color: var(--c-text-3);
}

.events__title {
  font-size: var(--fs-sm);
  font-weight: 500;
  color: var(--c-text);
}

.events__empty {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.events__list {
  display: grid;
  gap: var(--s-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.event {
  display: flex;
  gap: var(--s-2);
}

.event__dot {
  width: 6px;
  height: 6px;
  margin-top: 6px;
  flex: none;
  border-radius: 50%;
  background: var(--c-ok);
}

.event__body {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.event__label {
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.event__meta {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.event__note {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  overflow-wrap: anywhere;
}

.foot {
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  color: var(--c-text-3);
  padding-top: var(--s-2);
  border-top: 1px solid var(--c-line-soft);
}
</style>
