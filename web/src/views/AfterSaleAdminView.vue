<script setup lang="ts">
/**
 * 客服售后处理（B1 决策 + B2 收货验货 + B3 模拟退款）。
 *
 * 五点必须说清楚：
 * 1. 权限由服务端判定（`requireAdmin`）。页面隐藏按钮只是不让人白点一次，
 *    真正拦住越权的是后端 403；普通买家打开这个页面只会看到"当前身份不能处理"；
 * 2. 批准 = **保留额度**（那部分数量仍然不能再申请）；驳回 = **释放额度**
 *    （同一事务内把 reserved_qty 减回去）；
 * 3. 收货 = **只有已批准的单能收**，只有合格数量回补可售库存。
 *    收货**不释放**额度：收进来的件数已经把这份退货资格用掉了；
 * 4. 模拟退款 = **只有已批准且全部实收**才能做，金额由服务端按
 *    「原订单成交单价 × 累计实收」算（含损坏件），一张单最多一笔。
 *    它**不动库存、不写流水、不释放额度**，也不接支付平台——所以文案只说
 *    "模拟退款"，绝不说"已到账"；
 * 5. 重复或相反决策、超收、重复退款、状态已变都会被后端 409 拒绝，
 *    页面不重试、不猜测，而是重新读详情把真实事实摆出来。
 */
import { computed, onMounted, ref } from 'vue'
import AppShell from '@/components/AppShell.vue'
import ShopNav from '@/components/ShopNav.vue'
import AfterSaleAdminPanel from '@/components/AfterSaleAdminPanel.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import AppIcon from '@/components/AppIcon.vue'
import { session } from '@/stores/session'
import { shop } from '@/stores/shop'
import { afterSaleAdmin } from '@/stores/aftersaleAdmin'
import { afterSaleStatusTone, newClientRequestId } from '@/stores/aftersale'
import type { AfterSaleReturnVO } from '@/api/aftersale'
import { formatDateTime } from '@/utils/format'

const navOpen = ref(false)

const TABS: { value: string | null; label: string }[] = [
  { value: null, label: '全部' },
  { value: 'SUBMITTED', label: '待处理' },
  { value: 'APPROVED', label: '已批准' },
  { value: 'REJECTED', label: '已驳回' },
]

/** 正在填写驳回原因的那一单（null 表示没有）。 */
const rejectingNo = ref<string | null>(null)
const rejectReason = ref('')

/** 正在填写收货结果的那一单（null 表示没有）。 */
const receivingNo = ref<string | null>(null)
const receiveReceived = ref(1)
const receiveQualified = ref(1)
const receiveDamaged = ref(0)
const receiveNote = ref('')
/**
 * 这一批收货的标识：打开面板时生成一次，提交期间不变。
 * 同一次点击的重试会命中服务端的幂等键（不重复加库存）；
 * 改完数量再提交则是另一批，必须换新值——所以成功后才重置。
 */
const receiveRequestId = ref('')

/** 正在确认模拟退款的那一单（null 表示没有）。 */
const refundingNo = ref<string | null>(null)

/** 能不能对这一单发起模拟退款：已批准 + 全部实收 + 还没退过。 */
function canRefund(item: AfterSaleReturnVO): boolean {
  return item.status === 'APPROVED' && item.progress?.code === 'COMPLETE' && !item.refund
}

const detailOpen = computed(() => afterSaleAdmin.state.detail !== null)

const canOperate = computed(() => shop.canOperate.value)

const identityDisabled = computed(
  () => shop.state.session !== null && shop.state.session.identityMode === 'DISABLED',
)

/** 当前打开的那一单（用来显示"批准 / 累计 / 本次上限"）。 */
const receivingItem = computed<AfterSaleReturnVO | null>(
  () => afterSaleAdmin.state.returns.find((item) => item.returnNo === receivingNo.value) ?? null,
)

/** 本次最多能收几件（批准数量 - 累计实收）。 */
const receiveLimit = computed(() => receivingItem.value?.progress?.remainingQty ?? 0)

/** 前端只挡"明显不合法"的输入；真正的判定在服务端事务里。 */
const receiveInvalid = computed(
  () =>
    !Number.isInteger(receiveReceived.value) ||
    receiveReceived.value <= 0 ||
    receiveReceived.value > receiveLimit.value ||
    receiveQualified.value < 0 ||
    receiveDamaged.value < 0 ||
    receiveQualified.value + receiveDamaged.value !== receiveReceived.value,
)

async function loadAll(): Promise<void> {
  await shop.loadSession()
  if (!shop.canOperate.value) {
    return
  }
  await afterSaleAdmin.loadList()
}

async function onSwitchIdentity(userId: string): Promise<void> {
  if (!session.updateUserId(userId)) {
    return
  }
  shop.reset()
  afterSaleAdmin.reset()
  await loadAll()
}

function startReject(returnNo: string): void {
  rejectingNo.value = returnNo
  rejectReason.value = ''
}

function cancelReject(): void {
  rejectingNo.value = null
  rejectReason.value = ''
}

async function confirmReject(returnNo: string): Promise<void> {
  const reason = rejectReason.value.trim()
  if (!reason) {
    return
  }
  const updated = await afterSaleAdmin.reject(returnNo, reason)
  if (updated) {
    cancelReject()
  }
}

async function onApprove(returnNo: string): Promise<void> {
  await afterSaleAdmin.approve(returnNo)
}

function startReceive(item: AfterSaleReturnVO): void {
  receivingNo.value = item.returnNo
  const limit = Math.max(1, item.progress?.remainingQty ?? item.quantity)
  receiveReceived.value = limit
  receiveQualified.value = limit
  receiveDamaged.value = 0
  receiveNote.value = ''
  receiveRequestId.value = newClientRequestId()
}

function cancelReceive(): void {
  receivingNo.value = null
  receiveNote.value = ''
  receiveRequestId.value = ''
}

/**
 * 三个数量联动，保证"合格 + 损坏 = 实收"恒成立。
 *
 * 规则：最后编辑的那个字段说话，另外两个跟着算。
 * 不做"提交时再校验"，是因为操作员需要一个立刻能看懂的数字。
 */
function onReceivedChange(): void {
  const received = clampReceive(receiveReceived.value)
  receiveReceived.value = received
  receiveQualified.value = Math.min(Math.max(receiveQualified.value, 0), received)
  receiveDamaged.value = received - receiveQualified.value
}

function onQualifiedChange(): void {
  const received = clampReceive(receiveReceived.value)
  receiveQualified.value = Math.min(Math.max(receiveQualified.value, 0), received)
  receiveDamaged.value = received - receiveQualified.value
}

function onDamagedChange(): void {
  const received = clampReceive(receiveReceived.value)
  receiveDamaged.value = Math.min(Math.max(receiveDamaged.value, 0), received)
  receiveQualified.value = received - receiveDamaged.value
}

function clampReceive(value: number): number {
  if (!Number.isFinite(value)) {
    return 1
  }
  return Math.min(Math.max(Math.trunc(value), 1), Math.max(1, receiveLimit.value))
}

async function confirmReceive(returnNo: string): Promise<void> {
  if (receiveInvalid.value) {
    return
  }
  const updated = await afterSaleAdmin.receive(returnNo, {
    receivedQty: receiveReceived.value,
    qualifiedQty: receiveQualified.value,
    damagedQty: receiveDamaged.value,
    note: receiveNote.value,
    clientRequestId: receiveRequestId.value,
  })
  if (updated) {
    cancelReceive()
  }
}

function startRefund(returnNo: string): void {
  refundingNo.value = returnNo
}

function cancelRefund(): void {
  refundingNo.value = null
}

async function confirmRefund(returnNo: string): Promise<void> {
  const updated = await afterSaleAdmin.refund(returnNo)
  if (updated) {
    cancelRefund()
  }
}

onMounted(async () => {
  /*
   * 进入页面先关掉上一次留下的详情抽屉。
   *
   * store 是模块级单例，跨路由不销毁：从别的页面回来时 `state.detail` 还挂着
   * 上一单的内容，抽屉会盖住列表右半边（收货 / 详情按钮正好在那儿）。
   * 关掉它比让用户先找关闭按钮更合理。
   */
  afterSaleAdmin.closeDetail()
  await loadAll()
})
</script>

<template>
  <AppShell
    :panel-open="detailOpen"
    :nav-open="navOpen"
    @toggle-nav="navOpen = !navOpen"
    @close-overlays="navOpen = false; afterSaleAdmin.closeDetail()"
  >
    <template #nav>
      <ShopNav @switch-identity="onSwitchIdentity" />
    </template>

    <template #topbar>
      <div class="topbar">
        <h1 class="topbar__title">售后处理</h1>
        <span class="topbar__demo">B3 · 批准 / 驳回 / 收货 / 模拟退款</span>

        <div class="topbar__right">
          <span class="topbar__identity">{{ shop.state.session?.userId ?? '未识别身份' }}</span>
          <RouterLink class="topbar__link" to="/shop/admin">订单后台</RouterLink>
          <RouterLink class="topbar__link" to="/shop/aftersale">买家售后页</RouterLink>
        </div>
      </div>
    </template>

    <div class="admin">
      <div class="admin__inner">
        <ErrorNotice
          v-if="afterSaleAdmin.state.error"
          tone="danger"
          title="操作没有完成"
          :detail="afterSaleAdmin.state.error"
          action-label="刷新列表"
          @action="afterSaleAdmin.clearFeedback(); afterSaleAdmin.loadList()"
        />

        <p v-if="afterSaleAdmin.state.notice" class="banner banner--ok" role="status">
          <AppIcon name="check" :size="14" />
          {{ afterSaleAdmin.state.notice }}
        </p>

        <ErrorNotice
          v-if="!canOperate"
          tone="warn"
          title="当前身份不能处理售后"
          :detail="
            identityDisabled
              ? '商城身份未启用（服务端配置 mkagent.shop.demo-identity-enabled=false），后台私有读写一律被拒绝。'
              : '这个身份不是商城后台人员。可在左下角把联调身份切换为 music-admin-1。'
          "
        />

        <template v-else>
          <section class="section">
            <header class="section__head">
              <h2 class="section__title">退货申请</h2>
              <div class="tabs" role="tablist" aria-label="按状态筛选">
                <button
                  v-for="tab in TABS"
                  :key="tab.label"
                  type="button"
                  role="tab"
                  class="tabs__item"
                  :class="{ 'tabs__item--on': afterSaleAdmin.state.status === tab.value }"
                  :aria-selected="afterSaleAdmin.state.status === tab.value"
                  @click="afterSaleAdmin.loadList(tab.value)"
                >
                  {{ tab.label }}
                </button>
              </div>
              <button type="button" class="link-btn" @click="loadAll()">刷新</button>
            </header>

            <p class="section__hint">
              批准保留原额度，驳回释放本单申请数量。两种操作都不改可售库存、不写退货入库流水。
              已批准的单可以收货：只有合格数量回补可售库存，损坏只留记录；收货不释放额度。
              收满之后可以生成一笔<strong>模拟退款</strong>：金额按「原订单成交单价 × 累计实收」算
              （损坏件也算），一张单最多一笔；退款不动库存、不释放额度，也不接支付平台。
            </p>

            <p
              v-if="afterSaleAdmin.state.loadingList && !afterSaleAdmin.state.returns.length"
              class="muted"
            >
              正在读取退货申请…
            </p>
            <p v-else-if="!afterSaleAdmin.state.returns.length" class="muted">
              该状态下没有退货申请。买家在「售后」页提交后会出现在这里。
            </p>

            <ul v-else class="rows">
              <li
                v-for="item in afterSaleAdmin.state.returns"
                :key="item.returnNo"
                class="row"
                :data-return-no="item.returnNo"
              >
                <div class="row__grid">
                  <div class="row__cell row__cell--no">
                    <button
                      type="button"
                      class="row__no"
                      @click="afterSaleAdmin.openDetail(item.returnNo)"
                    >
                      {{ item.returnNo }}
                    </button>
                    <span class="row__time">{{ formatDateTime(item.createdAt) }}</span>
                  </div>

                  <div class="row__cell">
                    <p class="row__product">{{ item.productName }} × {{ item.quantity }}</p>
                    <p class="row__sku">{{ item.sku }} · 原订单 {{ item.orderNo }}</p>
                  </div>

                  <div class="row__cell row__cell--buyer">
                    <p class="row__buyer">{{ item.buyerId }}</p>
                    <p class="row__amount">{{ item.refundText }}</p>
                  </div>

                  <div class="row__cell row__cell--status">
                    <span class="chip" :class="`chip--${afterSaleStatusTone(item.status)}`">
                      {{ item.statusLabel }}
                    </span>
                    <span
                      v-if="item.status === 'APPROVED' && item.progress"
                      class="row__progress"
                      :data-receipt-progress="item.returnNo"
                    >
                      {{ item.progress.label }} {{ item.progress.receivedQty }}/{{
                        item.progress.approvedQty
                      }}
                    </span>
                  </div>
                </div>

                <div class="row__actions">
                  <!-- F-05：已决策单不再展示再次批准/驳回按钮；后端状态机 409 校验继续保留 -->
                  <template v-if="item.status === 'SUBMITTED'">
                    <button
                      type="button"
                      class="mk-btn mk-btn--sm mk-btn--primary"
                      data-action="approve"
                      :disabled="afterSaleAdmin.state.busyReturnNo !== null"
                      @click="onApprove(item.returnNo)"
                    >
                      {{
                        afterSaleAdmin.state.busyReturnNo === item.returnNo &&
                        afterSaleAdmin.state.busyAction === 'approve'
                          ? '处理中…'
                          : '批准'
                      }}
                    </button>
                    <button
                      type="button"
                      class="mk-btn mk-btn--sm"
                      data-action="reject"
                      :disabled="afterSaleAdmin.state.busyReturnNo !== null"
                      @click="startReject(item.returnNo)"
                    >
                      驳回
                    </button>
                  </template>
                  <button
                    v-if="item.status === 'APPROVED'"
                    type="button"
                    class="mk-btn mk-btn--sm mk-btn--primary"
                    data-action="receive"
                    :disabled="
                      (item.progress?.remainingQty ?? 0) <= 0 ||
                      afterSaleAdmin.state.busyReturnNo !== null
                    "
                    @click="startReceive(item)"
                  >
                    {{ (item.progress?.remainingQty ?? 0) <= 0 ? '已收满' : '收货' }}
                  </button>
                  <button
                    v-if="canRefund(item)"
                    type="button"
                    class="mk-btn mk-btn--sm mk-btn--primary"
                    data-action="refund"
                    :disabled="afterSaleAdmin.state.busyReturnNo !== null"
                    @click="startRefund(item.returnNo)"
                  >
                    {{
                      afterSaleAdmin.state.busyReturnNo === item.returnNo &&
                      afterSaleAdmin.state.busyAction === 'refund'
                        ? '处理中…'
                        : '模拟退款'
                    }}
                  </button>
                  <span
                    v-else-if="item.refund"
                    class="row__refund"
                    :data-refund-for="item.returnNo"
                  >
                    {{ item.refund.label }} {{ item.refund.amountText }}
                  </span>
                  <button
                    type="button"
                    class="link-btn"
                    @click="afterSaleAdmin.openDetail(item.returnNo)"
                  >
                    详情
                  </button>
                </div>

                <div v-if="rejectingNo === item.returnNo" class="reject" :data-reject-for="item.returnNo">
                  <label class="field">
                    <span class="field__label">驳回原因（必填，最多 200 字）</span>
                    <textarea
                      v-model="rejectReason"
                      class="field__input"
                      rows="2"
                      data-reject-input
                      placeholder="例如：超过 7 天无理由期限，且商品已使用"
                    />
                  </label>
                  <div class="reject__actions">
                    <button
                      type="button"
                      class="mk-btn mk-btn--sm mk-btn--primary"
                      data-action="reject-confirm"
                      :disabled="!rejectReason.trim() || afterSaleAdmin.state.busyReturnNo !== null"
                      @click="confirmReject(item.returnNo)"
                    >
                      {{
                        afterSaleAdmin.state.busyReturnNo === item.returnNo &&
                        afterSaleAdmin.state.busyAction === 'reject'
                          ? '处理中…'
                          : '确认驳回'
                      }}
                    </button>
                    <button type="button" class="mk-btn mk-btn--sm" @click="cancelReject()">
                      取消
                    </button>
                    <span class="reject__hint">驳回后额度立即释放，买家在「我的售后」能看到原因。</span>
                  </div>
                </div>

                <div
                  v-if="receivingNo === item.returnNo"
                  class="receive"
                  :data-receive-for="item.returnNo"
                >
                  <p class="receive__meta">
                    批准 <strong>{{ item.quantity }}</strong> 件 · 已收
                    <strong>{{ item.progress?.receivedQty ?? 0 }}</strong> 件 · 本批最多可收
                    <strong data-receive-limit>{{ receiveLimit }}</strong> 件
                  </p>

                  <div class="receive__grid">
                    <label class="field">
                      <span class="field__label">实收数量</span>
                      <input
                        v-model.number="receiveReceived"
                        class="field__input"
                        type="number"
                        min="1"
                        :max="receiveLimit"
                        data-receive-input="received"
                        @input="onReceivedChange"
                      />
                    </label>
                    <label class="field">
                      <span class="field__label">合格（回补可售）</span>
                      <input
                        v-model.number="receiveQualified"
                        class="field__input"
                        type="number"
                        min="0"
                        :max="receiveReceived"
                        data-receive-input="qualified"
                        @input="onQualifiedChange"
                      />
                    </label>
                    <label class="field">
                      <span class="field__label">损坏（只留记录）</span>
                      <input
                        v-model.number="receiveDamaged"
                        class="field__input"
                        type="number"
                        min="0"
                        :max="receiveReceived"
                        data-receive-input="damaged"
                        @input="onDamagedChange"
                      />
                    </label>
                  </div>

                  <label class="field">
                    <span class="field__label">备注（选填，最多 200 字）</span>
                    <input
                      v-model="receiveNote"
                      class="field__input"
                      type="text"
                      maxlength="200"
                      data-receive-input="note"
                      placeholder="例如：外箱压瘪，内件完好"
                    />
                  </label>

                  <div class="receive__actions">
                    <button
                      type="button"
                      class="mk-btn mk-btn--sm mk-btn--primary"
                      data-action="receive-confirm"
                      :disabled="receiveInvalid || afterSaleAdmin.state.busyReturnNo !== null"
                      @click="confirmReceive(item.returnNo)"
                    >
                      {{
                        afterSaleAdmin.state.busyReturnNo === item.returnNo &&
                        afterSaleAdmin.state.busyAction === 'receive'
                          ? '入库中…'
                          : '确认收货'
                      }}
                    </button>
                    <button type="button" class="mk-btn mk-btn--sm" @click="cancelReceive()">
                      取消
                    </button>
                    <span class="receive__hint">
                      合格 {{ receiveQualified }} 件回补可售库存；损坏
                      {{ receiveDamaged }} 件只留记录、不建残次库存；额度不释放。
                    </span>
                  </div>

                  <p v-if="receiveInvalid" class="receive__error" data-receive-error>
                    实收要大于 0 且不超过本批上限 {{ receiveLimit }} 件，并且「合格 + 损坏 = 实收」。
                  </p>
                </div>

                <div
                  v-if="refundingNo === item.returnNo"
                  class="refund"
                  :data-refund-confirm-for="item.returnNo"
                >
                  <p class="refund__meta">
                    将为 {{ item.returnNo }} 生成一笔<strong>模拟退款</strong>：金额按「成交单价
                    {{ item.unitPriceText }} × 累计实收
                    {{ item.progress?.receivedQty ?? 0 }} 件」由服务端计算，预计
                    <strong>{{ item.refundText }}</strong>（最终以服务端结果为准）。
                  </p>
                  <p class="refund__meta">
                    这是模拟退款：不接支付平台、不会真的打款到买家账户；
                    可售库存与可退额度都不变，一张退货单最多一笔。
                  </p>
                  <div class="refund__actions">
                    <button
                      type="button"
                      class="mk-btn mk-btn--sm mk-btn--primary"
                      data-action="refund-confirm"
                      :disabled="afterSaleAdmin.state.busyReturnNo !== null"
                      @click="confirmRefund(item.returnNo)"
                    >
                      {{
                        afterSaleAdmin.state.busyReturnNo === item.returnNo &&
                        afterSaleAdmin.state.busyAction === 'refund'
                          ? '处理中…'
                          : '确认模拟退款'
                      }}
                    </button>
                    <button type="button" class="mk-btn mk-btn--sm" @click="cancelRefund()">
                      取消
                    </button>
                  </div>
                </div>
              </li>
            </ul>

            <div v-if="afterSaleAdmin.totalPages.value > 1" class="pager">
              <button
                type="button"
                class="mk-btn mk-btn--sm"
                :disabled="afterSaleAdmin.state.page <= 1"
                @click="afterSaleAdmin.goPage(afterSaleAdmin.state.page - 1)"
              >
                上一页
              </button>
              <span class="pager__info">
                第 {{ afterSaleAdmin.state.page }} / {{ afterSaleAdmin.totalPages.value }} 页 · 共
                {{ afterSaleAdmin.state.total }} 单
              </span>
              <button
                type="button"
                class="mk-btn mk-btn--sm"
                :disabled="afterSaleAdmin.state.page >= afterSaleAdmin.totalPages.value"
                @click="afterSaleAdmin.goPage(afterSaleAdmin.state.page + 1)"
              >
                下一页
              </button>
            </div>
          </section>

          <p class="foot-note">
            决策在同一个数据库事务内完成：校验前置状态 → 条件更新状态 → （驳回）释放额度 →
            写决策记录 → 写事件。任一步失败整笔回滚，不会留下"状态改了但额度没退"的中间态。
            重复或相反决策一律 409，不新增事件、不改额度。
            收货同样在一个事务里：锁原订单行 → 重读状态与累计实收 → 校验不超收 → 写收货记录 →
            （合格数量）加可售库存 + 写一条退货入库流水。只有全部成功才提交，库存和记录不会只落一半。
            模拟退款也走同一把订单行锁：校验已批准 → 查是否已退过 → 校验累计实收已满 → 核对单价快照 →
            写一条退款记录。唯一键 `(return_id)` 保证一张退货单最多一笔，
            并发重复点击只有一个成功，另一个拿到可解释的 409。
          </p>
        </template>
      </div>
    </div>

    <template #panel>
      <AfterSaleAdminPanel
        v-if="afterSaleAdmin.state.detail"
        :value="afterSaleAdmin.state.detail"
        :busy="afterSaleAdmin.state.busyReturnNo !== null"
        @close="afterSaleAdmin.closeDetail()"
        @approve="onApprove(afterSaleAdmin.state.detail!.returnNo)"
        @reject="
          (reason) => afterSaleAdmin.reject(afterSaleAdmin.state.detail!.returnNo, reason)
        "
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
  line-height: var(--lh-base);
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
  flex-wrap: wrap;
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

.row__progress {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  white-space: nowrap;
}

.row__actions {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex: none;
}

.reject {
  flex-basis: 100%;
  display: grid;
  gap: var(--s-2);
  padding: var(--s-3);
  border: 1px dashed var(--c-line-strong);
  border-radius: var(--r-md);
}

.reject__actions {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
}

.reject__hint {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.receive {
  flex-basis: 100%;
  display: grid;
  gap: var(--s-2);
  padding: var(--s-3);
  border: 1px dashed var(--c-accent-ink);
  border-radius: var(--r-md);
  background: var(--c-accent-weak);
}

.receive__meta {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  line-height: var(--lh-base);
}

.receive__meta strong {
  font-family: var(--font-mono);
  color: var(--c-text);
}

.receive__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s-2);
}

.receive__actions {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
}

.receive__hint {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
  line-height: var(--lh-base);
}

.receive__error {
  font-size: var(--fs-xs);
  color: var(--c-danger);
  line-height: var(--lh-base);
}

.row__refund {
  font-size: var(--fs-xs);
  color: var(--c-ok);
  white-space: nowrap;
}

.refund {
  flex-basis: 100%;
  display: grid;
  gap: var(--s-2);
  padding: var(--s-3);
  border: 1px dashed var(--c-warn);
  border-radius: var(--r-md);
  background: var(--c-warn-weak);
}

.refund__meta {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
  line-height: var(--lh-base);
}

.refund__meta strong {
  font-family: var(--font-mono);
  color: var(--c-text);
}

.refund__actions {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
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

.chip--ok {
  background: var(--c-ok-weak);
  color: var(--c-ok);
}

.chip--neutral {
  background: var(--c-surface-2);
  color: var(--c-text-2);
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

  .receive__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
