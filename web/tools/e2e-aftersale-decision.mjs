/**
 * B1 页面验收：客服批准 / 驳回，以及买家刷新后看到结果。
 *
 * 两个独立案例，刻意覆盖两种额度走向：
 *   A 案：买 2 件、退 1 件 → 客服**驳回** → 额度 1 → 2（释放）
 *   B 案：买 1 件、退 1 件 → 客服**批准** → 额度维持 1（保留）
 *
 * 同时断言：
 * - 两种操作都不改可售库存、不写库存流水；
 * - 买家（只有商城身份、没有音乐身份）能提交、能刷新看到决策结果；
 * - 普通买家打开客服页看不到任何单、也点不到按钮（权限在服务端）。
 *
 * 用法（后端 demo + 前端 dev 都启动后）：
 *   node tools/e2e-aftersale-decision.mjs
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'

const WEB = process.env.WEB_BASE || 'http://localhost:5173'
const OUT = 'target/e2e-aftersale-decision'
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

/**
 * 记下所有 4xx/5xx 的业务响应体。
 *
 * 为什么要这个：只看"控制台有 409"没法定位是哪次请求、被哪条规则拒绝。
 * 把 URL 与响应体里的 code 打出来，失败时能直接看出是环境状态还是功能回归。
 */
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
 * 两个细节决定了这条断言是不是真的：
 * 1. 不能只等 `.banner--ok` 出现——上一次操作的成功提示可能还挂着，
 *    那样即使本次请求失败也会立刻"通过"。所以要等提示文案**变了**；
 * 2. 失败时必须把界面上的错误原因带进异常，而不是干等 20 秒超时
 *    （例如"库存不足"只会显示在错误提示里，超时信息完全看不出原因）。
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
 * 前置条件：可售库存够本脚本买 2 + 1 件。
 *
 * demo 的下单**不锁库存**（刻意如此，好让"发货时库存不足"这条路径可走），
 * 所以库被前面几个脚本买空时，下单照样成功、要到发货才 409。
 * 与其让它在第 3 步抛一个看不懂的超时，不如一开始就说清楚。
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
        ' demo 下单不锁库存、发货才扣库存，所以库被别的脚本买空后会在发货时 409。' +
        ' 请换一个全新 H2 库（或未买空的库）重跑。',
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

/** 读流水条数。 */
async function movementCount() {
  return page.locator('.moves__item').count()
}

/** 买家在售后页用普通表单提交一张退货申请，返回退货单号。 */
async function submitReturn(orderNo, reason) {
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(600)
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后与退货' }).click()
  await page.waitForURL('**/shop/aftersale', { timeout: 10000 })

  const orderRow = page.locator(`.order[data-order-no="${orderNo}"]`)
  await orderRow.waitFor({ timeout: 20000 })

  const form = page.locator('.form')
  await form.locator('select').selectOption(orderNo)
  await form.locator('.stepper__input').fill('1')
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
  await page.selectOption('.shop-nav__select', ADMIN)
  await sleep(600)
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后处理' }).click()
  await page.waitForURL('**/shop/admin/aftersale', { timeout: 10000 })
  await page.locator('.rows, .muted').first().waitFor({ timeout: 20000 })
}

try {
  /* ---------------- 0. 前置条件：库存够走完两个案例 ---------------- */
  const stock0 = await requireStock(3)
  check('前置条件：可售库存够走完 2 + 1 件', stock0 >= 3, `可售 ${stock0} 件`)

  /* ---------------- 1. 两单：A 买 2 件，B 买 1 件 ---------------- */
  const orderA = await placeOrder(2)
  check('A 案下单成功（2 件）', Boolean(orderA), orderA)
  await shipAndSign(orderA)

  const orderB = await placeOrder(1)
  check('B 案下单成功（1 件）', Boolean(orderB), orderB)
  await shipAndSign(orderB)

  const stockAfterShipping = await stockNow()
  const movementsAfterShipping = await movementCount()
  check('两单已签收，记录库存基线', stockAfterShipping !== null,
    `stock=${stockAfterShipping}, movements=${movementsAfterShipping}`)

  /* ---------------- 2. A 案：买家退 1 件 → 客服驳回 ---------------- */
  const returnA = await submitReturn(orderA, 'B1 驳回案例：键盘按键失灵')
  check('A 案退货单提交成功', Boolean(returnA), returnA)

  const quotaA1 = await quotaOf(orderA)
  check('A 案提交后额度 1/1', quotaA1.occupied === 1 && quotaA1.remaining === 1,
    JSON.stringify(quotaA1))

  await openAdminAftersale()

  const rowA = page.locator(`.row[data-return-no="${returnA}"]`)
  await rowA.waitFor({ timeout: 20000 })
  check('客服列表里出现 A 案', (await rowA.count()) === 1)
  check('客服列表能看到买家', (await rowA.innerText()).includes(BUYER),
    (await rowA.innerText()).replace(/\n/g, ' / '))

  await rowA.locator('[data-action="reject"]').click()
  const rejectBox = page.locator(`[data-reject-for="${returnA}"]`)
  await rejectBox.waitFor({ timeout: 10000 })

  const confirmDisabled = await rejectBox.locator('[data-action="reject-confirm"]').isDisabled()
  check('驳回原因没填时不能提交', confirmDisabled)

  await rejectBox.locator('[data-reject-input]').fill('超过 7 天无理由期限，且商品已使用')
  await rejectBox.locator('[data-action="reject-confirm"]').click()

  await page.locator('.banner--ok').waitFor({ timeout: 20000 })
  const rejectNotice = await page.locator('.banner--ok').innerText()
  check('驳回成功且提示写明释放数量', rejectNotice.includes('已驳回') && rejectNotice.includes('1 件'),
    rejectNotice.replace(/\n/g, ' '))

  const rowAText = await waitForText(rowA, '已驳回')
  check('A 案行内状态变为已驳回', rowAText.includes('已驳回'), rowAText.replace(/\n/g, ' / '))

  await page.screenshot({ path: `${OUT}/01-rejected.png` })

  /* ---------------- 3. 买家刷新：额度回到 2，看到驳回结果 ---------------- */
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(600)
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后与退货' }).click()
  await page.waitForURL('**/shop/aftersale', { timeout: 10000 })
  await page.reload({ waitUntil: 'domcontentloaded' })

  const quotaA2 = await quotaOf(orderA)
  check('驳回后可退额度回到 2（释放了 1 件）',
    quotaA2.occupied === 0 && quotaA2.remaining === 2, JSON.stringify(quotaA2))

  const returnAItem = page.locator(`.return[data-return-no="${returnA}"]`)
  await returnAItem.waitFor({ timeout: 20000 })
  const itemAText = await waitForText(returnAItem, '已驳回')
  check('买家「我的售后」显示已驳回', itemAText.includes('已驳回'),
    itemAText.replace(/\n/g, ' / '))
  check('买家列表提示额度已释放', itemAText.includes('额度已释放'),
    itemAText.replace(/\n/g, ' / '))

  await returnAItem.getByRole('button', { name: '详情' }).click()
  const panel = page.locator('[data-return-detail]')
  await panel.waitFor({ timeout: 15000 })
  const panelText = await waitForText(panel, '客服处理结果')
  check('买家详情能看到驳回原因',
    panelText.includes('超过 7 天无理由期限，且商品已使用'),
    panelText.replace(/\n/g, ' / '))
  await page.screenshot({ path: `${OUT}/02-buyer-sees-reject.png` })
  await panel.getByRole('button', { name: '关闭详情' }).click()

  /* ---------------- 4. B 案：买家退 1 件 → 客服批准 ---------------- */
  const returnB = await submitReturn(orderB, 'B1 批准案例：键盘按键失灵')
  check('B 案退货单提交成功', Boolean(returnB), returnB)

  const quotaB1 = await quotaOf(orderB)
  check('B 案提交后额度 1/0', quotaB1.occupied === 1 && quotaB1.remaining === 0,
    JSON.stringify(quotaB1))

  await openAdminAftersale()

  const rowB = page.locator(`.row[data-return-no="${returnB}"]`)
  await rowB.waitFor({ timeout: 20000 })
  await rowB.locator('[data-action="approve"]').click()
  await page.locator('.banner--ok').waitFor({ timeout: 20000 })
  const approveNotice = await page.locator('.banner--ok').innerText()
  check('批准成功且提示写明保留额度',
    approveNotice.includes('已批准') && approveNotice.includes('保留原额度'),
    approveNotice.replace(/\n/g, ' '))

  const rowBText = await waitForText(rowB, '已批准')
  check('B 案行内状态变为已批准', rowBText.includes('已批准'), rowBText.replace(/\n/g, ' / '))

  // 已决策的行不能再点
  check('已批准的行不能再驳回',
    await rowB.locator('[data-action="reject"]').isDisabled())

  await page.screenshot({ path: `${OUT}/03-approved.png` })

  /* ---------------- 5. 买家刷新：额度维持 1 ---------------- */
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(600)
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后与退货' }).click()
  await page.waitForURL('**/shop/aftersale', { timeout: 10000 })
  await page.reload({ waitUntil: 'domcontentloaded' })

  const quotaB2 = await quotaOf(orderB)
  check('批准后额度维持 1（不释放）',
    quotaB2.occupied === 1 && quotaB2.remaining === 0, JSON.stringify(quotaB2))

  const returnBItem = page.locator(`.return[data-return-no="${returnB}"]`)
  await returnBItem.waitFor({ timeout: 20000 })
  const itemBText = await waitForText(returnBItem, '已批准')
  check('买家「我的售后」显示已批准', itemBText.includes('已批准'),
    itemBText.replace(/\n/g, ' / '))

  await page.screenshot({ path: `${OUT}/04-buyer-sees-approve.png` })

  /* ---------------- 6. 权限：买家打开客服页看不到任何单 ---------------- */
  await page.goto(`${WEB}/shop/admin/aftersale`, { waitUntil: 'domcontentloaded' })
  await sleep(1200)
  const deniedText = await page.locator('.admin__inner').innerText()
  check('普通买家打开客服页被拦住', deniedText.includes('当前身份不能处理售后'),
    deniedText.replace(/\n/g, ' / ').slice(0, 160))
  check('普通买家看不到任何退货单行', (await page.locator('.row[data-return-no]').count()) === 0)

  /* ---------------- 7. 库存与流水不变 ---------------- */
  await openAdminAftersale()
  await page.locator('.shop-nav__links').getByRole('link', { name: '商城后台' }).click()
  await page.waitForURL('**/shop/admin', { timeout: 10000 })

  const stockAfterDecisions = await stockNow()
  const movementsAfterDecisions = await movementCount()
  check('客服决策没有改变可售库存', stockAfterDecisions === stockAfterShipping,
    `before=${stockAfterShipping} after=${stockAfterDecisions}`)
  check('客服决策没有新增库存流水', movementsAfterDecisions === movementsAfterShipping,
    `before=${movementsAfterShipping} after=${movementsAfterDecisions}`)

  await page.screenshot({ path: `${OUT}/05-stock-unchanged.png` })
} catch (error) {
  try {
    await page.screenshot({ path: `${OUT}/99-failure.png` })
    console.log('当前 URL：', page.url())
    console.log('导航可见性：', await page.locator('.shop-nav__links').isVisible())
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
  check('脚本执行未抛异常', false, String(error).slice(0, 300))
} finally {
  check('浏览器控制台没有错误', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))

  await browser.close()

  const passed = results.filter((item) => item.ok).length
  console.log(`\n${passed}/${results.length} passed`)
  if (failed > 0) {
    process.exitCode = 1
  }
}
