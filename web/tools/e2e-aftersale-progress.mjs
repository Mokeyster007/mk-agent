/**
 * C1 页面验收：买家售后进度（一条主线 + 一条驳回支线）。
 *
 * 主线：买 2 件 → 发货签收 → 售后 Agent 提交退 2 件 → 客服批准
 *      → 第一批 1 件合格（库存 8→9）→ 第二批 1 件损坏（库存仍 9）
 *      → 客服登记模拟退款 → 买家能追到 RF 单号。
 * 支线：再买 1 件并退货 → 客服驳回 → 买家只看到驳回原因，不出现收货/退款步骤。
 *
 * 断言的重点是"买家看得懂"：
 * - 每个阶段在**列表**和**详情**里都是同一句话（服务端算的阶段，页面不自己拼）；
 * - 已收 1/2、还差 1 件 / 已收 2/2、还差 0 件 这些数字必须对；
 * - 收满但没退款时必须说"尚未登记模拟退款"，不能说成已退款；
 * - 驳回是终态：不出现"待收货 / 待退款"，库存也不因驳回变化；
 * - 买家页面不出现后台操作人 ID。
 *
 * 用法（后端 demo + 前端 dev 都启动后）：
 *   node tools/e2e-aftersale-progress.mjs
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'

const WEB = process.env.WEB_BASE || 'http://localhost:5173'
const OUT = 'target/e2e-aftersale-progress'
const SKU = 'SKU-KEYBOARD-K87'
const BUYER = 'shop-user-1'
const ADMIN = 'music-admin-1'
const EXPECTED_REFUND_TEXT = '¥598.00'

fs.mkdirSync(OUT, { recursive: true })

const results = []
let failed = 0

function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  if (!ok) {
    failed += 1
  }
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * 回答里有没有"肯定地说钱已到账"。
 *
 * 不能直接用 `includes('已到账')`：一句"**不代表**已到账"是完全正确的否定句，
 * 用子串匹配会把正确回答判成失败（B3 的页面文案上踩过同一个坑）。
 * 所以按分句判断：分句里出现"已到账"且**没有任何否定词**，才算说错。
 */
function claimsArrived(text) {
  const clauses = (text || '').split(/[。！？\n；;]/)
  return clauses.some(
    (clause) => /(已到账|钱已退回|已原路退回|已打款)/.test(clause) && !/[不未非没无别]/.test(clause),
  )
}

async function waitForText(locator, needle, timeout = 25000) {
  const deadline = Date.now() + timeout
  let text = ''
  while (Date.now() < deadline) {
    try {
      text = await locator.innerText()
    } catch {
      text = ''
    }
    if (text.includes(needle)) {
      return text
    }
    await sleep(200)
  }
  return text
}

function parseQuota(text) {
  const occupied = /已占用\s*(\d+)\s*件/.exec(text || '')
  const remaining = /剩余可退\s*(\d+)\s*件/.exec(text || '')
  return {
    occupied: occupied ? Number(occupied[1]) : null,
    remaining: remaining ? Number(remaining[1]) : null,
  }
}

const browser = await chromium.launch({
  channel: 'chrome',
  headless: true,
  args: ['--mute-audio'],
})

const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
const page = await context.newPage()

const consoleErrors = []
page.on('console', (msg) => {
  if (msg.type() === 'error') {
    consoleErrors.push(msg.text())
  }
})
page.on('pageerror', (error) => consoleErrors.push(String(error)))

const apiFailures = []
page.on('response', (response) => {
  if (response.status() < 400 || !response.url().includes('/api/')) {
    return
  }
  const method = response.request().method()
  const url = response.url().replace(WEB, '')
  response
    .text()
    .then((body) => apiFailures.push(`${response.status()} ${method} ${url} → ${body.slice(0, 300)}`))
    .catch(() => apiFailures.push(`${response.status()} ${method} ${url} → <响应体读不到>`))
})

async function clickAndWait(click, timeout = 20000) {
  const ok = page.locator('.banner--ok')
  const err = page.locator('.notice--danger').first()
  const before = (await ok.count()) ? await ok.innerText() : ''

  await click()

  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await err.count()) {
      throw new Error(`服务端拒绝：${(await err.innerText()).replace(/\n+/g, ' / ')}`)
    }
    if (await ok.count()) {
      const text = await ok.innerText()
      if (text && text !== before) {
        return text
      }
    }
    await sleep(150)
  }
  throw new Error('等待写操作结果超时：既没有新的成功提示，也没有错误提示')
}

async function requireStock(min) {
  await page.goto(`${WEB}/shop`, { waitUntil: 'domcontentloaded' })
  const card = page.locator(`.card[data-sku="${SKU}"]`)
  await card.waitFor({ timeout: 20000 })
  const text = await card.locator('.card__stock').innerText()
  const match = /(\d+)/.exec(text)
  const available = match ? Number(match[1]) : 0
  if (available < min) {
    throw new Error(
      `环境不满足：${SKU} 当前可售 ${available} 件，本脚本至少要 ${min} 件。请换一个全新 H2 库重跑。`,
    )
  }
  return available
}

async function placeOrder(quantity) {
  await page.goto(`${WEB}/shop`, { waitUntil: 'domcontentloaded' })
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(600)

  const card = page.locator(`.card[data-sku="${SKU}"]`)
  await card.waitFor({ timeout: 20000 })

  for (let i = 1; i < quantity; i += 1) {
    await card.getByRole('button', { name: '增加一件' }).click()
  }

  const notice = await clickAndWait(() => card.getByRole('button', { name: '立即购买' }).click())
  const match = /SO\d{14}-[0-9A-F]{4}/.exec(notice)
  return match ? match[0] : ''
}

async function shipAndSign(orderNo) {
  await page.selectOption('.shop-nav__select', ADMIN)
  await sleep(600)
  await page.locator('.shop-nav__links').getByRole('link', { name: '商城后台' }).click()
  await page.waitForURL('**/shop/admin', { timeout: 10000 })

  const row = page.locator(`.row[data-order-no="${orderNo}"]`)
  await row.waitFor({ timeout: 15000 })
  await clickAndWait(() => row.getByRole('button', { name: '发货', exact: true }).click())
  await sleep(300)
  await clickAndWait(() => row.getByRole('button', { name: '标记签收' }).click())
  await sleep(300)
}

async function stockNow() {
  const row = page.locator(`tr[data-sku="${SKU}"]`)
  await row.waitFor({ timeout: 15000 })
  const text = await row.innerText()
  const match = /(\d+)\s*$/m.exec(text)
  return match ? Number(match[1]) : null
}

async function openBuyerAftersale() {
  await closeDetailIfOpen()
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(600)
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后与退货' }).click()
  await page.waitForURL('**/shop/aftersale', { timeout: 10000 })
}

async function openAdminAftersale() {
  await closeDetailIfOpen()
  await page.selectOption('.shop-nav__select', ADMIN)
  await sleep(600)
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后处理' }).click()
  await page.waitForURL('**/shop/admin/aftersale', { timeout: 10000 })
  await page.locator('.rows, .muted').first().waitFor({ timeout: 20000 })
}

async function openAdminShop() {
  await closeDetailIfOpen()
  await page.selectOption('.shop-nav__select', ADMIN)
  await sleep(600)
  await page.locator('.shop-nav__links').getByRole('link', { name: '商城后台' }).click()
  await page.waitForURL('**/shop/admin', { timeout: 10000 })
  await page.locator(`tr[data-sku="${SKU}"]`).waitFor({ timeout: 20000 })
}

async function closeDetailIfOpen() {
  const close = page.locator('[data-return-detail] .panel__close, [data-admin-return-detail] .panel__close')
  if (await close.count()) {
    await close.first().click()
    await sleep(300)
  }
}

async function submitReturnByAgent(orderNo, quantity, reason) {
  await openBuyerAftersale()
  const orderRow = page.locator(`.order[data-order-no="${orderNo}"]`)
  await orderRow.waitFor({ timeout: 20000 })

  // 列表里可能已有旧单（重跑脚本时尤其如此），所以用"提交前后多出来的那张"定位，
  // 不用 .return 的第一个——那不是"我这次建的单"，只是"列表第一条"。
  const beforeNos = await page
    .locator('.return')
    .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-return-no')))

  const composer = page.locator('.chat__input')
  const send = page.locator('.chat__composer').getByRole('button', { name: '发送' })

  await composer.fill(
    `帮我退订单 ${orderNo} 的键盘，退 ${quantity} 件，原因是${reason}。请直接提交退货申请。`,
  )
  await send.click()

  const card = page.locator('[data-approval-card]').last()
  let visible = false
  try {
    await card.waitFor({ timeout: 90000 })
    visible = true
  } catch {
    visible = false
  }

  if (!visible) {
    await composer.fill(
      `就退订单 ${orderNo}，数量 ${quantity} 件，原因：${reason}。不用再问，直接提交。`,
    )
    await send.click()
    try {
      await card.waitFor({ timeout: 90000 })
      visible = true
    } catch {
      visible = false
    }
  }

  if (!visible) {
    return null
  }

  await card.getByRole('button', { name: '批准并执行' }).click()

  // 批准后页面会刷新列表；等到出现"提交前没有的那张退货单"再返回它的单号。
  const deadline = Date.now() + 90000
  while (Date.now() < deadline) {
    const now = await page
      .locator('.return')
      .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-return-no')))
    const fresh = now.find((no) => no && !beforeNos.includes(no))
    if (fresh) {
      return fresh
    }
    await sleep(300)
  }
  return ''
}

async function submitReturnByForm(orderNo, quantity, reason) {
  await openBuyerAftersale()
  const orderRow = page.locator(`.order[data-order-no="${orderNo}"]`)
  await orderRow.waitFor({ timeout: 20000 })

  const form = page.locator('.form')
  await form.locator('select').selectOption(orderNo)
  await form.locator('.stepper__input').fill(String(quantity))
  await form.locator('textarea').fill(reason)

  const notice = await clickAndWait(() =>
    form.getByRole('button', { name: '提交退货申请' }).click(),
  )
  const match = /RT\d{14}-[0-9A-F]{4}/.exec(notice)
  await sleep(600)
  return match ? match[0] : ''
}

async function quotaOf(orderNo) {
  const row = page.locator(`.order[data-order-no="${orderNo}"]`)
  await row.waitFor({ timeout: 20000 })
  return parseQuota(await row.locator('.order__quota').innerText())
}

/** 买家列表里某单的"阶段"行文本。 */
async function buyerStageOf(returnNo) {
  const item = page.locator(`.return[data-return-no="${returnNo}"]`)
  await item.waitFor({ timeout: 20000 })
  const stage = item.locator('[data-stage]')
  await stage.waitFor({ timeout: 15000 })
  return (await stage.innerText()).replace(/\n+/g, ' ')
}

/** 打开买家详情，返回关键区域文本。 */
async function openBuyerDetail(returnNo) {
  await closeDetailIfOpen()
  const item = page.locator(`.return[data-return-no="${returnNo}"]`)
  await item.waitFor({ timeout: 20000 })
  await item.getByRole('button', { name: '详情' }).click()

  const panel = page.locator('[data-return-detail]')
  await panel.waitFor({ timeout: 15000 })
  await waitForText(panel, '提交申请')

  return {
    panel,
    stage: await panel.locator('[data-stage]').innerText(),
    stageCode: await panel.locator('[data-stage]').getAttribute('data-stage-code'),
    counts: (await panel.locator('[data-stage-counts]').count())
      ? await panel.locator('[data-stage-counts]').innerText()
      : '',
    text: await panel.innerText(),
  }
}

async function receiveBatch(returnNo, input) {
  const row = page.locator(`.row[data-return-no="${returnNo}"]`)
  await row.waitFor({ timeout: 20000 })
  await closeDetailIfOpen()
  await row.locator('[data-action="receive"]').click()

  const box = page.locator(`[data-receive-for="${returnNo}"]`)
  await box.waitFor({ timeout: 10000 })

  await box.locator('[data-receive-input="received"]').fill(String(input.received))
  await box.locator('[data-receive-input="qualified"]').fill(String(input.qualified))
  await box.locator('[data-receive-input="damaged"]').fill(String(input.damaged))
  if (input.note) {
    await box.locator('[data-receive-input="note"]').fill(input.note)
  }

  const notice = await clickAndWait(() => box.locator('[data-action="receive-confirm"]').click())
  await sleep(500)
  return notice
}

try {
  /* ---------------- 0. 前置 ---------------- */
  const stock0 = await requireStock(3)
  check('前置条件：可售库存够买 3 件（主线 2 + 支线 1）', stock0 >= 3, `可售 ${stock0} 件`)

  /* ---------------- 1. 主线：买 2 件 → 发货签收 ---------------- */
  const orderNo = await placeOrder(2)
  check('主线：下单成功（2 件）', Boolean(orderNo), orderNo)
  await shipAndSign(orderNo)

  await openAdminShop()
  const stockAfterShip = await stockNow()
  check('主线：发货后库存 -2', stockAfterShip === stock0 - 2,
    `before=${stock0} after=${stockAfterShip}`)

  /* ---------------- 2. 售后 Agent 提交退 2 件 ---------------- */
  const agentReturnNo = await submitReturnByAgent(orderNo, 2, '键盘按键失灵')
  let returnNo = agentReturnNo
  if (!returnNo) {
    check('主线：售后 Agent 提交退货申请', false, '两次都没有出现确认卡，退回普通表单继续验证')
    returnNo = await submitReturnByForm(orderNo, 2, 'C1 进度案例：键盘按键失灵')
  } else {
    check('主线：售后 Agent 提交退货申请', true, returnNo)
  }
  check('主线：退货单已建立', Boolean(returnNo), returnNo)

  await openBuyerAftersale()
  const stageSubmitted = await buyerStageOf(returnNo)
  check('待客服处理：列表阶段是「待客服处理」',
    stageSubmitted.includes('待客服处理'), stageSubmitted)

  const detailSubmitted = await openBuyerDetail(returnNo)
  check('待客服处理：详情显示退货单号与申请数量',
    detailSubmitted.text.includes(returnNo) && detailSubmitted.text.includes('2 件'),
    detailSubmitted.text.replace(/\n/g, ' / ').slice(0, 200))
  check('待客服处理：详情没有收货/退款步骤',
    (await detailSubmitted.panel.locator('[data-step="receipt"]').count()) === 0 &&
      (await detailSubmitted.panel.locator('[data-step="refund"]').count()) === 0)
  await detailSubmitted.panel.getByRole('button', { name: '关闭详情' }).click()

  /* ---------------- 3. 客服批准 ---------------- */
  await openAdminAftersale()
  const row = page.locator(`.row[data-return-no="${returnNo}"]`)
  await row.waitFor({ timeout: 20000 })
  await row.locator('[data-action="approve"]').click()
  await page.locator('.banner--ok').waitFor({ timeout: 20000 })
  await closeDetailIfOpen()

  await openBuyerAftersale()
  const stageApproved = await buyerStageOf(returnNo)
  check('已批准未收货：列表阶段是「待收货」并显示 0/2',
    stageApproved.includes('待收货') && stageApproved.includes('0/2') && stageApproved.includes('还差 2'),
    stageApproved)

  const detailApproved = await openBuyerDetail(returnNo)
  check('已批准未收货：详情写"批准 2 件 / 已收 0 件 / 还差 2 件"',
    detailApproved.counts.includes('批准 2') &&
      detailApproved.counts.includes('已收 0') &&
      detailApproved.counts.includes('还差 2'),
    detailApproved.counts.replace(/\n/g, ' '))
  check('已批准未收货：退款步骤提示"收满后才能登记"',
    (await detailApproved.panel.locator('[data-refund-blocked]').count()) === 1 &&
      (await detailApproved.panel.locator('[data-refund]').count()) === 0)
  await detailApproved.panel.getByRole('button', { name: '关闭详情' }).click()

  /* ---------------- 4. 第一批：1 件合格 → 库存 8→9 ---------------- */
  await openAdminAftersale()
  const notice1 = await receiveBatch(returnNo, { received: 1, qualified: 1, damaged: 0 })
  check('第一批收货成功', notice1.includes('部分收货（1/2）'), notice1.replace(/\n/g, ' '))

  await openAdminShop()
  const stockAfter1 = await stockNow()
  check('第一批后库存 8 → 9', stockAfter1 === stockAfterShip + 1,
    `before=${stockAfterShip} after=${stockAfter1}`)

  await openBuyerAftersale()
  const stagePartial = await buyerStageOf(returnNo)
  check('部分收货：列表阶段是「部分收货」并显示 1/2、还差 1',
    stagePartial.includes('部分收货') && stagePartial.includes('1/2') && stagePartial.includes('还差 1'),
    stagePartial)

  const detailPartial = await openBuyerDetail(returnNo)
  check('部分收货：详情写"已收 1 件 / 还差 1 件"',
    detailPartial.counts.includes('已收 1') && detailPartial.counts.includes('还差 1'),
    detailPartial.counts.replace(/\n/g, ' '))
  check('部分收货：详情逐批显示实收/合格/损坏',
    (await detailPartial.panel.locator('[data-receipt-no]').count()) === 1 &&
      detailPartial.text.includes('实收 1 件') &&
      detailPartial.text.includes('合格 1 件'),
    detailPartial.text.replace(/\n/g, ' / ').slice(0, 240))
  check('部分收货：详情仍显示"尚未登记"之前的状态（不出现退款记录）',
    (await detailPartial.panel.locator('[data-refund]').count()) === 0)
  check('部分收货：买家页面不出现后台操作人 ID',
    !detailPartial.text.includes(ADMIN),
    detailPartial.text.replace(/\n/g, ' / ').slice(0, 240))
  await page.screenshot({ path: `${OUT}/01-partial.png` })
  await detailPartial.panel.getByRole('button', { name: '关闭详情' }).click()

  /* ---------------- 5. 第二批：1 件损坏 → 库存仍 9 ---------------- */
  await openAdminAftersale()
  const notice2 = await receiveBatch(returnNo, {
    received: 1,
    qualified: 0,
    damaged: 1,
    note: '整批碎裂，无法二次销售',
  })
  check('第二批收货成功（全部损坏）', notice2.includes('全部收货（2/2）'), notice2.replace(/\n/g, ' '))

  await openAdminShop()
  const stockAfter2 = await stockNow()
  check('第二批损坏后库存仍是 9', stockAfter2 === stockAfter1,
    `before=${stockAfter1} after=${stockAfter2}`)

  await openBuyerAftersale()
  const stageComplete = await buyerStageOf(returnNo)
  check('全部收货未退款：列表阶段是「已全部收货，尚未登记模拟退款」',
    stageComplete.includes('已全部收货，尚未登记模拟退款'), stageComplete)
  check('全部收货未退款：列表显示 2/2、还差 0',
    stageComplete.includes('2/2') && stageComplete.includes('还差 0'), stageComplete)

  const detailComplete = await openBuyerDetail(returnNo)
  check('全部收货未退款：详情明确"尚未登记模拟退款"',
    (await detailComplete.panel.locator('[data-refund-pending]').count()) === 1 &&
      detailComplete.text.includes('尚未登记模拟退款'),
    detailComplete.text.replace(/\n/g, ' / ').slice(0, 240))
  check('全部收货未退款：详情不显示成"已退款"',
    !detailComplete.text.includes('已退款') && !detailComplete.text.includes('已登记模拟退款'),
    detailComplete.text.replace(/\n/g, ' / ').slice(0, 240))
  check('全部收货未退款：两批都可见，损坏那批能区分',
    (await detailComplete.panel.locator('[data-receipt-no]').count()) === 2 &&
      detailComplete.text.includes('损坏 1 件') &&
      detailComplete.text.includes('整批碎裂'),
    detailComplete.text.replace(/\n/g, ' / ').slice(0, 260))
  await page.screenshot({ path: `${OUT}/02-complete-not-refunded.png` })
  await detailComplete.panel.getByRole('button', { name: '关闭详情' }).click()

  /* ---------------- 6. 客服登记模拟退款 ---------------- */
  await openAdminAftersale()
  const row2 = page.locator(`.row[data-return-no="${returnNo}"]`)
  await row2.waitFor({ timeout: 20000 })
  await closeDetailIfOpen()
  await row2.locator('[data-action="refund"]').click()

  const confirmBox = page.locator(`[data-refund-confirm-for="${returnNo}"]`)
  await confirmBox.waitFor({ timeout: 10000 })
  const refundNotice = await clickAndWait(() =>
    confirmBox.locator('[data-action="refund-confirm"]').click(),
  )
  const refundNo = (/RF\d{14}-[0-9A-F]{4}/.exec(refundNotice) || [])[0] || ''
  check('客服登记模拟退款成功', Boolean(refundNo), refundNotice.replace(/\n/g, ' '))

  /* ---------------- 7. 买家追到 RF 单号与金额 ---------------- */
  await openBuyerAftersale()
  await page.reload({ waitUntil: 'domcontentloaded' })

  const stageRefunded = await buyerStageOf(returnNo)
  check('已登记退款：列表阶段是「已登记模拟退款」',
    stageRefunded.includes('已登记模拟退款'), stageRefunded)

  const detailRefunded = await openBuyerDetail(returnNo)
  check('已登记退款：详情能追到 RF 单号',
    detailRefunded.text.includes(refundNo), detailRefunded.text.replace(/\n/g, ' / ').slice(0, 260))
  check('已登记退款：金额是成交单价 × 2 = ¥598.00',
    (await detailRefunded.panel.locator('[data-refund-amount]').innerText()).includes(
      EXPECTED_REFUND_TEXT,
    ),
    await detailRefunded.panel.locator('[data-refund-amount]').innerText())
  check('已登记退款：说明没有真实资金流动',
    detailRefunded.text.includes('没有真实资金流动'), '')
  check('已登记退款：页面不出现"到账"', !detailRefunded.text.includes('到账'),
    detailRefunded.text.replace(/\n/g, ' / ').slice(0, 260))

  const quotaAfterAll = await quotaOf(orderNo)
  check('收货与退款都没有恢复可退额度（仍 2/0）',
    quotaAfterAll.occupied === 2 && quotaAfterAll.remaining === 0, JSON.stringify(quotaAfterAll))

  await page.screenshot({ path: `${OUT}/03-refunded.png` })
  await detailRefunded.panel.getByRole('button', { name: '关闭详情' }).click()

  /* ---------------- 8. Agent 回答同一组问题 ---------------- */
  const composer = page.locator('.chat__input')
  const send = page.locator('.chat__composer').getByRole('button', { name: '发送' })

  // 一轮对话就是一个 [data-turn-id] 节点，回答在其中的 .turn__answer 里。
  // 用"新增的 turn"而不是固定文案做等待条件：模型措辞不可控，但节点一定新增。
  const turns = page.locator('[data-aftersale-chat] [data-turn-id]')
  const beforeIds = await turns.evaluateAll((nodes) =>
    nodes.map((node) => node.getAttribute('data-turn-id')),
  )

  await composer.fill(
    `订单 ${orderNo} 那笔退货现在到哪一步了？有模拟退款单号吗？金额多少？还差几件没收到？`,
  )
  await send.click()

  const answerDeadline = Date.now() + 150000
  let answer = ''
  while (Date.now() < answerDeadline) {
    const snapshot = await turns.evaluateAll((nodes) =>
      nodes.map((node) => ({
        id: node.getAttribute('data-turn-id'),
        answer: (node.querySelector('.turn__answer')?.innerText || '').trim(),
        error: (node.querySelector('.turn__error')?.innerText || '').trim(),
      })),
    )
    const fresh = snapshot.filter((item) => !beforeIds.includes(item.id))
    const answered = fresh.find((item) => item.answer.length > 0)
    if (answered) {
      answer = answered.answer
      break
    }
    const broken = fresh.find((item) => item.error.length > 0)
    if (broken) {
      answer = `[错误] ${broken.error}`
      break
    }
    await sleep(500)
  }

  check('Agent 有回答（不是空回复）', answer.trim().length > 0, answer.slice(0, 200))
  check('Agent 引用了退款单号', Boolean(refundNo) && answer.includes(refundNo), answer.slice(0, 300))
  check('Agent 给出的金额是 ¥598.00 或 598 元',
    answer.includes('598'), answer.slice(0, 300))
  check('Agent 说清楚"还差 0 件 / 已收满"',
    answer.includes('0 件') || answer.includes('已收满') || answer.includes('全部收'),
    answer.slice(0, 300))
  check('Agent 没有把模拟退款说成资金到账',
    !claimsArrived(answer),
    answer.slice(0, 300))
  check('Agent 没有编造到账时间或支付渠道结果',
    !/个工作日/.test(answer) && !/T\+\d/.test(answer) && !/\d+\s*(小时|天)\s*内?到账/.test(answer),
    answer.slice(0, 300))

  await page.screenshot({ path: `${OUT}/04-agent-answer.png` })

  /* ---------------- 9. 支线：驳回是终态 ---------------- */
  const stockBeforeSide = await (async () => {
    await openAdminShop()
    return stockNow()
  })()

  const sideOrder = await placeOrder(1)
  check('支线：下单成功（1 件）', Boolean(sideOrder), sideOrder)
  await shipAndSign(sideOrder)

  await openAdminShop()
  const stockAfterSideShip = await stockNow()
  check('支线：发货后库存 -1', stockAfterSideShip === stockBeforeSide - 1,
    `before=${stockBeforeSide} after=${stockAfterSideShip}`)

  const sideReturn = await submitReturnByForm(sideOrder, 1, 'C1 支线：超过期限的申请')
  check('支线：退货单提交成功', Boolean(sideReturn), sideReturn)

  await openAdminAftersale()
  const sideRow = page.locator(`.row[data-return-no="${sideReturn}"]`)
  await sideRow.waitFor({ timeout: 20000 })
  await sideRow.locator('[data-action="reject"]').click()
  const rejectBox = page.locator(`[data-reject-for="${sideReturn}"]`)
  await rejectBox.waitFor({ timeout: 10000 })
  await rejectBox.locator('[data-reject-input]').fill('超过 7 天无理由期限，且商品已使用')
  await clickAndWait(() => rejectBox.locator('[data-action="reject-confirm"]').click())

  await openBuyerAftersale()
  await page.reload({ waitUntil: 'domcontentloaded' })

  const stageRejected = await buyerStageOf(sideReturn)
  check('已驳回：列表阶段是「已驳回」', stageRejected.includes('已驳回'), stageRejected)

  const detailRejected = await openBuyerDetail(sideReturn)
  check('已驳回：详情显示驳回原因',
    detailRejected.text.includes('超过 7 天无理由期限，且商品已使用'),
    detailRejected.text.replace(/\n/g, ' / ').slice(0, 240))
  check('已驳回：详情不出现收货步骤',
    (await detailRejected.panel.locator('[data-step="receipt"]').count()) === 0)
  check('已驳回：详情不出现退款步骤',
    (await detailRejected.panel.locator('[data-step="refund"]').count()) === 0)
  check('已驳回：页面不出现"待收货 / 待模拟退款"',
    !detailRejected.text.includes('待收货') && !detailRejected.text.includes('待模拟退款'),
    detailRejected.text.replace(/\n/g, ' / ').slice(0, 240))
  await page.screenshot({ path: `${OUT}/05-rejected.png` })
  await detailRejected.panel.getByRole('button', { name: '关闭详情' }).click()

  await openAdminShop()
  const stockFinal = await stockNow()
  check('已驳回：库存不因驳回变化', stockFinal === stockAfterSideShip,
    `before=${stockAfterSideShip} after=${stockFinal}`)
} catch (error) {
  try {
    await page.screenshot({ path: `${OUT}/99-failure.png` })
    console.log('当前 URL：', page.url())
    console.log('页面提示：', (await page.locator('.notice').allInnerTexts()).join(' / ').replace(/\n+/g, ' '))
  } catch {
    // 诊断信息取不到就算了，别覆盖真正的失败原因
  }
  if (apiFailures.length > 0) {
    console.log('被拒绝的接口调用：')
    for (const item of apiFailures) {
      console.log(`  - ${item}`)
    }
  }
  check('脚本执行未抛异常', false, String(error).slice(0, 1200))
} finally {
  check('浏览器控制台没有错误', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))

  await browser.close()

  const passed = results.filter((item) => item.ok).length
  console.log(`\n${passed}/${results.length} passed`)
  if (failed > 0) {
    process.exitCode = 1
  }
}
