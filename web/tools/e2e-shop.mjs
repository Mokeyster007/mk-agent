/**
 * 商城 A0 的页面级验收（真实浏览器 + 真实后端 + 真实事务）。
 *
 * 这个脚本替代的是"人工点一遍"：从商品页下单，切到后台发货、签收，
 * 再刷新页面核对状态与库存。它断言的是**可测量的事实**：
 * - 下单后库存数量不变（下单不预占、不扣库存）；
 * - 发货后库存正好减少购买数量，且订单详情里只有一笔 -N 流水；
 * - 签收后库存不再变化；
 * - 刷新页面后订单状态、出库记录仍然读得到（持久化，不是内存态）；
 * - 全程没有浏览器控制台错误。
 *
 * 库存断言用**相对值**（发货前 - 购买数量），不写死 10：
 * 这样脚本可以在同一个数据库上重复跑，不会因为上一次已经把库存买掉而失败。
 *
 * 用法（后端 demo + 前端 dev 都启动后）：
 *   node tools/e2e-shop.mjs
 *
 * 环境变量：
 *   WEB_BASE  前端地址，默认 http://localhost:5173
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'

const WEB = process.env.WEB_BASE || 'http://localhost:5173'
const OUT = 'target/e2e-shop'
const SKU = 'SKU-KEYBOARD-K87'
const BUYER = 'music-user-1'
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

/** 从"可售 10 件"这类文案里取数字 */
function parseStock(text) {
  const match = /可售\s*(\d+)\s*件/.exec(text || '')
  return match ? Number(match[1]) : null
}

const browser = await chromium.launch({
  channel: 'chrome',
  headless: true,
  args: ['--mute-audio'],
})

const context = await browser.newContext({ viewport: { width: 1440, height: 940 } })
const page = await context.newPage()

const consoleErrors = []
page.on('console', (msg) => {
  if (msg.type() === 'error') {
    consoleErrors.push(msg.text())
  }
})
page.on('pageerror', (error) => consoleErrors.push(String(error)))

try {
  /* ---------------- 1. 商品页 ---------------- */
  await page.goto(`${WEB}/shop`, { waitUntil: 'domcontentloaded' })

  const cards = page.locator('.card')
  await cards.first().waitFor({ timeout: 20000 })

  check('商品列表渲染 3 个商品', (await cards.count()) === 3, `cards=${await cards.count()}`)
  check(
    '顶部明确标注演示付款',
    (await page.locator('.topbar__demo').innerText()).includes('无真实资金交易'),
  )

  const keyboard = page.locator(`.card[data-sku="${SKU}"]`)
  const stockTextBefore = await keyboard.locator('.card__stock').innerText()
  const stockBefore = parseStock(stockTextBefore)

  check(
    '种子键盘可售数量可读',
    stockBefore !== null && stockBefore >= BUY_QUANTITY,
    `before=${stockTextBefore}`,
  )

  await page.screenshot({ path: `${OUT}/01-products.png` })

  /* ---------------- 2. 购买 2 件 ---------------- */
  await keyboard.getByRole('button', { name: '增加一件' }).click()
  const quantityText = await keyboard.locator('.stepper__value').innerText()
  check('数量步进到 2 件', quantityText.trim() === String(BUY_QUANTITY), `qty=${quantityText}`)

  await keyboard.getByRole('button', { name: '立即购买' }).click()

  const banner = page.locator('.banner--ok')
  await banner.waitFor({ timeout: 15000 })

  const bannerText = await banner.innerText()
  const orderMatch = /SO\d{14}-[0-9A-F]{4}/.exec(bannerText)
  check('下单成功并返回订单号', Boolean(orderMatch), bannerText.replace(/\n/g, ' '))

  const orderNo = orderMatch ? orderMatch[0] : ''

  check(
    '下单后提示"尚未扣减库存"',
    bannerText.includes('尚未扣减库存'),
    bannerText.replace(/\n/g, ' '),
  )

  /* ---------------- 3. 下单不扣库存 ---------------- */
  await sleep(400)
  const stockTextAfterOrder = await keyboard.locator('.card__stock').innerText()
  check(
    '下单后库存不变（下单不预占、不扣库存）',
    parseStock(stockTextAfterOrder) === stockBefore,
    `before=${stockBefore} after=${parseStock(stockTextAfterOrder)}`,
  )

  const buyerOrder = page.locator(`.order[data-order-no="${orderNo}"]`)
  await buyerOrder.waitFor({ timeout: 10000 })
  check(
    '我的订单里出现该订单且状态为待发货',
    (await buyerOrder.innerText()).includes('待发货'),
    (await buyerOrder.innerText()).replace(/\n/g, ' / '),
  )

  await page.screenshot({ path: `${OUT}/02-ordered.png` })

  /* ---------------- 4. 切换为后台人员 ---------------- */
  await page.selectOption('.shop-nav__select', ADMIN)
  await sleep(600)

  await page.locator('.shop-nav__links').getByRole('link', { name: '商城后台' }).click()
  await page.waitForURL('**/shop/admin', { timeout: 10000 })

  const adminRow = page.locator(`.row[data-order-no="${orderNo}"]`)
  await adminRow.waitFor({ timeout: 15000 })
  check('后台按订单号看到这一单', (await adminRow.count()) === 1)

  await page.screenshot({ path: `${OUT}/03-admin.png` })

  /* ---------------- 5. 发货 ---------------- */
  await adminRow.getByRole('button', { name: '发货', exact: true }).click()
  await page.locator('.banner--ok').waitFor({ timeout: 15000 })

  const shipNotice = await page.locator('.banner--ok').innerText()
  check('发货成功并说明扣减', shipNotice.includes('已发货'), shipNotice.replace(/\n/g, ' '))

  await sleep(500)

  const balanceRow = page.locator(`tr[data-sku="${SKU}"]`)
  const balanceText = await balanceRow.innerText()
  const balanceAfterShip = Number((balanceText.match(/(\d+)\s*$/m) || [])[1])

  check(
    '发货后库存正好减少购买数量',
    balanceAfterShip === stockBefore - BUY_QUANTITY,
    `before=${stockBefore} after=${balanceAfterShip}`,
  )

  /* ---------------- 6. 出库流水只有一笔 -2 ---------------- */
  await adminRow.getByRole('button', { name: '详情' }).click()
  const panel = page.locator('.panel')
  await panel.waitFor({ timeout: 10000 })

  const moveItems = panel.locator('.moves__item')
  check('订单详情里只有 1 笔出库流水', (await moveItems.count()) === 1, `count=${await moveItems.count()}`)

  const moveText = await moveItems.first().innerText()
  check(
    '流水数量为 -2 且类型为发货出库',
    moveText.includes('-2') && moveText.includes('OUTBOUND_SHIPMENT'),
    moveText.replace(/\n/g, ' / '),
  )

  await page.screenshot({ path: `${OUT}/04-shipped.png` })
  await panel.getByRole('button', { name: '关闭订单详情' }).click()

  /* ---------------- 7. 签收 ---------------- */
  await adminRow.getByRole('button', { name: '标记签收' }).click()
  await page.locator('.banner--ok').waitFor({ timeout: 15000 })
  const signNotice = await page.locator('.banner--ok').innerText()
  check('签收成功', signNotice.includes('已签收'), signNotice.replace(/\n/g, ' '))

  await sleep(500)
  const balanceAfterSign = Number(
    ((await page.locator(`tr[data-sku="${SKU}"]`).innerText()).match(/(\d+)\s*$/m) || [])[1],
  )
  check(
    '签收后库存不变',
    balanceAfterSign === balanceAfterShip,
    `afterShip=${balanceAfterShip} afterSign=${balanceAfterSign}`,
  )

  await page.screenshot({ path: `${OUT}/05-signed.png` })

  /* ---------------- 8. 刷新后仍然是已签收 ---------------- */
  await page.reload({ waitUntil: 'domcontentloaded' })
  const reloadedRow = page.locator(`.row[data-order-no="${orderNo}"]`)
  await reloadedRow.waitFor({ timeout: 15000 })
  check(
    '刷新后订单仍为已签收（持久化）',
    (await reloadedRow.innerText()).includes('已签收'),
    (await reloadedRow.innerText()).replace(/\n/g, ' / '),
  )

  /* ---------------- 9. 买家视角复核 ---------------- */
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(600)
  await page.getByRole('link', { name: '返回商城' }).click()
  await page.waitForURL('**/shop', { timeout: 10000 })

  const buyerOrderAfter = page.locator(`.order[data-order-no="${orderNo}"]`)
  await buyerOrderAfter.waitFor({ timeout: 15000 })
  check(
    '买家刷新后看到已签收',
    (await buyerOrderAfter.innerText()).includes('已签收'),
    (await buyerOrderAfter.innerText()).replace(/\n/g, ' / '),
  )

  await buyerOrderAfter.getByRole('button', { name: '详情' }).click()
  await page.locator('.panel').waitFor({ timeout: 10000 })
  check(
    '买家能看到该订单的出库记录',
    (await page.locator('.panel .moves__item').count()) === 1,
    `moves=${await page.locator('.panel .moves__item').count()}`,
  )

  await page.screenshot({ path: `${OUT}/06-buyer-signed.png` })
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
