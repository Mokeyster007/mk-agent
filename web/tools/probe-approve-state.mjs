/**
 * 探针：批准之后，审批卡与右侧"任务详情"多久能反映真实状态？
 *
 * 目的：区分"截图截早了"和"状态永远不刷新"。
 * 只读观察，不修改数据（重复加同一首歌，服务端会跳过）。
 */
import { chromium } from 'playwright-core'

const BASE = 'http://localhost:5173/'
const PLAYLIST = '联调验证-202611171817'

const browser = await chromium.launch({ channel: 'chrome' })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
const page = await ctx.newPage()
const errors = []
page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))

await page.goto(BASE, { waitUntil: 'networkidle' })
await page.fill('textarea', `把《末班地铁》加到歌单「${PLAYLIST}」里。`)
await page.click('.composer__send')
await page.waitForSelector('[data-approval-card]', { timeout: 150000 })
await page.waitForTimeout(500)

const t0 = Date.now()
const sample = async (label) => {
  const card = await page.locator('[data-approval-card]').first().innerText()
  const detail = await page.locator('aside, .detail').first().innerText().catch(() => '')
  const cardStatus = (card.match(/执行状态\s*\n?\s*(\S+)/) ?? [, '?'])[1]
  const hasAnswer = card.includes('最终回答')
  const ended = /结束\s*\n?\s*\d{4}-/.test(detail)
  console.log(
    `t=${String(Date.now() - t0).padStart(5)}ms  card=${cardStatus.padEnd(6)} detailEnded=${ended}  ${label}`,
  )
}

await page.click('[data-approval-card] >> text=批准并继续')
await sample('clicked')

for (let i = 0; i < 20; i += 1) {
  await page.waitForTimeout(500)
  await sample('')
}

await page.screenshot({ path: 'target/e2e/12-probe-after-approve.png' })
console.log(`console errors: ${errors.length}`)
await browser.close()
