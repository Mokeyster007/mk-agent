/**
 * 截图脚本（开发辅助，不参与构建）。
 *
 * 依赖本机安装的 Chrome（playwright-core 不自带浏览器）：
 *   npm i && node tools/screenshots.mjs
 *
 * 前置：
 *   1) 契约桩：node tools/stub-server.mjs 8123
 *   2) 只读实例：npm run dev                     （默认 http://localhost:5173）
 *   3) 写能力实例：VITE_CAPABILITY_MODE=write_approval npm run dev -- --port 5174
 *
 * 产物写到 target/screenshots/（target/ 已在 .gitignore 中）。
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'

const OUT = 'target/screenshots'
const READONLY = 'http://localhost:5173/'
const WRITE = 'http://localhost:5174/'

fs.mkdirSync(OUT, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome' })
const errors = []

async function shot(name, base, width, height, steps, fullPage = false) {
  const ctx = await browser.newContext({
    viewport: { width, height },
    deviceScaleFactor: 2,
    locale: 'zh-CN',
  })
  const page = await ctx.newPage()
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(`${name}: ${m.text()}`)
  })
  page.on('pageerror', (e) => errors.push(`${name}: ${e.message}`))
  await page.goto(base, { waitUntil: 'networkidle' })
  if (steps) await steps(page)
  await page.waitForTimeout(700)
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage })
  await ctx.close()
}

// —— 只读模式（生产默认装配） ——
await shot('01-desktop-empty', READONLY, 1440, 900)

await shot('02-desktop-answer', READONLY, 1440, 900, async (page) => {
  await page.fill('textarea', '帮我找一下夜航星的歌')
  await page.click('.composer__send')
  await page.waitForSelector('text=最终回答', { timeout: 15000 })
  await page.waitForTimeout(800)
})

await shot('03-mobile-empty', READONLY, 390, 844)

await shot('04-mobile-answer', READONLY, 390, 844, async (page) => {
  await page.fill('textarea', '我目前有哪些歌单？')
  await page.click('.composer__send')
  await page.waitForSelector('text=最终回答', { timeout: 15000 })
  await page.waitForTimeout(800)
})

// —— 审批卡预览页（固定示例数据，不发起请求） ——
await shot('05-preview', `${READONLY}preview/approval`, 1440, 900, null, true)

// —— 写能力模式（仅用于验证可复用界面，生产装配未启用） ——
await shot('06-write-empty', WRITE, 1440, 900)

await shot('07-write-approval', WRITE, 1440, 900, async (page) => {
  await page.fill('textarea', '帮我建一个歌单叫「清晨慢跑」')
  await page.click('.composer__send')
  await page.waitForSelector('text=需要你确认这次写操作', { timeout: 15000 })
  await page.waitForTimeout(900)
})

await shot('08-write-approved', WRITE, 1440, 900, async (page) => {
  await page.fill('textarea', '帮我建一个歌单叫「清晨慢跑」')
  await page.click('.composer__send')
  await page.waitForSelector('text=批准并继续', { timeout: 15000 })
  await page.click('text=批准并继续')
  await page.waitForSelector('text=最终回答', { timeout: 15000 })
  await page.waitForTimeout(900)
})

await shot('09-mobile-approval', WRITE, 390, 844, async (page) => {
  await page.fill('textarea', '帮我建一个歌单叫「清晨慢跑」')
  await page.click('.composer__send')
  await page.waitForSelector('text=需要你确认这次写操作', { timeout: 15000 })
  await page.waitForTimeout(900)
})

await browser.close()
console.log('ERRORS:', JSON.stringify(errors, null, 2))
console.log('DONE')
