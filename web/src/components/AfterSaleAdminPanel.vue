<script setup lang="ts">
/**
 * 客服售后详情抽屉（B1 决策 + B2 收货验货 + B3 模拟退款）。
 *
 * 只显示服务端返回的事实：
 * - 退货单号、买家、商品、数量、金额、原因、来源；
 * - 决策记录（谁在什么时候、批准还是驳回、驳回原因）；
 * - 收货进度（批准 / 已收 / 还可收）与每一批的验货结果；
 * - 模拟退款记录（单号、金额、操作人、时间）；
 * - 处理记录（提交 → 决策，一个动作一条）。
 *
 * 退款是**模拟**的：文案只说"模拟退款"，不写"已到账""已原路退回"——
 * 这个 demo 没有接支付平台，写那句话就是骗人。
 */
import { computed, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import { afterSaleStatusTone } from '@/stores/aftersale'
import type { AfterSaleReturnVO } from '@/api/aftersale'
import { formatDateTime } from '@/utils/format'

const props = defineProps<{ value: AfterSaleReturnVO; busy?: boolean }>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'approve'): void
  (e: 'reject', reason: string): void
}>()

const reason = ref('')

const pending = computed(() => props.value.status === 'SUBMITTED')

const tone = computed(() => afterSaleStatusTone(props.value.status))

const canReject = computed(() => !props.busy && reason.value.trim().length > 0)

/** 进度条宽度；没有进度时为 0。 */
const progressPercent = computed(() => {
  const progress = props.value.progress
  if (!progress || progress.approvedQty <= 0) {
    return 0
  }
  return Math.min(100, Math.round((progress.receivedQty / progress.approvedQty) * 100))
})

/** 流水增量带符号：+1 / -2。 */
function signed(value: number): string {
  return value > 0 ? `+${value}` : String(value)
}
</script>

<template>
  <aside class="panel" data-admin-return-detail>
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
        <dt>买家</dt>
        <dd class="mono">{{ value.buyerId }}</dd>
      </div>
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
        <dt>预计退款（按申请数量估）</dt>
        <dd>{{ value.refundText }}</dd>
      </div>
      <div class="fact">
        <dt>提交来源</dt>
        <dd>{{ value.sourceLabel }}</dd>
      </div>
      <div class="fact fact--wide">
        <dt>买家填写的原因</dt>
        <dd>{{ value.reason }}</dd>
      </div>
      <div class="fact">
        <dt>提交时间</dt>
        <dd>{{ formatDateTime(value.createdAt) }}</dd>
      </div>
    </dl>

    <section v-if="value.progress" class="progress" data-receipt-progress>
      <h3 class="section__title">收货进度</h3>
      <div class="progress__bar" role="img" :aria-label="value.progress.label">
        <span class="progress__fill" :style="{ width: `${progressPercent}%` }" />
      </div>
      <p class="progress__text">
        <span class="progress__label">{{ value.progress.label }}</span>
        批准 <strong class="mono">{{ value.progress.approvedQty }}</strong> 件 · 已收
        <strong class="mono">{{ value.progress.receivedQty }}</strong> 件 · 还可收
        <strong class="mono">{{ value.progress.remainingQty }}</strong> 件
      </p>
      <p v-if="value.progress.code === 'PARTIAL'" class="muted" style="margin-top: 6px; font-size: 12px;">
        当前版本暂不支持提前结束部分收货并释放未收件额度（下一阶段支持）；请在剩余 {{ value.progress.remainingQty }} 件到齐后继续登记收货。
      </p>
    </section>

    <section v-if="value.receipts && value.receipts.length" class="receipts" data-receipts>
      <h3 class="section__title">收货记录</h3>
      <ol class="receipts__list">
        <li
          v-for="receipt in value.receipts"
          :key="receipt.receiptNo"
          class="receipt"
          :data-receipt-no="receipt.receiptNo"
        >
          <p class="receipt__head">
            <span class="mono">{{ receipt.receiptNo }}</span>
            <span class="receipt__when">{{ formatDateTime(receipt.createdAt) }}</span>
          </p>
          <p class="receipt__qty">
            实收 <strong class="mono">{{ receipt.receivedQty }}</strong> 件 · 合格
            <strong class="mono receipt__ok">{{ receipt.qualifiedQty }}</strong> 件 · 损坏
            <strong class="mono" :class="{ receipt__warn: receipt.damagedQty > 0 }">
              {{ receipt.damagedQty }}
            </strong>
            件
          </p>
          <p v-if="receipt.movement" class="receipt__movement" data-receipt-movement>
            {{ receipt.movement.typeLabel }}
            <strong class="mono">{{ signed(receipt.movement.quantityDelta) }}</strong> 件 ·
            {{ receipt.movement.sku }} · 流水 #{{ receipt.movement.id }}
          </p>
          <p v-else class="receipt__movement receipt__movement--muted" data-receipt-no-movement>
            整批损坏：没有入库流水，可售库存未变。
          </p>
          <p v-if="receipt.note" class="receipt__note">备注：{{ receipt.note }}</p>
          <p class="receipt__who mono">{{ receipt.operatorId }}</p>
        </li>
      </ol>
    </section>

    <section v-if="value.refund" class="refund" data-refund>
      <h3 class="section__title">{{ value.refund.label }}</h3>
      <p class="refund__amount">
        <strong class="mono" data-refund-amount>{{ value.refund.amountText }}</strong>
        <span class="refund__no mono">{{ value.refund.refundNo }}</span>
      </p>
      <p class="refund__meta">
        {{ formatDateTime(value.refund.createdAt) }}
        <span class="mono">· {{ value.refund.operatorId }}</span>
      </p>
      <p class="refund__meta refund__meta--muted">
        按「原订单成交单价 × 累计实收」结算（含损坏件）。这是模拟退款，不接支付平台、没有真实资金流；
        可售库存与可退额度都没有变化。
      </p>
    </section>

    <section v-if="value.decision" class="decision" data-decision>
      <h3 class="section__title">决策记录</h3>
      <p class="decision__line">
        <span class="decision__kind" :class="`decision__kind--${tone}`">
          {{ value.decision.label }}
        </span>
        <span class="decision__who mono">{{ value.decision.decidedBy }}</span>
        <span class="decision__when">{{ formatDateTime(value.decision.decidedAt) }}</span>
      </p>
      <p v-if="value.decision.reason" class="decision__reason">
        驳回原因：{{ value.decision.reason }}
      </p>
      <p v-else class="decision__reason decision__reason--muted">
        批准不写原因；该申请占用的 {{ value.quantity }} 件额度继续保留，收货也不释放。
      </p>
    </section>

    <section v-if="value.status === 'APPROVED'" class="hint-block" data-receivable>
      <h3 class="section__title">这一单已批准</h3>
      <p class="hint-block__text">
        在列表里点「收货」登记这一批的实收 / 合格 / 损坏。还可收
        <strong class="mono">{{ value.progress?.remainingQty ?? 0 }}</strong> 件；只有合格数量回补可售库存。
      </p>
      <p v-if="!value.refund" class="hint-block__text">
        收满（{{ value.progress?.receivedQty ?? 0 }}/{{ value.progress?.approvedQty ?? 0 }}）之后，
        列表里会出现「模拟退款」按钮。
      </p>
    </section>

    <section v-if="pending" class="actions">
      <h3 class="section__title">处理这一单</h3>
      <div class="actions__row">
        <button
          type="button"
          class="mk-btn mk-btn--primary"
          :disabled="busy"
          data-action="approve"
          @click="emit('approve')"
        >
          {{ busy ? '处理中…' : '批准（保留额度）' }}
        </button>
      </div>

      <label class="field">
        <span class="field__label">驳回原因（必填，最多 200 字）</span>
        <textarea
          v-model="reason"
          class="field__input"
          rows="2"
          data-reject-reason
          placeholder="例如：超过 7 天无理由期限，且商品已使用"
        />
      </label>
      <div class="actions__row">
        <button
          type="button"
          class="mk-btn"
          :disabled="!canReject"
          data-action="reject"
          @click="emit('reject', reason.trim())"
        >
          {{ busy ? '处理中…' : '驳回（释放额度）' }}
        </button>
        <span class="actions__hint">驳回会释放本单申请数量，可退额度随之增加。</span>
      </div>
    </section>

    <section class="events">
      <h3 class="section__title">处理记录</h3>
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
      B3 停在模拟退款：只有「已批准且全部实收」的退货单能生成一笔退款记录，金额按
      「原订单成交单价 × 累计实收」结算。退款不动可售库存、不写第二条入库流水、不释放可退额度，
      也不接支付平台——这里没有真实资金流，所以只写「模拟退款」，不写任何资金已结清的措辞。
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

.section__title {
  font-size: var(--fs-sm);
  font-weight: 500;
  color: var(--c-text);
  margin-bottom: var(--s-2);
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
  flex-wrap: wrap;
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.decision__kind--ok {
  color: var(--c-ok);
}

.decision__kind--neutral {
  color: var(--c-warn);
}

.decision__who {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
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

.actions {
  display: grid;
  gap: var(--s-2);
  padding: var(--s-3);
  border: 1px dashed var(--c-line-strong);
  border-radius: var(--r-md);
}

.progress {
  display: grid;
  gap: var(--s-2);
}

.progress__bar {
  height: 6px;
  border-radius: var(--r-pill);
  background: var(--c-surface-2);
  overflow: hidden;
}

.progress__fill {
  display: block;
  height: 100%;
  border-radius: var(--r-pill);
  background: var(--c-ok);
  transition: width 0.2s ease;
}

.progress__text {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  line-height: var(--lh-base);
}

.progress__label {
  margin-right: var(--s-2);
  color: var(--c-text);
}

.receipts {
  display: grid;
  gap: var(--s-2);
}

.receipts__list {
  display: grid;
  gap: var(--s-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.receipt {
  display: grid;
  gap: 2px;
  padding: var(--s-2) var(--s-3);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-sm);
  background: var(--c-bg-soft);
}

.receipt__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  overflow-wrap: anywhere;
}

.receipt__when {
  flex: none;
}

.receipt__qty {
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.receipt__ok {
  color: var(--c-ok);
}

.receipt__warn {
  color: var(--c-warn);
}

.receipt__movement {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  overflow-wrap: anywhere;
}

.receipt__movement--muted {
  color: var(--c-text-3);
}

.receipt__note {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  overflow-wrap: anywhere;
}

.receipt__who {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.hint-block {
  display: grid;
  gap: 4px;
  padding: var(--s-3);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-accent-weak);
}

.refund {
  display: grid;
  gap: 4px;
  padding: var(--s-3);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-ok-weak);
}

.refund__amount {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
  flex-wrap: wrap;
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.refund__amount strong {
  font-size: var(--fs-lg);
  color: var(--c-ok);
}

.refund__no {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  overflow-wrap: anywhere;
}

.refund__meta {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  line-height: var(--lh-base);
}

.refund__meta--muted {
  color: var(--c-text-3);
}

.hint-block__text {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  line-height: var(--lh-base);
}

.actions__row {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
}

.actions__hint {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.field {
  display: grid;
  gap: 4px;
}

.field__label {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.field__input {
  width: 100%;
  padding: 6px var(--s-2);
  border: 1px solid var(--c-line);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-text);
  font-family: inherit;
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
  resize: vertical;
}

.events {
  display: grid;
  gap: var(--s-2);
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
