# Cadence · 音乐 Agent 工作台（前端）

面向音乐任务的前端工作台，连接本仓库现有的 Spring Boot / Spring AI 后端。

它**不是**播放器，也不是后台管理模板：没有假播放进度、没有波形动画、没有虚构的封面。
界面只展示后端真的给出的东西——真实事件、真实任务记录、真实审批摘要。

---

## 1. 技术栈与选择依据

| 选择 | 原因 |
|---|---|
| Vue 3 + TypeScript | 组件化与类型约束足够；项目此前没有前端，选生态最稳的一套 |
| Vite 6 | 冷启动快；内置代理解决后端无 CORS 的问题 |
| vue-router 4 | 只有两个页面（工作区 / 审批卡预览），但预览页需要独立 URL 才能被明确标注与审计 |
| 不引入 UI 组件库 | 界面元素少而集中（约 12 个组件），手写 CSS 变量比覆盖组件库默认样式更省、更可控；也避免多套视觉语言混杂 |
| 不引入 Pinia | 全局状态只有"会话 + 任务"，用一个 `reactive` 单例足够，少一层抽象 |
| markdown-it + DOMPurify | 模型输出是不可信内容，必须双重过滤 |
| Vitest + @vue/test-utils | 与 Vite 同源配置，零额外构建链路 |

图标全部是自绘 SVG（`src/components/AppIcon.vue`），同一套线宽与端点；不使用 emoji 作功能图标。

---

## 2. 目录结构

```
web/
├── index.html
├── vite.config.ts          # 代理、别名、Vitest 配置
├── src/
│   ├── main.ts / App.vue / router.ts
│   ├── api/
│   │   ├── client.ts       # fetch 封装（不自动重试、身份只走 header）
│   │   ├── sse.ts          # 基于 fetch 的 SSE 解帧（EventSource 无法带请求头）
│   │   ├── music.ts        # 音乐入口
│   │   ├── runs.ts         # 任务查询 / 分页 / 取消
│   │   ├── approvals.ts    # 审批查询 / 批准 / 拒绝 / 恢复
│   │   ├── errors.ts       # 错误分类与脱敏文案（按 code 分支）
│   │   ├── identity.ts     # 联调身份 + 能力模式（展示用）
│   │   └── types.ts        # 与后端 VO 一一对应的类型
│   ├── stores/
│   │   ├── taskStore.ts    # 任务状态机、提交、轮询、审批
│   │   └── session.ts      # 身份 / 入口 / 能力模式
│   ├── components/         # AppShell、SideNav、TaskComposer、TaskConversation…
│   ├── views/
│   │   ├── TaskWorkspaceView.vue
│   │   └── ApprovalPreviewView.vue   # 显式标记的组件预览，不发请求
│   ├── content/examples.ts # 空状态示例（按能力模式分开维护）
│   ├── utils/              # 状态映射、格式化
│   └── styles/             # 设计变量与基础样式
└── tools/
    ├── stub-server.mjs     # 契约桩（仅前端联调，非真实后端）
    └── screenshots.mjs     # 截图脚本（需要本机 Chrome，产物在 target/）
```

---

## 3. 启动

### 3.1 前置

- Node ≥ 20（当前验证版本 22.22.2）
- 后端默认监听 `8123`，context-path 为 `/api`

### 3.2 安装与运行

```bash
cd web
npm install
npm run dev          # http://localhost:5173
```

前端所有请求都是相对路径 `/api/**`，由 Vite 代理转发到 `http://localhost:8123`。
代码里没有硬编码的服务器地址，也不需要后端开 CORS。

代理目标可覆盖：

```bash
VITE_PROXY_TARGET=http://192.168.1.20:8123 npm run dev
```

### 3.3 与真实后端联调

1. 启动后端（默认 profile，需要它自己的数据库）。
2. 启动 `npm run dev`。
3. 浏览器打开 `http://localhost:5173`。
4. 左下角"开发联调"里确认身份（默认 `music-user-1`，必须是 `music_user` 表里的用户，否则后端返回 403）。

身份通过 `X-User-Id` 请求头传递，**不会**出现在 URL 里。

### 3.4 不启动后端时（契约桩）

想验证界面与传输层、又不想消耗模型额度时：

```bash
# 终端 1
node tools/stub-server.mjs 8123

# 终端 2
npm run dev
```

桩服务按真实契约返回 SSE 帧与 JSON 错误，但它**不是后端**：
数据在内存里、不连数据库、不调用模型。用它截图只能说明界面正常，不能说明模型效果。

### 3.5 构建与检查

```bash
npm run typecheck    # vue-tsc --noEmit
npm run test         # vitest run（77 条）
npm run build        # 类型检查 + 产物构建
```

### 3.6 截图（可选）

`tools/screenshots.mjs` 用本机 Chrome（`playwright-core` 不自带浏览器）跑一遍主要页面：

```bash
# 需要同时有：桩服务（8123）、只读实例（5173）、写能力实例（5174）
node tools/screenshots.mjs     # 产物在 target/screenshots/
```

写能力实例仅用于**离线**验证审批界面（没有后端时）：

```bash
VITE_CAPABILITY_MODE=write_approval npm run dev -- --port 5174
```

注意：这已经不是"唯一手段"。页面启动时会调用
`GET /api/ai/music/capabilities`，用**后端真实装配**校正"只读 / 审批写"文案；
`VITE_CAPABILITY_MODE` 只是探测失败时的保守默认值（`readonly`）。
界面里没有任何按钮能打开写能力——写工具是否交给模型，只由后端
`mkagent.agent.music.write-enabled` 决定。

---

## 4. 页面与操作

| 区域 | 内容 | 数据来源 |
|---|---|---|
| 左导航 | 产品标识、新任务、能力模式、本次会话、历史任务、开发联调、环境标识 | `GET /api/ai/runs/page` + 浏览器内存 |
| 中间工作区 | 空状态（能力说明 + 4 条示例）→ 任务视图（需求 / 状态 / 工具活动 / 审批 / 回答） | SSE + `GET /api/ai/runs/{runId}` |
| 输入区 | 一句话任务、Enter 发送、Shift+Enter 换行 | 提交到 `GET /api/ai/music/chat` |
| 右侧详情 | 运行编号、状态、模型、时间、步数、工具次数、Token、工具轨迹、审批 | `GET /api/ai/runs/{runId}` |
| 预览页 | 审批卡两种状态（待确认 / 决定已保存但恢复未完成） | 固定示例数据，按钮禁用 |

---

## 5. 关键行为约定

这些约定是为了"界面不骗人"，改动前请先读 `src/stores/taskStore.ts` 顶部注释。

1. **一次点击只发一次请求**：`transport` 在 `await` 之前同步置为 `submitting`，按钮同时置灰；
   另有 `submitting` 标志与 store 层 `isBusy` 守卫，防止 watch / mounted 触发第二次提交。
2. **`done` 不等于成功**：流结束只把传输状态置为 `closed`，成功与否一律以
   `GET /api/ai/runs/{runId}` 的 `state` 为准；未到终态时做有界轮询（最多 5 次，退避 0.8s→4s，遇终态即停）。
3. **中止不等于取消**：`AbortController` 只停止前端读取，界面显示"结果未知"并提供"核对状态"，
   绝不写"已取消"；只有 `POST /ai/runs/{id}/cancel` 成功后才显示取消。
4. **不自动重试执行类请求**：启动 Agent、审批、恢复、取消都只发一次。
   只有任务状态查询允许有界重试。
5. **每次提交是独立任务**：后端 `AgentRunContext` 没有会话关联，前端也不会把历史消息拼进 prompt。
   输入区下方固定提示这一点。
6. **审批内容只来自服务端**：来自 `approval_required` 事件的 data 与 `GET /ai/approvals/{id}`；
   前端不编辑参数、不生成幂等键、不根据模型文本拼审批。
7. **"决定已保存"与"执行成功"分开**：批准失败后重新查询审批视图，
   只有 `status` 真的变成 `APPROVED/REJECTED` 才提示"决定已保存"，并给出"手动恢复"。
   `EXPIRED` 不算决定保存。
8. **Token 缺失显示"暂无数据"**：不写 0。步数标为"步数"，不冒充模型调用次数。
9. **审批卡出现时滚到可视区**：审批卡通常高于视口，出现时按差量滚到底（直接赋值 `scrollTop`，
   不用受 `scroll-behavior` 影响的 `scrollTo`），保证"批准 / 拒绝"始终可点；
   卡片底部另有"批准≠执行成功"的说明，避免窄屏上头部说明被滚出视口后失去语义。
10. **任务落到服务端后刷新一次历史**：`submit` 结束后补一次只读的 `runs/page`，
    避免左侧"历史任务"仍显示空；历史只作辅助入口，刷新失败不影响当前任务。

---

## 6. 与后端能力的对应（当前）

已真实接入：

- `GET /api/ai/music/chat?message=`（SSE）
- `GET /api/ai/music/admin/chat?message=`（SSE，需 ADMIN 身份）
- `GET /api/ai/runs/{runId}`
- `GET /api/ai/runs/page`
- `POST /api/ai/runs/{runId}/cancel`
- `GET /api/ai/approvals/{approvalId}`
- `POST /api/ai/approvals/{approvalId}/approve|reject|resume`（SSE）

后端缺口（按阻塞程度排序）：

1. ~~没有"当前能力模式"接口~~ → 已补 `GET /api/ai/music/capabilities`，
   页面按后端真实装配显示"只读 / 审批写"与提示词版本。
2. ~~没有歌单 / 歌曲的结构化查询接口~~ → 已补
   `GET /api/ai/music/playlists` 与 `/playlists/{id}/songs`，
   左侧"我的歌单"抽屉直接展示数据库里的真实数据（仍**不**把回答文本正则解析成卡片）。
3. **`GET /music/chat` 用 query 传 message**：长文本受 URL 长度限制，且会进访问日志。
   建议后续改为 `POST` + 请求体。
4. **没有健康 / 恢复状态接口**：识别 503 + `RECOVERY_IN_PROGRESS` 只能靠错误码，
   无法展示恢复进度（也不伪造进度条）。
5. **没有会话 ID**：无法支持多轮；要支持需后端增加 conversationId 与历史读取接口。

---

## 7. 安全边界

- 生产构建里没有任何切换身份的界面；`VITE_ALLOW_DEV_IDENTITY` 只在 DEV 生效，
  正式构建调用 `setDevUserId` 也会被拒绝。
- 前端没有"打开写能力"的开关：能力模式是构建期常量，且不参与任何请求。
- Markdown 渲染关闭原始 HTML，再用 DOMPurify 白名单过滤，外链强制
  `target=_blank rel="noopener noreferrer nofollow"`。
- 不输出后端堆栈；错误文案统一来自 `src/api/errors.ts`。
- 前端不持有任何 API Key。
