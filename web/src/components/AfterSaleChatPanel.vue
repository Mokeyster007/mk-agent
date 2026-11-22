<script setup lang="ts">
/**
 * 售后对话面板。
 *
 * 展示顺序固定：用户这句话 → 进度（在做什么）→ 确认卡 → 最终回答 → 错误。
 *
 * 三条纪律：
 * 1. **确认卡只渲染服务端摘要**：订单号/商品/数量/原因都由 `approval_required`
 *    事件（随后用审批查询接口校准）给出，前端不拼、不改、不自己算钱；
 * 2. **按钮语义如实**：批准 = 同意执行，拒绝 = 什么都不做；
 *    没有"已批准"文案，只有"等待确认 / 已批准 / 已拒绝 / 已过期"这些真实状态；
 * 3. **回答不等于建单成功**：是否真的建单，以「我的售后」列表为准，
 *    面板底部明确写这句话，并在批准后由页面刷新列表。
 */
import { computed, nextTick, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import ApprovalCard from './ApprovalCard.vue'
import MarkdownView from './MarkdownView.vue'
import type { AfterSaleChatStore, AfterSaleTurn } from '@/stores/aftersaleChat'

const props = defineProps<{ chat: AfterSaleChatStore }>()

const draft = ref('')
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
  const text = draft.value
  if (!text.trim() || busy.value) {
    return
  }
  draft.value = ''
  const accepted = await props.chat.submit(text)
  if (!accepted) {
    // 被守卫拦下时不要把用户输入吞掉
    draft.value = text
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

function running(turn: AfterSaleTurn): boolean {
  return turn.transport === 'submitting' || turn.transport === 'streaming'
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
            能查你自己的订单、解释为什么不能退、查已有申请。创建退货申请是它唯一的写操作，
            且必须你确认后才会执行。
          </p>
        </div>
      </div>
    </header>

    <p v-if="conversationUnavailable" class="chat__warn">
      <AppIcon name="alert" :size="13" />
      会话没有建成，当前是单轮问答：追问不会带上文。表单与列表不受影响。
    </p>

    <div ref="listRef" class="chat__turns">
      <p v-if="!turns.length" class="chat__empty">
        例：「我签收的订单里还有哪些能退？」或「帮我退一把键盘，键盘坏了」。
      </p>

      <article
        v-for="turn in turns"
        :key="turn.id"
        class="turn"
        :data-turn-id="turn.id"
      >
        <p class="turn__prompt">{{ turn.prompt }}</p>

        <ul v-if="turn.steps.length" class="turn__steps">
          <li v-for="(step, index) in turn.steps" :key="index" class="turn__step">
            {{ step }}
          </li>
        </ul>

        <p v-if="running(turn) && !turn.steps.length" class="turn__pending">正在处理…</p>

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

        <p v-if="turn.error" class="turn__error">
          <AppIcon name="alert" :size="13" />
          {{ turn.error }}
        </p>
      </article>
    </div>

    <form class="chat__composer" @submit.prevent="onSend">
      <textarea
        v-model="draft"
        class="chat__input"
        rows="2"
        placeholder="说说你要退哪一单、几件、为什么（也可以先问）"
        :disabled="busy"
        aria-label="售后对话输入"
        @keydown.enter.exact.prevent="onSend"
      />
      <button type="submit" class="mk-btn mk-btn--primary" :disabled="busy || !draft.trim()">
        <AppIcon name="send" :size="14" />
        {{ busy ? '处理中…' : '发送' }}
      </button>
    </form>

    <p class="chat__foot">
      回答只是说明；有没有真的建单，以「我的售后」里的退货单号为准。
      拒绝确认不会创建申请、不会占用可退额度。
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
  overflow-wrap: anywhere;
}

.turn__pending {
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.turn__answer {
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
  color: var(--c-text);
  overflow-wrap: anywhere;
}

.turn__error {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  color: var(--c-danger);
  overflow-wrap: anywhere;
}

.chat__composer {
  display: flex;
  align-items: flex-end;
  gap: var(--s-2);
}

.chat__input {
  flex: 1;
  min-width: 0;
  padding: var(--s-2) var(--s-3);
  border: 1px solid var(--c-line);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-text);
  font-family: inherit;
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
  resize: vertical;
}

.chat__input:focus {
  outline: 2px solid var(--c-accent-line);
  outline-offset: 1px;
}

.chat__foot {
  font-size: 10.5px;
  line-height: 1.65;
  color: var(--c-text-3);
}
</style>
