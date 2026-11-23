<script setup lang="ts">
/**
 * 电商导购 Agent 对话面板。
 *
 * 展示顺序：用户输入 → 工具进度 → 结构化商品卡 / 对比表 / 购物车快照 / 结算预览 →
 * 下单确认卡（ApprovalCard）→ 订单创建成功卡（直达订单详情）→ 模型说明。
 *
 * 纪律：
 * - 普通用户不聊 Agent 也能完整选品、加购、结算下单；本面板是帮助选品和处理模糊诉求的入口；
 * - 所有商品卡、规格、价格、库存、订单号均由后端结构化事件驱动，绝不从聊天文案反解析。
 */
import { computed, nextTick, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import ApprovalCard from './ApprovalCard.vue'
import MarkdownView from './MarkdownView.vue'
import type { ShopProductVO } from '@/api/shop'
import type { ShopAssistantChatStore, ShopAssistantTurn } from '@/stores/shopAssistantChat'

const props = defineProps<{
  chat: ShopAssistantChatStore
  canOrder: boolean
}>()

const emit = defineEmits<{
  (e: 'add-to-cart', product: ShopProductVO): void
  (e: 'compare-skus', skus: string[]): void
  (e: 'preview-checkout', productId: number, quantity: number): void
  (e: 'open-order', orderNo: string): void
}>()

const draft = ref('')
const listRef = ref<HTMLElement | null>(null)

const turns = computed(() => props.chat.turns.value)
const busy = computed(() => props.chat.busy.value)
const conversationUnavailable = computed(() => props.chat.conversationUnavailable.value)

const QUICK_PROMPTS = [
  '400 元内、无线、兼容 Mac，最好安静的键盘',
  '对比前两款键盘的规格差异',
  '第二款加一把到购物车',
  '预览购物车商品的结算价格',
]

async function scrollToEnd(): Promise<void> {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

async function onSend(customPrompt?: string): Promise<void> {
  const text = customPrompt ?? draft.value
  if (!text.trim() || busy.value) return
  if (!customPrompt) draft.value = ''
  const accepted = await props.chat.submit(text)
  if (!accepted && !customPrompt) {
    draft.value = text
    return
  }
  await scrollToEnd()
}

function running(turn: ShopAssistantTurn): boolean {
  return turn.transport === 'submitting' || turn.transport === 'streaming'
}

async function onPrepareCheckoutCard(turn: ShopAssistantTurn): Promise<void> {
  const preview = turn.checkoutPreview
  if (!preview || !preview.eligible) return
  await props.chat.prepareCheckoutCard(preview.productId, preview.productName, preview.quantity, true)
  await scrollToEnd()
}
</script>

<template>
  <section id="guide" class="guide" data-shop-assistant>
    <header class="guide__head">
      <div class="guide__title">
        <span class="guide__icon" aria-hidden="true">
          <AppIcon name="sparkle" :size="15" />
        </span>
        <div>
          <h2 class="guide__name">电商导购助手</h2>
          <p class="guide__sub">
            自然语言按硬条件选品、规格对比、加入共享购物车与确认下单。
            下单前必须经确认卡批准，且实时重查价格与库存（演示付款，发货时才扣库存）。
          </p>
        </div>
      </div>
      <button
        v-if="turns.length"
        type="button"
        class="link-btn"
        :disabled="busy"
        @click="chat.reset()"
      >
        新对话
      </button>
    </header>

    <div class="guide__quick">
      <button
        v-for="prompt in QUICK_PROMPTS"
        :key="prompt"
        type="button"
        class="quick-chip"
        :disabled="busy"
        @click="onSend(prompt)"
      >
        {{ prompt }}
      </button>
    </div>

    <p v-if="conversationUnavailable" class="guide__warn">
      <AppIcon name="alert" :size="13" />
      会话未建成（可能模型或身份未启用），页面下方的筛选、对比、购物车与结算下单仍可直接使用。
    </p>

    <div ref="listRef" class="guide__turns">
      <p v-if="!turns.length" class="guide__empty">
        试试直接问：「400 元内、无线、兼容 Mac，最好安静的键盘」，再比较两款或说「第二款加一把」。
      </p>

      <article v-for="turn in turns" :key="turn.id" class="turn" :data-shop-turn-id="turn.id">
        <p class="turn__prompt">{{ turn.prompt }}</p>

        <ul v-if="turn.steps.length" class="turn__steps">
          <li v-for="(step, idx) in turn.steps" :key="idx" class="turn__step">{{ step }}</li>
        </ul>
        <p v-if="running(turn) && !turn.steps.length" class="turn__pending">正在处理…</p>

        <!-- 1. 结构化商品候选卡 -->
        <div v-if="turn.searchResult" class="struct-block" data-agent-product-cards>
          <div class="struct-block__head">
            <span class="struct-block__title">
              合规商品候选（{{ turn.searchResult.items.length }} 款）
            </span>
            <button
              v-if="turn.searchResult.items.length >= 2"
              type="button"
              class="link-btn"
              @click="emit('compare-skus', turn.searchResult.items.slice(0, 2).map((i) => i.sku))"
            >
              对比前两款
            </button>
          </div>

          <div v-if="!turn.searchResult.items.length" class="zero-box">
            <p class="zero-box__title">没有找到满足全部硬条件的商品（未推荐不合规商品）。</p>
            <ul v-if="turn.searchResult.relaxSuggestions.length" class="zero-box__list">
              <li v-for="(tip, i) in turn.searchResult.relaxSuggestions" :key="i">{{ tip }}</li>
            </ul>
          </div>

          <div v-else class="mini-cards">
            <div
              v-for="(item, idx) in turn.searchResult.items"
              :key="item.sku"
              class="mini-card"
              :data-card-sku="item.sku"
            >
              <div class="mini-card__top">
                <span class="mini-card__idx">#{{ idx + 1 }}</span>
                <strong class="mini-card__name">{{ item.name }}</strong>
                <span class="mini-card__price">{{ item.priceText }}</span>
              </div>
              <p class="mini-card__sub">
                <span class="mono">{{ item.sku }}</span> · 库存 {{ item.available }} 件
              </p>
              <div class="mini-card__specs">
                <span class="spec-tag">{{ item.connectivityLabel }}</span>
                <span class="spec-tag">Mac：{{ item.macCompatibleLabel }}</span>
                <span class="spec-tag">静音：{{ item.silentLabel }}</span>
                <span v-if="item.switchType && item.switchType !== '未知'" class="spec-tag">{{ item.switchType }}</span>
              </div>
              <div class="mini-card__actions">
                <button
                  type="button"
                  class="mk-btn mk-btn--sm"
                  :disabled="!canOrder"
                  @click="emit('add-to-cart', item)"
                >
                  加入购物车
                </button>
                <button
                  type="button"
                  class="mk-btn mk-btn--sm mk-btn--primary"
                  :disabled="!canOrder"
                  @click="emit('preview-checkout', item.id, 1)"
                >
                  结算预览
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- 2. 结构化规格对比表 -->
        <div v-if="turn.comparison" class="struct-block" data-agent-comparison>
          <p class="struct-block__title">规格对比（缺失字段如实标注「未知」）</p>
          <div class="cmp-wrap">
            <table class="cmp-table">
              <thead>
                <tr>
                  <th>维度</th>
                  <th v-for="p in turn.comparison.products" :key="p.sku">
                    {{ p.name }}
                    <div class="mono cmp-sku">{{ p.sku }}</div>
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="dim in turn.comparison.dimensions" :key="dim.label">
                  <td class="cmp-dim">{{ dim.label }}</td>
                  <td v-for="(val, i) in dim.values" :key="i">{{ val }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 3. 结算预览卡 -->
        <div v-if="turn.checkoutPreview" class="struct-block" data-agent-checkout-preview>
          <p class="struct-block__title">服务端结算预览（单 SKU 订单）</p>
          <p class="preview-line">
            <strong>{{ turn.checkoutPreview.productName }}</strong>
            <span class="mono">（{{ turn.checkoutPreview.sku }}）</span>
            × {{ turn.checkoutPreview.quantity }} · 单价 {{ turn.checkoutPreview.unitPriceText }} ·
            总额 <strong>{{ turn.checkoutPreview.totalText }}</strong>
          </p>
          <p class="preview-note">
            {{ turn.checkoutPreview.singleSkuNotice }} {{ turn.checkoutPreview.stockNotice }}
          </p>
          <p v-if="!turn.checkoutPreview.eligible" class="turn__error">
            {{ turn.checkoutPreview.message }}
          </p>
          <div v-else-if="!turn.approvalPayload && !turn.createdOrderNo" class="mini-card__actions">
            <button
              type="button"
              class="mk-btn mk-btn--sm mk-btn--primary"
              :disabled="busy || !canOrder"
              @click="onPrepareCheckoutCard(turn)"
            >
              发起下单确认卡
            </button>
          </div>
        </div>

        <!-- 4. 下单人工确认卡 -->
        <ApprovalCard
          v-if="turn.approvalPayload"
          :payload="turn.approvalPayload"
          :approval="turn.approval"
          :busy="turn.deciding"
          @approve="chat.decide(turn.id, true)"
          @reject="chat.decide(turn.id, false)"
          @resume="chat.resume(turn.id)"
          @refresh="chat.refreshApproval(turn.id)"
        />

        <!-- 5. 订单已创建卡 -->
        <div v-if="turn.createdOrderNo" class="order-done" data-agent-order-created>
          <div>
            <p class="order-done__title">演示订单已创建</p>
            <p class="order-done__no mono">{{ turn.createdOrderNo }}</p>
            <p v-if="turn.createdOrder" class="order-done__meta">
              {{ turn.createdOrder.item?.productName }} × {{ turn.createdOrder.item?.quantity }} ·
              {{ turn.createdOrder.totalText }} · 尚未扣减库存（发货时扣减）
            </p>
          </div>
          <button
            type="button"
            class="mk-btn mk-btn--sm mk-btn--primary"
            @click="emit('open-order', turn.createdOrderNo)"
          >
            打开订单详情
          </button>
        </div>

        <p v-if="turn.checkoutFailedMessage" class="turn__error">
          <AppIcon name="alert" :size="13" />
          {{ turn.checkoutFailedMessage }}
        </p>

        <div v-if="turn.answer" class="turn__answer">
          <MarkdownView :source="turn.answer" />
        </div>

        <p v-if="turn.error" class="turn__error">
          <AppIcon name="alert" :size="13" />
          {{ turn.error }}
        </p>
      </article>
    </div>

    <form class="guide__composer" @submit.prevent="onSend()">
      <textarea
        v-model="draft"
        class="guide__input"
        rows="2"
        placeholder="输入选品需求、比较问题，或「第二款加一把」「帮我结算预览」…"
        :disabled="busy"
        aria-label="导购助手输入"
        @keydown.enter.exact.prevent="onSend()"
      />
      <button type="submit" class="mk-btn mk-btn--primary" :disabled="busy || !draft.trim()">
        <AppIcon name="send" :size="14" />
        {{ busy ? '处理中…' : '发送' }}
      </button>
    </form>
  </section>
</template>

<style scoped>
.guide {
  display: grid;
  gap: var(--s-3);
  padding: var(--s-4);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-bg-soft);
}

.guide__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-3);
}

.guide__title {
  display: flex;
  gap: var(--s-3);
  min-width: 0;
}

.guide__icon {
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

.guide__name {
  font-size: var(--fs-md);
  font-weight: 500;
  color: var(--c-text);
}

.guide__sub {
  margin-top: 2px;
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  color: var(--c-text-3);
}

.guide__quick {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.quick-chip {
  padding: 4px 10px;
  border: 1px solid var(--c-line);
  border-radius: var(--r-pill);
  background: var(--c-surface);
  color: var(--c-text-2);
  font-size: var(--fs-xs);
  cursor: pointer;
}

.quick-chip:hover:not(:disabled) {
  border-color: var(--c-accent-line);
  color: var(--c-accent-ink);
}

.guide__warn {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--c-warn);
}

.guide__turns {
  display: grid;
  gap: var(--s-3);
  max-height: 560px;
  overflow-y: auto;
  padding-right: 2px;
}

.guide__empty {
  font-size: var(--fs-sm);
  color: var(--c-text-3);
}

.turn {
  display: grid;
  gap: var(--s-2);
  padding: var(--s-3);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-surface);
}

.turn__prompt {
  justify-self: end;
  max-width: 90%;
  padding: 6px 10px;
  border-radius: var(--r-md);
  background: var(--c-accent-weak);
  color: var(--c-text);
  font-size: var(--fs-sm);
}

.turn__steps {
  display: grid;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.turn__step,
.turn__pending {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.struct-block {
  display: grid;
  gap: var(--s-2);
  padding: var(--s-3);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-sm);
  background: var(--c-bg-soft);
}

.struct-block__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2);
}

.struct-block__title {
  font-size: var(--fs-xs);
  font-weight: 500;
  color: var(--c-text-2);
}

.zero-box {
  display: grid;
  gap: 4px;
  font-size: var(--fs-xs);
  color: var(--c-warn);
}

.zero-box__list {
  margin: 0;
  padding-left: 18px;
  color: var(--c-text-2);
}

.mini-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: var(--s-2);
}

.mini-card {
  display: grid;
  gap: 6px;
  padding: var(--s-2) var(--s-3);
  border: 1px solid var(--c-line);
  border-radius: var(--r-sm);
  background: var(--c-surface);
}

.mini-card__top {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.mini-card__idx {
  font-size: 11px;
  color: var(--c-accent-ink);
  font-family: var(--font-mono);
}

.mini-card__name {
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.mini-card__price {
  margin-left: auto;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--c-accent-ink);
}

.mini-card__sub {
  font-size: 11px;
  color: var(--c-text-3);
}

.mini-card__specs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.spec-tag {
  padding: 1px 6px;
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-pill);
  font-size: 11px;
  color: var(--c-text-2);
  background: var(--c-bg-soft);
}

.mini-card__actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  padding-top: 2px;
}

.cmp-wrap {
  overflow-x: auto;
}

.cmp-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-xs);
}

.cmp-table th,
.cmp-table td {
  padding: 6px 8px;
  border-bottom: 1px solid var(--c-line-soft);
  text-align: left;
  color: var(--c-text);
}

.cmp-dim {
  color: var(--c-text-3);
  white-space: nowrap;
}

.cmp-sku {
  font-size: 10.5px;
  font-weight: 400;
  color: var(--c-text-3);
}

.preview-line {
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.preview-note {
  font-size: 11px;
  color: var(--c-text-3);
}

.order-done {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  flex-wrap: wrap;
  padding: var(--s-3);
  border: 1px solid rgba(118, 214, 178, 0.35);
  border-radius: var(--r-sm);
  background: rgba(118, 214, 178, 0.08);
}

.order-done__title {
  font-size: var(--fs-xs);
  color: var(--c-ok);
  font-weight: 500;
}

.order-done__no {
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.order-done__meta {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
}

.turn__answer {
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
  color: var(--c-text);
}

.turn__error {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--c-danger);
}

.guide__composer {
  display: flex;
  align-items: flex-end;
  gap: var(--s-2);
}

.guide__input {
  flex: 1;
  min-width: 0;
  resize: vertical;
  padding: 8px 10px;
  border: 1px solid var(--c-line);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-text);
  font: inherit;
  font-size: var(--fs-sm);
}

.link-btn {
  font-size: var(--fs-xs);
  color: var(--c-accent-ink);
}

.mono {
  font-family: var(--font-mono);
}
</style>
