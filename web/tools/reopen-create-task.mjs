/**
 * 从"历史任务"里重新打开创建歌单那条（只读，不产生任何写入），
 * 用来证明：刷新/重开之后，页面显示的仍是数据库里的真实终态。
 */
import { chromium } from 'playwright-core'

const BASE = 'http://localhost:5173/'
const KEY = '帮我创建一个歌单'

const browser = await chromium.launch({ channel: 'chrome' })
const ctx = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  deviceScaleFactor: 2,
  locale: 'zh-CN',
})
const page = await ctx.newPage()
const errors = []
page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))
page.on('pageerror', (e) => errors.push(e.message))

await page.goto(BASE, { waitUntil: 'networkidle' })

const item = page.locator('text=' + KEY).first()
await item.click()
await page.waitForTimeout(2500)
await page.screenshot({ path: 'target/e2e/13-create-reopened.png' })

const card = await page.locator('[data-approval-card]').first().innerText()
const header = await page.locator('.topbar__mode').innerText()
console.log('mode:', header.trim())
console.log('card status:', (card.match(/执行状态\s*\n?\s*(\S+)/) ?? [, '?'])[1])
console.log('has 已批准 badge:', card.includes('已批准'))
console.log('console errors:', errors.length)

await browser.close()
