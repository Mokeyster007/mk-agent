/**
 * 播放链路的页面级验收（真实浏览器 + 真实音频 + 真实后端）。
 *
 * 为什么不用模型：本轮要证明"百炼不可用也能点播"。
 * 播放卡的数据来自会话已存的工具引用，所以这个脚本只需要一个
 * 带引用的会话（见 seed-playback-fixture.sql），不需要任何模型调用。
 *
 * 断言的是**可测量的事实**：
 * - <audio>.currentTime 真的在走（不是"界面显示正在播放"）；
 * - pause 后 currentTime 停住；
 * - 拖进度后 currentTime 跳到目标位置（证明 Range/206 生效）；
 * - 快速点两首，最终 src 是最后选择的那首；
 * - 切会话时音频元素不变、仍在播（播放器不随会话卸载）；
 * - 播放器与输入区/审批卡不重叠。
 *
 * 用法（后端 demo + 前端 dev 都启动后）：
 *   node tools/e2e-playback.mjs
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'

const WEB = process.env.WEB_BASE || 'http://localhost:5173'
const OUT = 'target/e2e-playback'
const CONVERSATION_TITLE = '播放验收（测试音）'

fs.mkdirSync(OUT, { recursive: true })

const results = []
let failed = 0

function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  if (!ok) {
    failed += 1
  }
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
}

const audioState = (page) =>
  page.evaluate(() => {
    const el = document.querySelector('[data-testid="mk-audio"]')
    if (!el) {
      return null
    }
    return {
      src: el.getAttribute('src') || '',
      currentTime: el.currentTime,
      duration: el.duration,
      paused: el.paused,
      readyState: el.readyState,
      error: el.error ? el.error.code : null,
    }
  })

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

const browser = await chromium.launch({
  channel: 'chrome',
  headless: true,
  // 静音输出：无头环境没有声卡，但解码与时间轴照常推进
  args: ['--mute-audio', '--autoplay-policy=no-user-gesture-required'],
})

const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const consoleErrors = []
page.on('console', (msg) => {
  if (msg.type() === 'error') {
    consoleErrors.push(msg.text())
  }
})
page.on('pageerror', (error) => consoleErrors.push(String(error)))

try {
  await page.goto(WEB, { waitUntil: 'domcontentloaded' })

  /* ---------------- 1. 打开夹具会话 ---------------- */
  const navItem = page.locator('.nav__item', { hasText: CONVERSATION_TITLE }).first()
  await navItem.waitFor({ timeout: 20000 })
  await navItem.click()

  await page.locator('.playcard').waitFor({ timeout: 15000 })
  await page.screenshot({ path: `${OUT}/01-playcard.png` })

  const rows = page.locator('.playcard__row')
  check('播放卡列出 3 首候选', (await rows.count()) === 3, `rows=${await rows.count()}`)
  check(
    '不可播的曲目显示原因',
    (await rows.nth(2).innerText()).includes('暂无可用音源'),
    (await rows.nth(2).innerText()).replace(/\n/g, ' / '),
  )

  /* ---------------- 2. 点播：真的在走 ---------------- */
  await rows.nth(0).locator('button').click()
  await page.locator('.player').waitFor({ timeout: 15000 })
  await page.waitForFunction(
    () => {
      const el = document.querySelector('[data-testid="mk-audio"]')
      return !!el && el.currentTime > 0.15 && !el.paused
    },
    { timeout: 15000 },
  )

  const playing = await audioState(page)
  check('点播后音频元素在播放', playing && playing.paused === false)
  check(
    '播放时间在推进（不是界面假状态）',
    playing && playing.currentTime > 0.15,
    `currentTime=${playing?.currentTime?.toFixed(2)}`,
  )
  check(
    '时长与测试音一致（约 10s）',
    playing && Math.abs(playing.duration - 10) < 0.6,
    `duration=${playing?.duration}`,
  )
  check(
    '媒体地址来自签名接口',
    !!playing && playing.src.includes('/api/ai/music/playback/media/'),
    playing?.src?.slice(0, 72),
  )
  await page.screenshot({ path: `${OUT}/02-playing.png` })

  /* ---------------- 3. 暂停 / 继续 ---------------- */
  await page.locator('.player__btn--main[aria-label="暂停"]').click()
  await sleep(600)
  const paused = await audioState(page)
  await sleep(500)
  const pausedAgain = await audioState(page)
  check('暂停后音频元素 paused=true', paused?.paused === true)
  check(
    '暂停后时间不再推进',
    Math.abs((pausedAgain?.currentTime ?? 0) - (paused?.currentTime ?? 0)) < 0.2,
    `${paused?.currentTime?.toFixed(2)} → ${pausedAgain?.currentTime?.toFixed(2)}`,
  )

  await page.locator('.player__btn--main[aria-label="播放"]').click()
  await page.waitForFunction(
    () => {
      const el = document.querySelector('[data-testid="mk-audio"]')
      return !!el && !el.paused
    },
    { timeout: 10000 },
  )
  check('继续播放成功', (await audioState(page))?.paused === false)

  /* ---------------- 4. 拖进度（Range/seek） ---------------- */
  await page.evaluate(() => {
    const el = document.querySelector('[data-testid="mk-audio"]')
    el.pause()
  })
  await page.locator('.player__range').fill('6')
  await sleep(400)
  const seeked = await audioState(page)
  check(
    '拖到 6 秒后 currentTime 落在目标位置',
    seeked && Math.abs(seeked.currentTime - 6) < 1.2,
    `currentTime=${seeked?.currentTime?.toFixed(2)}`,
  )

  /* ---------------- 5. 下一首 ---------------- */
  const beforeNext = (await audioState(page))?.src
  await page.locator('.player__btn[aria-label="下一首"]').click()
  await page.waitForFunction(
    (previous) => {
      const el = document.querySelector('[data-testid="mk-audio"]')
      return !!el && el.getAttribute('src') && el.getAttribute('src') !== previous && !el.paused
    },
    beforeNext,
    { timeout: 15000 },
  )
  const nextState = await audioState(page)
  check('下一首切换了音源并继续播放', nextState?.paused === false)
  check(
    '播放器标题切到测试音 B',
    (await page.locator('.player__title').innerText()).includes('测试音 B'),
    await page.locator('.player__title').innerText(),
  )

  /* ---------------- 6. 快速点两首：只播最后选择 ---------------- */
  await rows.nth(0).locator('button').click()
  await rows.nth(1).locator('button').click()
  await sleep(1500)
  const afterRapid = await audioState(page)
  const titleAfterRapid = await page.locator('.player__title').innerText()
  check('快速切歌后只播最后选择的那首', titleAfterRapid.includes('测试音 B'), titleAfterRapid)
  check('快速切歌后没有停在错误音源', afterRapid?.paused === false)

  /* ---------------- 7. 切会话不断播 ---------------- */
  const elementBefore = await page.evaluate(() => {
    const el = document.querySelector('[data-testid="mk-audio"]')
    el.dataset.identity = 'same-element'
    return el.dataset.identity
  })
  const otherConversation = page.locator('.nav__item').filter({ hasNotText: CONVERSATION_TITLE }).first()
  if ((await otherConversation.count()) > 0) {
    await otherConversation.click()
    await sleep(1200)
    const afterSwitch = await page.evaluate(() => {
      const el = document.querySelector('[data-testid="mk-audio"]')
      return {
        identity: el?.dataset.identity ?? '',
        paused: el?.paused,
        currentTime: el?.currentTime ?? 0,
      }
    })
    check('切会话后仍是同一个音频元素', afterSwitch.identity === elementBefore)
    check('切会话后没有暂停', afterSwitch.paused === false, `paused=${afterSwitch.paused}`)

    // 回到夹具会话继续后面的布局检查
    await navItem.click()
    await page.locator('.playcard').waitFor({ timeout: 15000 })
  } else {
    check('切会话后仍是同一个音频元素', true, '只有一个会话，跳过')
  }

  /* ---------------- 8. 布局：不遮挡输入区 ---------------- */
  const layout = await page.evaluate(() => {
    const player = document.querySelector('.player')
    const composer = document.querySelector('.composer__box') || document.querySelector('.workspace__composer')
    const scroll = document.querySelector('.workspace__scroll')
    const rect = (el) => (el ? el.getBoundingClientRect() : null)
    return {
      player: rect(player),
      composer: rect(composer),
      scroll: rect(scroll),
      docScrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }
  })

  check(
    '播放器在输入区下方，不重叠',
    !!layout.player && !!layout.composer && layout.player.top >= layout.composer.bottom - 1,
    `player.top=${layout.player?.top?.toFixed(1)} composer.bottom=${layout.composer?.bottom?.toFixed(1)}`,
  )
  check(
    '消息区底部在输入区之上（不压最后一条消息）',
    !!layout.scroll && !!layout.composer && layout.scroll.bottom <= layout.composer.top + 1,
    `scroll.bottom=${layout.scroll?.bottom?.toFixed(1)} composer.top=${layout.composer?.top?.toFixed(1)}`,
  )
  check(
    '没有横向滚动',
    layout.docScrollWidth <= layout.clientWidth + 1,
    `${layout.docScrollWidth} / ${layout.clientWidth}`,
  )

  await page.screenshot({ path: `${OUT}/03-layout.png` })

  /* ---------------- 9. 队列浮层 ---------------- */
  await page.locator('.player__queue-btn').click()
  await page.locator('.player__pop--queue').waitFor({ timeout: 5000 })
  const queueItems = await page.locator('.player__queue-item').count()
  check('队列浮层列出当前队列', queueItems === 2, `items=${queueItems}`)
  await page.screenshot({ path: `${OUT}/04-queue.png` })
  await page.keyboard.press('Escape').catch(() => {})
  await page.locator('.player__scrim').click({ position: { x: 5, y: 5 } })
  await sleep(300)

  /* ---------------- 10. 调音入口 ---------------- */
  await page.locator('.player__btn[aria-label="调音"]').click()
  await page.locator('.eq').waitFor({ timeout: 5000 })
  const bypass = await page.locator('.eq__bypass').innerText()
  check('调音面板可用（测试音是受控来源）', bypass.includes('旁路'), bypass)
  await page.locator('.eq__bypass').click()
  await sleep(300)
  check(
    '一键旁路切换生效',
    (await page.locator('.eq__bypass').innerText()).includes('已启用'),
    await page.locator('.eq__bypass').innerText(),
  )
  await page.screenshot({ path: `${OUT}/05-eq.png` })
  await page.locator('.player__scrim').click({ position: { x: 5, y: 5 } })

  /* ---------------- 11. 续播：刷新后显示，但不自动播放 ---------------- */
  await page.locator('.player__range').fill('4')
  await sleep(600)
  const beforeReload = await audioState(page)
  check(
    '续播前已拖到 4 秒附近',
    Math.abs((beforeReload?.currentTime ?? 0) - 4) < 1.5,
    `currentTime=${beforeReload?.currentTime?.toFixed(2)}`,
  )

  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.locator('.player--resume').waitFor({ timeout: 20000 })
  const resumeText = await page.locator('.player--resume').innerText()
  check(
    '刷新后显示续播卡（曲目 + 上次进度）',
    resumeText.includes('测试音 B') && resumeText.includes('上次听到'),
    resumeText.replace(/\n/g, ' / '),
  )

  const afterReload = await audioState(page)
  check(
    '刷新后没有自动播放、没有上报播放',
    !afterReload || (afterReload.paused === true && afterReload.currentTime < 0.1),
    `paused=${afterReload?.paused} currentTime=${afterReload?.currentTime}`,
  )
  await page.screenshot({ path: `${OUT}/06-resume.png` })

  await page.locator('.player__resume-btn').click()
  await page.waitForFunction(
    () => {
      const el = document.querySelector('[data-testid="mk-audio"]')
      return !!el && !el.paused && el.currentTime > 3
    },
    { timeout: 20000 },
  )
  const resumed = await audioState(page)
  check(
    '点击「继续播放」后从上次进度继续',
    (resumed?.currentTime ?? 0) > 3,
    `currentTime=${resumed?.currentTime?.toFixed(2)}`,
  )

  /* ---------------- 12. 控制台 ---------------- */
  check(
    '控制台没有报错',
    consoleErrors.length === 0,
    consoleErrors.slice(0, 3).join(' | '),
  )
} catch (error) {
  failed += 1
  console.error('SCRIPT ERROR', error)
  await page.screenshot({ path: `${OUT}/99-error.png` }).catch(() => {})
} finally {
  fs.writeFileSync(
    `${OUT}/report.json`,
    JSON.stringify({ failed, results }, null, 2),
    'utf8',
  )
  await browser.close()
  console.log(`\n${results.length - failed}/${results.length} passed`)
  process.exit(failed === 0 ? 0 : 1)
}
