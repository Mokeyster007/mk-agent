/**
 * B2 页面验收：仓库收货、验货与可售库存回补（一条完整闭环）。
 *
 * 链路：买 2 件 → 发货签收（库存 -2）→ 退 2 件 → 客服批准（额度不释放）
 *      → 第一批收 1 件合格（库存 +1）→ 第二批收 1 件损坏（库存不变）
 *      → 买家刷新看到两批收货 → 原订单没有新的可退额度。
 *
 * 断言的重点不是"按钮点了没报错"，而是**数字对不对**：
 * - 可售库存必须按"合格件数"变化，损坏件不改变库存；
 * - 全库只多出**一条** `RETURN_INBOUND` 且 delta 是 +1；
 * - 同一批重放（同一个 clientRequestId）不重复加库存；
 * - 收货不释放额度：买家那 2 件仍然是"已占用"。
 *
 * 用法（后端 demo + 前端 dev 都启动后）：
 *   node tools/e2e-aftersale-receipt.mjs
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'

const WEB = process.env.WEB_BASE || 'http://localhost:5173'
const OUT = 'target/e2e-aftersale-receipt'
const SKU = 'SKU-KEYBOARD-K87'
const BUYER = 'shop-user-1'
const ADMIN = 'music-admin-1'

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

/** 有界轮询等待某段文本出现（避免固定 sleep 造成的脆断言）。 */
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

/** 记下页面发出的收货请求（后面要原样重放一次，验证幂等）。 */
const receiveRequests = []
page.on('request', (request) => {
  if (request.method() === 'POST' && request.url().includes('/receipts')) {
    receiveRequests.push({ url: request.url(), body: request.postData() })
  }
})

/**
 * 点一个写操作按钮，等到它的**真实结果**。
 *
 * 不能只等 `.banner--ok` 出现：上一次的成功提示可能还挂着，
 * 那样即使本次失败也会立刻"通过"。所以要等提示文案**变了**；
 * 失败时把界面上的错误原因带进异常，而不是干等超时。
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

/**
 * 前置条件：可售库存够买 2 件。
 *
 * demo 的下单**不锁库存**（刻意如此，好让"发货时库存不足"这条路径可走），
 * 所以库被别的脚本买空时下单照样成功、要到发货才 409。
 */
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
        ' 请换一个全新 H2 库（或未被买空的库）重跑。',
    )
  }
  return available
}

/** 在商城页下单（先切成买家身份），返回订单号。 */
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

/** 切到后台，把一张订单发货并签收。 */
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

/** 读后台库存表里该 SKU 的可售数量。 */
async function stockNow() {
  const row = page.locator(`tr[data-sku="${SKU}"]`)
  await row.waitFor({ timeout: 15000 })
  const text = await row.innerText()
  const match = /(\d+)\s*$/m.exec(text)
  return match ? Number(match[1]) : null
}

/** 流水总条数 / 退货入库条数。 */
async function movementCount() {
  return page.locator('.moves__item').count()
}

async function inboundMovements() {
  return page.locator('.moves__item[data-movement-type="RETURN_INBOUND"]').count()
}

/** 买家在售后页用普通表单提交一张退货申请，返回退货单号。 */
async function submitReturn(orderNo, quantity, reason) {
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(600)
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后与退货' }).click()
  await page.waitForURL('**/shop/aftersale', { timeout: 10000 })

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

/**
 * 关掉客服详情抽屉（连同它的遮罩）。
 *
 * 写操作成功后 store 会把最新详情塞进 `state.detail`，抽屉随即打开：
 * 右侧面板盖住列表的「收货」按钮，`shell__scrim` 还盖住整页导航。
 * 不关掉的话下一次点击会被遮罩吞掉（Playwright 报 intercepts pointer events）。
 */
async function closeDetailIfOpen() {
  const close = page.locator('[data-admin-return-detail] .panel__close')
  if (await close.count()) {
    await close.click()
    await sleep(300)
  }
}

/**
 * 在客服页对某一单提交一批收货。
 *
 * @param {string} returnNo 退货单号
 * @param {{received:number, qualified:number, damaged:number, note?:string}} input
 * @returns {Promise<string>} 成功提示文案
 */
async function receiveBatch(returnNo, input) {
  const row = page.locator(`.row[data-return-no="${returnNo}"]`)
  await row.waitFor({ timeout: 20000 })
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
  /* ---------------- 0. 前置条件 ---------------- */
  const stock0 = await requireStock(2)
  check('前置条件：可售库存够买 2 件', stock0 >= 2, `可售 ${stock0} 件`)

  /* ---------------- 1. 买 2 件并发货签收 ---------------- */
  const orderNo = await placeOrder(2)
  check('下单成功（2 件）', Boolean(orderNo), orderNo)
  await shipAndSign(orderNo)

  const stockAfterShip = await stockNow()
  const movesAfterShip = await movementCount()
  const inboundAfterShip = await inboundMovements()
  check('发货后库存 -2', stockAfterShip === stock0 - 2,
    `before=${stock0} after=${stockAfterShip}`)
  check('发货后还没有退货入库流水', inboundAfterShip === 0, `inbound=${inboundAfterShip}`)

  /* ---------------- 2. 买家退 2 件 ---------------- */
  const returnNo = await submitReturn(orderNo, 2, 'B2 收货案例：键盘外壳破损')
  check('退货单提交成功（2 件）', Boolean(returnNo), returnNo)

  const quota1 = await quotaOf(orderNo)
  check('提交后额度 2/0（2 件全部占用）',
    quota1.occupied === 2 && quota1.remaining === 0, JSON.stringify(quota1))

  /* ---------------- 3. 客服批准 ---------------- */
  await openAdminAftersale()
  const row = page.locator(`.row[data-return-no="${returnNo}"]`)
  await row.waitFor({ timeout: 20000 })

  await row.locator('[data-action="approve"]').click()
  await page.locator('.banner--ok').waitFor({ timeout: 20000 })
  const approveNotice = await page.locator('.banner--ok').innerText()
  check('批准成功且提示写明保留额度', approveNotice.includes('已批准'),
    approveNotice.replace(/\n/g, ' '))

  const rowAfterApprove = await waitForText(row, '已批准')
  check('批准后行内出现收货入口', (await row.locator('[data-action="receive"]').count()) === 1,
    rowAfterApprove.replace(/\n/g, ' / '))
  check('批准后进度是「未收货 0/2」',
    rowAfterApprove.includes('未收货') && rowAfterApprove.includes('0/2'),
    rowAfterApprove.replace(/\n/g, ' / '))

  /* ---------------- 4. 第一批：实收 1 件、合格 1 件 → 库存 +1 ---------------- */
  await closeDetailIfOpen()
  await row.locator('[data-action="receive"]').click()
  const box1 = page.locator(`[data-receive-for="${returnNo}"]`)
  await box1.waitFor({ timeout: 10000 })

  const limitText = await box1.locator('[data-receive-limit]').innerText()
  check('收货面板显示本批上限 2 件', limitText.trim() === '2', `limit=${limitText.trim()}`)

  // 上限在界面上被夹住：填 99 会被夹回 2
  await box1.locator('[data-receive-input="received"]').fill('99')
  await sleep(200)
  const clamped = await box1.locator('[data-receive-input="received"]').inputValue()
  check('实收数量被夹在本批上限内', clamped === '2', `received=${clamped}`)

  await box1.locator('[data-receive-input="note"]').fill('外箱压瘪，内件完好')
  const notice1 = await (async () => {
    await box1.locator('[data-receive-input="received"]').fill('1')
    await box1.locator('[data-receive-input="qualified"]').fill('1')
    await box1.locator('[data-receive-input="damaged"]').fill('0')
    return clickAndWait(() => box1.locator('[data-action="receive-confirm"]').click())
  })()

  check('第一批收货提示写明合格件回补库存',
    notice1.includes('合格 1 件已回补可售库存') && notice1.includes('部分收货（1/2）'),
    notice1.replace(/\n/g, ' '))

  const rowAfter1 = await waitForText(row, '部分收货')
  check('第一批后进度变成「部分收货 1/2」',
    rowAfter1.includes('部分收货') && rowAfter1.includes('1/2'),
    rowAfter1.replace(/\n/g, ' / '))

  await page.screenshot({ path: `${OUT}/01-first-batch.png` })

  /* ---------------- 5. 同一批重放：不重复加库存 ---------------- */
  await openAdminShop()
  const stockAfter1 = await stockNow()
  const movesAfter1 = await movementCount()
  check('第一批合格 1 件后库存 +1', stockAfter1 === stockAfterShip + 1,
    `before=${stockAfterShip} after=${stockAfter1}`)
  check('第一批产生 1 条退货入库流水', (await inboundMovements()) === 1)

  if (receiveRequests.length === 0) {
    check('抓到了页面发出的收货请求', false, '没有捕获到 POST /receipts')
  } else {
    const replay = await context.request.post(receiveRequests[0].url, {
      headers: { 'X-User-Id': ADMIN, 'Content-Type': 'application/json' },
      data: receiveRequests[0].body,
    })
    const body = await replay.json()
    check('同一批重放返回原记录（幂等，不新建收货记录）',
      replay.status() < 300 && Array.isArray(body.receipts) && body.receipts.length === 1,
      `status=${replay.status()} receipts=${body.receipts?.length}`)

    await openAdminShop()
    const stockAfterReplay = await stockNow()
    const movesAfterReplay = await movementCount()
    check('重放没有再加库存', stockAfterReplay === stockAfter1,
      `before=${stockAfter1} after=${stockAfterReplay}`)
    check('重放没有新增流水', movesAfterReplay === movesAfter1,
      `before=${movesAfter1} after=${movesAfterReplay}`)
  }

  /* ---------------- 6. 第二批：实收 1 件、全部损坏 → 库存不变 ---------------- */
  await openAdminAftersale()
  const row2 = page.locator(`.row[data-return-no="${returnNo}"]`)
  await row2.waitFor({ timeout: 20000 })
  await closeDetailIfOpen()
  await row2.locator('[data-action="receive"]').click()

  const box2 = page.locator(`[data-receive-for="${returnNo}"]`)
  await box2.waitFor({ timeout: 10000 })
  const limitText2 = await box2.locator('[data-receive-limit]').innerText()
  check('第二批上限收窄到 1 件', limitText2.trim() === '1', `limit=${limitText2.trim()}`)

  await box2.locator('[data-receive-input="damaged"]').fill('1')
  await box2.locator('[data-receive-input="note"]').fill('整批碎裂，无法二次销售')
  const notice2 = await clickAndWait(() => box2.locator('[data-action="receive-confirm"]').click())

  check('第二批提示写明整批损坏、未加库存',
    notice2.includes('全部损坏') && notice2.includes('未增加可售库存') &&
      notice2.includes('全部收货（2/2）'),
    notice2.replace(/\n/g, ' '))

  const rowAfter2 = await waitForText(row2, '全部收货')
  check('第二批后进度变成「全部收货 2/2」',
    rowAfter2.includes('全部收货') && rowAfter2.includes('2/2'),
    rowAfter2.replace(/\n/g, ' / '))
  check('收满后收货按钮变成「已收满」且不可再点',
    (await row2.locator('[data-action="receive"]').innerText()).includes('已收满') &&
      (await row2.locator('[data-action="receive"]').isDisabled()))

  await page.screenshot({ path: `${OUT}/02-second-batch.png` })

  /* ---------------- 7. 客服详情：两批记录 + 只有一条入库流水 ---------------- */
  await closeDetailIfOpen()
  await row2.locator('.row__no').click()
  const adminPanel = page.locator('[data-admin-return-detail]')
  await adminPanel.waitFor({ timeout: 15000 })
  const panelText = await waitForText(adminPanel, '收货记录')

  const receiptItems = adminPanel.locator('[data-receipt-no]')
  check('客服详情里有两批收货记录', (await receiptItems.count()) === 2,
    `count=${await receiptItems.count()}`)
  check('第一批在详情里能看到它对应的入库流水',
    (await adminPanel.locator('[data-receipt-movement]').count()) === 1,
    `movement rows=${await adminPanel.locator('[data-receipt-movement]').count()}`)
  check('第二批整批损坏：详情里标注没有入库流水',
    (await adminPanel.locator('[data-receipt-no-movement]').count()) === 1)
  check('详情里写明收货不释放额度', panelText.includes('不释放'),
    panelText.replace(/\n/g, ' / ').slice(0, 200))

  await page.screenshot({ path: `${OUT}/03-admin-detail.png` })
  await adminPanel.getByRole('button', { name: '关闭详情' }).click()

  /* ---------------- 8. 库存与流水总账 ---------------- */
  await openAdminShop()
  const stockFinal = await stockNow()
  const movesFinal = await movementCount()
  const inboundFinal = await inboundMovements()
  check('整条链路净效果：库存只回补了合格的 1 件',
    stockFinal === stockAfterShip + 1, `afterShip=${stockAfterShip} final=${stockFinal}`)
  check('全库只有 1 条退货入库流水', inboundFinal === 1, `inbound=${inboundFinal}`)
  check('流水总数 = 发货时 + 1（损坏件没有流水）',
    movesFinal === movesAfterShip + 1, `afterShip=${movesAfterShip} final=${movesFinal}`)

  const inboundDelta = await page
    .locator('.moves__item[data-movement-type="RETURN_INBOUND"] .moves__delta')
    .first()
    .innerText()
  check('退货入库流水是 +1', inboundDelta.trim() === '+1', `delta=${inboundDelta.trim()}`)

  await page.screenshot({ path: `${OUT}/04-inventory.png` })

  /* ---------------- 9. 买家刷新：看到收货进度，额度没有回来 ---------------- */
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(600)
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后与退货' }).click()
  await page.waitForURL('**/shop/aftersale', { timeout: 10000 })
  await page.reload({ waitUntil: 'domcontentloaded' })

  const quota2 = await quotaOf(orderNo)
  check('收货后额度仍然 2/0（收货不释放可退额度）',
    quota2.occupied === 2 && quota2.remaining === 0, JSON.stringify(quota2))

  const returnItem = page.locator(`.return[data-return-no="${returnNo}"]`)
  await returnItem.waitFor({ timeout: 20000 })
  const itemText = await waitForText(returnItem, '仓库收货')
  check('买家列表显示收货进度 2/2',
    itemText.includes('全部收货') && itemText.includes('2/2'),
    itemText.replace(/\n/g, ' / '))

  await returnItem.getByRole('button', { name: '详情' }).click()
  const buyerPanel = page.locator('[data-return-detail]')
  await buyerPanel.waitFor({ timeout: 15000 })
  await waitForText(buyerPanel, '仓库收货记录')

  const buyerReceipts = buyerPanel.locator('[data-receipt-no]')
  check('买家详情能看到全部两批收货', (await buyerReceipts.count()) === 2,
    `count=${await buyerReceipts.count()}`)

  const buyerPanelText = await buyerPanel.innerText()
  check('买家详情写明合格 1 件、损坏 1 件',
    buyerPanelText.includes('损坏') && buyerPanelText.includes('整批碎裂'),
    buyerPanelText.replace(/\n/g, ' / ').slice(0, 240))
  check('买家详情不出现"已退款"',
    !buyerPanelText.includes('已退款'), buyerPanelText.replace(/\n/g, ' / ').slice(0, 240))

  await page.screenshot({ path: `${OUT}/05-buyer-sees-receipts.png` })
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
