/**
 * 验收复查（第二轮取样）：
 *  A. M1 同一句话再问一次，捕获 Agent 的真实回答（第一轮没有确认卡，需区分"模型不可用"与"Agent 追问"）
 *  B. M3 把"重新发起"真正提交一次，验证驳回后能从原订单再建一张新单
 *  C. 核对已驳回单在买家列表里的"预计退款"文案
 *
 *   node tools/acc-aftersale-recheck.mjs
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'

const WEB = process.env.WEB_BASE || 'http://localhost:5173'
const OUT = 'target/acc-aftersale'
const BUYER = 'shop-user-1'
const SIDE_ORDER = 'SO20261123221056-36EE'
const SIDE_RETURN = 'RT20261123221059-A4B4'

fs.mkdirSync(OUT, { recursive: true })
const log = []
const say = (tag, text) => {
  const line = `${tag}  ${text}`
  log.push(line)
  console.log(line)
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--mute-audio'] })
const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
const page = await context.newPage()

async function shot(name) {
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true }).catch(() => {})
}

async function openAftersale() {
  await page.goto(`${WEB}/shop`, { waitUntil: 'domcontentloaded' })
  await page.selectOption('.shop-nav__select', BUYER)
  await sleep(400)
  await page.locator('.shop-nav__links').getByRole('link', { name: '售后与退货' }).click()
  await page.waitForURL('**/shop/aftersale', { timeout: 15000 })
  await page.locator('.orders').waitFor({ timeout: 20000 })
}

try {
  /* ---------- A. M1 复查：同一句话，捕获回答 ---------- */
  say('NOTE', 'A. M1 复查开始（同一句话，不提供订单号）')
  await openAftersale()

  const before = await page.locator('.return').count()
  const turnsBefore = await page.locator('[data-aftersale-chat] [data-turn-id]').count()
  say('EVIDENCE', `复查前：退货单 ${before} 条，对话轮次 ${turnsBefore}`)

  await page.locator('.chat__input').fill('我买的那只鼠标按键不太灵，想退掉，帮我处理一下')
  await page.locator('.chat__composer').getByRole('button', { name: '发送' }).click()

  const deadline = Date.now() + 180000
  let answer = ''
  let hasCard = false
  let steps = []
  while (Date.now() < deadline) {
    hasCard = (await page.locator('[data-approval-card]').count()) > 0
    const turns = await page.locator('[data-aftersale-chat] [data-turn-id]').all()
    if (turns.length > turnsBefore) {
      const last = turns[turns.length - 1]
      answer = (await last.locator('.turn__answer').innerText().catch(() => '')).trim()
      steps = await last.locator('.turn__step').allInnerTexts().catch(() => [])
      if (answer || hasCard) break
    }
    await sleep(600)
  }

  const after = await page.locator('.return').count()
  say('EVIDENCE', `复查结果：确认卡=${hasCard ? '有' : '无'}，新增退货单=${after - before} 条`)
  say('EVIDENCE', `Agent 执行步骤：${steps.join(' / ') || '（未显示步骤）'}`)
  say('EVIDENCE', `Agent 回答：${answer.replace(/\n+/g, ' / ').slice(0, 600) || '（空）'}`)
  await shot('recheck-m1-agent')

  /* ---------- C. 已驳回单的"预计退款"文案 ---------- */
  say('NOTE', 'C. 核对已驳回单在列表里的金额文案')
  const rejectedRow = page.locator(`.return[data-return-no="${SIDE_RETURN}"]`)
  if (await rejectedRow.count()) {
    const t = (await rejectedRow.innerText()).replace(/\n+/g, ' / ')
    say('EVIDENCE', `已驳回列表行：${t.slice(0, 260)}`)
    const mentionsEstimate = t.includes('预计退款')
    say('NOTE', `已驳回行是否仍显示"预计退款"：${mentionsEstimate ? '是' : '否'}`)
  } else {
    say('NOTE', '列表里找不到已驳回单，跳过 C')
  }

  /* ---------- B. M3 真正重新发起 ---------- */
  say('NOTE', 'B. 从原订单重新发起一张新申请')
  const card = page.locator(`.order[data-order-no="${SIDE_ORDER}"]`)
  await card.waitFor({ timeout: 20000 })
  const chipBefore = (await card.locator('.chip').innerText()).trim()
  const quotaBefore = (await card.locator('.order__quota').innerText()).replace(/\n+/g, ' ')
  say('EVIDENCE', `重新发起前：${chipBefore} / ${quotaBefore}`)

  await card.getByRole('button', { name: '申请退货' }).click()
  await sleep(400)
  const form = page.locator('.form')
  const prefilledOrder = await form.locator('select').inputValue()
  say('EVIDENCE', `点「申请退货」后表单订单号=${prefilledOrder}`)

  await form.locator('textarea').fill('复查：驳回后重新发起，键盘外壳有裂痕')
  const ok = page.locator('.banner--ok')
  const beforeOk = (await ok.count()) ? await ok.innerText() : ''
  await form.getByRole('button', { name: '提交退货申请' }).click()

  let notice = ''
  const t2 = Date.now() + 25000
  while (Date.now() < t2) {
    if (await ok.count()) {
      const text = await ok.innerText()
      if (text && text !== beforeOk) {
        notice = text
        break
      }
    }
    await sleep(150)
  }
  say('EVIDENCE', `重新发起结果：${notice.replace(/\n+/g, ' ').slice(0, 240) || '（没有成功提示）'}`)
  const newNo = (/RT\d{14}-[0-9A-F]{4}/.exec(notice) || [])[0] || ''
  say('NOTE', `新单号=${newNo || '未生成'}，旧单 ${SIDE_RETURN} 是否被替换：${newNo && newNo !== SIDE_RETURN ? '否（新增一张）' : '未知'}`)

  await page.reload({ waitUntil: 'domcontentloaded' })
  await sleep(700)
  const quotaAfter = (await page.locator(`.order[data-order-no="${SIDE_ORDER}"] .order__quota`).innerText()).replace(/\n+/g, ' ')
  say('EVIDENCE', `重新发起后额度：${quotaAfter}`)
  const oldRow = page.locator(`.return[data-return-no="${SIDE_RETURN}"]`)
  if (await oldRow.count()) {
    say('EVIDENCE', `旧驳回单仍在列表：${(await oldRow.innerText()).replace(/\n+/g, ' / ').slice(0, 160)}`)
  }
  await shot('recheck-m3-reapply')
} catch (error) {
  await shot('recheck-failure')
  say('FAIL', `复查中断：${String(error).slice(0, 400)}`)
} finally {
  await browser.close()
  fs.writeFileSync(`${OUT}/recheck.log`, log.join('\n'), 'utf8')
}
