/**
 * V1 闭环的真实模型联调（浏览器 + 真实后端）。
 *
 * 前置：
 *   1) 后端：demo profile + 真实凭据（见 README 2.1）
 *   2) 前端：npm run dev（5173）
 *
 * 只做三件事：创建歌单、加歌、拒绝。每个场景都从页面输入、页面点按钮，
 * 断言走真实 HTTP 查询，不读前端内存状态。
 *
 * 产物：target/e2e/*.png + target/e2e/report.md + target/e2e/report.json
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'

const BASE = 'http://localhost:5173/'
const API = 'http://localhost:8123/api'
const USER = 'music-user-1'
const OUT = 'target/e2e'
const STAMP = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 12)
const PLAYLIST = `联调验证-${STAMP}`

fs.mkdirSync(OUT, { recursive: true })

const report = {
  startedAt: new Date().toISOString(),
  playlistName: PLAYLIST,
  user: USER,
  scenarios: [],
  capabilities: null,
  consoleErrors: [],
}
const md = []

function log(line = '') {
  md.push(line)
  console.log(line)
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

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'MAX_STEPS_REACHED'])

async function waitSettled(runId, timeoutMs = 150000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    const { body } = await api(`/ai/runs/${runId}`)
    if (body) {
      last = body
      if (TERMINAL.has(body.state) || body.state === 'WAITING_APPROVAL') {
        return body
      }
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

async function shot(page, name) {
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: false })
}

async function openLibrary(page) {
  const drawer = page.locator('.lib__sheet')
  if (await drawer.count() === 0) {
    await page.click('text=我的歌单')
    await page.waitForSelector('.lib__sheet', { timeout: 10000 })
  }
  await page.click('.lib__sheet >> text=刷新')
  await page.waitForTimeout(900)
}

async function closeLibrary(page) {
  await page.click('.lib__sheet button[aria-label="关闭"]')
  await page.waitForTimeout(300)
}

async function expandPlaylist(page, name) {
  const item = page.locator('.lib__item', { hasText: name }).first()
  await item.locator('.lib__row').click()
  await page.waitForTimeout(1200)
  return item
}

async function submit(page, text) {
  await page.fill('textarea', text)
  await page.click('.composer__send')
}

async function waitApproval(page) {
  await page.waitForSelector('[data-approval-card]', { timeout: 150000 })
  await page.waitForTimeout(700)
  return (await page.locator('[data-approval-card]').first().innerText()).trim()
}

async function waitAnswer(page) {
  // 审批卡片的按钮区会一直存在；这里等"最终回答"标题出现
  await page.waitForSelector('text=最终回答', { timeout: 180000 })
  await page.waitForTimeout(600)
  return (await page.locator('.workspace__scroll').innerText()).trim()
}

// ==================== 主流程 ====================

const browser = await chromium.launch({ channel: 'chrome' })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2, locale: 'zh-CN' })
const page = await ctx.newPage()
page.on('console', (m) => {
  if (m.type() === 'error') report.consoleErrors.push(m.text())
})
page.on('pageerror', (e) => report.consoleErrors.push(e.message))

await page.goto(BASE, { waitUntil: 'networkidle' })

const caps = await api('/ai/music/capabilities')
report.capabilities = caps.body
log(`# V1 真实模型联调报告`)
log()
log(`- 时间：${report.startedAt}`)
log(`- 页面：${BASE}（Vite 代理 → 8123）`)
log(`- 身份：${USER}`)
log(`- capabilities：\`${JSON.stringify(caps.body)}\``)
log()

const badge = (await page.locator('.topbar__mode').innerText()).trim()
log(`- 页面能力徽标：**${badge}**`)
log()

// ---------- 场景 1：创建歌单 ----------
const createMsg = `帮我创建一个歌单，名字叫「${PLAYLIST}」，把《城市霓虹》加进去。`
log(`## 场景 1：创建歌单`)
log()
log(`**输入**：${createMsg}`)
await submit(page, createMsg)
const createApproval = await waitApproval(page)
await shot(page, '01-create-approval')
const run1 = await latestRun('创建一个歌单')
const settled1 = run1 ? await waitSettled(run1.runId) : null
log()
log('**待审批卡片**：')
log('```')
log(createApproval)
log('```')
log()
log(`- runId：\`${run1?.runId}\`，state=\`${settled1?.state}\`，model=\`${settled1?.model}\``)
log(`- 工具轨迹：\`${settled1?.toolTrace}\``)

await page.click('[data-approval-card] >> text=批准并继续')
await waitAnswer(page)
await shot(page, '02-create-approved')
const afterApprove1 = run1 ? await waitSettled(run1.runId) : null
const playlists1 = await api('/ai/music/playlists')
const created = (playlists1.body ?? []).find((p) => p.name === PLAYLIST)
const songs1 = created ? await api(`/ai/music/playlists/${created.id}/songs`) : { body: [] }

log()
log(`- 批准后 state=\`${afterApprove1?.state}\`，tokens=${afterApprove1?.totalTokens}（prompt=${afterApprove1?.promptTokens}, completion=${afterApprove1?.completionTokens}）`)
log(`- 最终回答：`)
log('```')
log((afterApprove1?.finalAnswer ?? '').trim())
log('```')
log(`- 歌单：id=\`${created?.id}\`，name=\`${created?.name}\`，songCount=\`${created?.songCount}\``)
log(`- 曲目 ID：\`${(songs1.body ?? []).map((s) => s.id).join(', ')}\``)

report.scenarios.push({
  name: 'create',
  input: createMsg,
  approvalText: createApproval,
  run: afterApprove1,
  playlistId: created?.id ?? null,
  songIds: (songs1.body ?? []).map((s) => s.id),
})

// ---------- 场景 2：添加歌曲 ----------
await openLibrary(page)
await expandPlaylist(page, PLAYLIST)
await shot(page, '03-library-after-create')
await closeLibrary(page)

const addMsg = `把《深夜电台》加到歌单「${PLAYLIST}」里。`
log()
log(`## 场景 2：添加歌曲`)
log()
log(`**输入**：${addMsg}`)
await submit(page, addMsg)
const addApproval = await waitApproval(page)
await shot(page, '04-add-approval')
const run2 = await latestRun('深夜电台')
const settled2 = run2 ? await waitSettled(run2.runId) : null
log()
log('**待审批卡片**：')
log('```')
log(addApproval)
log('```')
log()
log(`- runId：\`${run2?.runId}\`，state=\`${settled2?.state}\``)
log(`- 工具轨迹：\`${settled2?.toolTrace}\``)

await page.click('[data-approval-card] >> text=批准并继续')
await waitAnswer(page)
await shot(page, '05-add-approved')
const afterApprove2 = run2 ? await waitSettled(run2.runId) : null
const songs2 = created ? await api(`/ai/music/playlists/${created.id}/songs`) : { body: [] }
log()
log(`- 批准后 state=\`${afterApprove2?.state}\`，tokens=${afterApprove2?.totalTokens}（prompt=${afterApprove2?.promptTokens}, completion=${afterApprove2?.completionTokens}）`)
log(`- 最终回答：`)
log('```')
log((afterApprove2?.finalAnswer ?? '').trim())
log('```')
log(`- 曲目 ID：\`${(songs2.body ?? []).map((s) => s.id).join(', ')}\``)

report.scenarios.push({
  name: 'add',
  input: addMsg,
  approvalText: addApproval,
  run: afterApprove2,
  songIds: (songs2.body ?? []).map((s) => s.id),
})

// ---------- 场景 3：拒绝 ----------
const rejectMsg = `把《末班地铁》加到歌单「${PLAYLIST}」里。`
log()
log(`## 场景 3：拒绝`)
log()
log(`**输入**：${rejectMsg}`)
await submit(page, rejectMsg)
const rejectApproval = await waitApproval(page)
await shot(page, '06-reject-approval')
const run3 = await latestRun('末班地铁')
const settled3 = run3 ? await waitSettled(run3.runId) : null
log()
log('**待审批卡片**：')
log('```')
log(rejectApproval)
log('```')

await page.click('[data-approval-card] >> text=拒绝')
await page.waitForTimeout(1200)
await waitAnswer(page)
await shot(page, '07-rejected')
const afterReject = run3 ? await waitSettled(run3.runId) : null
const songs3 = created ? await api(`/ai/music/playlists/${created.id}/songs`) : { body: [] }
log()
log(`- 拒绝后 state=\`${afterReject?.state}\`，tokens=${afterReject?.totalTokens}`)
log(`- 最终回答：`)
log('```')
log((afterReject?.finalAnswer ?? '').trim())
log('```')
log(`- 曲目 ID（应与场景 2 相同）：\`${(songs3.body ?? []).map((s) => s.id).join(', ')}\``)

report.scenarios.push({
  name: 'reject',
  input: rejectMsg,
  approvalText: rejectApproval,
  run: afterReject,
  songIds: (songs3.body ?? []).map((s) => s.id),
})

// ---------- 刷新后核对 ----------
log()
log(`## 刷新页面后核对`)
await page.reload({ waitUntil: 'networkidle' })
await openLibrary(page)
const item = await expandPlaylist(page, PLAYLIST)
const drawerText = (await item.innerText()).trim()
await shot(page, '08-after-reload')
log()
log('```')
log(drawerText)
log('```')

report.afterReload = { drawerText }
report.finishedAt = new Date().toISOString()
report.tokens = report.scenarios.map((s) => ({
  name: s.name,
  state: s.run?.state ?? null,
  total: s.run?.totalTokens ?? null,
}))

fs.writeFileSync(`${OUT}/report.md`, md.join('\n'))
fs.writeFileSync(`${OUT}/report.json`, JSON.stringify(report, null, 2))

await browser.close()
log()
log(`console errors: ${report.consoleErrors.length}`)
