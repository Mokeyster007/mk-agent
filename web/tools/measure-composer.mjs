/**
 * 输入区稳定性测量（一次性诊断脚本）。
 *
 * 目的：在改动样式**之前**，用真实数据把"输入框跳动"量出来。
 * 测量项：输入框（.composer__box）的左/宽/底边，以及所有可能滚动的容器
 * 的 clientHeight / scrollHeight。切换会话前后对比，差异超过 2px 即为跳动。
 *
 * 前置：后端 demo 在 8123、Vite 在 5173（只用查询接口，不触发模型调用）。
 * 用法：node tools/measure-composer.mjs [outfile]
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'
import path from 'node:path'

const WEB = 'http://localhost:5173/'
const OUT = path.resolve(process.argv[2] || 'target/layout/before.json')

/** 真实演示数据（来自 demo 库，不新建、不删除） */
const CASES = [
  { key: 'empty', label: '空会话', conversationId: null },
  {
    key: 'short',
    label: '短会话（2 条消息）',
    conversationId: 'd378b57b-92fd-4ac5-9c78-a65a4b5ab504',
  },
  {
    key: 'long',
    label: '长会话（12 条消息）',
    conversationId: '347c28a8-9581-4c38-8f24-20c942579191',
  },
  {
    key: 'pending',
    label: '待审批会话',
    conversationId: '8c7ed085-51b0-4dab-b542-66ac5a286704',
  },
]

const VIEWPORTS = [
  { width: 1440, height: 900 },
  { width: 1280, height: 720 },
  { width: 390, height: 844 },
]

const MEASURE = () => {
  const box = document.querySelector('.composer__box')
  const composer = document.querySelector('.workspace__composer')
  const rect = (el) => {
    if (!el) return null
    const r = el.getBoundingClientRect()
    return {
      left: +r.left.toFixed(1),
      width: +r.width.toFixed(1),
      top: +r.top.toFixed(1),
      bottom: +r.bottom.toFixed(1),
    }
  }

  /** 找出页面上真正会纵向滚动的容器 */
  const scrollers = []
  document.querySelectorAll('*').forEach((el) => {
    const style = getComputedStyle(el)
    const scrollable = /auto|scroll/.test(style.overflowY)
    if (!scrollable) return
    if (el.scrollHeight - el.clientHeight > 1) {
      scrollers.push({
        selector:
          el.className && typeof el.className === 'string'
            ? `.${el.className.split(' ')[0]}`
            : el.tagName.toLowerCase(),
        clientHeight: el.clientHeight,
        scrollHeight: el.scrollHeight,
        scrollTop: el.scrollTop,
      })
    }
  })

  return {
    box: rect(box),
    composer: rect(composer),
    textarea: rect(document.querySelector('.composer__input')),
    hintVisible: !!document.querySelector('.workspace__block'),
    shellContent: (() => {
      const el = document.querySelector('.shell__content')
      return el
        ? { clientHeight: el.clientHeight, scrollHeight: el.scrollHeight, scrollTop: el.scrollTop }
        : null
    })(),
    messageScroll: (() => {
      const el = document.querySelector('.workspace__scroll')
      return el
        ? { clientHeight: el.clientHeight, scrollHeight: el.scrollHeight, scrollTop: el.scrollTop }
        : null
    })(),
    docScroll: {
      innerHeight: window.innerHeight,
      docHeight: document.documentElement.scrollHeight,
      bodyHeight: document.body.scrollHeight,
    },
    horizontalOverflow:
      document.documentElement.scrollWidth - document.documentElement.clientWidth,
    scrollers,
  }
}

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const report = { capturedAt: new Date().toISOString(), viewports: [] }

for (const viewport of VIEWPORTS) {
  const context = await browser.newContext({
    viewport,
    deviceScaleFactor: 1,
    locale: 'zh-CN',
  })
  const page = await context.newPage()
  const entry = { viewport: `${viewport.width}x${viewport.height}`, cases: [] }

  for (const testCase of CASES) {
    // 用 localStorage 指定会话，然后整页刷新——等价于"打开/切换会话"
    await page.goto(WEB, { waitUntil: 'domcontentloaded' })
    await page.evaluate((id) => {
      if (id) {
        window.localStorage.setItem('mkagent.activeConversationId', id)
      } else {
        window.localStorage.removeItem('mkagent.activeConversationId')
      }
    }, testCase.conversationId)
    await page.goto(WEB, { waitUntil: 'networkidle' })
    // 等消息渲染稳定（会话详情是异步拉的）
    await page.waitForTimeout(900)

    const measured = await page.evaluate(MEASURE)
    entry.cases.push({ ...testCase, measured })
    console.log(
      `${entry.viewport} ${testCase.key.padEnd(7)} box.left=${measured.box?.left} ` +
        `width=${measured.box?.width} bottom=${measured.box?.bottom} ` +
        `hint=${measured.hintVisible} ` +
        `contentScroll=${measured.shellContent?.scrollHeight}/${measured.shellContent?.clientHeight}`,
    )
  }

  report.viewports.push(entry)
  await context.close()
}

fs.mkdirSync(path.dirname(OUT), { recursive: true })
fs.writeFileSync(OUT, JSON.stringify(report, null, 2))
await browser.close()
console.log(`\n写入 ${OUT}`)
