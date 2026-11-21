/**
 * 界面截图（真实 demo 数据，只用查询接口，不触发模型调用）。
 *
 * 用法：node tools/shot-ui.mjs
 * 产出：target/ui/*.png
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'
import path from 'node:path'

const WEB = 'http://localhost:5173/'
const OUT = path.resolve('target/ui')
const EMPTY = null
const SHORT = 'd378b57b-92fd-4ac5-9c78-a65a4b5ab504'
const LONG = '347c28a8-9581-4c38-8f24-20c942579191'
const PENDING = '8c7ed085-51b0-4dab-b542-66ac5a286704'

fs.mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'chrome', headless: true })

async function open(viewport, conversationId) {
  const context = await browser.newContext({ viewport, deviceScaleFactor: 2, locale: 'zh-CN' })
  const page = await context.newPage()
  await page.goto(WEB, { waitUntil: 'domcontentloaded' })
  await page.evaluate((id) => {
    if (id) window.localStorage.setItem('mkagent.activeConversationId', id)
    else window.localStorage.removeItem('mkagent.activeConversationId')
  }, conversationId)
  await page.goto(WEB, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1000)
  return { context, page }
}

async function shot(name, viewport, conversationId, before) {
  const { context, page } = await open(viewport, conversationId)
  if (before) {
    await before(page)
  }
  await page.screenshot({ path: path.join(OUT, `${name}.png`) })
  console.log(`✓ ${name}.png`)
  await context.close()
}

const DESKTOP = { width: 1440, height: 900 }
const LAPTOP = { width: 1280, height: 720 }
const MOBILE = { width: 390, height: 844 }

await shot('01-empty-desktop', DESKTOP, EMPTY)
await shot('02-long-desktop', DESKTOP, LONG)
await shot('03-pending-desktop', DESKTOP, PENDING)
await shot('04-library-drawer', DESKTOP, LONG, async (page) => {
  await page.click('.topbar__btn')
  await page.waitForTimeout(700)
})
await shot('05-detail-panel', DESKTOP, LONG, async (page) => {
  await page.click('.topbar__icon')
  await page.waitForTimeout(500)
})
await shot('06-long-laptop', LAPTOP, LONG)
await shot('07-mobile-long', MOBILE, LONG)
await shot('08-mobile-nav', MOBILE, LONG, async (page) => {
  await page.click('.shell__menu')
  await page.waitForTimeout(400)
})
await shot('09-mobile-empty', MOBILE, EMPTY)

await browser.close()
console.log(`\n写入 ${OUT}`)
