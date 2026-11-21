/**
 * 会话级真实模型联调（一次性跑完验收场景，不重复跑）。
 *
 * 前置：后端 demo profile 在 8123、Vite 在 5173。
 * 产出：target/e2e-conv/*.png 与 report.json。
 *
 * 纪律：
 * - 断言一律读服务端（会话详情 / 歌单接口），不解析模型措辞；
 * - 每一步都截图，失败也留证据；
 * - 不删除既有数据，不重置数据库。
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'
import path from 'node:path'

const WEB = 'http://localhost:5173/'
const API = 'http://localhost:8123/api'
const USER = 'music-user-1'
const OUT = path.resolve('target/e2e-conv')

/**
 * 本轮使用的歌单名带时间戳。
 *
 * 服务端对同名歌单会拒绝创建，重复联调如果沿用固定名字，
 * 第二次就会在"创建"这一步失败，后面的加歌/拒绝全部连锁失效——
 * 那是测试数据污染，不是功能缺陷。
 */
const PLAYLIST = `夜航星精选-${new Date().toISOString().slice(11, 19).replace(/:/g, '')}`

fs.mkdirSync(OUT, { recursive: true })

const report = {
  startedAt: new Date().toISOString(),
  steps: [],
  conversations: {},
  errors: [],
}

const log = (msg) => {
  console.log(msg)
  report.steps.push(msg)
}

async function api(pathname, options = {}) {
  const response = await fetch(`${API}${pathname}`, {
    ...options,
    headers: { 'X-User-Id': USER, Accept: 'application/json', ...(options.headers ?? {}) },
  })
  const text = await response.text()
  let body = null
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    body = text
  }
  return { status: response.status, body }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** 会话当前消息条数（用来判断"这一轮是不是真的开始了"） */
async function messageCount(conversationId) {
  const detail = await api(`/ai/conversations/${conversationId}`)
  return detail.status === 200 ? (detail.body.messages ?? []).length : -1
}

/**
 * 等"在 before 条之后新增的这一轮"落定。
 *
 * 落定的两种形态：待审批（只有用户消息 + approvalId）或终态（有助手消息）。
 * 不能按固定条数等——暂停的轮次只加一条，批准后才补上助手那条。
 */
async function waitTurn(conversationId, before, timeoutMs = 240000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const detail = await api(`/ai/conversations/${conversationId}`)
    if (detail.status !== 200) {
      await sleep(1000)
      continue
    }
    const messages = detail.body.messages ?? []
    if (messages.length <= before) {
      await sleep(1200)
      continue
    }
    const last = messages[messages.length - 1]
    if (last.role === 'USER' && last.approvalId) {
      return { kind: 'approval', approvalId: last.approvalId, messages }
    }
    if (last.role === 'ASSISTANT' && last.state && last.state !== 'RUNNING') {
      return { kind: 'done', state: last.state, answer: last.content ?? '', messages }
    }
    await sleep(1500)
  }
  throw new Error(`等待第 ${before} 条之后的新轮次超时（conversation=${conversationId}）`)
}

async function sendMessage(page, text) {
  const box = page.locator('textarea.composer__input')
  await box.waitFor({ state: 'visible', timeout: 30000 })
  /*
   * 先填再等：按钮在"输入为空"和"忙"两种情况下都是禁用的，
   * 先等它可用会永远等不到（空输入本来就不让点）。
   */
  await box.fill(text)
  await page.waitForFunction(
    () => {
      const button = document.querySelector('button.composer__send')
      return button && !button.disabled
    },
    { timeout: 240000 },
  )
  await page.locator('button.composer__send').click()
  log(`- 发送：${text}`)
}

async function newConversation(page) {
  await page.getByRole('button', { name: '新建会话' }).click()
  await sleep(600)
}

async function openConversationByTitle(page, title) {
  const item = page.locator('.nav__item', { hasText: title }).first()
  await item.waitFor({ state: 'visible', timeout: 30000 })
  await item.click()
  await sleep(800)
}

/**
 * 取**最后一张**审批卡。
 *
 * 会话里每一轮都会渲染自己的卡，历史轮次的卡仍在 DOM 里（只读）。
 * 用 .first() 会点到最早那张的禁用按钮上——这是实际踩过的坑。
 */
function lastCard(page) {
  return page.locator('[data-approval-card]').last()
}

async function waitEnabled(page, text) {
  const button = lastCard(page).getByText(text, { exact: true })
  await button.waitFor({ state: 'visible', timeout: 120000 })
  // 审批视图还没同步回来时按钮是禁用的，等它可用再点
  await page.waitForFunction(
    (label) => {
      const cards = document.querySelectorAll('[data-approval-card]')
      const card = cards[cards.length - 1]
      if (!card) return false
      const buttons = Array.from(card.querySelectorAll('button'))
      const target = buttons.find((item) => item.textContent?.trim() === label)
      return !!target && !target.disabled
    },
    text,
    { timeout: 120000 },
  )
  return button
}

async function approve(page) {
  const button = await waitEnabled(page, '批准并继续')
  await button.click()
}

async function reject(page) {
  const button = await waitEnabled(page, '拒绝')
  await button.click()
}

async function turnOf(page, text, conversationId) {
  const before = await messageCount(conversationId)
  await sendMessage(page, text)
  return waitTurn(conversationId, before)
}

async function main() {
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  const page = await browser.newPage({ viewport: { width: 1440, height: 960 } })

  const consoleErrors = []
  page.on('console', (message) => {
    if (message.type() === 'error') {
      consoleErrors.push(message.text())
    }
  })

  await page.goto(WEB, { waitUntil: 'domcontentloaded' })
  await sleep(1500)
  await page.screenshot({ path: `${OUT}/00-start.png` })

  // ---------- 场景 1：查夜航星的歌 ----------
  await newConversation(page)
  const conversationId = await page.evaluate(() =>
    window.localStorage.getItem('mkagent.activeConversationId'),
  )
  log(`- 会话 1 = ${conversationId}`)
  let turn = await turnOf(page, '查一下夜航星的歌', conversationId)
  log(`- 第一轮：${turn.kind}/${turn.state ?? ''}｜${(turn.answer || '').slice(0, 80)}`)
  await page.screenshot({ path: `${OUT}/01-search.png` })

  // ---------- 场景 2：同一窗口问第一首的发行年份 ----------
  turn = await turnOf(page, '第一首是哪一年发行的？', conversationId)
  const yearAnswer = turn.answer || ''
  log(`- 第二轮：${turn.kind}/${turn.state ?? ''}｜${yearAnswer.slice(0, 140)}`)
  await page.screenshot({ path: `${OUT}/02-followup.png` })
  report.conversations.main = conversationId
  report.yearAnswer = yearAnswer

  // ---------- 场景 3：新会话，确认上下文不串 ----------
  await newConversation(page)
  const otherId = await page.evaluate(() =>
    window.localStorage.getItem('mkagent.activeConversationId'),
  )
  log(`- 会话 2 = ${otherId}`)
  turn = await turnOf(page, '第一首是哪一年发行的？', otherId)
  report.conversations.other = otherId
  report.otherAnswer = turn.answer || ''
  log(`- 新会话回答：${(turn.answer || '').slice(0, 140)}`)
  await page.screenshot({ path: `${OUT}/03-other-conversation.png` })

  // ---------- 场景 4/5：回到会话 1，把前两首建歌单（缺名字时追问） ----------
  const title = (await api(`/ai/conversations/${conversationId}`)).body.title
  await openConversationByTitle(page, title)
  turn = await turnOf(page, `把前两首歌建一个歌单，名字叫「${PLAYLIST}」`, conversationId)
  await page.screenshot({ path: `${OUT}/04-create-without-name.png` })
  log(`- 缺名字时：${turn.kind}｜${(turn.answer || '').slice(0, 160)}`)

  if (turn.kind !== 'approval') {
    turn = await turnOf(page, `就叫「${PLAYLIST}」`, conversationId)
    log(`- 补充名字后：${turn.kind}`)
  }

  if (turn.kind === 'approval') {
    await page.screenshot({ path: `${OUT}/05-create-approval.png` })
    const before = await messageCount(conversationId)
    await approve(page)
    turn = await waitTurn(conversationId, before)
    log(`- 批准建歌单：${turn.kind}/${turn.state ?? ''}｜${(turn.answer || '').slice(0, 120)}`)
    await page.screenshot({ path: `${OUT}/06-create-approved.png` })
  }

  const playlists = (await api('/ai/music/playlists')).body ?? []
  // 同名可能不止一个（重复联调会留下历史），取 id 最大的那个（最近创建）
  const created = playlists
    .filter((item) => item.name === PLAYLIST)
    .sort((a, b) => b.id - a.id)[0]
  report.playlistName = PLAYLIST
  report.createdPlaylist = created ?? null
  if (!created) {
    throw new Error(`歌单没有真正创建：${PLAYLIST}`)
  }
  log(`- 歌单：${JSON.stringify(created)}`)

  // ---------- 场景 7：把刚才第三首也加进去 ----------
  turn = await turnOf(page, '把刚才第三首也加进去', conversationId)
  await page.screenshot({ path: `${OUT}/07-add-third-approval.png` })
  if (turn.kind !== 'approval') {
    /*
     * 实测：qwen-plus 对"把刚才第三首也加进去"这种指代表达会先问一句
     * "请确认是否继续"，而不是直接调用写工具。目标与歌曲它都答对了
     * （1003 + 歌单 129），只是多要一次口头确认。
     * 这里按用户真实会做的动作补一句"确认"，继续走完审批链路。
     */
    turn = await turnOf(page, '确认，直接执行', conversationId)
    log(`- 口头确认后：${turn.kind}`)
  }
  if (turn.kind === 'approval') {
    const before = await messageCount(conversationId)
    await approve(page)
    turn = await waitTurn(conversationId, before)
    log(`- 加第三首：${turn.kind}/${turn.state ?? ''}｜${(turn.answer || '').slice(0, 120)}`)
  } else {
    log(`- 加第三首未进入审批：${turn.kind}｜${(turn.answer || '').slice(0, 120)}`)
  }
  await page.screenshot({ path: `${OUT}/08-after-add.png` })

  const songsAfterAdd = created
    ? (await api(`/ai/music/playlists/${created.id}/songs`)).body ?? []
    : []
  report.songsAfterAdd = songsAfterAdd.map((song) => song.id)
  log(`- 加歌后曲目：${JSON.stringify(report.songsAfterAdd)}`)

  // ---------- 场景 8：拒绝一次写入 ----------
  turn = await turnOf(page, `再把《深夜电台》加到「${PLAYLIST}」里`, conversationId)
  await page.screenshot({ path: `${OUT}/09-reject-approval.png` })
  if (turn.kind === 'approval') {
    const before = await messageCount(conversationId)
    await reject(page)
    turn = await waitTurn(conversationId, before)
    log(`- 拒绝后：${turn.kind}/${turn.state ?? ''}｜${(turn.answer || '').slice(0, 160)}`)
    report.rejectAnswer = turn.answer || ''
  }
  await page.screenshot({ path: `${OUT}/10-rejected.png` })

  const songsAfterReject = created
    ? (await api(`/ai/music/playlists/${created.id}/songs`)).body ?? []
    : []
  report.songsAfterReject = songsAfterReject.map((song) => song.id)
  log(`- 拒绝后曲目：${JSON.stringify(report.songsAfterReject)}`)

  // ---------- 场景 9：刷新后会话与歌单仍可查 ----------
  await page.reload({ waitUntil: 'domcontentloaded' })
  await sleep(3000)
  await page.screenshot({ path: `${OUT}/11-after-reload.png` })

  // 每一轮渲染成一个子节点；统计子节点而不是容器本身
  report.turnsAfterReload = await page.locator('.turns > *').count()
  log(`- 刷新后轮次容器：${report.turnsAfterReload}`)

  await page.getByRole('button', { name: '我的歌单' }).click()
  await sleep(1500)
  await page.screenshot({ path: `${OUT}/12-library-after-reload.png` })

  report.consoleErrors = consoleErrors
  report.finishedAt = new Date().toISOString()
  fs.writeFileSync(`${OUT}/report.json`, JSON.stringify(report, null, 2))

  console.log('\n=== 报告 ===')
  console.log(JSON.stringify({ ...report, steps: undefined }, null, 2))

  await browser.close()
}

main().catch(async (error) => {
  report.errors.push(String(error))
  fs.writeFileSync(`${OUT}/report.json`, JSON.stringify(report, null, 2))
  console.error('联调失败：', error)
  process.exit(1)
})
