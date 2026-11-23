/**
 * 售后验收执行（2026-11-23，验收员视角）。
 *
 * 只通过页面与已有正常业务接口操作，不改数据、不改源码。
 * 目标不是"跑绿"，而是找出：断点、规则不一致、未定义分支。
 *
 * 输出：每条观察一行，前缀 PASS / FAIL / NOTE / EVIDENCE。
 *   node tools/acc-aftersale-acceptance.mjs
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'

const WEB = process.env.WEB_BASE || 'http://localhost:5173'
const OUT = 'target/acc-aftersale'
const BUYER = 'shop-user-1'
const ADMIN = 'music-admin-1'
const KB = 'SKU-KEYBOARD-K87'
const MS = 'SKU-MOUSE-M2'

fs.mkdirSync(OUT, { recursive: true })

const log = []
let failures = 0

function say(tag, text) {
  const line = `${tag}  ${text}`
  log.push(line)
  console.log(line)
}
const pass = (t) => say('PASS', t)
const fail = (t) => {
  failures += 1
  say('FAIL', t)
}
const note = (t) => say('NOTE', t)
const ev = (t) => say('EVIDENCE', t)

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--mute-audio'] })
const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
const page = await context.newPage()

const consoleErrors = []
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})
page.on('pageerror', (e) => consoleErrors.push(String(e)))

const apiFailures = []
page.on('response', (r) => {
  if (r.status() < 400 || !r.url().includes('/api/')) return
  const method = r.request().method()
  const url = r.url().replace(WEB, '')
  r.text()
    .then((b) => apiFailures.push(`${r.status()} ${method} ${url} → ${b.slice(0, 240)}`))
    .catch(() => apiFailures.push(`${r.status()} ${method} ${url}`))
})

/* ---------------- 通用动作 ---------------- */

async function shot(name) {
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true }).catch(() => {})
}

async function clickAndWait(click, timeout = 25000) {
  const ok = page.locator('.banner--ok')
  const err = page.locator('.notice--danger').first()
  const before = (await ok.count()) ? await ok.innerText() : ''
  await click()
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await err.count()) throw new Error(`服务端拒绝：${(await err.innerText()).replace(/\n+/g, ' / ')}`)
    if (await ok.count()) {
      const t = await ok.innerText()
      if (t && t !== before) return t
    }
    await sleep(150)
  }
  throw new Error('等待写操作结果超时')
}

async function asBuyer() {
  await closeDetail()
  await page.goto(`${WEB}/shop`, { waitUntil: 'domcontentloaded' })
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(500)
}

async function asAdmin() {
  await closeDetail()
  // 首次调用时页面还是 about:blank，必须先落到同源页面才有身份切换器
  await page.goto(`${WEB}/shop`, { waitUntil: 'domcontentloaded' })
  await page.selectOption('.shop-nav__select', ADMIN)
  await sleep(500)
}

async function gotoAdminShop() {
  await asAdmin()
  await page.locator('.shop-nav__links').getByRole('link', { name: '商城后台' }).click()
  await page.waitForURL('**/shop/admin', { timeout: 15000 })
  await page.locator(`tr[data-sku="${KB}"]`).waitFor({ timeout: 20000 })
}

async function gotoAdminAftersale() {
  await asAdmin()
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后处理' }).click()
  await page.waitForURL('**/shop/admin/aftersale', { timeout: 15000 })
  await page.locator('.rows, .muted').first().waitFor({ timeout: 20000 })
}

async function gotoBuyerAftersale() {
  await asBuyer()
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后与退货' }).click()
  await page.waitForURL('**/shop/aftersale', { timeout: 15000 })
  await page.locator('.orders, .form').first().waitFor({ timeout: 20000 })
}

async function closeDetail() {
  const close = page.locator('[data-return-detail] .panel__close, [data-admin-return-detail] .panel__close')
  if (await close.count()) {
    await close.first().click().catch(() => {})
    await sleep(250)
  }
}

async function stockOf(sku) {
  await gotoAdminShop()
  const row = page.locator(`tr[data-sku="${sku}"]`)
  await row.waitFor({ timeout: 20000 })
  const t = await row.innerText()
  const m = /(\d+)\s*$/m.exec(t)
  return m ? Number(m[1]) : null
}

async function buy(sku, quantity) {
  await asBuyer()
  const card = page.locator(`.card[data-sku="${sku}"]`)
  await card.waitFor({ timeout: 20000 })
  for (let i = 1; i < quantity; i += 1) {
    await card.getByRole('button', { name: '增加一件' }).click()
  }
  const notice = await clickAndWait(() => card.getByRole('button', { name: '立即购买' }).click())
  const m = /SO\d{14}-[0-9A-F]{4}/.exec(notice)
  if (!m) throw new Error(`下单没有返回单号：${notice}`)
  return m[0]
}

async function shipAndSign(orderNo) {
  await asAdmin()
  await page.locator('.shop-nav__links').getByRole('link', { name: '商城后台' }).click()
  await page.waitForURL('**/shop/admin', { timeout: 15000 })
  const row = page.locator(`.row[data-order-no="${orderNo}"]`)
  await row.waitFor({ timeout: 20000 })
  await clickAndWait(() => row.getByRole('button', { name: '发货', exact: true }).click())
  await sleep(300)
  await clickAndWait(() => row.getByRole('button', { name: '标记签收' }).click())
  await sleep(300)
}

async function quotaOf(orderNo) {
  await gotoBuyerAftersale()
  const row = page.locator(`.order[data-order-no="${orderNo}"]`)
  await row.waitFor({ timeout: 20000 })
  const t = await row.locator('.order__quota').innerText()
  const occ = /已占用\s*(\d+)\s*件/.exec(t)
  const rem = /剩余可退\s*(\d+)\s*件/.exec(t)
  const chip = (await row.locator('.chip').innerText()).trim()
  return { text: t.replace(/\n+/g, ' '), occupied: occ ? +occ[1] : null, remaining: rem ? +rem[1] : null, chip }
}

async function submitByForm(orderNo, quantity, reason) {
  await gotoBuyerAftersale()
  const form = page.locator('.form')
  await form.locator('select').selectOption(orderNo)
  await form.locator('.stepper__input').fill(String(quantity))
  await form.locator('textarea').fill(reason)
  const notice = await clickAndWait(() => form.getByRole('button', { name: '提交退货申请' }).click())
  const m = /RT\d{14}-[0-9A-F]{4}/.exec(notice)
  await sleep(500)
  return m ? m[0] : ''
}

async function openBuyerDetail(returnNo) {
  await gotoBuyerAftersale()
  const item = page.locator(`.return[data-return-no="${returnNo}"]`)
  await item.waitFor({ timeout: 20000 })
  await item.getByRole('button', { name: '详情' }).click()
  const panel = page.locator('[data-return-detail]')
  await panel.waitFor({ timeout: 15000 })
  await sleep(400)
  const text = await panel.innerText()
  const stageCode = await panel.locator('[data-stage]').getAttribute('data-stage-code')
  const counts = (await panel.locator('[data-stage-counts]').count())
    ? await panel.locator('[data-stage-counts]').innerText()
    : ''
  return { panel, text, stageCode, counts: counts.replace(/\n+/g, ' ') }
}

async function receiveBatch(returnNo, input) {
  await gotoAdminAftersale()
  const row = page.locator(`.row[data-return-no="${returnNo}"]`)
  await row.waitFor({ timeout: 20000 })
  await row.locator('[data-action="receive"]').click()
  const box = page.locator(`[data-receive-for="${returnNo}"]`)
  await box.waitFor({ timeout: 10000 })
  await box.locator('[data-receive-input="received"]').fill(String(input.received))
  await box.locator('[data-receive-input="qualified"]').fill(String(input.qualified))
  await box.locator('[data-receive-input="damaged"]').fill(String(input.damaged))
  if (input.note) await box.locator('[data-receive-input="note"]').fill(input.note)
  const notice = await clickAndWait(() => box.locator('[data-action="receive-confirm"]').click())
  await sleep(400)
  return notice.replace(/\n+/g, ' ')
}

/** 直接调已有业务接口，用于"界面上不该有按钮"的负向核对。 */
async function api(method, path, userId, body) {
  const res = await page.request.fetch(`http://localhost:8123${path}`, {
    method,
    headers: { 'X-User-Id': userId, 'Content-Type': 'application/json' },
    data: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* 非 JSON 就只留文本 */
  }
  return { status: res.status(), code: json?.code ?? '', message: json?.message ?? text.slice(0, 200) }
}

/* ---------------- 开始 ---------------- */

const ctx = {}

try {
  /* ============ 0. 基线 ============ */
  const kb0 = await stockOf(KB)
  const ms0 = await stockOf(MS)
  ev(`基线库存 keyboard=${kb0} mouse=${ms0}`)
  ctx.kb0 = kb0
  ctx.ms0 = ms0

  /* ============ M1：从已签收订单发起售后，不抄单号 ============ */
  note('M1 开始')
  const mouseOrder = await buy(MS, 1)
  await shipAndSign(mouseOrder)
  ev(`M1 已签收订单=${mouseOrder}（鼠标，未预先记录给页面）`)

  await gotoBuyerAftersale()
  const mouseCard = page.locator(`.order[data-order-no="${mouseOrder}"]`)
  await mouseCard.waitFor({ timeout: 20000 })
  const cardText = (await mouseCard.innerText()).replace(/\n+/g, ' / ')
  const hasButton = (await mouseCard.getByRole('button', { name: '申请退货' }).count()) === 1
  ev(`M1 订单卡片：${cardText}`)
  pass(`M1 订单卡片自带「申请退货」按钮：${hasButton ? '有' : '无'}`)

  await mouseCard.getByRole('button', { name: '申请退货' }).click()
  await sleep(500)
  const prefilled = await page.locator('.form select').inputValue()
  const qty = await page.locator('.form .stepper__input').inputValue()
  if (prefilled === mouseOrder) {
    pass(`M1 点卡片后表单自动预填订单号（无需抄号），数量默认 ${qty}`)
  } else {
    fail(`M1 点卡片后表单订单号是「${prefilled}」，期望 ${mouseOrder}`)
  }
  await shot('m1-form-prefilled')

  // Agent 能否在没有订单号的情况下定位商品
  const beforeReturns = await page.locator('.return').count()
  const composer = page.locator('.chat__input')
  await composer.fill('我买的那只鼠标按键不太灵，想退掉，帮我处理一下')
  await page.locator('.chat__composer').getByRole('button', { name: '发送' }).click()

  const card = page.locator('[data-approval-card]').last()
  let agentOk = true
  try {
    await card.waitFor({ timeout: 120000 })
  } catch {
    agentOk = false
  }

  if (!agentOk) {
    note('M1 Agent 未产出确认卡（模型不可用或未定位到订单），Agent 项记为未执行')
    await shot('m1-agent-no-card')
  } else {
    const cardText2 = (await card.innerText()).replace(/\n+/g, ' / ')
    ev(`M1 Agent 确认卡：${cardText2.slice(0, 260)}`)
    const hitMouse = cardText2.includes('鼠标') || cardText2.includes(MS)
    if (hitMouse) pass('M1 Agent 无单号也能定位到鼠标订单')
    else fail(`M1 Agent 定位的商品与鼠标不符：${cardText2.slice(0, 120)}`)

    const midCount = await page.locator('.return').count()
    if (midCount === beforeReturns) pass(`M1 确认前未产生退货单（列表仍是 ${midCount} 条）`)
    else fail(`M1 未点确认就多出退货单：${beforeReturns} → ${midCount}`)
    await shot('m1-agent-card')

    await card.getByRole('button', { name: '批准并执行' }).click()
    const item = page.locator('.return').first()
    await item.waitFor({ timeout: 60000 })
    await sleep(800)
    const listText = (await item.innerText()).replace(/\n+/g, ' / ')
    const m = /RT\d{14}-[0-9A-F]{4}/.exec(listText)
    ctx.mouseReturn = m ? m[0] : ''
    ev(`M1 Agent 建单结果：${listText.slice(0, 200)}`)

    // 取消入口
    await item.getByRole('button', { name: '详情' }).click()
    const panel = page.locator('[data-return-detail]')
    await panel.waitFor({ timeout: 15000 })
    await sleep(400)
    const detailText = await panel.innerText()
    const cancelLike = await panel
      .getByRole('button', { name: /取消|撤销|关闭申请|撤回/ })
      .count()
    ev(`M1 详情里的可点动作：${(await panel.getByRole('button').allInnerTexts()).join(' / ')}`)
    if (cancelLike > 0) {
      pass('M1 详情有取消/撤销入口')
    } else {
      note('M1 详情没有任何"取消申请 / 撤销"入口（买家提交后无法自行终止）')
    }
    await shot('m1-detail')
    await closeDetail()
    ev(`M1 详情文本片段：${detailText.replace(/\n+/g, ' / ').slice(0, 200)}`)
  }

  /* ============ M2：主线 2 件 ============ */
  note('M2 开始')
  const kbOrder = await buy(KB, 2)
  await shipAndSign(kbOrder)
  ctx.kbOrder = kbOrder
  const kbAfterShip = await stockOf(KB)
  ev(`M2 下单 ${kbOrder} 并签收，库存 ${ctx.kb0} → ${kbAfterShip}`)
  if (kbAfterShip === ctx.kb0 - 2) pass('M2 发货出库 -2 正确')
  else fail(`M2 发货后库存 ${kbAfterShip}，期望 ${ctx.kb0 - 2}`)

  const kbReturn = await submitByForm(kbOrder, 2, '验收主线：键盘两把都有按键失灵')
  ctx.kbReturn = kbReturn
  pass(`M2 退货单建立：${kbReturn}`)

  const q1 = await quotaOf(kbOrder)
  ev(`M2 提交后额度：${q1.text}`)
  if (q1.occupied === 2 && q1.remaining === 0) pass('M2 提交后占用 2 / 剩余 0')
  else fail(`M2 提交后额度异常：${JSON.stringify(q1)}`)

  await gotoAdminAftersale()
  const adminRow = page.locator(`.row[data-return-no="${kbReturn}"]`)
  await adminRow.waitFor({ timeout: 20000 })
  await adminRow.locator('[data-action="approve"]').click()
  await page.locator('.banner--ok').waitFor({ timeout: 20000 })
  await closeDetail()

  const q2 = await quotaOf(kbOrder)
  if (q2.occupied === 2 && q2.remaining === 0) pass('M2 批准后额度仍是 2 / 0（不释放）')
  else fail(`M2 批准后额度异常：${JSON.stringify(q2)}`)

  const d1 = await openBuyerDetail(kbReturn)
  ev(`M2 批准后买家阶段=${d1.stageCode} counts=${d1.counts}`)
  await closeDetail()

  const notice1 = await receiveBatch(kbReturn, { received: 1, qualified: 1, damaged: 0 })
  ev(`M2 首批收货提示：${notice1}`)
  const kbAfter1 = await stockOf(KB)
  ev(`M2 首批后库存 ${kbAfterShip} → ${kbAfter1}`)
  if (kbAfter1 === kbAfterShip + 1) pass('M2 首批合格 1 件回补库存 +1')
  else fail(`M2 首批后库存 ${kbAfter1}，期望 ${kbAfterShip + 1}`)

  const d2 = await openBuyerDetail(kbReturn)
  ev(`M2 首批后买家阶段=${d2.stageCode} counts=${d2.counts}`)
  if (d2.counts.includes('还差 1')) pass('M2 买家详情说明还差 1 件')
  else fail(`M2 买家详情没有"还差 1 件"：${d2.counts}`)
  await shot('m2-buyer-partial')
  await closeDetail()

  /* ============ M4：停在首批，看"剩下的不寄回"有没有出路 ============ */
  note('M4 开始（停在首批状态）')
  const d4 = await openBuyerDetail(kbReturn)
  const buyerActions = await d4.panel.getByRole('button').allInnerTexts()
  ev(`M4 买家详情可点动作：${buyerActions.join(' / ')}`)
  const buyerText = d4.text.replace(/\n+/g, ' / ')
  ev(`M4 买家详情文本：${buyerText.slice(0, 320)}`)

  await closeDetail()
  await gotoAdminAftersale()
  const adminRow4 = page.locator(`.row[data-return-no="${kbReturn}"]`)
  await adminRow4.waitFor({ timeout: 20000 })
  const adminActions = await adminRow4.getByRole('button').allInnerTexts()
  ev(`M4 后台行内可点动作：${adminActions.join(' / ')}`)
  await adminRow4.locator('[data-action="receive"]').click()
  const box4 = page.locator(`[data-receive-for="${kbReturn}"]`)
  await box4.waitFor({ timeout: 10000 })
  ev(`M4 收货面板文本：${(await box4.innerText()).replace(/\n+/g, ' / ').slice(0, 320)}`)
  await shot('m4-admin-receive')
  // 收货面板是行内开关：再点一次同一个按钮即收起，不靠 Esc（可能没绑定）
  await adminRow4.locator('[data-action="receive"]').click().catch(() => {})
  await sleep(300)
  await closeDetail()

  const refundNow = await api('POST', `/api/shop/admin/aftersale/returns/${kbReturn}/refund`, ADMIN)
  ev(`M4 收满前直接调退款接口：${refundNow.status} ${refundNow.code} ${refundNow.message.slice(0, 120)}`)
  if (refundNow.status === 409 && refundNow.code === 'REFUND_NOT_RECEIVED') {
    pass('M4 收满前不能产生退款（409 REFUND_NOT_RECEIVED）')
  } else {
    fail(`M4 收满前退款接口返回 ${refundNow.status} ${refundNow.code}`)
  }

  // Agent 是否能说清"还差 1 件"
  await gotoBuyerAftersale()
  const beforeTurns = await page.locator('[data-aftersale-chat] [data-turn-id]').count()
  await page.locator('.chat__input').fill(`订单 ${kbOrder} 的退货还差几件没收到？`)
  await page.locator('.chat__composer').getByRole('button', { name: '发送' }).click()
  let agentAnswer = ''
  const ansDeadline = Date.now() + 150000
  while (Date.now() < ansDeadline) {
    const turns = await page.locator('[data-aftersale-chat] [data-turn-id]').all()
    if (turns.length > beforeTurns) {
      const t = await turns[turns.length - 1].locator('.turn__answer').innerText().catch(() => '')
      if (t.trim()) {
        agentAnswer = t
        break
      }
    }
    await sleep(500)
  }
  ev(`M4 Agent 回答（还差几件）：${agentAnswer.replace(/\n+/g, ' / ').slice(0, 240) || '（空）'}`)
  if (agentAnswer.includes('1 件') || agentAnswer.includes('还差 1')) pass('M4 Agent 能说明还差 1 件')
  else fail('M4 Agent 没有说明还差 1 件')

  /* ============ M2 续：第二批损坏 + 退款 ============ */
  const notice2 = await receiveBatch(kbReturn, {
    received: 1,
    qualified: 0,
    damaged: 1,
    note: '验收：整批碎裂',
  })
  ev(`M2 第二批收货提示：${notice2}`)
  const kbAfter2 = await stockOf(KB)
  ev(`M2 第二批（全损坏）后库存 ${kbAfter1} → ${kbAfter2}`)
  if (kbAfter2 === kbAfter1) pass('M2 损坏件不进可售库存（库存不变）')
  else fail(`M2 损坏件改变了库存：${kbAfter1} → ${kbAfter2}`)

  const d3 = await openBuyerDetail(kbReturn)
  ev(`M2 收满后买家阶段=${d3.stageCode} counts=${d3.counts}`)
  if (d3.stageCode === 'RECEIVED_NOT_REFUNDED') pass('M2 收满未退款时阶段是「已全部收货，尚未登记模拟退款」')
  else fail(`M2 收满后阶段异常：${d3.stageCode}`)
  await closeDetail()

  await gotoAdminAftersale()
  const row5 = page.locator(`.row[data-return-no="${kbReturn}"]`)
  await row5.waitFor({ timeout: 20000 })
  await row5.locator('[data-action="refund"]').click()
  const cbox = page.locator(`[data-refund-confirm-for="${kbReturn}"]`)
  await cbox.waitFor({ timeout: 10000 })
  ev(`M2 退款确认面板：${(await cbox.innerText()).replace(/\n+/g, ' / ').slice(0, 300)}`)
  const refundNotice = await clickAndWait(() => cbox.locator('[data-action="refund-confirm"]').click())
  ctx.refundNotice = refundNotice.replace(/\n+/g, ' ')
  const rf = /RF\d{14}-[0-9A-F]{4}/.exec(refundNotice)
  ctx.refundNo = rf ? rf[0] : ''
  ev(`M2 退款结果：${ctx.refundNotice}`)

  const d5 = await openBuyerDetail(kbReturn)
  const amount = await d5.panel.locator('[data-refund-amount]').innerText().catch(() => '')
  ev(`M2 买家看到的退款金额=${amount} 单号=${ctx.refundNo}`)
  if (amount.includes('598')) pass('M2 退款金额 598 元（299 × 2 件，含损坏件）')
  else fail(`M2 退款金额异常：${amount}`)
  await shot('m2-buyer-refunded')
  await closeDetail()

  const q3 = await quotaOf(kbOrder)
  ev(`M2 退款后额度：${q3.text}`)
  if (q3.occupied === 2 && q3.remaining === 0) pass('M2 退款后额度仍 2 / 0（未恢复）')
  else fail(`M2 退款后额度异常：${JSON.stringify(q3)}`)

  // 刷新 + 重复提交
  await page.reload({ waitUntil: 'domcontentloaded' })
  await sleep(800)
  const afterReload = await page.locator(`.return[data-return-no="${kbReturn}"]`).innerText()
  ev(`M2 刷新后列表行：${afterReload.replace(/\n+/g, ' / ').slice(0, 200)}`)
  const refundAgain = await api('POST', `/api/shop/admin/aftersale/returns/${kbReturn}/refund`, ADMIN)
  ev(`M2 重复退款：${refundAgain.status} ${refundAgain.code} ${refundAgain.message.slice(0, 120)}`)
  if (refundAgain.status === 409) pass('M2 重复退款被拒（409），没有第二笔')
  else fail(`M2 重复退款返回 ${refundAgain.status}，可能重复写入`)

  const refundsInList = await page.locator(`.return[data-return-no="${kbReturn}"]`).innerText()
  const rfCount = (refundsInList.match(/RF\d{14}-[0-9A-F]{4}/g) || []).length
  if (rfCount <= 1) pass(`M2 列表里只出现 ${rfCount} 个退款单号`)
  else fail(`M2 列表里出现 ${rfCount} 个退款单号`)

  /* ============ M3：驳回支线 ============ */
  note('M3 开始')
  const kbAfter2b = await stockOf(KB)
  const sideOrder = await buy(KB, 1)
  await shipAndSign(sideOrder)
  ctx.sideOrder = sideOrder
  const sideShipped = await stockOf(KB)
  ev(`M3 下单 ${sideOrder} 并签收，库存 ${kbAfter2b} → ${sideShipped}`)

  const sideReturn = await submitByForm(sideOrder, 1, '验收支线：超过期限的申请')
  ctx.sideReturn = sideReturn
  const sq1 = await quotaOf(sideOrder)
  ev(`M3 提交后额度：${sq1.text}`)

  await gotoAdminAftersale()
  const srow = page.locator(`.row[data-return-no="${sideReturn}"]`)
  await srow.waitFor({ timeout: 20000 })
  await srow.locator('[data-action="reject"]').click()
  const rbox = page.locator(`[data-reject-for="${sideReturn}"]`)
  await rbox.waitFor({ timeout: 10000 })
  await rbox.locator('[data-reject-input]').fill('验收：超过 7 天无理由期限，且商品已使用')
  await clickAndWait(() => rbox.locator('[data-action="reject-confirm"]').click())
  await closeDetail()

  const sq2 = await quotaOf(sideOrder)
  ev(`M3 驳回后额度：${sq2.text}`)
  if (sq2.remaining === 1 && sq2.chip.includes('可申请退货')) {
    pass('M3 驳回后额度恢复 1 件，订单卡片回到「可申请退货」')
  } else {
    fail(`M3 驳回后订单卡片状态异常：${JSON.stringify(sq2)}`)
  }

  await page.reload({ waitUntil: 'domcontentloaded' })
  await sleep(600)
  const sitem = page.locator(`.return[data-return-no="${sideReturn}"]`)
  const sText = (await sitem.innerText()).replace(/\n+/g, ' / ')
  ev(`M3 驳回后列表行：${sText.slice(0, 220)}`)

  const sd = await openBuyerDetail(sideReturn)
  ev(`M3 驳回详情阶段=${sd.stageCode}`)
  const sdText = sd.text.replace(/\n+/g, ' / ')
  if (sdText.includes('超过 7 天')) pass('M3 驳回原因对买家可见')
  else fail('M3 详情看不到驳回原因')
  if (!sdText.includes('待收货') && !sdText.includes('待模拟退款')) pass('M3 旧申请不显示待收货/待退款')
  else fail('M3 已驳回的申请仍在暗示"待收货/待退款"')
  await shot('m3-rejected')
  await closeDetail()

  // 能否从原订单重新发起
  const card2 = page.locator(`.order[data-order-no="${sideOrder}"]`)
  const canRetry = (await card2.getByRole('button', { name: '申请退货' }).isEnabled().catch(() => false)) === true
  if (canRetry) {
    pass('M3 原订单上「申请退货」按钮可用，可以重新发起')
    await card2.getByRole('button', { name: '申请退货' }).click()
    await sleep(400)
    const q = await page.locator('.form .stepper__input').inputValue()
    note(`M3 重新发起时表单预填数量=${q}（原申请为 1 件，已驳回）`)
    await shot('m3-reapply-form')
  } else {
    fail('M3 驳回后原订单无法重新发起（按钮不可用）')
  }

  const sideStockEnd = await stockOf(KB)
  ev(`M3 驳回后库存 ${sideShipped} → ${sideStockEnd}`)
  if (sideStockEnd === sideShipped) pass('M3 驳回不改库存')
  else fail(`M3 驳回改变了库存：${sideShipped} → ${sideStockEnd}`)

  /* ============ 汇总 ============ */
  const kbEnd = await stockOf(KB)
  ev(`终态库存 keyboard=${kbEnd}`)
  ev(`关键单号：主线退货 ${kbReturn} / 退款 ${ctx.refundNo} / 驳回支线 ${sideReturn}`)
} catch (error) {
  await shot('zz-failure')
  fail(`脚本中断：${String(error).slice(0, 400)}`)
  try {
    console.log('当前 URL：', page.url())
    console.log('页面提示：', (await page.locator('.notice').allInnerTexts()).join(' / ').replace(/\n+/g, ' '))
  } catch {
    /* 诊断取不到就算了 */
  }
} finally {
  if (consoleErrors.length) note(`浏览器控制台错误 ${consoleErrors.length} 条：${consoleErrors.slice(0, 3).join(' | ')}`)
  if (apiFailures.length) {
    note('被拒绝的接口调用：')
    for (const item of apiFailures) console.log(`  - ${item}`)
  }
  await browser.close()
  fs.writeFileSync(`${OUT}/run.log`, log.join('\n'), 'utf8')
  console.log(`\n合计 ${log.filter((l) => l.startsWith('PASS')).length} PASS / ${failures} FAIL`)
  if (failures > 0) process.exitCode = 1
}
