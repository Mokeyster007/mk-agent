<script setup lang="ts">
/**
 * 买家售后详情抽屉（C1：把客服结果、仓库批次、模拟退款聚合成一条时间线）。
 *
 * 四条边界：
 * 1. **阶段是服务端算好的**（`value.stage`）：页面不自己拼"待入库 / 待退款"这类说法，
 *    否则同一个事实在页面和售后 Agent 嘴里会变成两句话。
 * 2. **驳回是终止分支**：只显示提交与驳回结论，不出现收货批次、也不出现"待退款"，
 *    否则买家会以为还要继续等什么。
 * 3. **"已收满"不等于"已退款"**：收满但没有退款记录时，明确写"尚未登记模拟退款"。
 * 4. **不展示内部字段**：没有后台操作人 ID、没有请求哈希、没有流水 ID。
 *    退款也只说"模拟退款"，不写"已到账"。
 */
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import { afterSaleStatusTone } from '@/stores/aftersale'
import type { AfterSaleReturnVO } from '@/api/aftersale'
import { formatDateTime } from '@/utils/format'

const props = defineProps<{ value: AfterSaleReturnVO }>()

const emit = defineEmits<{ (e: 'close'): void }>()

const tone = computed(() => afterSaleStatusTone(props.value.status))

/** 已批准才有"收货 / 退款"这两步；驳回是终态，不展示它们。 */
const approved = computed(() => props.value.status === 'APPROVED')

const rejected = computed(() => props.value.status === 'REJECTED')

/** 进度条宽度；没有进度时为 0。 */
const progressPercent = computed(() => {
  const progress = props.value.progress
  if (!progress || progress.approvedQty <= 0) {
    return 0
  }
  return Math.min(100, Math.round((progress.receivedQty / progress.approvedQty) * 100))
})

/** 收货这一步是否已经完成（决定退款节点说"尚未登记"还是"等收满"）。 */
const receivedAll = computed(() => props.value.progress?.code === 'COMPLETE')
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

    <!-- 一句话结论：现在到哪一步、接下来等什么 -->
    <section class="stage" data-stage :data-stage-code="value.stage.code">
      <p class="stage__label">{{ value.stage.label }}</p>
      <p class="stage__hint">{{ value.stage.hint }}</p>
      <p v-if="value.progress" class="stage__counts" data-stage-counts>
        批准 <strong class="mono">{{ value.progress.approvedQty }}</strong> 件 · 已收
        <strong class="mono">{{ value.progress.receivedQty }}</strong> 件 · 还差
        <strong class="mono">{{ value.progress.remainingQty }}</strong> 件
      </p>
    </section>

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
        <dt>申请数量</dt>
        <dd>{{ value.quantity }} 件</dd>
      </div>
      <div class="fact">
        <dt>单价</dt>
        <dd>{{ value.unitPriceText }}</dd>
      </div>
      <!-- F-04：已驳回单不再并列展示“预计退款”，保留申请数量/单价/原因与驳回结论 -->
      <div v-if="!rejected" class="fact">
        <dt>预计退款（按申请数量估）</dt>
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

    <!-- 时间线：提交 → 客服 → 仓库 → 退款，一条线看完 -->
    <ol class="timeline" data-timeline>
      <li class="step step--done" data-step="submit">
        <span class="step__dot" aria-hidden="true" />
        <div class="step__body">
          <p class="step__title">提交申请</p>
          <p class="step__meta">{{ value.quantity }} 件 · {{ formatDateTime(value.createdAt) }}</p>
        </div>
      </li>

      <li
        class="step"
        :class="approved || rejected ? 'step--done' : 'step--current'"
        data-step="decision"
      >
        <span class="step__dot" :class="{ 'step__dot--warn': rejected }" aria-hidden="true" />
        <div class="step__body">
          <p class="step__title">客服处理结果</p>

          <template v-if="value.decision">
            <p class="step__meta" data-decision>
              {{ value.decision.label }} · {{ formatDateTime(value.decision.decidedAt) }}
            </p>
            <p v-if="value.decision.reason" class="step__note">
              驳回原因：{{ value.decision.reason }}
            </p>
            <p v-else class="step__note step__note--muted">
              批准不写原因；这 {{ value.quantity }} 件仍计入已占用额度，收货也不释放。
            </p>
          </template>
          <p v-else class="step__meta">等客服处理中，还没有结论。</p>
        </div>
      </li>

      <template v-if="approved">
        <li class="step" :class="receivedAll ? 'step--done' : 'step--current'" data-step="receipt">
          <span class="step__dot" aria-hidden="true" />
          <div class="step__body">
            <p class="step__title">仓库收货记录</p>
            <p class="step__meta" data-receipt-progress>
              {{ value.progress?.label }} {{ value.progress?.receivedQty ?? 0 }}/{{
                value.progress?.approvedQty ?? 0
              }}
              · 还差 {{ value.progress?.remainingQty ?? 0 }} 件
            </p>
            <p
              v-if="value.progress?.code === 'PARTIAL'"
              class="step__note step__note--muted"
              data-partial-close-note
            >
              当前版本暂不支持提前结束部分收货或释放未收件额度；请等待剩余 {{ value.progress.remainingQty }} 件收齐（提前结束将在下一阶段支持）。
            </p>

            <div class="progress__bar" role="img" :aria-label="value.progress?.label ?? '收货进度'">
              <span class="progress__fill" :style="{ width: `${progressPercent}%` }" />
            </div>

            <ol v-if="value.receipts && value.receipts.length" class="receipts" data-receipts>
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
                <p v-if="receipt.note" class="receipt__note">仓库备注：{{ receipt.note }}</p>
              </li>
            </ol>
            <p v-else class="step__note step__note--muted">仓库还没有登记任何一批收货。</p>
          </div>
        </li>

        <li class="step" :class="value.refund ? 'step--done' : 'step--current'" data-step="refund">
          <span class="step__dot" aria-hidden="true" />
          <div class="step__body">
            <p class="step__title">模拟退款</p>

            <template v-if="value.refund">
              <div data-refund>
                <p class="refund__amount">
                  <strong class="mono" data-refund-amount>{{ value.refund.amountText }}</strong>
                  <span class="refund__no mono">{{ value.refund.refundNo }}</span>
                </p>
                <p class="step__meta">
                  {{ value.refund.label }} · {{ formatDateTime(value.refund.createdAt) }}
                </p>
                <p class="step__note step__note--muted">
                  金额按「成交单价 × 累计实收」结算（含损坏件）。这是模拟退款：不接支付平台，
                  没有真实资金流动，也不需要你提供任何账户信息。
                </p>
              </div>
            </template>

            <p v-else-if="receivedAll" class="step__note" data-refund-pending>
              已全部收货，尚未登记模拟退款。
            </p>
            <p v-else class="step__note step__note--muted" data-refund-blocked>
              收满 {{ value.progress?.approvedQty ?? value.quantity }} 件后，客服才能登记模拟退款。
            </p>
          </div>
        </li>
      </template>
    </ol>

    <section class="events">
      <h3 class="events__title">处理记录</h3>
      <p v-if="!value.events || !value.events.length" class="events__empty">暂无记录。</p>
      <ol v-else class="events__list">
        <li v-for="(event, index) in value.events" :key="`${event.type}-${index}`" class="event">
          <span class="event__dot" aria-hidden="true" />
          <div class="event__body">
            <p class="event__label">{{ event.label }}</p>
            <p class="event__meta">{{ formatDateTime(event.createdAt) }}</p>
            <p v-if="event.note" class="event__note">{{ event.note }}</p>
          </div>
        </li>
      </ol>
    </section>

    <p class="foot">
      提交、客服结论、仓库收货、模拟退款是四件不同的事：驳回会释放额度并终止流程；
      收货只回补合格件的可售库存、不释放额度；模拟退款按累计实收结算，不改库存、不释放额度，
      也不接支付平台——这里没有真实资金流。
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

.stage {
  display: grid;
  gap: 3px;
  padding: var(--s-3);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-accent-weak);
}

.stage__label {
  font-size: var(--fs-base);
  font-weight: 500;
  color: var(--c-text);
}

.stage__hint {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  line-height: var(--lh-base);
}

.stage__counts {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
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

.timeline {
  display: grid;
  gap: var(--s-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.step {
  display: flex;
  gap: var(--s-2);
  padding-left: 2px;
}

.step__dot {
  width: 8px;
  height: 8px;
  margin-top: 6px;
  flex: none;
  border-radius: 50%;
  background: var(--c-line-strong);
}

.step--done .step__dot {
  background: var(--c-ok);
}

.step--current .step__dot {
  background: var(--c-warn);
}

.step__dot--warn {
  background: var(--c-warn);
}

.step__body {
  min-width: 0;
  display: grid;
  gap: 3px;
  flex: 1;
}

.step__title {
  font-size: var(--fs-sm);
  font-weight: 500;
  color: var(--c-text);
}

.step__meta {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  overflow-wrap: anywhere;
}

.step__note {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  line-height: var(--lh-base);
  overflow-wrap: anywhere;
}

.step__note--muted {
  color: var(--c-text-3);
}

.progress__bar {
  height: 6px;
  margin: 2px 0;
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

.receipts {
  display: grid;
  gap: var(--s-2);
  margin: var(--s-1) 0 0;
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

.receipt__note {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  overflow-wrap: anywhere;
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

.events {
  display: grid;
  gap: var(--s-2);
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
