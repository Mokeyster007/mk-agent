/**
 * M1 收尾：接着上一轮的追问回答确认，看能否走完 Agent 建单，
 * 并核对"确认前不建单"与"建单后有没有撤回入口"。
 *
 *   node tools/acc-aftersale-m1-finish.mjs
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'

const WEB = process.env.WEB_BASE || 'http://localhost:5173'
const OUT = 'target/acc-aftersale'
const BUYER = 'shop-user-1'

fs.mkdirSync(OUT, { recursive: true })
const log = []
const say = (t, s) => {
  const line = `${t}  ${s}`
  log.push(line)
  console.log(line)
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--mute-audio'] })
const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
const page = await context.newPage()

try {
  await page.goto(`${WEB}/shop`, { waitUntil: 'domcontentloaded' })
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(400)
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后与退货' }).click()
  await page.waitForURL('**/shop/aftersale', { timeout: 15000 })
  await page.locator('.orders').waitFor({ timeout: 20000 })

  const before = await page.locator('.return').count()
  say('EVIDENCE', `建单前退货单数量=${before}`)

  await page
    .locator('.chat__input')
    .fill('对，就是这一单，退 1 件，原因是按键失灵。信息都齐了，直接提交，不用再问。')
  await page.locator('.chat__composer').getByRole('button', { name: '发送' }).click()

  const card = page.locator('[data-approval-card]').last()
  let hasCard = false
  const deadline = Date.now() + 150000
  while (Date.now() < deadline) {
    if ((await card.count()) > 0) {
      hasCard = true
      break
    }
    await sleep(500)
  }

  if (!hasCard) {
    const turns = await page.locator('[data-aftersale-chat] [data-turn-id]').all()
    const last = turns[turns.length - 1]
    const answer = await last.locator('.turn__answer').innerText().catch(() => '')
    say('NOTE', 'M1 第二轮仍未出确认卡')
    say('EVIDENCE', `回答：${answer.replace(/\n+/g, ' / ').slice(0, 400)}`)
    await page.screenshot({ path: `${OUT}/m1-finish-no-card.png`, fullPage: true })
  } else {
    const cardText = (await card.innerText()).replace(/\n+/g, ' / ')
    say('EVIDENCE', `确认卡：${cardText.slice(0, 300)}`)
    const mid = await page.locator('.return').count()
    say('NOTE', `确认卡出现、尚未点批准时退货单数量=${mid}（建单前 ${before}）`)

    await card.getByRole('button', { name: '批准并执行' }).click()
    await sleep(2500)
    const after = await page.locator('.return').count()
    const first = await page.locator('.return').first().innerText()
    say('EVIDENCE', `批准后退货单数量=${after}，首行：${first.replace(/\n+/g, ' / ').slice(0, 200)}`)
    await page.screenshot({ path: `${OUT}/m1-finish-created.png`, fullPage: true })

    const item = page.locator('.return').first()
    await item.getByRole('button', { name: '详情' }).click()
    const panel = page.locator('[data-return-detail]')
    await panel.waitFor({ timeout: 15000 })
    await sleep(400)
    const buttons = await panel.locator('button').evaluateAll((nodes) =>
      nodes.map((n) => `${(n.innerText || '').trim() || '(无文字)'}[${n.getAttribute('aria-label') || ''}]`),
    )
    say('EVIDENCE', `买家详情里所有按钮：${buttons.join(' / ')}`)
    await page.screenshot({ path: `${OUT}/m1-finish-detail.png`, fullPage: true })
  }
} catch (error) {
  say('FAIL', `中断：${String(error).slice(0, 300)}`)
  await page.screenshot({ path: `${OUT}/m1-finish-failure.png`, fullPage: true }).catch(() => {})
} finally {
  await browser.close()
  fs.writeFileSync(`${OUT}/m1-finish.log`, log.join('\n'), 'utf8')
}
