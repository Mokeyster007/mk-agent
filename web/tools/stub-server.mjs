/**
 * 契约桩服务（仅前端联调使用）
 * ---------------------------------------------------------------
 * 作用：在没有启动真实后端、也不想消耗模型额度时，用真实 HTTP 契约
 * 跑通前端的 SSE 解析、状态轮询、审批卡片与错误分支。
 *
 * 它**不是**后端，也不代表真实 Agent 行为：
 * - 所有事件都是按后端 AgentEvent 结构手写的固定帧；
 * - 数据是内存里的假任务，重启即清空；
 * - 不连接任何数据库，不调用任何模型。
 *
 * 因此凡是"用桩服务截出来的图"，只能说明界面与传输层工作正常，
 * 不能说明模型效果或业务正确性。
 *
 * 启动：node tools/stub-server.mjs [port]
 */
import http from 'node:http'

const PORT = Number(process.argv[2] || process.env.PORT || 8123)
const USER_ID = 'music-user-1'

/** 内存任务表 */
const runs = new Map()
/** 内存审批表 */
const approvals = new Map()

let seq = 0
const now = () => new Date().toISOString().slice(0, 19)

function sse(res) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream;charset=UTF-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no',
  })
  return (event, data) => {
    res.write(`event:${event}\n`)
    res.write(`data:${JSON.stringify(data)}\n\n`)
  }
}

function frame(type, runId, message, step = null) {
  return { runId, type, message, step, timestamp: Date.now() }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function json(res, status, body) {
  const payload = JSON.stringify(body)
  res.writeHead(status, { 'Content-Type': 'application/json;charset=UTF-8' })
  res.end(payload)
}

function requireUser(req, res) {
  const userId = req.headers['x-user-id']
  if (!userId) {
    json(res, 401, {
      success: false,
      message: '缺少用户身份，请在请求头中携带 X-User-Id',
    })
    return null
  }
  return String(userId)
}

const SONGS = [
  '夜航星 - 城市霓虹',
  '夜航星 - 午夜电台',
  '夜航星 - 沿海公路',
  '夜航星 - 玻璃海',
  '夜航星 - 灯塔',
]

/** 主流程：模拟一次只读查询 */
async function runQuery(res, runId, prompt) {
  const send = sse(res)

  send('run_id', frame('run_id', runId, ''))
  await sleep(220)

  send('status', frame('status', runId, '任务开始执行', 1))
  await sleep(180)

  send('tool_start', frame('tool_start', runId, '正在调用 search_songs 搜索歌曲', 1))
  await sleep(420)

  send('tool_result', frame('tool_result', runId, 'search_songs 返回 5 条结果', 1))
  await sleep(200)

  send('step', frame('step', runId, '第 1 轮执行完成', 1))
  await sleep(160)

  const answer = [
    '夜航星一共有 **5 首**歌曲，都收录在同名专辑《城市霓虹》里：',
    '',
    ...SONGS.map((song, index) => `${index + 1}. ${song}`),
    '',
    '需要我把其中几首加入某个歌单吗？（当前装配为只读，写操作尚未开放）',
  ].join('\n')

  send('final_answer', frame('final_answer', runId, answer, 2))
  await sleep(120)
  send('done', frame('done', runId, '[DONE]', 2))
  res.end()

  runs.set(runId, {
    runId,
    userId: USER_ID,
    agentType: 'MUSIC_USER',
    userPrompt: prompt,
    state: 'SUCCEEDED',
    currentStep: 2,
    toolCallCount: 1,
    toolTrace: 'search_songs,terminate_task',
    finalAnswer: answer,
    errorMessage: null,
    errorCode: null,
    model: 'stub-model',
    promptTokens: 1180,
    completionTokens: 246,
    totalTokens: 1426,
    startedAt: now(),
    finishedAt: now(),
    totalCostMillis: 1240,
    createdAt: now(),
  })
}

/** 审批流程：先暂停，批准/拒绝后继续 */
async function runApproval(res, runId, prompt) {
  const send = sse(res)
  const approvalId = `ap-${Date.now()}`

  send('run_id', frame('run_id', runId, ''))
  await sleep(200)
  send('tool_start', frame('tool_start', runId, '正在调用 search_songs 搜索歌曲', 1))
  await sleep(300)

  const payload = {
    approvalId,
    expiresAt: new Date(Date.now() + 30 * 60_000).toISOString().slice(0, 19),
    summary: {
      tool: 'create_playlist',
      toolLabel: '创建歌单',
      playlistName: '清晨慢跑',
      songCount: 2,
      songs: ['城市霓虹', '午夜电台'],
      roundToolCount: 1,
      text: '即将创建歌单「清晨慢跑」，并放入 2 首歌曲。',
    },
  }

  send('status', frame('status', runId, '等待人工确认，本段连接结束。', 2))
  send('approval_required', frame('approval_required', runId, JSON.stringify(payload), 2))
  send('done', frame('done', runId, '[DONE]', 2))
  res.end()

  approvals.set(approvalId, {
    approvalId,
    runId,
    agentType: 'MUSIC_USER',
    step: 2,
    primaryTool: 'create_playlist',
    toolCount: 1,
    summaryText: payload.summary.text,
    summary: payload.summary,
    status: 'PENDING',
    resumeState: 'PENDING_RESUME',
    resumeAttempts: 0,
    decidedAt: null,
    expiresAt: payload.expiresAt,
  })

  runs.set(runId, {
    runId,
    userId: USER_ID,
    agentType: 'MUSIC_USER',
    userPrompt: prompt,
    state: 'WAITING_APPROVAL',
    currentStep: 2,
    toolCallCount: 1,
    toolTrace: 'search_songs',
    finalAnswer: null,
    errorMessage: null,
    errorCode: null,
    model: 'stub-model',
    promptTokens: 900,
    completionTokens: 120,
    totalTokens: 1020,
    startedAt: now(),
    finishedAt: null,
    totalCostMillis: null,
    createdAt: now(),
  })
}

async function finishApproval(res, approvalId, approved) {
  const approval = approvals.get(approvalId)
  if (!approval) {
    json(res, 404, { success: false, code: 'APPROVAL_NOT_FOUND', message: '审批不存在' })
    return
  }
  if (approval.status !== 'PENDING') {
    json(res, 409, { success: false, code: 'APPROVAL_NOT_PENDING', message: '审批已不在待确认状态' })
    return
  }

  approval.status = approved ? 'APPROVED' : 'REJECTED'
  approval.resumeState = 'CLAIMED'
  approval.decidedAt = now()

  const run = runs.get(approval.runId)
  if (run) {
    run.state = 'RUNNING'
  }

  const send = sse(res)
  send('run_id', frame('run_id', approval.runId, ''))
  await sleep(180)

  if (approved) {
    send('tool_start', frame('tool_start', approval.runId, '正在调用 create_playlist 创建歌单', 3))
    await sleep(420)
    send('tool_result', frame('tool_result', approval.runId, 'create_playlist 执行完成', 3))
    await sleep(160)
  } else {
    send('tool_result', frame('tool_result', approval.runId, '用户拒绝了本次写操作，未执行', 3))
    await sleep(160)
  }

  const answer = approved
    ? '已按你的批准创建歌单「清晨慢跑」，并放入《城市霓虹》《午夜电台》两首歌。'
    : '你拒绝了这次写操作，因此没有创建歌单，也没有改动任何数据。'

  send('final_answer', frame('final_answer', approval.runId, answer, 4))
  send('done', frame('done', approval.runId, '[DONE]', 4))
  res.end()

  approval.resumeState = 'FINISHED'
  if (run) {
    run.state = 'SUCCEEDED'
    run.finalAnswer = answer
    run.finishedAt = now()
    run.toolTrace = approved
      ? 'search_songs,create_playlist,terminate_task'
      : 'search_songs,terminate_task'
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`)
  const path = url.pathname

  // 统一身份校验（与真实后端一致：缺头 401）
  if (!path.startsWith('/api')) {
    json(res, 404, { success: false, message: '资源不存在' })
    return
  }
  const userId = requireUser(req, res)
  if (!userId) {
    return
  }

  // ---- 音乐入口 ----
  if (path === '/api/ai/music/chat' || path === '/api/ai/music/admin/chat') {
    const prompt = url.searchParams.get('message') || ''
    if (!prompt.trim()) {
      json(res, 400, { success: false, message: '缺少 message 参数' })
      return
    }
    // 恢复门禁分支：方便验证 503 文案
    if (prompt.includes('recovery-test')) {
      json(res, 503, {
        success: false,
        code: 'RECOVERY_IN_PROGRESS',
        message: '服务正在恢复中断的任务',
      })
      return
    }
    if (path === '/api/ai/music/admin/chat' && userId !== 'music-admin-1') {
      json(res, 403, { success: false, message: '无权访问音乐管理接口' })
      return
    }

    seq += 1
    const runId = `stub-run-${seq}`
    if (prompt.includes('建一个歌单') || prompt.includes('创建歌单')) {
      await runApproval(res, runId, prompt)
    } else {
      await runQuery(res, runId, prompt)
    }
    return
  }

  // ---- 任务查询 ----
  if (path === '/api/ai/runs/page') {
    const records = [...runs.values()].sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
    json(res, 200, {
      pageNum: 1,
      pageSize: 20,
      total: records.length,
      pages: records.length ? 1 : 0,
      records,
    })
    return
  }

  const runMatch = path.match(/^\/api\/ai\/runs\/([^/]+)$/)
  if (runMatch) {
    const run = runs.get(runMatch[1])
    if (!run) {
      json(res, 404, { success: false, message: '任务不存在' })
      return
    }
    json(res, 200, run)
    return
  }

  const cancelMatch = path.match(/^\/api\/ai\/runs\/([^/]+)\/cancel$/)
  if (cancelMatch) {
    const run = runs.get(cancelMatch[1])
    if (!run) {
      json(res, 404, { success: false, message: '任务不存在' })
      return
    }
    run.state = 'CANCELLED'
    json(res, 200, { success: true, runId: run.runId, state: 'CANCELLED' })
    return
  }

  // ---- 审批 ----
  const approveMatch = path.match(/^\/api\/ai\/approvals\/([^/]+)\/(approve|reject|resume)$/)
  if (approveMatch) {
    await finishApproval(res, approveMatch[1], approveMatch[2] === 'approve')
    return
  }

  const approvalMatch = path.match(/^\/api\/ai\/approvals\/([^/]+)$/)
  if (approvalMatch) {
    const approval = approvals.get(approvalMatch[1])
    if (!approval) {
      json(res, 404, { success: false, code: 'APPROVAL_NOT_FOUND', message: '审批不存在' })
      return
    }
    json(res, 200, approval)
    return
  }

  json(res, 404, { success: false, message: '资源不存在' })
})

server.listen(PORT, () => {
  process.stdout.write(`stub backend on http://localhost:${PORT} (契约桩，非真实后端)\n`)
})
