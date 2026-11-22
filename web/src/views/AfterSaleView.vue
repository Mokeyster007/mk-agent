<script setup lang="ts">
/**
 * 买家售后页（A1）：售后对话 + 可退订单 + 普通退货表单 + 我的售后。
 *
 * 三条产品约束（本轮范围）：
 * 1. 只做到「已提交、可查」：没有客服审核、仓库验收、退货入库、退款；
 * 2. A1 **不动可售库存、不写退货入库流水**，页面上明确写这句话；
 * 3. 额度三个数（已购 / 已占用 / 剩余）全部来自服务端，前端不自己减。
 *
 * 页面不判断权限：能不能提交由服务端判定，页面只按 code 显示原因。
 */
import { computed, onMounted, ref } from 'vue'
import AppShell from '@/components/AppShell.vue'
import ShopNav from '@/components/ShopNav.vue'
import AppIcon from '@/components/AppIcon.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import AfterSaleChatPanel from '@/components/AfterSaleChatPanel.vue'
import AfterSaleReturnPanel from '@/components/AfterSaleReturnPanel.vue'
import { session } from '@/stores/session'
import { shop } from '@/stores/shop'
import { afterSale } from '@/stores/aftersale'
import { createAfterSaleChatStore } from '@/stores/aftersaleChat'
import { runAfterSaleChat } from '@/api/aftersale'
import { createConversation } from '@/api/conversations'
import { decideApproval, getApproval, resumeApproval } from '@/api/approvals'
import { formatDateTime } from '@/utils/format'

const navOpen = ref(false)

/**
 * 对话 store 在页面里建，是为了把"批准之后刷新业务数据"接上：
 * 批准只代表同意执行，是否真的建单要看「我的售后」——
 * 所以执行完必须重新读一次列表与额度，而不是相信对话里的措辞。
 */
const chat = createAfterSaleChatStore({
  chat: runAfterSaleChat,
  conversation: createConversation,
  approval: getApproval,
  decide: decideApproval,
  resume: resumeApproval,
  onExecuted: async () => {
    await Promise.all([afterSale.loadReturns(), afterSale.loadOrders()])
  },
})

const detailOpen = computed(() => afterSale.state.detail !== null)
const selected = computed(() => afterSale.quotaOf(afterSale.form.orderNo))
const maxQuantity = computed(() => {
  const order = selected.value
  return order ? Math.max(1, order.quota.purchased) : 1
})

const canSubmit = computed(
  () =>
    !!afterSale.form.orderNo &&
    !afterSale.state.submitting &&
    !!afterSale.form.reason.trim(),
)

function step(delta: number): void {
  afterSale.setQuantity(afterSale.form.quantity + delta)
}

function onQuantityInput(event: Event): void {
  const value = Number((event.target as HTMLInputElement).value)
  afterSale.setQuantity(Number.isFinite(value) ? value : 1)
}

function onReasonInput(event: Event): void {
  afterSale.setReason((event.target as HTMLTextAreaElement).value)
}

function quotaText(occupied: number, remaining: number): string {
  return `已占用 ${occupied} 件 · 剩余可退 ${remaining} 件`
}

async function onSwitchIdentity(userId: string): Promise<void> {
  if (!session.updateUserId(userId)) {
    return
  }
  // 换人就是换数据：先清空，再按新身份重新读
  shop.reset()
  afterSale.reset()
  chat.reset()
  await shop.loadSession()
  await Promise.all([afterSale.loadOrders(), afterSale.loadReturns()])
}

async function reloadAll(): Promise<void> {
  afterSale.clearFeedback()
  await Promise.all([afterSale.loadOrders(), afterSale.loadReturns()])
}

onMounted(async () => {
  await shop.loadSession()
  await Promise.all([afterSale.loadOrders(), afterSale.loadReturns()])
})
</script>

<template>
  <AppShell
    :panel-open="detailOpen"
    :nav-open="navOpen"
    @toggle-nav="navOpen = !navOpen"
    @close-overlays="navOpen = false; afterSale.closeDetail()"
  >
    <template #nav>
      <ShopNav @switch-identity="onSwitchIdentity" />
    </template>

    <template #topbar>
      <div class="topbar">
        <h1 class="topbar__title">售后</h1>
        <span class="topbar__demo">A1 · 只到「已提交」</span>

        <div class="topbar__right">
          <span class="topbar__identity" :title="shop.state.session?.userId ?? '未识别'">
            {{ shop.state.session?.userId ?? '未识别身份' }}
          </span>
          <RouterLink class="topbar__link" to="/shop">返回商城</RouterLink>
        </div>
      </div>
    </template>

    <div class="page">
      <div class="page__inner">
        <ErrorNotice
          v-if="afterSale.state.error"
          tone="danger"
          title="操作没有完成"
          :detail="afterSale.state.error"
          action-label="重新读取"
          @action="reloadAll"
        />

        <p v-if="afterSale.state.notice" class="banner banner--ok" role="status">
          <AppIcon name="check" :size="14" />
          {{ afterSale.state.notice }}
        </p>

        <AfterSaleChatPanel :chat="chat" />

        <section id="orders" class="section">
          <header class="section__head">
            <h2 class="section__title">可退订单</h2>
            <p class="section__hint">
              只列已签收的订单。退货窗口按签收时间计算（演示默认 7 天，服务端
              mkagent.shop.return-window-days 可配）。
            </p>
            <button type="button" class="link-btn" @click="reloadAll">刷新</button>
          </header>

          <p v-if="afterSale.state.loadingOrders && !afterSale.state.orders.length" class="muted">
            正在读取订单…
          </p>
          <p v-else-if="!afterSale.state.orders.length" class="muted">
            没有已签收的订单。先在商城下单，再由后台发货、签收，然后回到这里申请退货。
          </p>

          <ul v-else class="orders">
            <li
              v-for="order in afterSale.state.orders"
              :key="order.orderNo"
              class="order"
              :data-order-no="order.orderNo"
            >
              <div class="order__main">
                <div class="order__title">
                  <span class="chip" :class="order.returnable ? 'chip--ok' : 'chip--wait'">
                    {{ order.returnable ? '可申请退货' : order.expired ? '已超窗口' : '额度用尽' }}
                  </span>
                  <span class="order__no">{{ order.orderNo }}</span>
                </div>
                <p class="order__item">
                  {{ order.productName }} × {{ order.quota.purchased }}
                  <span class="order__sku">{{ order.sku }}</span>
                </p>
                <p class="order__time">
                  签收 {{ formatDateTime(order.signedAt) }} · 截止 {{ formatDateTime(order.deadline) }}
                </p>
              </div>

              <div class="order__side">
                <span class="order__quota">
                  {{ quotaText(order.quota.occupied, order.quota.remaining) }}
                </span>
                <button
                  type="button"
                  class="mk-btn mk-btn--sm"
                  :disabled="!order.returnable"
                  @click="afterSale.selectOrder(order.orderNo)"
                >
                  申请退货
                </button>
              </div>
            </li>
          </ul>
        </section>

        <section id="form" class="section">
          <header class="section__head">
            <h2 class="section__title">普通退货表单</h2>
            <p class="section__hint">不依赖模型：模型不可用时这里照常提交。</p>
          </header>

          <form class="form" @submit.prevent="afterSale.submitForm()">
            <label class="field">
              <span class="field__label">原订单</span>
              <select
                class="field__input"
                :value="afterSale.form.orderNo"
                @change="afterSale.selectOrder(($event.target as HTMLSelectElement).value)"
              >
                <option value="">请选择已签收的订单</option>
                <option
                  v-for="order in afterSale.state.orders"
                  :key="order.orderNo"
                  :value="order.orderNo"
                  :disabled="!order.returnable"
                >
                  {{ order.orderNo }} · {{ order.productName }}（可退 {{ order.quota.remaining }}）
                </option>
              </select>
            </label>

            <div class="field">
              <span class="field__label">退货数量</span>
              <div class="stepper" role="group" aria-label="退货数量">
                <button
                  type="button"
                  class="stepper__btn"
                  aria-label="减少一件"
                  :disabled="afterSale.form.quantity <= 1"
                  @click="step(-1)"
                >
                  −
                </button>
                <input
                  class="stepper__input"
                  type="number"
                  min="1"
                  :max="maxQuantity"
                  :value="afterSale.form.quantity"
                  aria-label="退货数量"
                  @input="onQuantityInput"
                />
                <button
                  type="button"
                  class="stepper__btn"
                  aria-label="增加一件"
                  :disabled="afterSale.form.quantity >= maxQuantity"
                  @click="step(1)"
                >
                  +
                </button>
              </div>
              <p v-if="selected" class="field__hint">
                已购 {{ selected.quota.purchased }} 件 · 已占用 {{ selected.quota.occupied }} 件 ·
                剩余可退 {{ selected.quota.remaining }} 件
              </p>
            </div>

            <label class="field field--wide">
              <span class="field__label">退货原因（必填，最多 200 字）</span>
              <textarea
                class="field__input"
                rows="3"
                :value="afterSale.form.reason"
                placeholder="例如：键盘按键失灵"
                @input="onReasonInput"
              />
            </label>

            <div class="form__actions">
              <button
                type="button"
                class="mk-btn"
                :disabled="afterSale.state.previewing || !afterSale.form.orderNo"
                @click="afterSale.previewForm()"
              >
                {{ afterSale.state.previewing ? '试算中…' : '试算退款' }}
              </button>
              <button type="submit" class="mk-btn mk-btn--primary" :disabled="!canSubmit">
                {{ afterSale.state.submitting ? '提交中…' : '提交退货申请' }}
              </button>
            </div>

            <p class="form__note">
              试算只读取额度、不占用额度，也「不是」资格凭证：提交时服务端会重新校验归属、
              签收状态、退货窗口与剩余额度。
            </p>
          </form>

          <div v-if="afterSale.state.preview" class="preview" data-preview>
            <p class="preview__line">
              {{ afterSale.state.preview.productName }} × {{ afterSale.state.preview.requestedQty }}
              · 预计退款 <strong>{{ afterSale.state.preview.refundText }}</strong>
            </p>
            <p class="preview__line preview__line--muted">
              已购 {{ afterSale.state.preview.quota.purchased }} 件 · 已占用
              {{ afterSale.state.preview.quota.occupied }} 件 · 剩余可退
              {{ afterSale.state.preview.quota.remaining }} 件
            </p>
            <p v-if="!afterSale.state.preview.eligible" class="preview__blocked">
              当前不能提交：{{ afterSale.state.preview.message }}
              <span v-if="afterSale.state.preview.code" class="preview__code">
                （{{ afterSale.state.preview.code }}）
              </span>
            </p>
          </div>
        </section>

        <section id="returns" class="section">
          <header class="section__head">
            <h2 class="section__title">我的售后</h2>
            <button type="button" class="link-btn" @click="afterSale.loadReturns()">刷新</button>
          </header>

          <p v-if="afterSale.state.loadingReturns && !afterSale.state.returns.length" class="muted">
            正在读取售后记录…
          </p>
          <p v-else-if="!afterSale.state.returns.length" class="muted">
            还没有退货申请。
          </p>

          <ul v-else class="returns">
            <li
              v-for="item in afterSale.state.returns"
              :key="item.returnNo"
              class="return"
              :data-return-no="item.returnNo"
            >
              <div class="return__main">
                <div class="return__title">
                  <span class="chip chip--ok">{{ item.statusLabel }}</span>
                  <span class="return__no">{{ item.returnNo }}</span>
                  <span class="return__source">{{ item.sourceLabel }}</span>
                </div>
                <p class="return__item">
                  {{ item.productName }} × {{ item.quantity }}
                  <span class="return__sku">{{ item.sku }}</span>
                </p>
                <p class="return__meta">
                  原订单 {{ item.orderNo }} · 预计退款 {{ item.refundText }} · 提交
                  {{ formatDateTime(item.createdAt) }}
                </p>
                <p class="return__reason">原因：{{ item.reason }}</p>
              </div>

              <div class="return__side">
                <button
                  type="button"
                  class="mk-btn mk-btn--sm"
                  @click="afterSale.openReturn(item.returnNo)"
                >
                  详情
                </button>
              </div>
            </li>
          </ul>
        </section>

        <p class="foot-note">
          本轮只做到「已提交、可查」。A1 不修改可售库存、不写退货入库流水；
          客服审核、仓库验收、退货入库与退款都在后续阶段。
        </p>
      </div>
    </div>

    <template #panel>
      <AfterSaleReturnPanel
        v-if="afterSale.state.detail"
        :value="afterSale.state.detail"
        @close="afterSale.closeDetail()"
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

.page {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
}

.page__inner {
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

.orders,
.returns {
  display: grid;
  gap: var(--s-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.order,
.return {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-4);
  padding: var(--s-3) var(--s-4);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-bg-soft);
}

.order__main,
.return__main {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.order__title,
.return__title {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  min-width: 0;
  flex-wrap: wrap;
}

.order__no,
.return__no {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  overflow-wrap: anywhere;
}

.order__item,
.return__item {
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.order__sku,
.return__sku {
  margin-left: var(--s-2);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.order__time,
.return__meta {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.return__source {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.return__reason {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  overflow-wrap: anywhere;
}

.order__side,
.return__side {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  flex: none;
}

.order__quota {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  white-space: nowrap;
}

.form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s-3);
  padding: var(--s-4);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-bg-soft);
}

.field {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.field--wide {
  grid-column: 1 / -1;
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

.field__hint {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.form__actions {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  gap: var(--s-2);
}

.form__note {
  grid-column: 1 / -1;
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  color: var(--c-text-3);
}

.stepper {
  display: inline-flex;
  align-items: center;
  width: fit-content;
  border: 1px solid var(--c-line);
  border-radius: var(--r-sm);
  overflow: hidden;
  background: var(--c-surface);
}

.stepper__btn {
  width: 26px;
  height: 28px;
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

.stepper__input {
  width: 48px;
  height: 28px;
  border: 0;
  background: transparent;
  color: var(--c-text);
  font-size: var(--fs-sm);
  text-align: center;
  font-variant-numeric: tabular-nums;
}

.preview {
  display: grid;
  gap: 3px;
  padding: var(--s-3) var(--s-4);
  border: 1px dashed var(--c-line-strong);
  border-radius: var(--r-md);
}

.preview__line {
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.preview__line--muted {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.preview__blocked {
  font-size: var(--fs-xs);
  color: var(--c-warn);
}

.preview__code {
  font-family: var(--font-mono);
  color: var(--c-text-3);
}

.chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
  border-radius: var(--r-pill);
  font-size: var(--fs-xs);
  white-space: nowrap;
}

.chip--ok {
  background: var(--c-ok-weak);
  color: var(--c-ok);
}

.chip--wait {
  background: var(--c-warn-weak);
  color: var(--c-warn);
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

  .page__inner {
    padding: var(--s-4) var(--s-3) var(--s-10);
  }

  .form {
    grid-template-columns: minmax(0, 1fr);
  }

  .order,
  .return {
    flex-direction: column;
    align-items: stretch;
  }

  .order__side,
  .return__side {
    justify-content: space-between;
  }
}
</style>
