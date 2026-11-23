# 商城售后 A1：退货申请 + 售后 Agent

> 本轮只做到 **「退货申请已提交、可查」**。
> 客服审核、仓库验收、退货入库、退款、Redis / MQ / 外部 ERP **都没有做**，
> A1 也**不修改可售库存、不写退货入库流水**。
>
> 📌 **后续阶段**：客服批准 / 驳回已在 **B1** 实施（`docs/shop-b1.md`）。
> B1 把状态 `CHECK` 扩成 `SUBMITTED / APPROVED / REJECTED`，并新增
> `shop_aftersale_decision` 表；本文中"状态只有一种"等描述记录的是 **A1 交付时**的事实，
> 迁移与扩展方式见 `docs/shop-b1.md` 第 5、6 节。

---

## 1. 范围

| 做了 | 没做（留给后续阶段） |
| --- | --- |
| 普通退货表单：选单 → 数量 → 原因 → 提交 → 我的售后列表/详情 | 客服审批 / 驳回 / 释放额度 |
| 售后 Agent：查自己的订单、解释资格判定、查已有申请、预览、确认后提交、查进度 | 仓库验收、退货入库、退款 |
| 额度三算（已购 / 已占用 / 剩余可退）+ 行锁 + 原子条件更新 + `CHECK` 兜底 | 库存回补、逆向流水 |
| 两套技术幂等（表单 `clientRequestId` / Agent `runId+step+toolCallId`） | 跨系统对账、ERP 推送 |
| 复用现有会话 / SSE / 工具白名单 / 人工确认 / 审批恢复 | 真实认证（`X-User-Id` 仍是 demo 边界） |

**状态只有一种**：`SUBMITTED`（已提交）。数据库里 `CHECK (status IN ('SUBMITTED'))`，
想写「已批准」会直接被约束拒绝——不是靠文案自觉。

---

## 2. 模块

新增包 `com.example.mkagent.shop.aftersale`（13 个文件，纯业务、不依赖模型）：

| 文件 | 职责 |
| --- | --- |
| `AfterSaleStatus` | 状态枚举，A1 只有 `SUBMITTED`；未知值 `parse` 直接抛 |
| `AfterSaleSource` | 来源枚举 `FORM` / `AGENT`（页面据此显示"普通表单 / 售后 Agent"） |
| `AfterSaleReturn` | 退货单领域对象（含商品快照、金额、原因、状态、来源） |
| `AfterSaleQuota` | 额度三算 `(purchased, occupied, remaining)`，`remaining` 用 `max(0, …)` 兜底 |
| `ReturnSubject` | 「一张订单行 + 当前额度」的只读视图，判定函数的输入 |
| `AfterSaleEligibility` | **唯一**的资格判定纯函数（归属/签收/窗口/数量/额度/原因） |
| `AfterSaleKeys` | 表单幂等键与请求哈希（`sha256`，带长度前缀防拼接歧义） |
| `AfterSaleEvent` | 售后事件（A1 只有 `SUBMITTED`），`labelOf` 给中文 |
| `AfterSaleRepository` | 所有 SQL：读、行锁、额度条件更新、建单、写事件、幂等记录 |
| `AfterSaleWriter` | **唯一的写入口**，事务边界在这里 |
| `AfterSalePreview` | 预览结果（不写库） |
| `AfterSaleService` | 读 + 预览 + 表单幂等编排 + Agent 建单入口 |
| `AfterSaleApprovalSummaryContributor` | 确认卡摘要扩展（订单号/商品/数量/金额/原因/剩余额度） |

接入层：

| 文件 | 职责 |
| --- | --- |
| `controller/AfterSaleController` | `/api/shop/aftersale/**`（买家侧，5 个接口） |
| `controller/AiController#doChatWithAfterSale` | `/api/ai/aftersale/chat`（售后 Agent 入口） |
| `agent/AfterSaleAgent` | `AgentType.AFTER_SALES`，复用 `ToolCallAgent` 的循环/预算/SSE/审批 |
| `config/AfterSaleAgentConfig` | 装配 14 个协作者 + `mkagent.agent.aftersale.*` 预算 |
| `config/AfterSaleToolConfig` | `submit_return` 包成 `IdempotentToolCallback`（事务 + 幂等） |
| `tools/AfterSaleQueryTool` | 5 个只读工具 |
| `tools/AfterSaleWriteTool` | `submit_return`（唯一写工具，`@RequiresApproval`） |
| `shop/api/AfterSaleViews` | 响应对象与映射（金额/状态中文/额度都在服务端算一次） |

通用 Agent 循环**没有改**：`BaseAgent` / `ToolCallAgent` 一行未动。
白名单只是新增了一个 `AgentType.AFTER_SALES` 分支。

---

## 3. 数据表（4 张，`db/schema.sql`）

### 3.1 `shop_aftersale_return`（退货申请）

| 列 | 说明 |
| --- | --- |
| `return_no` | `RT` + `yyyyMMddHHmmss` + `-` + 4 位十六进制，`UNIQUE` |
| `order_id` / `order_item_id` | 外键到 `shop_order` / `shop_order_item`（**一张申请只针对一个订单行**） |
| `buyer_id` | 建单时的买家（来自服务端身份，不来自请求体） |
| `sku` / `product_name` / `unit_price_cents` | 下单时的快照（不随商品改价漂移） |
| `quantity` / `refund_cents` | 退货数量与预计退款（`unit_price × quantity`，`multiplyExact` 防溢出） |
| `reason` | 必填，`CHECK (char_length(reason) > 0)` |
| `status` | `CHECK (status IN ('SUBMITTED'))` |
| `source` | `CHECK (source IN ('FORM','AGENT'))` |

**A1 没有任何库存列**：本表不参与库存计算，B 阶段做退货入库时再单独建入库单。

### 3.2 `shop_aftersale_quota`（订单行额度）

| 列 | 说明 |
| --- | --- |
| `order_item_id` | 主键（一个订单行一行额度） |
| `quantity_total` | 已购数量（从订单行复制，之后只读） |
| `reserved_qty` | 已占用数量，`DEFAULT 0` |

约束：`CHECK (reserved_qty >= 0 AND reserved_qty <= quantity_total)` —— **数据库兜底**。

### 3.3 `shop_aftersale_event`（处理记录）

`UNIQUE (return_id, event_type)` 保证"同一张单的同一类事件只有一条"，
`CHECK (event_type IN ('SUBMITTED'))`。B 阶段加状态时要 `DROP` + `ADD` 这两个 CHECK
（schema 注释里写明了）。

### 3.4 `shop_aftersale_idempotency`（表单幂等）

`idempotency_key` 主键、`request_hash`、`return_no`。
**只有普通表单用这张表**；Agent 的幂等走框架已有的 `music_tool_idempotency`
（名字带 music 是历史原因，它是框架级存储，与音乐业务无关）。

---

## 4. 退货额度怎么算

三个数，全部由服务端算，前端不自己减：

```
purchased = shop_aftersale_quota.quantity_total            （= 下单时的订单行数量）
occupied  = shop_aftersale_quota.reserved_qty              （A1 只由"已提交"的申请占用）
remaining = max(0, purchased - occupied)
```

**A1 哪些记录占额度**：只有 `shop_aftersale_return.status = 'SUBMITTED'` 的申请占额度，
数量就是 `return.quantity`。客服驳回 / 释放额度不在本轮——所以本轮**没有**释放路径，
额度只增不减（除非事务回滚）。

> B1 之后不变式变成：`reserved_qty = Σ(status ∈ {SUBMITTED, APPROVED} 的 quantity)`。
> 批准仍占额度（实物还在买家手里），**只有驳回**释放。见 `docs/shop-b1.md` 第 4.1 节。

判定顺序（`AfterSaleEligibility`，服务端唯一一份）：

1. 订单行存在且属于当前买家 → 否则 `ORDER_NOT_FOUND`（HTTP 404，不是 403）；
2. 订单状态必须是 `SIGNED` 且有 `signed_at` → 否则 `ORDER_NOT_SIGNED`；
3. `now` 不晚于 `signed_at + mkagent.shop.return-window-days`（默认 7 天）
   → 否则 `RETURN_WINDOW_CLOSED`；
4. `quantity >= 1` → 否则 `INVALID_QUANTITY`；
5. `quantity <= remaining` → 否则 `RETURN_QUOTA_EXCEEDED`；
6. 原因非空（去控制字符后 ≤ 200 字）→ 否则 `INVALID_REASON`。

**请求体里没有 `buyerId` / 单价 / 签收时间 / 已购数量**：DTO 里没有这些字段，
传了也绑不进来。签收时间与已购数量只从数据库读。

### 4.1 金额：预览与提交共用一份计算

`refundCents = unit_price_cents × quantity`，**整数分**，由 `AfterSaleMoney` 一处实现，
预览与提交都调它（`Math.multiplyExact`，溢出抛异常而不是静默回绕）。

| 情况 | 预览 | 提交 |
| --- | --- | --- |
| 正常 | 显示金额（与提交算出的完全一致） | 落库同一个数 |
| 溢出 | `eligible=false`、`code=REFUND_AMOUNT_OUT_OF_RANGE`、金额显示「—」 | `400` 同一个码，不建单、不占额度 |

早期的预览用的是裸乘法，溢出会回绕成负数（页面显示「预计退款 -¥21474836.48」），
而提交那边直接拒绝——同一笔申请两个入口给出互相矛盾的结果。
现在两处共用一份实现，`AfterSaleAmountConsistencyTest` 同时钉住"正常一致"和"溢出两边都拒"。

---

## 5. 接口

### 5.1 买家侧（普通表单，不依赖模型）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/api/shop/aftersale/orders` | 买家身份 | 已签收订单 + 每单额度三算 + 截止时间 + `expired` / `returnable` |
| `POST` | `/api/shop/aftersale/preview` | 买家身份 | 试算：退几件、退多少钱、为什么不能退。**不写库、不占额度** |
| `POST` | `/api/shop/aftersale/returns` | 买家身份 | 提交退货申请，`201` + 真实退货单号 |
| `GET` | `/api/shop/aftersale/returns` | 买家身份 | 我的售后列表（新的在前，不含事件流） |
| `GET` | `/api/shop/aftersale/returns/{returnNo}` | 买家身份 | 详情（含处理记录）；别人的单 → **404** |

提交请求体只有四个字段：

```json
{ "orderNo": "SO20261122100000-1A2B", "quantity": 1, "reason": "键盘按键失灵",
  "clientRequestId": "8f0c…（同一次提交重试必须不变）" }
```

### 5.2 售后 Agent

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/api/ai/aftersale/chat?message=…&conversationId=…` | 买家身份 | SSE；不带 `conversationId` 是单轮，带上就是同一会话的下一轮 |
| `POST` | `/api/shop/aftersale/conversations` | 买家身份 | 新建**售后**会话（先建再发，刷新后能接回来） |
| `GET` | `/api/shop/aftersale/conversations` | 买家身份 | 我的售后会话（只含 `AFTER_SALES` 领域） |
| `GET` | `/api/shop/aftersale/conversations/{id}` | 买家身份 | 会话详情；刷新后靠它恢复对话与待确认的卡 |

**为什么售后有自己的一套会话接口**：`/api/ai/conversations` 的守卫要求 `music_user`。
合法的商城买家完全可能没开通音乐服务，用那个入口会 403 → 建不出会话 → 追问退化成单轮。
修法不是放宽音乐守卫（那会让音乐入口对非音乐用户开放），也不是给买家补一条 `music_user`
（那是伪造身份），而是**另开一个领域受限的入口**：守卫换成买家身份，会话领域钉死 `AFTER_SALES`。

两个方向的跨领域读取都是 **404**（与"不存在"同一句文案，不泄露它是否存在）：
音乐会话 id 拿到售后入口读不到，售后会话 id 拿到 `/ai/conversations` 也读不到。

审批复用现有接口，**没有新增**：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/ai/approvals/{approvalId}` | 查审批真实状态与摘要 |
| `POST` | `/api/ai/approvals/{approvalId}/approve` | 批准 → 落决定 → 恢复执行 → 续流 |
| `POST` | `/api/ai/approvals/{approvalId}/reject` | 拒绝 → 落决定 → 回填拒绝 → 续流（**不执行工具**） |
| `POST` | `/api/ai/approvals/{approvalId}/resume` | 仅恢复（决定已保存但执行没起来时） |

### 5.3 工具

只读（白名单 `AFTER_SALES`）：
`list_my_signed_orders`、`get_my_order_detail`、`check_return_eligibility`、
`list_my_return_requests`、`preview_return`、`terminate_task`。

写：`submit_return`（**唯一**写操作，`@RequiresApproval`）。
每个工具第一行都是 `AgentRunContextHolder.requireUserId()`，**签名里没有 buyerId**。

---

## 6. 权限边界

1. 所有售后入口（含 Agent 对话）第一行都是
   `ShopAccessGuard.requireBuyerIdentity()`：
   - 缺 `X-User-Id` → 401；
   - demo 身份关闭且无可信身份 → 403 `SHOP_IDENTITY_NOT_CONFIGURED`（**先于**角色判断）；
   - `shop_user` 里没有这个人 → 403 `SHOP_USER_NOT_PROVISIONED`。
2. 别人的订单 / 别人的退货单 / 别人的会话一律 **404**（不是 403）：403 等于告诉对方"它存在"。
3. 审批、恢复、重放、详情都校验归属：`ApprovalResumeService` 按 run 的 owner 判定，
   `AfterSaleService` 按 `buyer_id` 过滤；**操作人不来自模型参数**。
   会话同理：`ConversationService` 同时校验 `user_id` 与 `agent_type`。
4. 写工具的身份只来自 `AgentRunContextHolder`，它在 `runStream` 的同步段被冻结，
   模型无法改。
5. **本轮不是真实认证上线**：`X-User-Id` 仍是本地 demo 边界内的占位方案。
   共享部署请保持 `mkagent.shop.demo-identity-enabled=false`（默认），
   此时所有售后入口一律 403（有测试钉住）。

---

## 7. 幂等：两套身份，不要混

| 入口 | 身份 | 存在哪 | 重放返回 |
| --- | --- | --- | --- |
| 普通表单 | `sha256("v1\|aftersale-form\|u{len}:{userId}\|k{len}:{clientRequestId}")` | `shop_aftersale_idempotency` | 原退货单 |
| 售后 Agent | `sha256("v2\|u{len}:{userId}\|r{runId}\|s{step}\|c{len}:{toolCallId}")` | `music_tool_idempotency`（框架级） | 原退货单 |

- **同键同参数** → 返回原单号（201，不新建、不再占额度）；
- **同键不同参数** → `409 IDEMPOTENCY_KEY_REUSED`，明确拒绝，不做"猜你想干嘛"；
- **重放不重新校验额度**：重放回答的是"这次提交成没成"，不是"现在还能不能提交"。
  先查幂等记录再判定，因此"后来额度被用光了"不会把一次成功误判成失败
  （`AfterSaleFlowIntegrationTest#replaySucceedsEvenWhenQuotaIsNowExhausted`）。
- 表单的 `clientRequestId` 生命周期只在前端 store 里：**重试沿用**、
  改订单/数量/原因就**换新**（否则会撞上"同键不同参数"）。
- Agent 的幂等键来自框架：`runId + step + toolCallId`，因此"同一次确认被重试"
  天然是同一个键，而"用户重新发起一次申请"是新的 `runId` → 新的键。

### 7.1 只有"幂等占位行"的冲突才算命中

`submitForm` 早期版本 catch 了笼统的 `DuplicateKeyException`，于是**退货单号重号**、
**事件 `(return_id, event_type)` 重复**这类真实写入失败，会被当成"同一提交正在处理中"——
用户看到"稍等再试"，而那次提交其实根本没成功，等多久都不会好。

现在只有占位行的冲突会被翻译成 `IdempotencyKeyConflictException`，也**只有它**触发回放：

| 冲突位置 | 含义 | 处理 |
| --- | --- | --- |
| `shop_aftersale_idempotency.idempotency_key` | 同一次提交已经（或正在）被处理 | 回放首次结果，返回原单号 |
| `shop_aftersale_return.return_no` | 退货单号撞了（数据异常） | `409 RETURN_WRITE_CONFLICT`，不回放 |
| `shop_aftersale_event (return_id, event_type)` | 事件重复（数据异常） | `409 RETURN_WRITE_CONFLICT`，不回放 |

两条路径都**整体回滚**：额度不占、退货单不留、幂等占位行也不留，
因此同一个 `clientRequestId` 重试不会被误判成"已经提交过"。
`AfterSaleConflictClassificationTest` 用同一组参数分别覆盖这两条路径。

---

## 8. 确认 → 建单的调用链与事务边界

```
浏览器点「批准并执行」
  → POST /api/ai/approvals/{id}/approve            （ApprovalController，校验 run 归属）
  → ApprovalDecisionService.decide                 （落 APPROVED，epoch 递增）
  → ApprovalResumeService.resume                   （领取审批：PENDING_RESUME → CLAIMED → RUNNING）
  → ToolCallAgent.resumeExecution                  （重建 pending ToolCall，绑定 ToolCallPlan）
  → toolCallingManager.executeToolCalls
  → IdempotentToolCallback                         （算 callKey，claim 计划）
  → IdempotentWriteExecutor.execute  ┌───────────── 事务开始（@Transactional）
                                     │ lockRun(runId)（状态 RUNNING + epoch + owner 三道校验）
                                     │ insertPlaceholder（幂等占位行，第一笔写）
                                     │ AfterSaleWriteTool.submitReturn
                                     │   → AfterSaleService.createForAgent(buyerId, …)
                                     │     → AfterSaleWriter.create (Propagation.MANDATORY)
                                     │       → 资格预检（不加锁，快速失败）
                                     │       → SELECT … FOR UPDATE 锁订单行
                                     │       → ensureQuota / 读最新额度
                                     │       → 用最新额度重新判定（锁之后重算）
                                     │       → UPDATE reserved_qty + ? WHERE reserved_qty + ? <= quantity_total
                                     │           （0 行 → 409 RETURN_QUOTA_EXCEEDED）
                                     │       → INSERT shop_aftersale_return
                                     │       → INSERT shop_aftersale_event(SUBMITTED)
                                     │ saveResult（写幂等结果）
                                     └───────────── 事务提交
```

三条事务纪律：

1. **额度占用与建单在同一个事务里**：占用后建单失败必须整体回滚，
   不是"补偿"（`AfterSaleRollbackTest` / PG 版用例用故障注入证明了这一点，
   并在注入点里读了一次 `reserved_qty`，确认扣减确实发生过）。
2. **`Propagation.MANDATORY`**：`AfterSaleWriter.create` 只能在事务里被调用。
   脱离事务直接调工具会立刻 `IllegalTransactionStateException`——
   这样"忘了开事务"会当场暴露，而不是悄悄变成非原子写。
3. **确认前零写入**：`submit_return` 命中 `@RequiresApproval`，暂停发生在
   工具执行**之前**；拒绝走的是"回填拒绝回执"路径，工具不会被执行
   （`AfterSaleApprovalFlowTest#rejectWritesNothing`）。

---

## 9. 前端

| 文件 | 说明 |
| --- | --- |
| `web/src/api/aftersale.ts` | 类型 + 5 个 REST 调用 + SSE 对话入口 |
| `web/src/api/approvalPayload.ts` | 确认卡摘要解析（音乐与售后共用一份，含 `details`） |
| `web/src/stores/aftersale.ts` | 表单/列表/详情/预览状态 + `clientRequestId` 生命周期 + 错误码映射 |
| `web/src/stores/aftersaleChat.ts` | 对话状态：进度、确认卡、批准/拒绝/恢复、执行后刷新业务数据 |
| `web/src/views/AfterSaleView.vue` | `/shop/aftersale`：对话 + 可退订单 + 表单 + 我的售后 |
| `web/src/components/AfterSaleChatPanel.vue` | 对话面板（确认卡、进度、回答、错误） |
| `web/src/components/AfterSaleReturnPanel.vue` | 售后详情抽屉（含处理记录） |
| `web/src/components/ApprovalCard.vue` | 新增 `details` 渲染；音乐卡（歌单/歌曲）行为不变 |

确认卡上固定五项：**订单号、商品、退货数量、预计退款、退货原因**
（外加"当前剩余可退"）。这些都来自服务端摘要，前端不拼、不改、不算钱。

---

## 10. 启动与复跑

### 10.1 起服务（演示）

```bash
# 终端 1：后端（demo profile：H2 文件库 + 演示身份；模型凭据从环境变量来）
export MKAGENT_MODEL_API_KEY=sk-...
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo

# 终端 2：前端
cd web && npm run dev
```

浏览器：`http://localhost:5173/shop/aftersale`（左下角切 `music-user-1`）。

### 10.2 自动化测试

```bash
# A1 业务（H2）：表单闭环 / 边界 / 幂等 / 并发 / 回滚
./mvnw test -Dtest='AfterSaleFlowIntegrationTest,AfterSaleConcurrencyTest,AfterSaleRollbackTest'

# A1 Agent：工具归属、真实审批暂停/批准/拒绝、卡过期重校验、模型不可用
./mvnw test -Dtest='AfterSaleAgentToolTest,AfterSaleApprovalFlowTest,AfterSaleModelIndependenceTest'

# A1 收尾：会话领域与归属、异常归类、金额一致
./mvnw test -Dtest='AfterSaleConversationIntegrationTest,AfterSaleConflictClassificationTest,AfterSaleAmountConsistencyTest'

# 白名单与守卫（含音乐侧不回归）
./mvnw test -Dtest='WriteToolGuardTest,AgentToolWhitelistTest,ApprovalSummaryBuilderTest'

# 全量默认套件（显式不继承模型密钥，避免凭据类用例被本机环境影响）
env -u MKAGENT_MODEL_API_KEY ./mvnw test

# 真实 PostgreSQL 16（一次性容器，默认构建排除）
./mvnw -Ppg test -Dtest='AfterSalePostgres*'
./mvnw -Ppg test -Dtest='ShopPostgres*'      # A0-PG 复跑
```

### 10.3 浏览器验收

```bash
cd web && node tools/e2e-aftersale.mjs           # A1 主链路（music-user-1，24 项）
cd web && node tools/e2e-aftersale-form.mjs      # 拒绝零写入 + 普通表单（15 项）
cd web && node tools/e2e-aftersale-shopbuyer.mjs # 纯商城买家多轮 + 刷新恢复（26 项）
cd web && node tools/e2e-shop.mjs                # A0 复跑（19 项）
```

四个脚本**各自新建订单**，互不依赖，但都假设"该买家在这些订单上没有售后记录"。
重复跑会持续消耗 SKU 库存；库存见底时换一个新的 H2 文件库再起后端。
`e2e-aftersale-shopbuyer.mjs` 用 `shop-user-1`，它是**只有商城身份**的买家，
因此能真正覆盖"不是音乐用户也能多轮售后"这条路径。

---

## 11. 实跑结果

> 分开报告：自动化测试（H2）、真实 PostgreSQL、浏览器验收、真实模型联调。
> 没跑的标"未验证"，模型桩**不算**联调。

### 11.1 自动化测试（H2 / 默认 profile）

| 测试类 | 用例 | 结果 |
| --- | --- | --- |
| `AfterSaleFlowIntegrationTest` | 16 | 16/16 通过 |
| `AfterSaleConcurrencyTest` | 4 | 4/4 通过 |
| `AfterSaleRollbackTest` | 1 | 1/1 通过 |
| `AfterSaleAgentToolTest` | 8 | 8/8 通过 |
| `AfterSaleApprovalFlowTest` | 6 | 6/6 通过 |
| `AfterSaleModelIndependenceTest` | 1 | 1/1 通过 |
| `AfterSaleConversationIntegrationTest` | 6（收尾新增） | 6/6 通过 |
| `AfterSaleConflictClassificationTest` | 3（收尾新增） | 3/3 通过 |
| `AfterSaleAmountConsistencyTest` | 3（收尾新增） | 3/3 通过 |
| `WriteToolGuardTest` | 5 | 5/5 通过 |
| `AgentToolWhitelistTest` | 7 | 7/7 通过 |
| `ApprovalSummaryBuilderTest` | 6 | 6/6 通过 |
| `ShopAccessControlIntegrationTest` | 8（含 2 个新增） | 8/8 通过 |
| `ShopIdentityDisabledIntegrationTest` | 7（含 1 个新增） | 7/7 通过 |
| 全量默认套件 | 见 §11.5 | 通过 |

覆盖到的关键点：

- 额度三算与"已提交占额度"；部分退货（2 件退 1 件）；
- 未签收 / 超窗口 / 数量 0 / 数量超额 / 原因空 / 缺 `clientRequestId` 的拒绝；
- 同键同参数重放返回同一单号；同键不同参数 409；**额度耗尽后重放仍成功**；
- 两请求争抢最后一个名额：1×201 + 1×409，库存与流水不变；
- 同键并发：两个 201、同一单号、只建一单；
- 打开确认卡后额度被另一请求用掉 → 旧卡执行被重新校验拦住（409 信封，不建第二单）；
- 额度占用后写事件失败（仅测试的故障注入）→ 额度恢复、无单、无幂等行、库存不变；
- 确认前不建单；批准后恰好一单；拒绝零写入；重复批准 409 `ALREADY_APPROVED`；
- 他人批准/拒绝 404；他人订单预览/提交 404；列表里看不到别人的单；
- 工具无上下文失败关闭（`NO_CONTEXT_MESSAGE` / `NO_USER_MESSAGE`）；
- 模型不可用时表单仍能提交与查询（`@Primary` 换成必抛异常的模型）；
- **纯商城买家**（`shop-user-1`，不在 `music_user` 里）能在售后入口建会话、读详情、列列表，
  而音乐入口对它仍然 403；
- **领域隔离**：同一个人的音乐会话与售后会话两个方向都读不到（都是 404）；
- **异常归类**：只有幂等占位行冲突才回放，退货单/事件的唯一键冲突是 `RETURN_WRITE_CONFLICT`，
  且失败后零残留、同 `clientRequestId` 重试能正常建单；
- **金额一致**：正常金额预览与提交同值，溢出时预览不下发金额（「—」）、提交 400 不建单。

### 11.2 真实 PostgreSQL 16

`./mvnw -Ppg test -Dtest='AfterSalePostgres*'` → **5/5 通过**（一次性容器
`pgvector/pgvector:pg16`，随机端口 + 随机口令，JVM 退出即删）：

| 用例 | 证明什么 |
| --- | --- |
| `databaseIsRealPostgres` | 连的确实是 PG 16（读 `version()` / `server_version_num` / 隔离级别） |
| `lastSlotRaceCreatesExactlyOne` | 真实行锁 + READ COMMITTED 重判 WHERE：最后一名额只有一个请求拿到 |
| `sameRequestIdRaceReplaysToOneReturn` | 唯一索引冲突 → 事务 aborted → 优雅退化成重放，两单同号 |
| `failureAfterQuotaOccupiedRollsBackEverything` | 占用后失败整体回滚（含在注入点读到 `reserved_qty = 1` 的前置证明） |
| `ownershipIsolatedOnRealDatabase` | 跨买家 404 且零写入 |

`./mvnw -Ppg test -Dtest='ShopPostgres*'` → **13/13 通过**（A0-PG 无回归）。

### 11.3 前端

| 命令 | 结果 |
| --- | --- |
| `npm run typecheck` | 通过 |
| `npx vitest run` | 21 个文件 / 173 个用例全部通过（含新增 22 个售后用例 + 1 个确认卡 `details` 用例） |
| `npm run build` | 通过（`AfterSaleView` 独立分包） |

### 11.4 浏览器验收（真实 Chrome + 真实模型）

前置：后端 `-Pdemo`（H2 文件库 `tmp/a1-e2e-db`，真实 qwen-plus）+ 前端 dev server。
三个脚本**各自新建订单**，互不依赖，但都假设"该买家在这些新订单上没有售后记录"；
重复跑会持续消耗 SKU 库存，库存见底时先清 `tmp/` 下的 H2 文件库。

#### 11.4.1 主脚本 `node tools/e2e-aftersale.mjs` → 24/24 通过

链路：买两把键盘 → 后台发货 → 签收 → 售后页在对话里说"退一把" → 确认卡 → 批准 → 提交 → 刷新。

| 断言 | 实测 |
| --- | --- |
| 确认卡固定订单号 / 商品 / 数量 / 原因 / 剩余可退 | `SO20261122181239-7763` / 机械键盘 K87 / 1 件 / 键盘按键失灵 / 2 件 |
| 确认前不建单、不占额度 | 列表 0 条，额度 `0/2` |
| 批准后返回真实退货单号 | `RT20261122181250-E748` |
| 「我的售后」刷新后仍在 | 1 条，来源「售后助手」 |
| 剩余可退额度 | `{"occupied":1,"remaining":1}` |
| 可售库存不变 | 8 → 8 |
| 出库流水不变 | 1 → 1 |
| 换另一个买家 | 看不到这单 |

#### 11.4.2 拒绝 + 表单脚本 `node tools/e2e-aftersale-form.mjs` → 15/15 通过

| 断言 | 实测 |
| --- | --- |
| 新订单额度 | `0/2` |
| 确认卡点「拒绝」后状态 | 「已拒绝」，批准按钮 disabled |
| 拒绝没有建单 | `before=2 after=2` |
| 拒绝没有占用额度 | `{"occupied":0,"remaining":2}` |
| 试算区显示已购 / 已占用 / 剩余 | 已购 2 件 · 已占用 0 件 · 剩余可退 2 件 |
| 表单提交返回真实单号 | `RT20261122181452-2E2C`（来源标注「普通表单」） |
| 表单提交后额度 | `{"occupied":1,"remaining":1}`，刷新后仍在 |
| 库存不变 | 4 → 4 |
| 控制台错误 | 无 |

> 修过一次**断言时机**（不是功能缺陷）：拒绝走的是"落决定 → SSE 恢复流回填回执 → 续流"，
> 原脚本点完按钮固定 `sleep(2500)` 就断言，此时前端仍是"提交中…"。
> 改成最多 20s、每 250ms 读一次的有界轮询后 15/15。

#### 11.4.3 A0 页面回归 `node tools/e2e-shop.mjs` → 19/19 通过

A1 改了 `AgentToolProvider` / `ToolRegistryConfig` / `ApprovalSummary` / schema，
所以在同一个后端上复跑 A0 全链路：下单不扣库存（4→4）→ 发货扣 2 件（4→2）
→ 只有 1 笔 `OUTBOUND_SHIPMENT -2` 流水 → 签收库存不变 → 刷新后仍为已签收。
**A0 无回归。**

#### 11.4.4 纯商城买家 `node tools/e2e-aftersale-shopbuyer.mjs` → 26/26 通过

用 `shop-user-1`（只有 `shop_user`，**不在** `music_user` 里）走完整多轮链路：

| 断言 | 实测 |
| --- | --- |
| 下单 / 发货 / 签收 | `SO20261122185055-5A35` 等，全程走页面 |
| 售后页没有「会话没建成」的降级提示 | 无 |
| 第一轮只说"我要退货" → 追问 | 助手回答"请提供订单号…"，**没有出卡** |
| 第一轮不建单 | `returns=0` |
| 第二轮补齐参数 → 确认卡 | 订单号 / 1 件 / 键盘按键失灵 / 剩余 2 件 |
| 确认前不建单、不占额度 | `returns=0`，`0/2` |
| **刷新后两轮对话都恢复** | `turns=2` |
| **刷新后确认卡还在，仍是「待确认」** | 内容仍是服务端摘要（订单号 + 原因） |
| 恢复后仍未建单、未占额度 | `returns=0`，`occupied=0` |
| 在恢复出来的卡上批准 → 真实单号 | `RT20261122184933-C4D3` |
| 剩余可退额度 | `{"occupied":1,"remaining":1}` |
| 再刷新，单仍在 | 是 |
| 另一个买家：看不到单、恢复不到会话 | `visible=0`，`turns=0` |
| 控制台错误 | 无 |

> 这一段能成立的前提是收尾修的两处：会话按领域隔离 + 售后有自己的会话入口。
> 在此之前，这个身份在售后页会 403 → 退化单轮 → 第二轮追问失忆。

### 11.5 全量默认套件

```
env -u MKAGENT_MODEL_API_KEY ./mvnw -o test
```

→ **894 项，0 失败，0 错误，BUILD SUCCESS**（不继承模型密钥的干净进程）。

> 之前那次"882 项 / 1 失败"是 `ModelCredentialConfigTest` 受本机
> `MKAGENT_MODEL_API_KEY` 影响，与本轮改动无关；这次按验收要求显式去掉该变量后全绿。
> 894 = 882 + 12 个收尾新增用例。

### 11.6 汇总

| 层次 | 范围 | 结果 |
| --- | --- | --- |
| 自动化测试（H2，模型桩） | A1 业务 + Agent + 会话领域 + 异常归类 + 金额 + 白名单 + A0 回归 | 全通过（见 §11.1） |
| 真实 PostgreSQL 16（一次性容器） | A1 并发/回滚/归属 + A0-PG 回归 | 5/5 + 13/13 通过（见 §11.2） |
| 前端单测 / 类型 / 构建 | vitest 21 文件 177 用例 | 全通过（见 §11.3） |
| 浏览器验收（真实 Chrome） | 主链路 24 + 拒绝/表单 15 + A0 回归 19 + 纯商城买家 26 | 84/84 通过（见 §11.4） |
| 真实模型联调 | 真实 qwen-plus | 确认卡由模型真实产出，单号真实落库 |
| 全量默认套件 | 894 项（不继承模型密钥） | 0 失败 |

**未验证 / 不在本轮**：真实认证（`X-User-Id` 仍可伪造）、客服审批与额度释放、退货入库、
退款、多实例部署下的限流、移动端布局。

> 注意区分：§11.1 的 Agent 用例用的是**模型桩**，只能证明"确认 → 建单"的编排与事务正确；
> 只有 §11.4 是真实模型联调，证明模型能按提示词调用工具并填对参数。

---

## 12. 限制（必须一起读）

1. **没有真实认证**：`X-User-Id` 可伪造。demo 关闭后所有售后入口 403，但这只是"不开放"，
   不是"认证"。
2. **状态只有「已提交」**：没有客服审批、没有驳回、没有额度释放。
   页面与详情都写明这一点，不假装已批准。
3. **不碰库存**：A1 不改 `shop_inventory`、不写 `shop_stock_movement`。
   退货入库是 B 阶段的事。
4. **额度只增不减**：因为本轮没有驳回路径，额度一旦占用就只能靠事务回滚恢复。
5. **会话有自己的领域维度**（收尾修复）：`agent_conversation.agent_type` 区分
   `MUSIC_USER` / `AFTER_SALES`，售后走 `/api/shop/aftersale/conversations`。
   纯商城买家（`shop-user-1`）现在可以正常多轮，音乐入口对它仍然 403——
   这不是"放宽守卫"，是另开了一个领域受限的入口。
6. **退货窗口是 demo 值**（默认 7 天，`mkagent.shop.return-window-days`），
   不是业务规则，也不含"物流在途"等真实场景。
7. **金额只有"预计退款"**：`unit_price × quantity`（整数分，溢出即拒），
   没有运费、优惠、税费；也**没有真实退款**。
8. **售后 Agent 的写工具依赖框架幂等存储** `music_tool_idempotency`（历史命名），
   表名带 music 但语义是框架级；改名前请先看 `AfterSaleToolConfig` 的注释。
9. **恢复只还原文本与待确认的卡**：刷新后不会重放工具进度（历史进度不是当前进度），
   也不会把已结束的轮次重新执行。

---

## 13. B 阶段边界（未实施）

- 客服审批：`shop_aftersale_return.status` 增加 `APPROVED` / `REJECTED`，
  需要 `DROP` + `ADD` 两个 `CHECK`（schema 注释已写明）；
- 驳回释放额度：`reserved_qty` 减回，需要新的 `RELEASED` 事件与并发方案；
- 仓库验收与退货入库：新建入库单 + `shop_stock_movement` 的 `RETURN_INBOUND` 类型，
  **本轮特意没有预留这些列/枚举**，避免留下半成品；
- 退款：与支付网关对接，本轮 `payment_mode = 'DEMO'`。
