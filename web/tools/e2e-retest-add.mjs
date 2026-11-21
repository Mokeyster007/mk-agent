/**
 * 复测（只跑"加歌"一个场景）：确认审批卡显示真实歌单名。
 *
 * 背景：首轮真实联调时 add_songs_to_playlist 的审批卡显示
 * "目标歌单：未指定歌单名"（工具参数只有 playlistId）。
 * 修复后用同一个真实模型、同一个页面重跑这一条。
 *
 * 前置：后端 demo（8123）+ Vite（5173）已启动，歌单已存在。
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'

const BASE = 'http://localhost:5173/'
const API = 'http://localhost:8123/api'
const USER = 'music-user-1'
const OUT = 'target/e2e'
const PLAYLIST = '联调验证-202611171817'

fs.mkdirSync(OUT, { recursive: true })

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'MAX_STEPS_REACHED'])
const out = []
const log = (l = '') => {
  out.push(l)
  console.log(l)
}

async function api(path) {
  const res = await fetch(`${API}${path}`, { headers: { 'X-User-Id': USER } })
  let body = null
  try {
    body = await res.json()
  } catch {
    body = null
  }
  return { status: res.status, body }
}

async function waitSettled(runId, timeoutMs = 150000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    const { body } = await api(`/ai/runs/${runId}`)
    if (body) {
      last = body
      if (TERMINAL.has(body.state) || body.state === 'WAITING_APPROVAL') return body
    }
    await new Promise((r) => setTimeout(r, 800))
  }
  return last
}

async function latestRun(matchText) {
  const { body } = await api('/ai/runs/page?pageNum=1&pageSize=10')
  const records = body?.records ?? []
  return records.find((r) => (r.userPrompt ?? '').includes(matchText)) ?? records[0] ?? null
}

// ---------- 修复前状态 ----------
const before = await api(`/ai/music/playlists`)
const target = (before.body ?? []).find((p) => p.name === PLAYLIST)
if (!target) {
  console.error(`歌单不存在：${PLAYLIST}`)
  process.exit(1)
}
const beforeSongs = await api(`/ai/music/playlists/${target.id}/songs`)
const beforeIds = (beforeSongs.body ?? []).map((s) => s.id)

const browser = await chromium.launch({ channel: 'chrome' })
const ctx = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  deviceScaleFactor: 2,
  locale: 'zh-CN',
})
const page = await ctx.newPage()
const consoleErrors = []
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})
page.on('pageerror', (e) => consoleErrors.push(e.message))

await page.goto(BASE, { waitUntil: 'networkidle' })

const msg = `把《末班地铁》加到歌单「${PLAYLIST}」里。`
await page.fill('textarea', msg)
await page.click('.composer__send')
await page.waitForSelector('[data-approval-card]', { timeout: 150000 })
await page.waitForTimeout(800)
const cardText = (await page.locator('[data-approval-card]').first().innerText()).trim()
await page.screenshot({ path: `${OUT}/09-add-approval-fixed.png` })

const run = await latestRun('末班地铁')
const paused = run ? await waitSettled(run.runId) : null

log(`# 复测：加歌审批卡显示真实歌单名`)
log()
log(`**输入**：${msg}`)
log()
log('**待审批卡片**：')
log('```')
log(cardText)
log('```')
log()
log(`- runId：\`${run?.runId}\`，state=\`${paused?.state}\``)
log(`- 工具轨迹：\`${paused?.toolTrace}\``)
log(`- 卡片是否含歌单名：**${cardText.includes(PLAYLIST) ? '是' : '否'}**`)
log(`- 卡片是否仍出现"未指定歌单名"：**${cardText.includes('未指定歌单名') ? '是（未修复）' : '否'}**`)

// 批准
await page.click('[data-approval-card] >> text=批准并继续')

/*
 * 注意：不能等 'text=最终回答' 就截图。
 * final_answer 事件比"任务真正收尾"早到（模型写完答案后还要落库收尾），
 * 那一刻审批卡仍是"等待恢复"，截出来的图会像是没更新。
 * 正确做法：等卡片自己离开"等待恢复"，那才是数据库里的真实终态。
 */
const approveStart = Date.now()
await page.waitForFunction(
  () => {
    const card = document.querySelector('[data-approval-card]')
    return card && !card.textContent.includes('等待恢复')
  },
  { timeout: 180000 },
)
const settleMs = Date.now() - approveStart
await page.waitForTimeout(400)
await page.screenshot({ path: `${OUT}/10-add-approved-fixed.png` })
const done = run ? await waitSettled(run.runId) : null

const afterSongs = await api(`/ai/music/playlists/${target.id}/songs`)
const afterIds = (afterSongs.body ?? []).map((s) => s.id)

log()
log(`- 批准后 state=\`${done?.state}\`，卡片离开"等待恢复"用时 ${settleMs}ms，tokens=${done?.totalTokens}（prompt=${done?.promptTokens}, completion=${done?.completionTokens}）`)
log(`- 最终回答：`)
log('```')
log((done?.finalAnswer ?? '').trim())
log('```')
log(`- 曲目 ID：修复前 \`${beforeIds.join(', ')}\` → 修复后 \`${afterIds.join(', ')}\``)

// 刷新后核对
await page.reload({ waitUntil: 'networkidle' })
await page.click('text=我的歌单')
await page.waitForSelector('.lib__sheet', { timeout: 10000 })
await page.click('.lib__sheet >> text=刷新')
await page.waitForTimeout(900)
const item = page.locator('.lib__item', { hasText: PLAYLIST }).first()
await item.locator('.lib__row').click()
await page.waitForTimeout(1200)
const drawerText = (await item.innerText()).trim()
await page.screenshot({ path: `${OUT}/11-library-after-add-fixed.png` })

log()
log('**刷新后「我的歌单」**：')
log('```')
log(drawerText)
log('```')
log()
log(`console errors: ${consoleErrors.length}`)

fs.writeFileSync(`${OUT}/retest-add.md`, out.join('\n'))
fs.writeFileSync(
  `${OUT}/retest-add.json`,
  JSON.stringify(
    { playlist: target, beforeIds, afterIds, cardText, run: done, consoleErrors },
    null,
    2,
  ),
)

await browser.close()

const ok = cardText.includes(PLAYLIST) && done?.state === 'SUCCEEDED'
console.log(ok ? '\nRESULT=PASS' : '\nRESULT=FAIL')
process.exit(ok ? 0 : 1)
