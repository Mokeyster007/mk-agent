<script setup lang="ts">
/**
 * 售后对话面板（F-01 / F-02 真实状态与确定性确认卡路径）。
 *
 * 纪律：
 * 1. 按真实业务结果区分状态：信息待补 / 已准备、待确认 / 尚未提交 / 提交失败 / 提交成功；
 *    只有收到真实 returnNo 才显示「提交成功」；Agent run SUCCEEDED 不是业务成功；
 * 2. 确认卡内容完全来自服务端摘要（approval_required / GET /ai/approvals/{id}）；
 * 3. 若带入明确订单上下文（从订单详情或可退订单卡片点入），展示绑定订单并透传 orderNo 给后端；
 * 4. 当预览已齐备但未出确认卡时，提供显式按钮「发起退货确认卡」（仍经人工审批，不绕过确认）
 *    与「填入普通表单」入口。
 */
import { computed, nextTick, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import ApprovalCard from './ApprovalCard.vue'
import MarkdownView from './MarkdownView.vue'
import type { AfterSaleReturnDraft, AfterSaleSignedOrderVO } from '@/api/aftersale'
import {
  AFTERSALE_STATUS_LABELS,
  type AfterSaleChatStore,
  type AfterSaleTurn,
} from '@/stores/aftersaleChat'

const props = defineProps<{
  chat: AfterSaleChatStore
  activeOrder?: AfterSaleSignedOrderVO | null
}>()

const emit = defineEmits<{
  (e: 'clear-order'): void
  (e: 'pick-order', orderNo: string): void
  (e: 'fill-form', draft: AfterSaleReturnDraft): void
  (e: 'open-return', returnNo: string): void
}>()

const draftText = ref('')
const listRef = ref<HTMLElement | null>(null)

const turns = computed(() => props.chat.turns.value)
const busy = computed(() => props.chat.busy.value)
const conversationUnavailable = computed(() => props.chat.conversationUnavailable.value)

async function scrollToEnd(): Promise<void> {
  await nextTick()
  const element = listRef.value
  if (element) {
    element.scrollTop = element.scrollHeight
  }
}

async function onSend(): Promise<void> {
  const text = draftText.value
  if (!text.trim() || busy.value) {
    return
  }
  draftText.value = ''
  const accepted = await props.chat.submit(text)
  if (!accepted) {
    draftText.value = text
    return
  }
  await scrollToEnd()
}

function onApprove(turn: AfterSaleTurn): void {
  void props.chat.decide(turn.id, true)
}

function onReject(turn: AfterSaleTurn): void {
  void props.chat.decide(turn.id, false)
}

function onResume(turn: AfterSaleTurn): void {
  void props.chat.resume(turn.id)
}

function onRefresh(turn: AfterSaleTurn): void {
  void props.chat.refreshApproval(turn.id)
}

async function onPrepareCard(draft: AfterSaleReturnDraft): Promise<void> {
  await props.chat.prepareApprovalCard(draft)
  await scrollToEnd()
}

function onPickCandidate(orderNo: string): void {
  emit('pick-order', orderNo)
  if (!draftText.value.trim()) {
    draftText.value = `我要退订单 ${orderNo}，数量 1 件，原因是：`
  }
}

function running(turn: AfterSaleTurn): boolean {
  return turn.transport === 'submitting' || turn.transport === 'streaming'
}

function statusClass(status: AfterSaleTurn['businessStatus']): string {
  switch (status) {
    case 'SUBMITTED':
      return 'turn__badge--ok'
    case 'READY_FOR_CONFIRMATION':
      return 'turn__badge--wait'
    case 'SUBMIT_FAILED':
      return 'turn__badge--danger'
    case 'NEEDS_INFO':
    case 'NOT_SUBMITTED':
    default:
      return 'turn__badge--neutral'
  }
}
</script>

<template>
  <section class="chat" data-aftersale-chat>
    <header class="chat__head">
      <div class="chat__title">
        <span class="chat__icon" aria-hidden="true">
          <AppIcon name="sparkle" :size="15" />
        </span>
        <div>
          <h2 class="chat__name">售后对话</h2>
          <p class="chat__sub">
            支持查询本人签收订单、核对退货资格、发起退货确认卡。确认前零建单、零占额度；
            仅收到真实退货单号才算提交成功。
          </p>
        </div>
      </div>
    </header>

    <!-- 从订单详情或可退订单卡带入的明确订单上下文 -->
    <div v-if="activeOrder" class="chat__context" data-active-order-context>
      <div class="chat__context-info">
        <span class="chat__context-tag">当前关联订单</span>
        <strong class="mono">{{ activeOrder.orderNo }}</strong>
        <span>{{ activeOrder.productName }}（可退 {{ activeOrder.quota.remaining }}/{{ activeOrder.quota.purchased }} 件）</span>
      </div>
      <div class="chat__context-actions">
        <RouterLink
          class="link-btn"
          :to="{ path: '/shop', query: { orderNo: activeOrder.orderNo } }"
        >
          返回订单详情
        </RouterLink>
        <button type="button" class="link-btn" @click="emit('clear-order')">取消关联</button>
      </div>
    </div>

    <p v-if="conversationUnavailable" class="chat__warn">
      <AppIcon name="alert" :size="13" />
      会话没有建成，当前是单轮问答：追问不会带上文。表单与列表不受影响。
    </p>

    <div ref="listRef" class="chat__turns">
      <p v-if="!turns.length" class="chat__empty">
        <template v-if="activeOrder">
          已绑定订单 {{ activeOrder.orderNo }}。直接说「退 1 把，原因是按键回弹偏硬」即可试算并生成退货确认卡。
        </template>
        <template v-else>
          例：「我签收的订单里还有哪些能退？」或「帮我退 1 件，原因是按键失灵」。
        </template>
      </p>

      <article
        v-for="turn in turns"
        :key="turn.id"
        class="turn"
        :data-turn-id="turn.id"
        :data-business-status="turn.businessStatus ?? 'PENDING'"
      >
        <div class="turn__top">
          <span
            v-if="turn.businessStatus"
            class="turn__badge"
            :class="statusClass(turn.businessStatus)"
            data-turn-status
          >
            {{ AFTERSALE_STATUS_LABELS[turn.businessStatus] }}
          </span>
          <button
            v-if="turn.returnNo"
            type="button"
            class="link-btn mono"
            data-turn-return-no
            @click="emit('open-return', turn.returnNo)"
          >
            退货单号：{{ turn.returnNo }}（查看详情）
          </button>
        </div>

        <p class="turn__prompt">{{ turn.prompt }}</p>

        <ul v-if="turn.steps.length" class="turn__steps">
          <li v-for="(step, index) in turn.steps" :key="index" class="turn__step">
            {{ step }}
          </li>
        </ul>

        <p v-if="running(turn) && !turn.steps.length" class="turn__pending">正在处理…</p>

        <p v-if="turn.businessMessage" class="turn__status-note" data-business-message>
          {{ turn.businessMessage }}
        </p>

        <!-- 多笔可退订单候选：让买家显式选择，绝不猜测 -->
        <div v-if="turn.candidates.length > 1 && !turn.approvalPayload && !turn.returnNo" class="turn__candidates">
          <p class="turn__candidates-label">请选择要退货的订单：</p>
          <div class="turn__candidates-list">
            <button
              v-for="c in turn.candidates"
              :key="c.orderNo"
              type="button"
              class="mk-btn mk-btn--sm"
              @click="onPickCandidate(c.orderNo)"
            >
              {{ c.orderNo }} · {{ c.productName }}（可退 {{ c.remainingQty }}）
            </button>
          </div>
        </div>

        <ApprovalCard
          v-if="turn.approvalPayload"
          :payload="turn.approvalPayload"
          :approval="turn.approval"
          :busy="turn.deciding"
          @approve="onApprove(turn)"
          @reject="onReject(turn)"
          @resume="onResume(turn)"
          @refresh="onRefresh(turn)"
        />

        <div v-if="turn.answer" class="turn__answer">
          <MarkdownView :source="turn.answer" />
        </div>

        <!-- 尚未产出确认卡且未提交时：给出显式继续方式（准备确认卡 / 普通表单） -->
        <div
          v-if="!running(turn) && !turn.approvalPayload && !turn.returnNo && turn.businessStatus !== 'SUBMITTED'"
          class="turn__actions"
        >
          <button
            v-if="turn.draft"
            type="button"
            class="mk-btn mk-btn--sm mk-btn--primary"
            data-prepare-approval
            :disabled="busy"
            @click="onPrepareCard(turn.draft)"
          >
            发起退货确认卡（{{ turn.draft.orderNo }} × {{ turn.draft.quantity }}）
          </button>
          <button
            v-if="turn.draft"
            type="button"
            class="mk-btn mk-btn--sm"
            @click="emit('fill-form', turn.draft)"
          >
            填入普通退货表单
          </button>
          <a v-else class="mk-btn mk-btn--sm" href="#form">改用普通退货表单</a>
        </div>

        <p v-if="turn.error" class="turn__error">
          <AppIcon name="alert" :size="13" />
          {{ turn.error }}
        </p>
      </article>
    </div>

    <form class="chat__composer" @submit.prevent="onSend">
      <textarea
        v-model="draftText"
        class="chat__input"
        rows="2"
        placeholder="说说你要退哪一单、几件、为什么（也可以先问）"
        :disabled="busy"
        aria-label="售后对话输入"
        @keydown.enter.exact.prevent="onSend"
      />
      <button type="submit" class="mk-btn mk-btn--primary" :disabled="busy || !draftText.trim()">
        <AppIcon name="send" :size="14" />
        {{ busy ? '处理中…' : '发送' }}
      </button>
    </form>

    <p class="chat__foot">
      回答只是说明，不代表已提交；只有生成真实退货单号并在「我的售后」可见时才视为已提交。
      拒绝确认不会建单、不会占额度。
    </p>
  </section>
</template>

<style scoped>
.chat {
  display: grid;
  gap: var(--s-3);
  padding: var(--s-4);
  border: 1px solid var(--c-line-soft);
  border-radius: var(--r-md);
  background: var(--c-bg-soft);
}

.chat__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-3);
}

.chat__title {
  display: flex;
  gap: var(--s-3);
  min-width: 0;
}

.chat__icon {
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

.chat__name {
  font-size: var(--fs-md);
  font-weight: 500;
  color: var(--c-text);
}

.chat__sub {
  margin-top: 2px;
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  color: var(--c-text-3);
}

.chat__context {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  flex-wrap: wrap;
  padding: 8px var(--s-3);
  border: 1px solid var(--c-accent-line);
  border-radius: var(--r-sm);
  background: var(--c-accent-weak);
  font-size: var(--fs-xs);
  color: var(--c-text);
}

.chat__context-info {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
}

.chat__context-tag {
  padding: 1px 6px;
  border-radius: var(--r-pill);
  background: var(--c-surface);
  color: var(--c-accent-ink);
  font-weight: 500;
}

.chat__context-actions {
  display: flex;
  align-items: center;
  gap: var(--s-3);
}

.chat__warn {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--c-warn);
}

.chat__turns {
  display: grid;
  gap: var(--s-3);
  max-height: 520px;
  overflow-y: auto;
  padding-right: 2px;
}

.chat__empty {
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

.turn__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2);
  flex-wrap: wrap;
}

.turn__badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: var(--r-pill);
  font-size: 11px;
  font-weight: 500;
  border: 1px solid var(--c-line);
}

.turn__badge--ok {
  border-color: rgba(118, 214, 178, 0.35);
  background: rgba(118, 214, 178, 0.12);
  color: var(--c-ok);
}

.turn__badge--wait {
  border-color: rgba(224, 177, 95, 0.38);
  background: var(--c-warn-weak);
  color: var(--c-warn);
}

.turn__badge--danger {
  border-color: rgba(232, 114, 114, 0.38);
  background: rgba(232, 114, 114, 0.12);
  color: var(--c-danger);
}

.turn__badge--neutral {
  color: var(--c-text-2);
  background: var(--c-bg-soft);
}

.turn__status-note {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
}

.turn__prompt {
  justify-self: end;
  max-width: 90%;
  padding: 6px 10px;
  border-radius: var(--r-md);
  background: var(--c-accent-weak);
  color: var(--c-text);
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
  overflow-wrap: anywhere;
}

.turn__steps {
  display: grid;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.turn__step {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.turn__pending {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.turn__candidates {
  display: grid;
  gap: 6px;
}

.turn__candidates-label {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
}

.turn__candidates-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.turn__answer {
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
  color: var(--c-text);
}

.turn__actions {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
  padding-top: 2px;
}

.turn__error {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--c-danger);
}

.chat__composer {
  display: flex;
  align-items: flex-end;
  gap: var(--s-2);
}

.chat__input {
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
  line-height: var(--lh-base);
}

.chat__foot {
  font-size: 11px;
  color: var(--c-text-3);
}

.link-btn {
  font-size: var(--fs-xs);
  color: var(--c-accent-ink);
  text-decoration: none;
}

.link-btn:hover {
  text-decoration: underline;
}

.mono {
  font-family: var(--font-mono);
}
</style>
