/**
 * 售后 A1 收尾验收：**纯商城买家**的多轮对话 + 刷新恢复 + 确认卡提交。
 *
 * 为什么单独一段：前两个脚本用的都是 music-user-1，
 * 而它同时是 music_user 与 shop_user——正好掩盖了"售后页建会话走音乐守卫"这个缺陷。
 * 这一段用 `shop-user-1`（只有 shop_user，没有 music_user）把真实路径走通：
 *
 *   1. 第一轮只说"我要退货"（缺订单/数量/原因）→ 助手应当追问，不出确认卡；
 *   2. 第二轮补齐参数 → 出确认卡（此时仍未建单、未占额度）；
 *   3. **刷新页面** → 两轮对话与确认卡都要恢复（靠服务端会话，不是内存）；
 *   4. 在恢复出来的卡上批准 → 「我的售后」出现真实退货单号，额度 1/1；
 *   5. 换另一个买家 → 看不到这张单，也恢复不到这个会话。
 *
 * 用法（后端 demo + 前端 dev 都启动后）：
 *   node tools/e2e-aftersale-shopbuyer.mjs
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'

const WEB = process.env.WEB_BASE || 'http://localhost:5173'
const OUT = 'target/e2e-aftersale-shopbuyer'
const SKU = 'SKU-KEYBOARD-K87'
const BUYER = 'shop-user-1'
const OTHER_BUYER = 'music-user-2'
const ADMIN = 'music-admin-1'
const BUY_QUANTITY = 2

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

function parseQuota(text) {
  const occupied = /已占用\s*(\d+)\s*件/.exec(text || '')
  const remaining = /剩余可退\s*(\d+)\s*件/.exec(text || '')
  return {
    occupied: occupied ? Number(occupied[1]) : null,
    remaining: remaining ? Number(remaining[1]) : null,
  }
}

/** 有界轮询：等某个条件成立，最多 timeout 毫秒。 */
async function until(fn, timeout = 30000, interval = 300) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await fn()) {
      return true
    }
    await sleep(interval)
  }
  return false
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

try {
  /* ---------------- 1. 纯商城买家下单（2 把） ---------------- */
  await page.goto(`${WEB}/shop`, { waitUntil: 'domcontentloaded' })

  // 先切到纯商城买家，再下单：订单必须属于它
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(800)
  await page.reload({ waitUntil: 'domcontentloaded' })

  const keyboard = page.locator(`.card[data-sku="${SKU}"]`)
  await keyboard.waitFor({ timeout: 20000 })

  await keyboard.getByRole('button', { name: '增加一件' }).click()
  await keyboard.getByRole('button', { name: '立即购买' }).click()

  const banner = page.locator('.banner--ok')
  await banner.waitFor({ timeout: 15000 })
  const orderMatch = /SO\d{14}-[0-9A-F]{4}/.exec(await banner.innerText())
  check('纯商城买家下单成功', Boolean(orderMatch), orderMatch ? orderMatch[0] : '')
  const orderNo = orderMatch ? orderMatch[0] : ''

  /* ---------------- 2. 后台发货 + 签收 ---------------- */
  await page.selectOption('.shop-nav__select', ADMIN)
  await sleep(800)
  await page.locator('.shop-nav__links').getByRole('link', { name: '商城后台' }).click()
  await page.waitForURL('**/shop/admin', { timeout: 10000 })

  const adminRow = page.locator(`.row[data-order-no="${orderNo}"]`)
  await adminRow.waitFor({ timeout: 15000 })
  await adminRow.getByRole('button', { name: '发货', exact: true }).click()
  await page.locator('.banner--ok').waitFor({ timeout: 15000 })
  await sleep(400)
  await adminRow.getByRole('button', { name: '标记签收' }).click()
  await page.locator('.banner--ok').waitFor({ timeout: 15000 })
  await sleep(400)
  check('后台发货并签收成功', true, orderNo)

  /* ---------------- 3. 切回纯商城买家，进售后页 ---------------- */
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(800)
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后与退货' }).click()
  await page.waitForURL('**/shop/aftersale', { timeout: 10000 })

  const orderRow = page.locator(`.order[data-order-no="${orderNo}"]`)
  await orderRow.waitFor({ timeout: 20000 })

  /*
   * 只认**这一单**的退货记录。
   *
   * 同一个库上重复跑脚本时，买家名下会有上一轮留下的单，
   * 用 `.return` 第一条会让断言命中旧数据（看起来"确认前就建单了"）。
   * 按订单号过滤才能测到本轮的真实状态。
   */
  const orderReturn = page.locator('.return').filter({ hasText: orderNo })

  const quotaBefore = parseQuota(await orderRow.locator('.order__quota').innerText())
  check(
    '新订单额度为 0/2',
    quotaBefore.occupied === 0 && quotaBefore.remaining === BUY_QUANTITY,
    JSON.stringify(quotaBefore),
  )

  // 建会话对纯商城买家必须是通的（原先会 403 → 退化单轮）
  const warn = page.locator('.chat__warn')
  await sleep(500)
  check(
    '没有出现「会话没建成」的降级提示',
    (await warn.count()) === 0,
    (await warn.count()) ? await warn.innerText() : '',
  )

  /* ---------------- 4. 第一轮：只说"我要退货"，应当追问 ---------------- */
  const composer = page.locator('.chat__input')
  const send = page.locator('.chat__composer').getByRole('button', { name: '发送' })

  await composer.fill('我要退货')
  await send.click()

  // 等这一轮结束（发送按钮重新可用）
  await until(async () => !(await send.isDisabled()), 120000)
  await sleep(800)

  const turnsAfterFirst = page.locator('.turn')
  check('第一轮已渲染', (await turnsAfterFirst.count()) >= 1, `turns=${await turnsAfterFirst.count()}`)

  const firstText = (await turnsAfterFirst.first().innerText()).replace(/\n/g, ' / ')
  check(
    '第一轮助手给了回答（不是空轮）',
    firstText.length > 20,
    firstText.slice(0, 200),
  )
  check(
    '第一轮没有建单',
    (await orderReturn.count()) === 0,
    `returns=${await orderReturn.count()}`,
  )

  const cardOnFirstTurn = await page.locator('[data-approval-card]').count()
  check(
    '第一轮是追问而不是直接出卡（缺订单/数量/原因）',
    cardOnFirstTurn === 0,
    cardOnFirstTurn ? '第一轮就出了确认卡：模型没有先追问' : '无卡，符合预期',
  )

  await page.screenshot({ path: `${OUT}/01-first-turn.png` })

  /* ---------------- 5. 第二轮：补齐参数 → 出确认卡 ---------------- */
  await composer.fill(`订单 ${orderNo}，退 1 件，原因：键盘按键失灵。请直接提交。`)
  await send.click()

  const card = page.locator('[data-approval-card]').last()
  let cardVisible = false
  try {
    await card.waitFor({ timeout: 120000 })
    cardVisible = true
  } catch {
    cardVisible = false
  }
  check('第二轮出现确认卡', cardVisible)

  if (!cardVisible) {
    throw new Error('第二轮没有出现确认卡，后续步骤无法继续')
  }

  await until(async () => !(await send.isDisabled()), 60000)
  await sleep(600)

  const cardText = await card.innerText()
  check('确认卡固定订单号', cardText.includes(orderNo), cardText.replace(/\n/g, ' / '))
  check('确认卡固定退货数量', /退货数量\s*1\s*件/.test(cardText), cardText.replace(/\n/g, ' / '))
  check('确认卡固定退货原因', cardText.includes('键盘按键失灵'), cardText.replace(/\n/g, ' / '))

  check(
    '确认前「我的售后」没有这一单',
    (await orderReturn.count()) === 0,
    `returns=${await orderReturn.count()}`,
  )
  const quotaBeforeApprove = parseQuota(await orderRow.locator('.order__quota').innerText())
  check(
    '确认前没有占用额度',
    quotaBeforeApprove.occupied === 0 && quotaBeforeApprove.remaining === BUY_QUANTITY,
    JSON.stringify(quotaBeforeApprove),
  )

  await page.screenshot({ path: `${OUT}/02-card-before-refresh.png` })

  /* ---------------- 6. 刷新页面 → 会话与确认卡都要恢复 ---------------- */
  await page.reload({ waitUntil: 'domcontentloaded' })

  const restoredTurns = page.locator('.turn')
  const restoredOk = await until(async () => (await restoredTurns.count()) >= 2, 30000)
  check(
    '刷新后两轮对话都恢复了',
    restoredOk && (await restoredTurns.count()) === 2,
    `turns=${await restoredTurns.count()}`,
  )

  const restoredCard = page.locator('[data-approval-card]').last()
  const cardRestored = await until(async () => (await restoredCard.count()) > 0, 20000)
  check('刷新后确认卡还在', cardRestored)

  if (cardRestored) {
    const restoredCardText = await restoredCard.innerText()
    check(
      '恢复出来的卡仍是待确认（不是已执行）',
      restoredCardText.includes('待确认'),
      restoredCardText.replace(/\n/g, ' / '),
    )
    check(
      '恢复出来的卡内容仍是服务端摘要',
      restoredCardText.includes(orderNo) && restoredCardText.includes('键盘按键失灵'),
      restoredCardText.replace(/\n/g, ' / '),
    )
    check(
      '恢复后仍未建单、未占额度',
      (await orderReturn.count()) === 0 &&
        parseQuota(await orderRow.locator('.order__quota').innerText()).occupied === 0,
      `returns=${await orderReturn.count()}`,
    )
  }

  await page.screenshot({ path: `${OUT}/03-restored-after-refresh.png` })

  /* ---------------- 7. 在恢复出来的卡上批准 ---------------- */
  await restoredCard.getByRole('button', { name: '批准并执行' }).click()

  const returnItem = orderReturn.first()
  await returnItem.waitFor({ timeout: 120000 })

  const returnText = await returnItem.innerText()
  const returnMatch = /RT\d{14}-[0-9A-F]{4}/.exec(returnText)
  check('「我的售后」出现真实退货单号', Boolean(returnMatch), returnText.replace(/\n/g, ' / '))
  check('状态为已提交', returnText.includes('已提交'), returnText.replace(/\n/g, ' / '))
  const returnNo = returnMatch ? returnMatch[0] : ''

  await sleep(800)
  const quotaAfter = parseQuota(await orderRow.locator('.order__quota').innerText())
  check(
    '剩余可退额度为 1（已占用 1）',
    quotaAfter.occupied === 1 && quotaAfter.remaining === 1,
    JSON.stringify(quotaAfter),
  )

  await page.screenshot({ path: `${OUT}/04-submitted.png` })

  /* ---------------- 8. 再刷新：单与额度都在 ---------------- */
  await page.reload({ waitUntil: 'domcontentloaded' })
  const reloadedReturn = page.locator(`.return[data-return-no="${returnNo}"]`)
  await reloadedReturn.waitFor({ timeout: 20000 })
  check('刷新后退货单仍在', (await reloadedReturn.innerText()).includes(returnNo))

  /* ---------------- 9. 另一个买家：看不到单，也恢复不到会话 ---------------- */
  await page.selectOption('.shop-nav__select', OTHER_BUYER)
  await sleep(1000)

  check(
    '另一个买家看不到这笔退货申请',
    (await page.locator(`.return[data-return-no="${returnNo}"]`).count()) === 0,
    `visible=${await page.locator('.return').count()}`,
  )
  check(
    '另一个买家恢复不到这个会话（对话为空）',
    (await page.locator('.turn').count()) === 0,
    `turns=${await page.locator('.turn').count()}`,
  )

  await page.screenshot({ path: `${OUT}/05-other-buyer.png` })
} catch (error) {
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
