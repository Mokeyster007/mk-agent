/**
 * B3 页面验收：模拟退款（一条完整闭环）。
 *
 * 链路：买 2 件 → 发货签收（库存 8）→ 售后 Agent 申请退 2 件 → 客服批准
 *      → 第一批收 1 件合格（库存 9）→ 第二批收 1 件损坏（库存仍 9）
 *      → 生成一笔模拟退款（金额 = 原成交单价 × 2 = ¥598.00）
 *      → 买家刷新：看到模拟退款，额度仍被占着。
 *
 * 断言的重点：
 * - **未收满不能退**：界面没有按钮，直接调接口也是 409 REFUND_NOT_RECEIVED；
 * - **只留一笔**：重复调用是 409 REFUND_ALREADY_EXISTS 且带原单号；
 * - **金额按累计实收**：1 合格 + 1 损坏 → 仍按 2 件结算 ¥598.00；
 * - **退款不动库存、不写流水、不释放额度**；
 * - **文案不撒谎**：页面只说"模拟退款"，不出现"已退款 / 已到账"。
 *
 * 用法（后端 demo + 前端 dev 都启动后）：
 *   node tools/e2e-aftersale-refund.mjs
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'

const WEB = process.env.WEB_BASE || 'http://localhost:5173'
const OUT = 'target/e2e-aftersale-refund'
const SKU = 'SKU-KEYBOARD-K87'
const BUYER = 'shop-user-1'
const ADMIN = 'music-admin-1'

/** 种子商品成交单价 29900 分 → 退 2 件应为 ¥598.00 */
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

async function waitForText(locator, needle, timeout = 20000) {
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

/** 记下所有 4xx/5xx 的业务响应体（失败时能直接看出被哪条规则拒绝）。 */
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

/**
 * 点一个写操作按钮，等到它的**真实结果**。
 *
 * 不能只等 `.banner--ok` 出现：上一次的成功提示可能还挂着，
 * 那样即使本次失败也会立刻"通过"。所以要等提示文案**变了**。
 */
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
      `环境不满足：${SKU} 当前可售 ${available} 件，本脚本至少要 ${min} 件。` +
        ' 请换一个全新 H2 库重跑。',
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

async function movementCount() {
  return page.locator('.moves__item').count()
}

async function inboundMovements() {
  return page.locator('.moves__item[data-movement-type="RETURN_INBOUND"]').count()
}

async function openBuyerAftersale() {
  await closeDetailIfOpen()
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(600)
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后与退货' }).click()
  await page.waitForURL('**/shop/aftersale', { timeout: 10000 })
}

/**
 * 用售后 Agent 提交退货申请（买家在确认卡上批准后才真正建单）。
 *
 * 模型可能先追问，所以给第二次更明确的说法；两次都没出卡就返回 null，
 * 由调用方决定是否退回普通表单——**不静默重试**，路径会如实记录。
 */
async function submitReturnByAgent(orderNo, quantity, reason) {
  await openBuyerAftersale()
  const orderRow = page.locator(`.order[data-order-no="${orderNo}"]`)
  await orderRow.waitFor({ timeout: 20000 })

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

  const item = page.locator('.return').first()
  await item.waitFor({ timeout: 90000 })
  const text = await item.innerText()
  const match = /RT\d{14}-[0-9A-F]{4}/.exec(text)
  return match ? match[0] : ''
}

/** 退回普通表单提交（Agent 不可用时的兜底，路径会如实记录）。 */
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
  const close = page.locator('[data-admin-return-detail] .panel__close')
  if (await close.count()) {
    await close.click()
    await sleep(300)
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

/** 直接调接口发起退款（用于验证后端规则，不经过界面）。 */
async function refundViaApi(returnNo) {
  const response = await context.request.post(
    `${WEB}/api/shop/admin/aftersale/returns/${encodeURIComponent(returnNo)}/refund`,
    { headers: { 'X-User-Id': ADMIN, 'Content-Type': 'application/json' } },
  )
  let body = {}
  try {
    body = await response.json()
  } catch {
    body = {}
  }
  return { status: response.status(), body }
}

try {
  /* ---------------- 0. 前置条件 ---------------- */
  const stock0 = await requireStock(2)
  check('前置条件：可售库存够买 2 件', stock0 >= 2, `可售 ${stock0} 件`)
  check('前置条件：这是一套全新库（可售 10 件，链路数字可核对）', stock0 === 10,
    `可售 ${stock0} 件（不是 10 也能跑，但下面的 8/9 只按相对变化断言）`)

  /* ---------------- 1. 买 2 件 → 发货签收 ---------------- */
  const orderNo = await placeOrder(2)
  check('下单成功（2 件）', Boolean(orderNo), orderNo)
  await shipAndSign(orderNo)

  await openAdminShop()
  const stockAfterShip = await stockNow()
  const movesAfterShip = await movementCount()
  check('发货后库存 -2（10 → 8）', stockAfterShip === stock0 - 2,
    `before=${stock0} after=${stockAfterShip}`)
  check('发货后还没有退货入库流水', (await inboundMovements()) === 0)

  /* ---------------- 2. 售后 Agent 申请退 2 件 ---------------- */
  const agentReturnNo = await submitReturnByAgent(orderNo, 2, '键盘按键失灵')
  let returnNo = agentReturnNo
  let path = '售后 Agent'

  if (!returnNo) {
    check('售后 Agent 提交退货申请（确认卡 → 建单）', false,
      '两次都没有出现确认卡，退回普通表单继续验证退款链路')
    returnNo = await submitReturnByForm(orderNo, 2, 'B3 模拟退款案例：键盘按键失灵')
    path = '普通表单（Agent 兜底）'
  } else {
    check('售后 Agent 提交退货申请（确认卡 → 建单）', true, returnNo)
  }

  check(`退货单已建立（路径：${path}）`, Boolean(returnNo), returnNo)

  const returnItem = page.locator(`.return[data-return-no="${returnNo}"]`)
  await returnItem.waitFor({ timeout: 20000 })
  const itemText = await returnItem.innerText()
  check('退货单状态是「已提交」', itemText.includes('已提交'),
    itemText.replace(/\n/g, ' / '))
  check('退货单来源标注与实际路径一致',
    path.startsWith('售后 Agent') ? itemText.includes('售后 Agent') : itemText.includes('普通表单'),
    itemText.replace(/\n/g, ' / '))

  const quota1 = await quotaOf(orderNo)
  check('提交后额度 2/0（2 件全部占用）',
    quota1.occupied === 2 && quota1.remaining === 0, JSON.stringify(quota1))

  await page.screenshot({ path: `${OUT}/01-submitted.png` })

  /* ---------------- 3. 客服批准 ---------------- */
  await openAdminAftersale()
  const row = page.locator(`.row[data-return-no="${returnNo}"]`)
  await row.waitFor({ timeout: 20000 })
  await row.locator('[data-action="approve"]').click()
  await page.locator('.banner--ok').waitFor({ timeout: 20000 })
  const approveNotice = await page.locator('.banner--ok').innerText()
  check('批准成功且提示写明保留额度', approveNotice.includes('已批准'),
    approveNotice.replace(/\n/g, ' '))

  await closeDetailIfOpen()
  const rowAfterApprove = await waitForText(row, '未收货')
  check('批准后进度是「未收货 0/2」',
    rowAfterApprove.includes('未收货') && rowAfterApprove.includes('0/2'),
    rowAfterApprove.replace(/\n/g, ' / '))

  /* ---------------- 4. 未收满：不能退款 ---------------- */
  check('未收满时行内没有「模拟退款」按钮',
    (await row.locator('[data-action="refund"]').count()) === 0)

  const earlyRefund = await refundViaApi(returnNo)
  check('未收满直接调接口也是 409 REFUND_NOT_RECEIVED',
    earlyRefund.status === 409 && earlyRefund.body?.code === 'REFUND_NOT_RECEIVED',
    `status=${earlyRefund.status} code=${earlyRefund.body?.code}`)
  check('被拒绝的退款没有留下记录（详情里没有退款块）',
    (await row.locator('[data-refund-for]').count()) === 0)

  /* ---------------- 5. 第一批：1 件合格 → 库存 +1 ---------------- */
  const notice1 = await receiveBatch(returnNo, { received: 1, qualified: 1, damaged: 0 })
  check('第一批收货：合格 1 件回补库存',
    notice1.includes('合格 1 件已回补可售库存') && notice1.includes('部分收货（1/2）'),
    notice1.replace(/\n/g, ' '))

  await openAdminShop()
  const stockAfter1 = await stockNow()
  check('第一批后库存 +1（8 → 9）', stockAfter1 === stockAfterShip + 1,
    `afterShip=${stockAfterShip} after=${stockAfter1}`)

  /* ---------------- 6. 第二批：1 件损坏 → 库存不变 ---------------- */
  await openAdminAftersale()
  const row2 = page.locator(`.row[data-return-no="${returnNo}"]`)
  await row2.waitFor({ timeout: 20000 })
  const notice2 = await receiveBatch(returnNo, {
    received: 1,
    qualified: 0,
    damaged: 1,
    note: '整批碎裂，无法二次销售',
  })
  check('第二批收货：全部损坏、未加库存',
    notice2.includes('全部损坏') && notice2.includes('未增加可售库存') &&
      notice2.includes('全部收货（2/2）'),
    notice2.replace(/\n/g, ' '))

  await openAdminShop()
  const stockAfter2 = await stockNow()
  const movesAfter2 = await movementCount()
  const inboundAfter2 = await inboundMovements()
  check('第二批损坏后库存仍是 9', stockAfter2 === stockAfter1,
    `before=${stockAfter1} after=${stockAfter2}`)
  check('全库只有 1 条退货入库流水（损坏件没有流水）', inboundAfter2 === 1,
    `inbound=${inboundAfter2}`)

  /* ---------------- 7. 收满后可退款：确认面板 ---------------- */
  await openAdminAftersale()
  const row3 = page.locator(`.row[data-return-no="${returnNo}"]`)
  await row3.waitFor({ timeout: 20000 })
  await closeDetailIfOpen()

  const refundBtn = row3.locator('[data-action="refund"]')
  check('收满后行内出现「模拟退款」按钮',
    (await refundBtn.count()) === 1 && (await refundBtn.innerText()).includes('模拟退款'),
    (await refundBtn.count()) === 1 ? await refundBtn.innerText() : 'button missing')

  await refundBtn.click()
  const confirmBox = page.locator(`[data-refund-confirm-for="${returnNo}"]`)
  await confirmBox.waitFor({ timeout: 10000 })
  const confirmText = await confirmBox.innerText()

  check('确认面板写明金额按「成交单价 × 累计实收」计算',
    confirmText.includes('成交单价') && confirmText.includes('累计实收') &&
      confirmText.includes('2'),
    confirmText.replace(/\n/g, ' / ').slice(0, 240))
  check('确认面板写明这是模拟退款、不接支付平台',
    confirmText.includes('模拟退款') && confirmText.includes('不接支付平台'),
    confirmText.replace(/\n/g, ' / ').slice(0, 240))
  check('确认面板不承诺到账', !confirmText.includes('到账'),
    confirmText.replace(/\n/g, ' / ').slice(0, 240))

  await page.screenshot({ path: `${OUT}/02-refund-confirm.png` })

  /* ---------------- 8. 确认退款 ---------------- */
  const refundNotice = await clickAndWait(() =>
    confirmBox.locator('[data-action="refund-confirm"]').click(),
  )
  const refundNo = (/RF\d{14}-[0-9A-F]{4}/.exec(refundNotice) || [])[0] || ''
  check('退款成功提示带真实退款单号', Boolean(refundNo), refundNotice.replace(/\n/g, ' '))
  check('提示金额是成交单价 × 2 = ¥598.00', refundNotice.includes(EXPECTED_REFUND_TEXT),
    refundNotice.replace(/\n/g, ' '))
  check('提示写明库存与额度未变',
    refundNotice.includes('库存') && refundNotice.includes('额度'),
    refundNotice.replace(/\n/g, ' '))
  check('提示不出现「到账」', !refundNotice.includes('到账'),
    refundNotice.replace(/\n/g, ' '))

  await closeDetailIfOpen()
  const rowAfterRefund = await waitForText(row3, '模拟退款')
  check('行内变成「模拟退款 ¥598.00」且按钮消失',
    rowAfterRefund.includes(EXPECTED_REFUND_TEXT) &&
      (await row3.locator('[data-action="refund"]').count()) === 0,
    rowAfterRefund.replace(/\n/g, ' / '))

  await page.screenshot({ path: `${OUT}/03-refund-done.png` })

  /* ---------------- 9. 重复退款：只留一笔 ---------------- */
  const dup = await refundViaApi(returnNo)
  check('重复退款是 409 REFUND_ALREADY_EXISTS',
    dup.status === 409 && dup.body?.code === 'REFUND_ALREADY_EXISTS',
    `status=${dup.status} code=${dup.body?.code}`)
  check('409 消息里带着已经存在的那张退款单号',
    Boolean(refundNo) && String(dup.body?.message || '').includes(refundNo),
    String(dup.body?.message || '').slice(0, 160))

  /* ---------------- 10. 退款前后：库存与流水不变 ---------------- */
  await openAdminShop()
  const stockAfterRefund = await stockNow()
  const movesAfterRefund = await movementCount()
  const inboundAfterRefund = await inboundMovements()
  check('退款没有改动可售库存（仍 9）', stockAfterRefund === stockAfter2,
    `before=${stockAfter2} after=${stockAfterRefund}`)
  check('退款没有写第二条入库流水', inboundAfterRefund === inboundAfter2,
    `before=${inboundAfter2} after=${inboundAfterRefund}`)
  check('退款没有产生任何新流水', movesAfterRefund === movesAfter2,
    `before=${movesAfter2} after=${movesAfterRefund}`)

  await page.screenshot({ path: `${OUT}/04-inventory-unchanged.png` })

  /* ---------------- 11. 客服详情：只有一笔退款 ---------------- */
  await openAdminAftersale()
  const row4 = page.locator(`.row[data-return-no="${returnNo}"]`)
  await row4.waitFor({ timeout: 20000 })
  await closeDetailIfOpen()
  await row4.locator('.row__no').click()

  const adminPanel = page.locator('[data-admin-return-detail]')
  await adminPanel.waitFor({ timeout: 15000 })
  await waitForText(adminPanel, '模拟退款')

  check('客服详情里只有一笔退款记录',
    (await adminPanel.locator('[data-refund]').count()) === 1,
    `count=${await adminPanel.locator('[data-refund]').count()}`)
  const adminPanelText = await adminPanel.innerText()
  check('客服详情显示退款金额 ¥598.00',
    (await adminPanel.locator('[data-refund-amount]').innerText()).includes(EXPECTED_REFUND_TEXT),
    await adminPanel.locator('[data-refund-amount]').innerText())
  check('客服详情写明退款不动库存与额度',
    adminPanelText.includes('库存') && adminPanelText.includes('额度'),
    adminPanelText.replace(/\n/g, ' / ').slice(0, 240))
  check('客服详情不出现「已到账」', !adminPanelText.includes('到账'),
    adminPanelText.replace(/\n/g, ' / ').slice(0, 240))

  await page.screenshot({ path: `${OUT}/05-admin-detail.png` })
  await adminPanel.getByRole('button', { name: '关闭详情' }).click()

  /* ---------------- 12. 买家视角：只读、额度仍被占用 ---------------- */
  await openBuyerAftersale()
  await page.reload({ waitUntil: 'domcontentloaded' })

  const quota2 = await quotaOf(orderNo)
  check('退款后额度仍然 2/0（退款不释放可退额度）',
    quota2.occupied === 2 && quota2.remaining === 0, JSON.stringify(quota2))

  const buyerItem = page.locator(`.return[data-return-no="${returnNo}"]`)
  await buyerItem.waitFor({ timeout: 20000 })
  const buyerItemText = await waitForText(buyerItem, '模拟退款')
  check('买家列表显示「模拟退款 ¥598.00」',
    buyerItemText.includes('模拟退款') && buyerItemText.includes(EXPECTED_REFUND_TEXT),
    buyerItemText.replace(/\n/g, ' / '))
  check('买家列表说明不会真的打款', buyerItemText.includes('不会真的打款'),
    buyerItemText.replace(/\n/g, ' / '))

  await buyerItem.getByRole('button', { name: '详情' }).click()
  const buyerPanel = page.locator('[data-return-detail]')
  await buyerPanel.waitFor({ timeout: 15000 })
  await waitForText(buyerPanel, '模拟退款')

  const buyerPanelText = await buyerPanel.innerText()
  check('买家详情里只有一笔退款',
    (await buyerPanel.locator('[data-refund]').count()) === 1,
    `count=${await buyerPanel.locator('[data-refund]').count()}`)
  check('买家详情写明不需要提供账户信息',
    buyerPanelText.includes('不需要你提供任何账户信息'),
    buyerPanelText.replace(/\n/g, ' / ').slice(0, 240))
  check('买家详情不出现「已退款 / 已到账」',
    !buyerPanelText.includes('已退款') && !buyerPanelText.includes('到账'),
    buyerPanelText.replace(/\n/g, ' / ').slice(0, 240))
  check('买家侧没有退款按钮（写权限只在客服）',
    (await buyerPanel.locator('[data-action="refund"]').count()) === 0)

  await page.screenshot({ path: `${OUT}/06-buyer-sees-refund.png` })
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
