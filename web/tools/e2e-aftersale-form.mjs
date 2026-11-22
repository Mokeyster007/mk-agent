/**
 * 售后 A1 的第二段页面验收：**拒绝零写入** + **普通表单路径**。
 *
 * 与 e2e-aftersale.mjs 分开，是因为两段验收要的初始状态不同：
 * 主脚本假设"这个买家还没有任何售后记录"，这一段要在一单新的订单上
 * 先拒绝一次、再用表单提交一次。
 *
 * 断言：
 * 1. 确认卡上点「拒绝」后：没有建单、额度仍是 0/2、状态是"已拒绝"；
 * 2. 同一个页面下半部分的普通表单能提交，并给出真实退货单号；
 * 3. 表单提交后额度变成 1/1，刷新后仍在；
 * 4. 库存与出库流水不变。
 *
 * 用法（后端 demo + 前端 dev 都启动后）：
 *   node tools/e2e-aftersale-form.mjs
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'

const WEB = process.env.WEB_BASE || 'http://localhost:5173'
const OUT = 'target/e2e-aftersale-form'
const SKU = 'SKU-KEYBOARD-K87'
const BUYER = 'music-user-1'
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

/**
 * 有界轮询等待某个 locator 的文本出现关键字。
 *
 * 拒绝/批准走的是 SSE 恢复流：点按钮 → 落决定 → 恢复执行回填回执 → 续流，
 * 前端要等这一轮结束才会把状态从"提交中…"改成"已拒绝"。固定 sleep 是脆的，
 * 这里改成最多等 timeout 毫秒、每 250ms 读一次。
 */
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
    await sleep(250)
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

try {
  /* ---------------- 1. 新订单：买 2 把 → 发货 → 签收 ---------------- */
  await page.goto(`${WEB}/shop`, { waitUntil: 'domcontentloaded' })
  const keyboard = page.locator(`.card[data-sku="${SKU}"]`)
  await keyboard.waitFor({ timeout: 20000 })

  await keyboard.getByRole('button', { name: '增加一件' }).click()
  await keyboard.getByRole('button', { name: '立即购买' }).click()

  const banner = page.locator('.banner--ok')
  await banner.waitFor({ timeout: 15000 })
  const orderMatch = /SO\d{14}-[0-9A-F]{4}/.exec(await banner.innerText())
  check('第二单下单成功', Boolean(orderMatch))
  const orderNo = orderMatch ? orderMatch[0] : ''

  await page.selectOption('.shop-nav__select', ADMIN)
  await sleep(600)
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

  const stockAfterSign = Number(
    ((await page.locator(`tr[data-sku="${SKU}"]`).innerText()).match(/(\d+)\s*$/m) || [])[1],
  )

  /* ---------------- 2. 进售后页 ---------------- */
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(600)
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后与退货' }).click()
  await page.waitForURL('**/shop/aftersale', { timeout: 10000 })

  const orderRow = page.locator(`.order[data-order-no="${orderNo}"]`)
  await orderRow.waitFor({ timeout: 20000 })

  const returnsBefore = await page.locator('.return').count()
  const quotaBefore = parseQuota(await orderRow.locator('.order__quota').innerText())
  check(
    '新订单额度为 0/2',
    quotaBefore.occupied === 0 && quotaBefore.remaining === 2,
    JSON.stringify(quotaBefore),
  )

  /* ---------------- 3. 对话里申请，然后**拒绝** ---------------- */
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
    await composer.fill(`就退订单 ${orderNo}，数量 1 件，原因：键盘按键失灵。请直接提交。`)
    await page.locator('.chat__composer').getByRole('button', { name: '发送' }).click()
    try {
      await card.waitFor({ timeout: 90000 })
      cardVisible = true
    } catch {
      cardVisible = false
    }
  }
  check('确认卡出现', cardVisible)

  if (cardVisible) {
    await page.screenshot({ path: `${OUT}/01-card.png` })

    await card.getByRole('button', { name: '拒绝' }).click()

    // 等 SSE 恢复流把回执回填成"已拒绝"（最多 20s），而不是拍脑袋 sleep
    const cardText = await waitForText(card, '已拒绝')
    check('确认卡状态变为已拒绝', cardText.includes('已拒绝'), cardText.replace(/\n/g, ' / '))
    check('拒绝后按钮不可再点', cardText.includes('拒绝') && (await card.getByRole('button', { name: '批准并执行' }).isDisabled()))

    check(
      '拒绝没有建单',
      (await page.locator('.return').count()) === returnsBefore,
      `before=${returnsBefore} after=${await page.locator('.return').count()}`,
    )

    const quotaAfterReject = parseQuota(await orderRow.locator('.order__quota').innerText())
    check(
      '拒绝没有占用额度（仍是 0/2）',
      quotaAfterReject.occupied === 0 && quotaAfterReject.remaining === 2,
      JSON.stringify(quotaAfterReject),
    )

    await page.screenshot({ path: `${OUT}/02-rejected.png` })
  }

  /* ---------------- 4. 普通表单提交 ---------------- */
  const form = page.locator('.form')
  await form.locator('select').selectOption(orderNo)
  await form.locator('.stepper__input').fill('1')
  await form.locator('textarea').fill('表单提交：键盘按键失灵')

  await form.getByRole('button', { name: '试算退款' }).click()
  const preview = page.locator('[data-preview]')
  await preview.waitFor({ timeout: 15000 })
  check(
    '试算显示预计退款与额度',
    (await preview.innerText()).includes('¥299.00'),
    (await preview.innerText()).replace(/\n/g, ' / '),
  )

  await form.getByRole('button', { name: '提交退货申请' }).click()
  await page.locator('.banner--ok').waitFor({ timeout: 20000 })

  const notice = await page.locator('.banner--ok').innerText()
  const formReturnMatch = /RT\d{14}-[0-9A-F]{4}/.exec(notice)
  check('表单提交成功并返回真实退货单号', Boolean(formReturnMatch), notice.replace(/\n/g, ' '))

  const formReturnNo = formReturnMatch ? formReturnMatch[0] : ''

  await sleep(800)
  const formItem = page.locator(`.return[data-return-no="${formReturnNo}"]`)
  check('列表里出现表单提交的单', (await formItem.count()) === 1)
  check(
    '来源标注为普通表单',
    (await formItem.innerText()).includes('普通表单'),
    (await formItem.innerText()).replace(/\n/g, ' / '),
  )

  const quotaAfterForm = parseQuota(await orderRow.locator('.order__quota').innerText())
  check(
    '表单提交后额度为 1/1',
    quotaAfterForm.occupied === 1 && quotaAfterForm.remaining === 1,
    JSON.stringify(quotaAfterForm),
  )

  await page.screenshot({ path: `${OUT}/03-form-submitted.png` })

  /* ---------------- 5. 刷新仍在 + 库存不变 ---------------- */
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.locator(`.return[data-return-no="${formReturnNo}"]`).waitFor({ timeout: 20000 })
  check('刷新后表单提交的单仍在', true)

  await page.selectOption('.shop-nav__select', ADMIN)
  await sleep(600)
  await page.locator('.shop-nav__links').getByRole('link', { name: '商城后台' }).click()
  await page.waitForURL('**/shop/admin', { timeout: 10000 })

  const stockAfter = Number(
    ((await page.locator(`tr[data-sku="${SKU}"]`).innerText()).match(/(\d+)\s*$/m) || [])[1],
  )
  check('表单提交没有改变库存', stockAfter === stockAfterSign, `before=${stockAfterSign} after=${stockAfter}`)

  await page.screenshot({ path: `${OUT}/04-stock-unchanged.png` })
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
