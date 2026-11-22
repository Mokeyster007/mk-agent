/**
 * 售后 A1 的页面级验收（真实浏览器 + 真实后端 + 真实事务 + 真实模型）。
 *
 * 走的就是需求里那条主验收路径：
 *   买两把键盘 → 后台发货 → 签收 → 在对话里申请退一把 → 确认卡 → 批准
 *   → 「我的售后」出现真实退货单号 → 剩余可退额度为 1
 *   → 可售库存与出库流水都没变。
 *
 * 断言的都是可测量的事实，不读内存态：
 * - 退货单号来自页面上的文本（它只能来自数据库）；
 * - 额度三个数来自服务端渲染的"已占用 / 剩余可退"；
 * - 库存与流水回到后台页用同一套接口核对；
 * - 刷新页面后记录仍在。
 *
 * 模型是**真实**的（demo profile + 环境变量里的 API Key）：
 * 如果模型这一轮没给出确认卡（比如先追问），脚本会补一句更明确的话再等一次，
 * 仍然拿不到才算失败——不把"模型没按剧本走"伪装成通过。
 *
 * 用法（后端 demo + 前端 dev 都启动后）：
 *   node tools/e2e-aftersale.mjs
 *
 * 环境变量：
 *   WEB_BASE  前端地址，默认 http://localhost:5173
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'

const WEB = process.env.WEB_BASE || 'http://localhost:5173'
const OUT = 'target/e2e-aftersale'
const SKU = 'SKU-KEYBOARD-K87'
const BUYER = 'music-user-1'
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

function parseStock(text) {
  const match = /可售\s*(\d+)\s*件/.exec(text || '')
  return match ? Number(match[1]) : null
}

/** "已占用 1 件 · 剩余可退 1 件" → { occupied: 1, remaining: 1 } */
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

try {
  /* ---------------- 1. 买两把键盘 ---------------- */
  await page.goto(`${WEB}/shop`, { waitUntil: 'domcontentloaded' })

  const keyboard = page.locator(`.card[data-sku="${SKU}"]`)
  await keyboard.waitFor({ timeout: 20000 })

  const stockBefore = parseStock(await keyboard.locator('.card__stock').innerText())
  check('种子键盘可售数量可读', stockBefore !== null, `before=${stockBefore}`)

  await keyboard.getByRole('button', { name: '增加一件' }).click()
  await keyboard.getByRole('button', { name: '立即购买' }).click()

  const banner = page.locator('.banner--ok')
  await banner.waitFor({ timeout: 15000 })
  const bannerText = await banner.innerText()
  const orderMatch = /SO\d{14}-[0-9A-F]{4}/.exec(bannerText)
  check('下单成功并返回订单号', Boolean(orderMatch), bannerText.replace(/\n/g, ' '))

  const orderNo = orderMatch ? orderMatch[0] : ''
  await page.screenshot({ path: `${OUT}/01-ordered.png` })

  /* ---------------- 2. 后台发货 + 签收 ---------------- */
  await page.selectOption('.shop-nav__select', ADMIN)
  await sleep(600)
  await page.locator('.shop-nav__links').getByRole('link', { name: '商城后台' }).click()
  await page.waitForURL('**/shop/admin', { timeout: 10000 })

  const adminRow = page.locator(`.row[data-order-no="${orderNo}"]`)
  await adminRow.waitFor({ timeout: 15000 })

  await adminRow.getByRole('button', { name: '发货', exact: true }).click()
  await page.locator('.banner--ok').waitFor({ timeout: 15000 })
  await sleep(400)

  const balanceAfterShip = Number(
    ((await page.locator(`tr[data-sku="${SKU}"]`).innerText()).match(/(\d+)\s*$/m) || [])[1],
  )
  check(
    '发货后库存减少购买数量',
    balanceAfterShip === stockBefore - BUY_QUANTITY,
    `before=${stockBefore} after=${balanceAfterShip}`,
  )

  await adminRow.getByRole('button', { name: '标记签收' }).click()
  await page.locator('.banner--ok').waitFor({ timeout: 15000 })
  await sleep(400)

  const stockAfterSign = Number(
    ((await page.locator(`tr[data-sku="${SKU}"]`).innerText()).match(/(\d+)\s*$/m) || [])[1],
  )
  check('签收后库存不变', stockAfterSign === balanceAfterShip, `stock=${stockAfterSign}`)

  // 出库流水：这一单只有 1 笔 -2
  await adminRow.getByRole('button', { name: '详情' }).click()
  const adminPanel = page.locator('.panel')
  await adminPanel.waitFor({ timeout: 10000 })
  const movesBefore = await adminPanel.locator('.moves__item').count()
  check('签收时该订单有 1 笔出库流水', movesBefore === 1, `moves=${movesBefore}`)
  await adminPanel.getByRole('button', { name: '关闭订单详情' }).click()

  await page.screenshot({ path: `${OUT}/02-signed.png` })

  /* ---------------- 3. 切回买家，进售后页 ---------------- */
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(600)
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后与退货' }).click()
  await page.waitForURL('**/shop/aftersale', { timeout: 10000 })

  const orderRow = page.locator(`.order[data-order-no="${orderNo}"]`)
  await orderRow.waitFor({ timeout: 20000 })

  const quotaBefore = parseQuota(await orderRow.locator('.order__quota').innerText())
  check(
    '可退订单显示"已购 2 / 已占用 0 / 剩余可退 2"',
    quotaBefore.occupied === 0 && quotaBefore.remaining === BUY_QUANTITY,
    JSON.stringify(quotaBefore),
  )

  await page.screenshot({ path: `${OUT}/03-aftersale.png` })

  /* ---------------- 4. 在对话里申请退一把 ---------------- */
  const composer = page.locator('.chat__input')
  await composer.fill(`帮我退一把订单 ${orderNo} 的键盘，退 1 件，原因是键盘按键失灵`)
  await page.locator('.chat__composer').getByRole('button', { name: '发送' }).click()

  const card = page.locator('[data-approval-card]').last()
  let cardVisible = false
  try {
    await card.waitFor({ timeout: 90000 })
    cardVisible = true
  } catch {
    cardVisible = false
  }

  if (!cardVisible) {
    // 模型可能先追问：补一句更明确的，再等一次（不静默重试，只是把话说全）
    await composer.fill(`就退订单 ${orderNo}，数量 1 件，原因：键盘按键失灵。请直接提交。`)
    await page.locator('.chat__composer').getByRole('button', { name: '发送' }).click()
    try {
      await card.waitFor({ timeout: 90000 })
      cardVisible = true
    } catch {
      cardVisible = false
    }
  }

  check('对话里出现人工确认卡（未确认前不建单）', cardVisible)

  if (cardVisible) {
    const cardText = await card.innerText()
    check('确认卡固定订单号', cardText.includes(orderNo), cardText.replace(/\n/g, ' / '))
    check('确认卡固定商品', cardText.includes('键盘'), cardText.replace(/\n/g, ' / '))
    check('确认卡固定退货数量', /退货数量\s*1\s*件/.test(cardText), cardText.replace(/\n/g, ' / '))
    check('确认卡固定退货原因', cardText.includes('键盘按键失灵'), cardText.replace(/\n/g, ' / '))

    // 确认前：列表仍然没有退货单
    check(
      '确认前「我的售后」没有新单',
      (await page.locator('.return').count()) === 0,
      `returns=${await page.locator('.return').count()}`,
    )

    await page.screenshot({ path: `${OUT}/04-confirmation-card.png` })

    /* ---------------- 5. 批准 ---------------- */
    await card.getByRole('button', { name: '批准并执行' }).click()

    const returnItem = page.locator('.return').first()
    await returnItem.waitFor({ timeout: 90000 })

    const returnText = await returnItem.innerText()
    const returnMatch = /RT\d{14}-[0-9A-F]{4}/.exec(returnText)
    check('「我的售后」出现真实退货单号', Boolean(returnMatch), returnText.replace(/\n/g, ' / '))
    check('状态为已提交（不是已批准 / 已退款）', returnText.includes('已提交'), returnText.replace(/\n/g, ' / '))
    check('来源标注为售后 Agent', returnText.includes('售后 Agent'), returnText.replace(/\n/g, ' / '))

    const returnNo = returnMatch ? returnMatch[0] : ''

    /* ---------------- 6. 剩余可退额度 = 1 ---------------- */
    await sleep(600)
    const quotaAfter = parseQuota(await orderRow.locator('.order__quota').innerText())
    check(
      '剩余可退额度为 1（已占用 1）',
      quotaAfter.occupied === 1 && quotaAfter.remaining === 1,
      JSON.stringify(quotaAfter),
    )

    /* ---------------- 7. 刷新后仍在（持久化） ---------------- */
    await page.reload({ waitUntil: 'domcontentloaded' })
    const reloaded = page.locator(`.return[data-return-no="${returnNo}"]`)
    await reloaded.waitFor({ timeout: 20000 })
    check(
      '刷新后「我的售后」仍能看到这张单',
      (await reloaded.innerText()).includes(returnNo),
      (await reloaded.innerText()).replace(/\n/g, ' / '),
    )

    // 详情：一条处理记录
    await reloaded.getByRole('button', { name: '详情' }).click()
    const panel = page.locator('.panel')
    await panel.waitFor({ timeout: 10000 })
    check(
      '详情里有 1 条处理记录（买家提交退货申请）',
      (await panel.locator('.event').count()) === 1,
      `events=${await panel.locator('.event').count()}`,
    )
    check(
      '详情写明本轮只到「已提交」',
      (await panel.innerText()).includes('已提交'),
      (await panel.innerText()).replace(/\n/g, ' / '),
    )
    await panel.getByRole('button', { name: '关闭详情' }).click()

    await page.screenshot({ path: `${OUT}/05-submitted.png` })

    /* ---------------- 8. 库存与出库流水都没变 ---------------- */
    await page.selectOption('.shop-nav__select', ADMIN)
    await sleep(600)
    await page.locator('.shop-nav__links').getByRole('link', { name: '商城后台' }).click()
    await page.waitForURL('**/shop/admin', { timeout: 10000 })

    const stockAfterReturn = Number(
      ((await page.locator(`tr[data-sku="${SKU}"]`).innerText()).match(/(\d+)\s*$/m) || [])[1],
    )
    check(
      '退货申请没有改变可售库存',
      stockAfterReturn === stockAfterSign,
      `before=${stockAfterSign} after=${stockAfterReturn}`,
    )

    const adminRowAfter = page.locator(`.row[data-order-no="${orderNo}"]`)
    await adminRowAfter.getByRole('button', { name: '详情' }).click()
    const panelAfter = page.locator('.panel')
    await panelAfter.waitFor({ timeout: 10000 })
    check(
      '退货申请没有新增出库 / 入库流水',
      (await panelAfter.locator('.moves__item').count()) === movesBefore,
      `before=${movesBefore} after=${await panelAfter.locator('.moves__item').count()}`,
    )
    await panelAfter.getByRole('button', { name: '关闭订单详情' }).click()

    /* ---------------- 9. 另一个买家看不到这张单 ---------------- */
    await page.selectOption('.shop-nav__select', OTHER_BUYER)
    await sleep(600)
    await page.locator('.shop-nav__links').getByRole('link', { name: '售后与退货' }).click()
    await page.waitForURL('**/shop/aftersale', { timeout: 10000 })
    await sleep(800)

    check(
      '另一个买家看不到这笔退货申请',
      (await page.locator(`.return[data-return-no="${returnNo}"]`).count()) === 0,
      `visible=${await page.locator('.return').count()}`,
    )
    check(
      '另一个买家的可退订单里没有这张单',
      (await page.locator(`.order[data-order-no="${orderNo}"]`).count()) === 0,
    )

    await page.screenshot({ path: `${OUT}/06-other-buyer.png` })
  }
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
