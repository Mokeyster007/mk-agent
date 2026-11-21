/**
 * 定点复测：会话里的"把刚才第三首也加进去"是否真的走到审批卡。
 *
 * 上一次联调里模型正确识别了歌曲（1003）与歌单（97），但只回了一句
 * "正在为您执行添加操作…"，没有发出 tool_call —— 用户看到的是假承诺。
 * 这次只跑这一步，验证提示词 v5 之后是否直接调用工具。
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'
import path from 'node:path'

const WEB = 'http://localhost:5173/'
const API = 'http://localhost:8123/api'
const USER = 'music-user-1'
const OUT = path.resolve('target/e2e-conv')
const CONVERSATION = process.argv[2]
const PLAYLIST_ID = Number(process.argv[3])

fs.mkdirSync(OUT, { recursive: true })

const log = (msg) => console.log(msg)
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function api(pathname) {
  const response = await fetch(`${API}${pathname}`, {
    headers: { 'X-User-Id': USER, Accept: 'application/json' },
  })
  const text = await response.text()
  return { status: response.status, body: text ? JSON.parse(text) : null }
}

async function messageCount(conversationId) {
  const detail = await api(`/ai/conversations/${conversationId}`)
  return detail.status === 200 ? (detail.body.messages ?? []).length : -1
}

async function waitTurn(conversationId, before, timeoutMs = 240000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const detail = await api(`/ai/conversations/${conversationId}`)
    const messages = detail.body?.messages ?? []
    if (messages.length > before) {
      const last = messages[messages.length - 1]
      if (last.role === 'USER' && last.approvalId) {
        return { kind: 'approval', approvalId: last.approvalId, messages }
      }
      if (last.role === 'ASSISTANT' && last.state && last.state !== 'RUNNING') {
        return { kind: 'done', state: last.state, answer: last.content ?? '', messages }
      }
    }
    await sleep(1500)
  }
  throw new Error('等待超时')
}

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 960 } })
const consoleErrors = []
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})

await page.goto(WEB, { waitUntil: 'domcontentloaded' })
await sleep(1500)

// 打开原会话（列表里按标题点进去）
const title = (await api(`/ai/conversations/${CONVERSATION}`)).body.title
const item = page.locator('.nav__item', { hasText: title }).first()
await item.waitFor({ state: 'visible', timeout: 30000 })
await item.click()
await sleep(1200)
await page.screenshot({ path: `${OUT}/13-retest-open.png` })

const before = await messageCount(CONVERSATION)

const box = page.locator('textarea.composer__input')
await box.waitFor({ state: 'visible' })
await box.fill('把刚才第三首也加进去')
await page.waitForFunction(
  () => {
    const b = document.querySelector('button.composer__send')
    return b && !b.disabled
  },
  { timeout: 240000 },
)
await page.locator('button.composer__send').click()

let turn = await waitTurn(CONVERSATION, before)
log(`- 结果：${turn.kind}/${turn.state ?? ''}｜${(turn.answer || '').slice(0, 140)}`)
await page.screenshot({ path: `${OUT}/14-retest-approval.png` })

if (turn.kind === 'approval') {
  const card = page.locator('[data-approval-card]').last()
  await card.waitFor({ state: 'visible' })
  await page.waitForFunction(
    () => {
      const cards = document.querySelectorAll('[data-approval-card]')
      const last = cards[cards.length - 1]
      const button = last && last.querySelector('button.mk-btn--primary')
      return !!button && !button.disabled
    },
    { timeout: 120000 },
  )
  await card.getByText('批准并继续').click()
  turn = await waitTurn(CONVERSATION, before + 1)
  log(`- 批准后：${turn.kind}/${turn.state ?? ''}｜${(turn.answer || '').slice(0, 140)}`)
  await page.screenshot({ path: `${OUT}/15-retest-approved.png` })
}

const songs = (await api(`/ai/music/playlists/${PLAYLIST_ID}/songs`)).body ?? []
log(`- 曲目：${JSON.stringify(songs.map((s) => s.id))}`)
log(`- console errors：${consoleErrors.length}`)

fs.writeFileSync(
  `${OUT}/retest-add.json`,
  JSON.stringify(
    {
      conversation: CONVERSATION,
      playlistId: PLAYLIST_ID,
      kind: turn.kind,
      state: turn.state ?? null,
      answer: turn.answer ?? '',
      songs: songs.map((s) => s.id),
      consoleErrors,
    },
    null,
    2,
  ),
)

await browser.close()
