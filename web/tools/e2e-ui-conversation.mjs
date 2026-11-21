/**
 * 页面级验收（不调用模型）。
 *
 * 目的：在真实页面 + 真实会话数据上，稳定验证三件本轮改动最容易弄坏的事：
 * 1. 在已有会话里发消息，请求带的是**同一个 conversationId**，不会新建会话；
 * 2. 新回答追加在同一个会话里，输入框位置不变；
 * 3. 待审批会话的审批卡内容来自服务端，批准/拒绝按钮可用。
 *
 * 做法：拦截 /api/ai/music/chat 与 /api/ai/runs/{id}，用固定的 SSE 帧与终态
 * 冒充一次真实提交。会话详情、审批详情走真实后端（只读 GET）。
 *
 * 产出：target/ui-e2e/report.json
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'
import path from 'node:path'

const WEB = 'http://localhost:5173/'
const OUT = path.resolve('target/ui-e2e')
const SHORT = 'd378b57b-92fd-4ac5-9c78-a65a4b5ab504'
const PENDING = '8c7ed085-51b0-4dab-b542-66ac5a286704'
const LONG = '347c28a8-9581-4c38-8f24-20c942579191'
const FAKE_RUN = 'ui-e2e-run-1'

fs.mkdirSync(OUT, { recursive: true })

const report = { steps: [], checks: [], failed: [] }
const log = (msg) => {
  console.log(msg)
  report.steps.push(msg)
}
function check(name, ok, detail = '') {
  report.checks.push({ name, ok, detail })
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? ` — ${detail}` : ''}`)
  if (!ok) report.failed.push({ name, detail })
}

const frame = (type, message, step = null) => ({
  runId: FAKE_RUN,
  type,
  message,
  step,
  timestamp: Date.now(),
})

function sseBody(frames) {
  return frames.map((f) => `event:${f.type}\ndata:${JSON.stringify(f)}\n\n`).join('')
}

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  locale: 'zh-CN',
})
const page = await context.newPage()

const consoleErrors = []
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text())
})

const chatUrls = []
let createCalls = 0

await page.route('**/api/ai/conversations', async (route) => {
  if (route.request().method() === 'POST') {
    createCalls += 1
  }
  await route.continue()
})

await page.route('**/api/ai/music/chat**', async (route) => {
  chatUrls.push(route.request().url())
  const body = sseBody([
    frame('run_id', ''),
    frame('status', '任务开始执行', 1),
    frame('tool_start', '正在调用 search_songs 搜索歌曲', 1),
    frame('tool_result', 'search_songs 返回 5 条结果', 1),
    frame('final_answer', '第一首《城市霓虹》发行于 2021 年。', 2),
    frame('done', '[DONE]', 2),
  ])
  await route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'text/event-stream;charset=UTF-8', 'Cache-Control': 'no-cache' },
    body,
  })
})

await page.route(`**/api/ai/runs/${FAKE_RUN}`, async (route) => {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      runId: FAKE_RUN,
      userId: 'music-user-1',
      agentType: 'MUSIC_USER',
      userPrompt: '第一首是哪一年发行的？',
      state: 'SUCCEEDED',
      currentStep: 2,
      toolCallCount: 1,
      toolTrace: 'search_songs',
      finalAnswer: '第一首《城市霓虹》发行于 2021 年。',
      errorMessage: null,
      errorCode: null,
      model: 'stub-for-layout-check',
      promptTokens: null,
      completionTokens: null,
      totalTokens: null,
      startedAt: '2026-11-18T16:00:00',
      finishedAt: '2026-11-18T16:00:03',
      totalCostMillis: 3000,
      createdAt: '2026-11-18T16:00:00',
    }),
  })
})

/* ---------------- 1. 已有会话内继续发消息 ---------------- */
await page.goto(WEB, { waitUntil: 'domcontentloaded' })
await page.evaluate((id) => window.localStorage.setItem('mkagent.activeConversationId', id), SHORT)
await page.goto(WEB, { waitUntil: 'domcontentloaded' })
await page.locator('.turn').first().waitFor({ timeout: 15000 })
await page.waitForTimeout(400)

const turnsBefore = await page.locator('.turns > *').count()
const composerBefore = await page.locator('.composer__box').boundingBox()
log(`初始会话轮次 ${turnsBefore}，输入框 ${JSON.stringify(composerBefore)}`)

await page.locator('textarea').fill('第一首是哪一年发行的？')
await page.locator('.composer__send').click()
await page.waitForTimeout(1800)

const turnsAfter = await page.locator('.turns > *').count()
const composerAfter = await page.locator('.composer__box').boundingBox()

check('提交请求带上当前会话 id', chatUrls.some((u) => u.includes(`conversationId=${SHORT}`)), chatUrls[0] ?? '无请求')
check('没有新建会话', createCalls === 0, `POST /ai/conversations 次数=${createCalls}`)
check('新回答追加在同一会话', turnsAfter === turnsBefore + 1, `${turnsBefore} → ${turnsAfter}`)
check(
  '提交前后输入框位置不变',
  Math.abs(composerAfter.x - composerBefore.x) <= 2 &&
    Math.abs(composerAfter.width - composerBefore.width) <= 2 &&
    Math.abs(composerAfter.y + composerAfter.height - (composerBefore.y + composerBefore.height)) <= 2,
  `${composerBefore.x},${composerBefore.width} → ${composerAfter.x},${composerAfter.width}`,
)
const answerText = await page.locator('.turn__answer').last().innerText().catch(() => '')
check('回答渲染在对话里', answerText.includes('2021'), answerText.slice(0, 40))

await page.screenshot({ path: path.join(OUT, '01-same-conversation.png') })

/* ---------------- 2. 待审批会话：卡片内容与按钮 ---------------- */
await page.goto(WEB, { waitUntil: 'domcontentloaded' })
await page.evaluate((id) => window.localStorage.setItem('mkagent.activeConversationId', id), PENDING)
await page.goto(WEB, { waitUntil: 'domcontentloaded' })
await page.locator('[data-approval-card]').first().waitFor({ timeout: 15000 })
await page.waitForTimeout(400)

const card = page.locator('[data-approval-card]')
const cardCount = await card.count()
check('刷新后重建审批卡', cardCount >= 1, `卡片数=${cardCount}`)

const cardText = cardCount ? await card.last().innerText() : ''
check('卡片含目标歌单', cardText.includes('目标歌单'), cardText.split('\n').slice(0, 6).join(' / '))
check('卡片含歌曲清单', cardText.includes('将写入的歌曲'))
const approve = page.locator('[data-approval-card]').last().getByRole('button', { name: '批准并执行' })
const reject = page.locator('[data-approval-card]').last().getByRole('button', { name: '拒绝' })
check('批准按钮可见且可用', (await approve.count()) === 1 && (await approve.isEnabled()))
check('拒绝按钮可见且可用', (await reject.count()) === 1 && (await reject.isEnabled()))

await page.screenshot({ path: path.join(OUT, '02-approval-card.png') })

/* ---------------- 3. 抽屉开合不改变输入框 ---------------- */
const composerBeforeDrawer = await page.locator('.composer__box').boundingBox()
await page.locator('.topbar__btn').click()
await page.waitForTimeout(600)
const composerWithDrawer = await page.locator('.composer__box').boundingBox()
check(
  '打开歌单抽屉不挤压主区',
  Math.abs(composerWithDrawer.x - composerBeforeDrawer.x) <= 2 &&
    Math.abs(composerWithDrawer.width - composerBeforeDrawer.width) <= 2,
  `${composerBeforeDrawer.x},${composerBeforeDrawer.width} → ${composerWithDrawer.x},${composerWithDrawer.width}`,
)
await page.screenshot({ path: path.join(OUT, '03-drawer-open.png') })
await page.keyboard.press('Escape')
await page.waitForTimeout(400)
const focusedLabel = await page.evaluate(() => document.activeElement?.getAttribute('aria-label') ?? '')
check('关闭抽屉后焦点回到触发按钮', focusedLabel === '打开我的歌单', focusedLabel)

/* ---------------- 4. 末条消息与审批按钮不被输入区遮挡 ---------------- */
const composerRect = await page.locator('.composer__box').boundingBox()
const approveRect = await approve.boundingBox()
check(
  '审批按钮不被输入区遮挡',
  !!approveRect && approveRect.y + approveRect.height <= composerRect.y + 1,
  `按钮底 ${approveRect ? Math.round(approveRect.y + approveRect.height) : '?'} / 输入框顶 ${Math.round(composerRect.y)}`,
)

/* ---------------- 5. 快速切换会话：输入框不跳 ---------------- */
const switchBase = await page.locator('.composer__box').boundingBox()
const items = page.locator('.nav__item')
const itemCount = Math.min(await items.count(), 6)
const positions = []
for (let i = 0; i < itemCount; i += 1) {
  await items.nth(i).click()
  await page.waitForTimeout(120)
  const box = await page.locator('.composer__box').boundingBox()
  positions.push(box)
}
await page.waitForTimeout(900)
const switchSettled = await page.locator('.composer__box').boundingBox()
const maxDx = Math.max(...positions.map((p) => Math.abs(p.x - switchBase.x)))
const maxDw = Math.max(...positions.map((p) => Math.abs(p.width - switchBase.width)))
check(
  `快速切换 ${itemCount} 个会话，输入框不位移`,
  maxDx <= 2 && maxDw <= 2,
  `最大水平差 ${maxDx.toFixed(1)}px / 宽度差 ${maxDw.toFixed(1)}px`,
)
check(
  '切换稳定后输入框仍在原位',
  Math.abs(switchSettled.x - switchBase.x) <= 2 && Math.abs(switchSettled.y - switchBase.y) <= 2,
  `${switchBase.x},${switchBase.y} → ${switchSettled.x},${switchSettled.y}`,
)

/* ---------------- 6. 读历史时不强制拉到底 ---------------- */
await page.goto(WEB, { waitUntil: 'domcontentloaded' })
await page.evaluate((id) => window.localStorage.setItem('mkagent.activeConversationId', id), LONG)
await page.goto(WEB, { waitUntil: 'domcontentloaded' })
await page.locator('.turn').first().waitFor({ timeout: 15000 })
await page.waitForTimeout(500)

await page.evaluate(() => {
  const el = document.querySelector('.workspace__scroll')
  if (el) el.scrollTop = 0
})
await page.waitForTimeout(300)

const jumpCount = await page.locator('.workspace__jump').count()
const composerWhileReading = await page.locator('.composer__box').boundingBox()
check('读历史时出现「回到最新」', jumpCount === 1, `按钮数=${jumpCount}`)
check(
  '读历史时输入框位置不变',
  Math.abs(composerWhileReading.y - composerBeforeDrawer.y) <= 2,
  `${composerBeforeDrawer.y} → ${composerWhileReading.y}`,
)
await page.screenshot({ path: path.join(OUT, '04-scrolled-up.png') })

await page.locator('.workspace__jump').click()
await page.waitForTimeout(500)
const backAtBottom = await page.evaluate(() => {
  const el = document.querySelector('.workspace__scroll')
  if (!el) return false
  return el.scrollHeight - el.scrollTop - el.clientHeight < 4
})
check('点击后回到最新', backAtBottom)

/* ---------------- 7. 横向溢出 ---------------- */
for (const width of [1440, 1280, 390]) {
  await page.setViewportSize({ width, height: width === 390 ? 844 : 900 })
  await page.waitForTimeout(300)
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  check(`${width}px 无页面横向滚动`, overflow <= 0, `溢出 ${overflow}px`)
}

check('控制台无报错', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))

report.consoleErrors = consoleErrors
fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2))
await browser.close()

console.log(`\n${report.failed.length ? `失败 ${report.failed.length} 项` : '全部通过'}`)
process.exit(report.failed.length ? 1 : 0)
