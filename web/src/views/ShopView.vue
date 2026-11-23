<script setup lang="ts">
/**
 * 电商首页（默认体验）：
 * 1. 电商导购助手（可选入口：自然语言选品、比较、加购、结算确认）；
 * 2. 商品筛选与对比（普通用户不聊 Agent 也能完整筛选、对比、加购、结算）；
 * 3. 共享购物车（与 Agent 共用同一购物车服务；单 SKU 结算限制与不锁库存明示）；
 * 4. 结算预览与确认（服务端实时报价，价格变化重新确认，幂等防重）；
 * 5. 我的订单与订单详情抽屉（支持 ?orderNo=... 直达详情，签收后一键去售后）。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import ShopNav from '@/components/ShopNav.vue'
import ShopOrderDetailPanel from '@/components/ShopOrderDetailPanel.vue'
import ShopAssistantPanel from '@/components/ShopAssistantPanel.vue'
import AppIcon from '@/components/AppIcon.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import { session } from '@/stores/session'
import { shop } from '@/stores/shop'
import { createShopAssistantChatStore } from '@/stores/shopAssistantChat'
import {
  createShopConversation,
  getShopConversation,
  prepareCheckoutApproval,
  runShopAssistantChat,
  type ShopOrderStatus,
  type ShopProductVO,
} from '@/api/shop'
import { decideApproval, getApproval, resumeApproval } from '@/api/approvals'
import { formatDateTime } from '@/utils/format'
import type { Tone } from '@/utils/status'

const route = useRoute()
const navOpen = ref(false)

/** 商品卡片数量草稿 */
const quantities = reactive<Record<number, number>>({})

/** 普通页面硬条件 + 偏好筛选表单 */
const filter = reactive({
  keyword: '',
  category: '',
  maxPriceYuan: '',
  wirelessOnly: false,
  macCompatible: false,
  preferSilent: false,
  inStockOnly: false,
})

function assistantStorageKey(): string {
  return `mkagent.shopAssistantConversation.${session.userId.value ?? 'anonymous'}`
}

const assistant = createShopAssistantChatStore({
  chat: runShopAssistantChat,
  conversation: createShopConversation,
  conversationDetail: getShopConversation,
  prepareCheckout: prepareCheckoutApproval,
  storageKey: assistantStorageKey,
  approval: getApproval,
  decide: decideApproval,
  resume: resumeApproval,
  onBusinessChanged: async () => {
    await Promise.all([shop.loadCart(), shop.loadMyOrders(), shop.loadProducts()])
  },
})

const detailOpen = computed(() => shop.state.detail !== null)
const canOrder = computed(() => shop.canOrder.value)
const identityDisabled = computed(
  () => shop.state.session !== null && !shop.canOrder.value,
)

function quantityOf(productId: number): number {
  return quantities[productId] ?? 1
}

function step(productId: number, delta: number): void {
  quantities[productId] = Math.min(99, Math.max(1, quantityOf(productId) + delta))
}

async function onApplyFilter(): Promise<void> {
  const rawMax = String(filter.maxPriceYuan ?? '').trim()
  const maxYuan = rawMax ? Number(rawMax) : null
  await shop.searchProducts({
    keyword: filter.keyword.trim() || null,
    category: filter.category || null,
    maxPriceYuan: maxYuan !== null && Number.isFinite(maxYuan) ? maxYuan : null,
    wirelessOnly: filter.wirelessOnly || null,
    macCompatible: filter.macCompatible || null,
    preferSilent: filter.preferSilent || null,
    inStockOnly: filter.inStockOnly || null,
  })
}

async function onResetFilter(): Promise<void> {
  filter.keyword = ''
  filter.category = ''
  filter.maxPriceYuan = ''
  filter.wirelessOnly = false
  filter.macCompatible = false
  filter.preferSilent = false
  filter.inStockOnly = false
  await shop.clearSearch()
}

async function onAddCardToCart(product: ShopProductVO): Promise<void> {
  const qty = quantityOf(product.id)
  const ok = await shop.addToCart({ productId: product.id, quantity: qty })
  if (ok) {
    quantities[product.id] = 1
  }
}

async function onAgentAddToCart(product: ShopProductVO): Promise<void> {
  await shop.addToCart({ productId: product.id, quantity: 1 })
}

async function onAgentCompare(skus: string[]): Promise<void> {
  shop.state.compareKeys = skus.slice(0, 3)
  await shop.runCompare(skus)
  document.getElementById('compare')?.scrollIntoView({ behavior: 'smooth' })
}

async function onPreviewCheckout(productId: number, quantity: number): Promise<void> {
  await shop.openCheckoutPreview(productId, quantity)
  document.getElementById('checkout')?.scrollIntoView({ behavior: 'smooth' })
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
  shop.reset()
  assistant.reset()
  await Promise.all([shop.loadSession(), shop.loadProducts()])
  await Promise.all([shop.loadCart(), shop.loadMyOrders()])
  await assistant.restore()
}

async function openRouteOrderIfNeeded(): Promise<void> {
  const q = route.query.orderNo
  if (typeof q === 'string' && q.trim() && canOrder.value) {
    await shop.openOrder(q.trim())
  }
}

watch(() => route.query.orderNo, () => {
  void openRouteOrderIfNeeded()
})

onMounted(async () => {
  await Promise.all([shop.loadSession(), shop.loadProducts()])
  await Promise.all([shop.loadCart(), shop.loadMyOrders()])
  await openRouteOrderIfNeeded()
  await assistant.restore()
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
        <h1 class="topbar__title">导购与商城</h1>
        <span class="topbar__demo">演示付款 · 无真实资金交易 · 发货才扣库存</span>

        <div class="topbar__right">
          <span class="topbar__identity" :title="shop.state.session?.userId ?? '未识别'">
            {{ shop.state.session?.userId ?? '未识别身份' }}
          </span>
          <a class="topbar__link" href="#cart">
            购物车（{{ shop.state.cart?.totalQuantity ?? 0 }}）
          </a>
          <RouterLink class="topbar__link" to="/shop/aftersale">我的售后</RouterLink>
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
          @action="shop.clearFeedback(); shop.loadProducts(); shop.loadCart(); shop.loadMyOrders()"
        />

        <p v-if="shop.state.notice" class="banner banner--ok" role="status">
          <AppIcon name="check" :size="14" />
          {{ shop.state.notice }}
        </p>

        <ErrorNotice
          v-if="identityDisabled"
          tone="warn"
          title="当前身份不能加购或下单"
          :detail="
            shop.state.session?.identityMode === 'DISABLED'
              ? '商城身份未启用（服务端配置 mkagent.shop.demo-identity-enabled=false）。商品可以浏览，但加购、下单、发货、签收都被服务端拒绝。'
              : '这个身份没有开通商城（shop_user 表里没有它）。可在左下角切换为 shop-user-1。'
          "
        />

        <!-- 1. 导购助手（可选入口，帮助选品与自然语言下单） -->
        <ShopAssistantPanel
          :chat="assistant"
          :can-order="canOrder"
          @add-to-cart="onAgentAddToCart"
          @compare-skus="onAgentCompare"
          @preview-checkout="onPreviewCheckout"
          @open-order="shop.openOrder"
        />

        <!-- 2. 商品筛选、卡片与对比（普通用户无需聊 Agent 即可完成） -->
        <section id="products" class="section">
          <header class="section__head">
            <h2 class="section__title">商品筛选与选购</h2>
            <p class="section__hint">
              硬条件由商品服务严格过滤；无匹配时不展示不合规商品。缺失规格标注为「未知」。
            </p>
          </header>

          <form class="filter-bar" @submit.prevent="onApplyFilter">
            <input
              v-model="filter.keyword"
              class="filter-input"
              type="text"
              placeholder="关键词（型号/轴体）"
            />
            <select v-model="filter.category" class="filter-select" aria-label="商品品类">
              <option value="">全部品类</option>
              <option value="KEYBOARD">键盘</option>
              <option value="MOUSE">鼠标</option>
              <option value="ACCESSORY">配件</option>
            </select>
            <input
              v-model="filter.maxPriceYuan"
              class="filter-input filter-input--num"
              type="number"
              min="1"
              placeholder="预算上限（元）"
            />
            <label class="filter-check">
              <input v-model="filter.wirelessOnly" type="checkbox" />
              仅无线
            </label>
            <label class="filter-check">
              <input v-model="filter.macCompatible" type="checkbox" />
              兼容 Mac
            </label>
            <label class="filter-check">
              <input v-model="filter.preferSilent" type="checkbox" />
              偏好静音
            </label>
            <label class="filter-check">
              <input v-model="filter.inStockOnly" type="checkbox" />
              仅看有货
            </label>
            <button type="submit" class="mk-btn mk-btn--sm mk-btn--primary">筛选</button>
            <button type="button" class="mk-btn mk-btn--sm" @click="onResetFilter">重置</button>
          </form>

          <!-- 对比选择条 -->
          <div v-if="shop.state.compareKeys.length" class="compare-bar">
            <span>已选对比（{{ shop.state.compareKeys.length }}/3）：{{ shop.state.compareKeys.join(' / ') }}</span>
            <button
              type="button"
              class="mk-btn mk-btn--sm mk-btn--primary"
              :disabled="shop.state.compareKeys.length < 2"
              @click="shop.runCompare()"
            >
              开始对比
            </button>
            <button type="button" class="link-btn" @click="shop.clearCompare()">清空</button>
          </div>

          <!-- 无匹配反馈与放宽建议 -->
          <div
            v-if="shop.state.searchResult && !shop.state.products.length"
            class="zero-banner"
            data-zero-match
          >
            <p class="zero-banner__title">没有找到符合全部硬条件的商品（不以不合规商品充数）。</p>
            <ul v-if="shop.state.searchResult.relaxSuggestions.length" class="zero-banner__tips">
              <li v-for="(tip, idx) in shop.state.searchResult.relaxSuggestions" :key="idx">
                {{ tip }}
              </li>
            </ul>
            <button type="button" class="mk-btn mk-btn--sm" @click="onResetFilter">查看全部商品</button>
          </div>

          <p v-else-if="shop.state.loadingProducts && !shop.state.products.length" class="muted">
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

              <div class="card__specs">
                <span class="spec-chip">{{ product.categoryLabel ?? '外设' }}</span>
                <span class="spec-chip">{{ product.connectivityLabel ?? '未知' }}</span>
                <span class="spec-chip">Mac：{{ product.macCompatibleLabel ?? '未知' }}</span>
                <span class="spec-chip">静音：{{ product.silentLabel ?? '未知' }}</span>
                <span v-if="product.switchType && product.switchType !== '未知'" class="spec-chip">
                  轴体：{{ product.switchType }}
                </span>
              </div>

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
                <div class="stepper" role="group" :aria-label="`${product.name} 数量`">
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
                  class="mk-btn mk-btn--sm"
                  :disabled="!canOrder || !product.purchasable"
                  data-add-cart
                  @click="onAddCardToCart(product)"
                >
                  加入购物车
                </button>
                <button
                  type="button"
                  class="mk-btn mk-btn--sm mk-btn--primary"
                  :disabled="!canOrder || !product.purchasable"
                  data-preview-buy
                  @click="onPreviewCheckout(product.id, quantityOf(product.id))"
                >
                  结算预览
                </button>
                <button
                  type="button"
                  class="mk-btn mk-btn--sm mk-btn--ghost"
                  @click="shop.toggleCompare(product.sku)"
                >
                  {{ shop.state.compareKeys.includes(product.sku) ? '取消对比' : '对比' }}
                </button>
              </div>
            </article>
          </div>

          <!-- 并排对比表 -->
          <div v-if="shop.state.comparison" id="compare" class="compare-panel" data-compare-panel>
            <div class="section__head">
              <h3 class="compare-panel__title">规格对比</h3>
              <button type="button" class="link-btn" @click="shop.clearCompare()">关闭对比</button>
            </div>
            <div class="table-scroll">
              <table class="compare-table">
                <thead>
                  <tr>
                    <th>规格项</th>
                    <th v-for="p in shop.state.comparison.products" :key="p.sku">
                      {{ p.name }}
                      <div class="mono compare-sku">{{ p.sku }}</div>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="dim in shop.state.comparison.dimensions" :key="dim.label">
                    <td class="compare-dim">{{ dim.label }}</td>
                    <td v-for="(val, idx) in dim.values" :key="idx">{{ val }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </section>

        <!-- 3. 共享购物车 -->
        <section id="cart" class="section" data-shared-cart>
          <header class="section__head">
            <h2 class="section__title">共享购物车</h2>
            <p class="section__hint">
              与导购助手共享同一购物车。加购不等于下单，也不锁定库存。
            </p>
            <button type="button" class="link-btn" @click="shop.loadCart()">刷新</button>
          </header>

          <p class="limit-note">
            {{ shop.state.cart?.singleSkuNotice ?? '当前订单模型只支持单 SKU 订单，请从购物车选择一款商品单独结算预览。' }}
          </p>

          <p v-if="!canOrder" class="muted">身份不可用时无法读取购物车。</p>
          <p v-else-if="shop.state.loadingCart && !shop.state.cart" class="muted">正在读取购物车…</p>
          <p v-else-if="!shop.state.cart || !shop.state.cart.items.length" class="muted">
            购物车为空。在上方商品卡点「加入购物车」，或让导购助手帮你加购。
          </p>

          <ul v-else class="cart-list">
            <li
              v-for="item in shop.state.cart.items"
              :key="item.productId"
              class="cart-row"
              :data-cart-sku="item.sku"
            >
              <div class="cart-row__main">
                <p class="cart-row__name">
                  <strong>{{ item.productName }}</strong>
                  <span class="mono cart-row__sku">{{ item.sku }}</span>
                </p>
                <p class="cart-row__meta">
                  当前单价 {{ item.currentUnitPriceText }} · 小计 {{ item.lineText }} ·
                  可售 {{ item.available }} 件
                  <span v-if="!item.active" class="tag-warn">已下架</span>
                </p>
              </div>
              <div class="cart-row__actions">
                <div class="stepper">
                  <button
                    type="button"
                    class="stepper__btn"
                    :disabled="item.quantity <= 1"
                    @click="shop.updateCartQty(item.productId, item.quantity - 1)"
                  >
                    −
                  </button>
                  <span class="stepper__value">{{ item.quantity }}</span>
                  <button
                    type="button"
                    class="stepper__btn"
                    :disabled="item.quantity >= 99"
                    @click="shop.updateCartQty(item.productId, item.quantity + 1)"
                  >
                    +
                  </button>
                </div>
                <button
                  type="button"
                  class="mk-btn mk-btn--sm mk-btn--primary"
                  :disabled="!item.checkoutReady"
                  data-cart-checkout
                  @click="onPreviewCheckout(item.productId, item.quantity)"
                >
                  结算预览
                </button>
                <button
                  type="button"
                  class="mk-btn mk-btn--sm"
                  @click="shop.removeFromCart(item.productId)"
                >
                  移除
                </button>
              </div>
            </li>
          </ul>
        </section>

        <!-- 4. 结算预览与确认下单（普通页面路径） -->
        <section
          v-if="shop.state.checkoutPreview"
          id="checkout"
          class="checkout-box"
          data-checkout-preview
        >
          <header class="section__head">
            <h2 class="section__title">结算预览与确认</h2>
            <button type="button" class="link-btn" @click="shop.closeCheckoutPreview()">取消</button>
          </header>

          <p v-if="shop.state.checkoutPriceChanged" class="banner banner--warn" data-price-changed>
            <AppIcon name="alert" :size="14" />
            价格已更新：旧报价未建单。请核对下方最新报价后再次点击「确认提交订单」。
          </p>

          <dl class="checkout-facts">
            <div>
              <dt>商品</dt>
              <dd>{{ shop.state.checkoutPreview.productName }}（<span class="mono">{{ shop.state.checkoutPreview.sku }}</span>）</dd>
            </div>
            <div>
              <dt>数量</dt>
              <dd>{{ shop.state.checkoutPreview.quantity }} 件</dd>
            </div>
            <div>
              <dt>当前单价</dt>
              <dd>{{ shop.state.checkoutPreview.unitPriceText }}</dd>
            </div>
            <div>
              <dt>应付总额（演示付款）</dt>
              <dd class="checkout-total">{{ shop.state.checkoutPreview.totalText }}</dd>
            </div>
          </dl>

          <p class="checkout-notice">
            {{ shop.state.checkoutPreview.singleSkuNotice }} {{ shop.state.checkoutPreview.stockNotice }}
          </p>

          <p v-if="!shop.state.checkoutPreview.eligible" class="checkout-blocked">
            {{ shop.state.checkoutPreview.message }}
          </p>

          <div class="checkout-actions">
            <button
              type="button"
              class="mk-btn mk-btn--primary"
              data-confirm-checkout
              :disabled="!shop.state.checkoutPreview.eligible || shop.state.checkoutBusy"
              @click="shop.confirmCurrentCheckout(true)"
            >
              {{ shop.state.checkoutBusy ? '正在校验并提交…' : '确认提交演示订单' }}
            </button>
            <button
              type="button"
              class="mk-btn"
              :disabled="shop.state.checkoutBusy"
              @click="shop.closeCheckoutPreview()"
            >
              返回修改
            </button>
          </div>
        </section>

        <!-- 5. 我的订单 -->
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
            还没有订单。你可以直接筛选商品并结算预览，或在顶部用导购助手选品下单。
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
                  data-open-order-detail
                  @click="shop.openOrder(order.orderNo)"
                >
                  详情
                </button>
                <RouterLink
                  v-if="order.status === 'SIGNED'"
                  class="mk-btn mk-btn--sm"
                  :to="{ path: '/shop/aftersale', query: { orderNo: order.orderNo } }"
                >
                  申请退货
                </RouterLink>
              </div>
            </li>
          </ul>
        </section>

        <p class="foot-note">
          MkAgent 电商一期：选品比较 → 共享购物车 → 单 SKU 结算确认 → 订单详情 → 售后退货闭环。
          演示付款无真实资金交易，沿用「发货才扣库存」规则。
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
  width: 100%;
  min-width: 0;
}

.topbar__title {
  font-size: var(--fs-md);
  font-weight: 500;
  color: var(--c-text);
}

.topbar__demo {
  padding: 2px 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--r-pill);
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.topbar__right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--s-3);
  min-width: 0;
}

.topbar__identity {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--c-text-2);
}

.topbar__link {
  font-size: var(--fs-xs);
  color: var(--c-accent-ink);
  text-decoration: none;
}

.topbar__link:hover {
  text-decoration: underline;
}

.shop {
  height: 100%;
  overflow-y: auto;
  padding: var(--s-5) var(--s-4) var(--s-8);
}

.shop__inner {
  max-width: 1040px;
  margin: 0 auto;
  display: grid;
  gap: var(--s-6);
}

.banner {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  padding: 10px var(--s-3);
  border-radius: var(--r-md);
  font-size: var(--fs-sm);
}

.banner--ok {
  border: 1px solid rgba(118, 214, 178, 0.3);
  background: rgba(118, 214, 178, 0.08);
  color: var(--c-ok);
}

.banner--warn {
  border: 1px solid rgba(224, 177, 95, 0.35);
  background: var(--c-warn-weak);
  color: var(--c-warn);
}

.section {
  display: grid;
  gap: var(--s-3);
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

.link-btn {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--c-accent-ink);
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-2);
  padding: var(--s-3);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-bg-soft);
}

.filter-input,
.filter-select {
  padding: 6px 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--fs-xs);
}

.filter-input--num {
  width: 120px;
}

.filter-check {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-xs);
  color: var(--c-text-2);
}

.compare-bar {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
  padding: 8px var(--s-3);
  border: 1px solid var(--c-accent-line);
  border-radius: var(--r-sm);
  background: var(--c-accent-weak);
  font-size: var(--fs-xs);
  color: var(--c-text);
}

.zero-banner {
  display: grid;
  gap: var(--s-2);
  padding: var(--s-4);
  border: 1px solid rgba(224, 177, 95, 0.35);
  border-radius: var(--r-md);
  background: var(--c-warn-weak);
}

.zero-banner__title {
  font-size: var(--fs-sm);
  color: var(--c-warn);
  font-weight: 500;
}

.zero-banner__tips {
  margin: 0;
  padding-left: 18px;
  font-size: var(--fs-xs);
  color: var(--c-text-2);
}

.products {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(290px, 1fr));
  gap: var(--s-3);
}

.card {
  display: grid;
  grid-template-rows: auto auto auto auto auto;
  gap: var(--s-2);
  padding: var(--s-4);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-surface);
}

.card__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-2);
}

.card__name {
  font-size: var(--fs-base);
  font-weight: 500;
  color: var(--c-text);
}

.card__price {
  font-size: var(--fs-md);
  font-weight: 600;
  color: var(--c-accent-ink);
  font-variant-numeric: tabular-nums;
}

.card__sub {
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  color: var(--c-text-3);
}

.card__specs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.spec-chip {
  padding: 2px 7px;
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-pill);
  font-size: 11px;
  color: var(--c-text-2);
  background: var(--c-bg-soft);
}

.card__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2);
  padding-top: var(--s-1);
  border-top: 1px solid var(--c-line-soft);
  font-size: var(--fs-xs);
}

.card__sku {
  font-family: var(--font-mono);
  color: var(--c-text-3);
}

.card__stock {
  color: var(--c-ok);
  font-variant-numeric: tabular-nums;
}

.card__stock--out {
  color: var(--c-warn);
}

.card__actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding-top: var(--s-1);
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
  height: 28px;
  color: var(--c-text-2);
  background: var(--c-bg-soft);
}

.stepper__btn:hover:not(:disabled) {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.stepper__value {
  min-width: 28px;
  text-align: center;
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
  color: var(--c-text);
}

.compare-panel {
  display: grid;
  gap: var(--s-2);
  padding: var(--s-4);
  border: 1px solid var(--c-accent-line);
  border-radius: var(--r-md);
  background: var(--c-bg-soft);
}

.compare-panel__title {
  font-size: var(--fs-base);
  font-weight: 500;
  color: var(--c-text);
}

.table-scroll {
  overflow-x: auto;
}

.compare-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-xs);
}

.compare-table th,
.compare-table td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--c-line-soft);
  text-align: left;
  color: var(--c-text);
}

.compare-dim {
  color: var(--c-text-3);
  white-space: nowrap;
}

.compare-sku {
  font-size: 10.5px;
  font-weight: 400;
  color: var(--c-text-3);
}

.limit-note {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.cart-list {
  display: grid;
  gap: var(--s-2);
  list-style: none;
  margin: 0;
  padding: 0;
}

.cart-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  flex-wrap: wrap;
  padding: var(--s-3) var(--s-4);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-surface);
}

.cart-row__name {
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.cart-row__sku {
  margin-left: 6px;
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.cart-row__meta {
  margin-top: 2px;
  font-size: var(--fs-xs);
  color: var(--c-text-2);
}

.tag-warn {
  margin-left: 6px;
  color: var(--c-danger);
}

.cart-row__actions {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
}

.checkout-box {
  display: grid;
  gap: var(--s-3);
  padding: var(--s-4);
  border: 1px solid var(--c-accent-line);
  border-radius: var(--r-md);
  background: var(--c-bg-soft);
}

.checkout-facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--s-3);
  margin: 0;
}

.checkout-facts dt {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.checkout-facts dd {
  margin: 2px 0 0;
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.checkout-total {
  font-size: var(--fs-md);
  font-weight: 600;
  color: var(--c-accent-ink);
}

.checkout-notice {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.checkout-blocked {
  font-size: var(--fs-sm);
  color: var(--c-danger);
}

.checkout-actions {
  display: flex;
  gap: var(--s-2);
  flex-wrap: wrap;
}

.orders {
  display: grid;
  gap: var(--s-2);
  list-style: none;
  margin: 0;
  padding: 0;
}

.order {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  flex-wrap: wrap;
  padding: var(--s-3) var(--s-4);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-surface);
}

.order__main {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.order__title {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
}

.order__no {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--c-text-2);
}

.order__item {
  font-size: var(--fs-sm);
  color: var(--c-text);
}

.order__sku {
  margin-left: 6px;
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
  gap: var(--s-2);
}

.order__amount {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.chip {
  padding: 2px 8px;
  border-radius: var(--r-pill);
  font-size: 11px;
  border: 1px solid var(--c-line);
}

.chip--wait {
  border-color: rgba(224, 177, 95, 0.35);
  background: var(--c-warn-weak);
  color: var(--c-warn);
}

.chip--active {
  border-color: var(--c-accent-line);
  background: var(--c-accent-weak);
  color: var(--c-accent-ink);
}

.chip--ok {
  border-color: rgba(118, 214, 178, 0.35);
  background: rgba(118, 214, 178, 0.12);
  color: var(--c-ok);
}

.muted {
  font-size: var(--fs-sm);
  color: var(--c-text-3);
}

.foot-note {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.mono {
  font-family: var(--font-mono);
}
</style>
