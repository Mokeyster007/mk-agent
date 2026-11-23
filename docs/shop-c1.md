# 商城售后 C1：买家进度与业务单据追溯

> 本轮不新增业务流程，只解决一个"看不懂"的问题：
> 买家打开「我的售后」时，不必分别理解**客服决策**、**仓库批次**、**退款记录**三件事，
> 就能看懂"现在到哪一步、接下来等什么、最后对应哪张单"。
>
> 做法是**派生一个阶段**（`stage`），不是新增状态机：
> 阶段由 `status + 收货进度 + 退款记录` 算出来，不落库、不改 `shop_aftersale_return.status`，
> 页面与售后 Agent 调**同一个函数**，因此说的必然是同一句话。

---

## 1. 范围

| 做了 | 没做（留给后续阶段） |
| --- | --- |
| 服务端派生阶段 `stage{code,label,hint}`，随买家列表 / 详情 / Agent 查询返回 | 新表、新状态、新的"进度"实体 |
| 买家列表加一行阶段摘要（含已收 X/Y、还差 Z） | 进度条式的跨单看板、通知、订阅推送 |
| 买家详情改成"阶段卡 + 时间线"（提交 → 决策 → 收货 → 退款） | 物流轨迹、承运商信息、真实到账时间 |
| 驳回做成**终止分支**：DOM 里没有收货/退款节点 | 驳回后重新申请、申诉、二次审核 |
| 买家页面去掉后台操作人 ID 等内部字段 | 真实身份与操作审计（`X-User-Id` 仍是 demo 边界） |
| 售后 Agent 查询结果补 `approvedQty / stageCode / stageLabel / stageHint`（只读） | Agent 的收货 / 退款写工具（**明确不加**） |
| 顺手补掉 B3 的一处漏洞：累计实收**超过**批准数量时拒绝退款 | 冲正、部分退款、多次退款 |

A0 下单发货、A1 提交、B1 批准 / 驳回、B2 收货、B3 模拟退款的**行为都没变**（回归见第 7 节）。

---

## 2. 六种阶段

`stage.code` 只有六个值，`label` 是买家看到的那句话，`hint` 是"接下来等什么"。

| code | label | 什么时候出现 | hint 举例 |
| --- | --- | --- | --- |
| `WAITING_DECISION` | 待客服处理 | `status = SUBMITTED` | 客服还没有给出结论；批准或驳回之后这里会显示下一步。 |
| `REJECTED` | 已驳回 | `status = REJECTED` | 处理已终止：不会再收货，也不会有模拟退款。驳回原因见下方处理结果。 |
| `WAITING_RECEIPT` | 待收货 | `APPROVED` 且一件没收到 | 客服已批准 2 件，仓库还没有收到任何一件。 |
| `PARTIAL_RECEIPT` | 部分收货 | `APPROVED` 且 0 < 累计实收 < 批准数量 | 已收 1 件，还差 1 件；损坏件也计入实收。 |
| `RECEIVED_NOT_REFUNDED` | 已全部收货，尚未登记模拟退款 | `APPROVED` 且收满、无退款记录 | 货已收齐；客服登记模拟退款后，这里会显示退款单号与金额。 |
| `REFUNDED` | 已登记模拟退款 | `APPROVED` 且收满、有退款记录 | 已登记一笔模拟退款（RF…）；这是业务记录，没有真实资金流动。 |

三条措辞纪律：

1. **"收满"不等于"已退款"**。`RECEIVED_NOT_REFUNDED` 的 label 字面上就带"尚未"，
   这样"页面不出现『已退款』"这条不变量可以机械检查。
2. **驳回是终止**。`REJECTED` 的 hint 用"不会再收货"，**不用"待收货"**——
   否则页面虽然没显示收货步骤，文案里却还在暗示有下一步。
3. **不预测时间**。没有"预计 X 天到账""物流在路上"，hint 只描述业务事实与下一个动作。

---

## 3. 为什么是派生值，不是新状态

`status` 是**业务状态**（提交 / 批准 / 驳回），收货进度是**仓库事实**，退款是**财务事实**。
三者正交：一张单可以"已批准 + 收了一半 + 没有退款"。

如果把 `APPROVED` 拆成 `WAITING_RECEIPT / PARTIALLY_RECEIVED / RECEIVED / REFUNDED`，
会立刻出问题：

- 收货与退款是**两张表**的事实，把它们塞回 `shop_aftersale_return.status` 就变成双写，
  任何一边回滚都要同步回滚状态；
- B2 的额度不变式（`reserved_qty = Σ(status ∈ {SUBMITTED, APPROVED})`）依赖 `status` 只有三个值，
  加状态就要改额度公式——**为了一个进度条改账，不值得**；
- 真正的需求只是"给买家一句人话"，那是**读模型**的事。

所以：

```java
// AfterSaleViews.java
public static StageVO stage(AfterSaleReturn value,
                            AfterSaleReceiptProgress progress,
                            AfterSaleRefund refund)
```

纯函数，输入是三件已有事实，输出是 `{code,label,hint}`。
列表、详情、Agent 查询都调它——**措辞只有一处定义，不可能漂移**。

---

## 4. 页面

### 4.1 买家列表（`/shop/aftersale`）

每张卡片多一行：

```
阶段：部分收货（已收 1/2 件，还差 1 件）
```

锚点：`[data-stage]`（值等于 `stage.code`）+ `[data-stage-counts]`。

### 4.2 买家详情（`AfterSaleReturnPanel.vue`）

结构从"字段清单"改成"阶段卡 + 时间线"：

```
[data-stage][data-stage-code]   阶段卡：label + hint
[data-stage-counts]             批准 N 件 · 已收 X 件 · 还差 Y 件
──────────────────────────────
[data-timeline]
  [data-step="submit"]          提交申请（数量、原因、来源、时间）
  [data-step="decision"]        客服处理结果（批准 / 驳回 + 原因 + 时间）
  [data-step="receipt"]         仓库收货（进度条 + 逐批 [data-receipt-no]）
  [data-step="refund"]          模拟退款 / 尚未登记 / 收满后才能登记
```

关键取舍：

- **驳回时 `receipt` 与 `refund` 两步在 DOM 里根本不存在**（`<template v-if="approved">`），
  不是靠 CSS 隐藏。所以"页面上没有等待入库"这件事可以被断言。
- 退款节点有三种形态，互斥：
  - 有退款 → `[data-refund]` + `[data-refund-amount]` + 单号 + 登记时间 + "没有真实资金流动"；
  - 收满无退款 → `[data-refund-pending]`「已全部收货，尚未登记模拟退款。」；
  - 未收满 → `[data-refund-blocked]`「收满 N 件后，客服才能登记模拟退款。」。
- 逐批收货显示**实收 / 合格 / 损坏**三个数，损坏件也计入累计实收——
  否则买家会以为"损坏的那件白寄了"。
- 处理记录**不再渲染 `event.actorId`**。数据仍在 DTO 里给客服页用，买家页面不显示。

---

## 5. 售后 Agent

没有新增工具，也没有改工具名。`list_my_return_requests` 的返回行加了三个只读字段：

| 字段 | 说明 |
| --- | --- |
| `approvedQty` | 批准数量（未批准为 null） |
| `stageCode` / `stageLabel` / `stageHint` | 与页面同一套措辞 |

（`approvedQty` 之外还有既有的 `receivedQty / remainingQty / receiptProgress / refundNo / refundAmountText`，
本轮**全部复用**。）

工具描述里写清了三件事：

1. 回答"退到哪一步"时**优先引用 `stageLabel` + `stageHint`**，不要自己另起说法；
2. `receivedQty` 是累计实收、**损坏也算**；
3. 退款字段为 null 就说"尚未登记模拟退款"；
   **不要推断物流轨迹、到账时间、支付渠道结果**，也**不要解释"真实退款要几个工作日"**——
   这些数据不在工具里，说了就是编。

`toReturnRow(...)` 里也是直接调 `AfterSaleViews.stage(...)`，与页面共用同一个函数。

---

## 6. 顺手补掉的一处 B3 漏洞

`AfterSaleRefundWriter` 原来只判断 `received < approved`。于是：

| 累计实收 vs 批准数量 | 原行为 | 现行为 |
| --- | --- | --- |
| `received < approved` | 409 `REFUND_NOT_RECEIVED` | 不变 |
| `received == approved` | 正常退款 | 不变 |
| `received > approved` | **按超量金额退钱** | 409 `REFUND_RECEIPT_QUANTITY_MISMATCH`，不生成退款记录 |

正常写入路径写不出这种数据（`AfterSaleReceiptWriter` 在锁内校验"累计 + 本批 ≤ 批准"），
只可能是绕过业务路径改库。这时"按实收退"会多退钱，"按批准数量退"是用一笔来源不明的记录定金额
——两者都是猜，所以直接拒绝，让人去核对数据。

定向测试：`AfterSaleRefundIntegrationTest#overReceivedRejected`
（正常收满 2 件后直接插一条 `received_qty=1` 的记录，构造累计 3 > 批准 2，断言 409 + 0 条退款记录）。

---

## 7. 验收

### 7.1 主线（浏览器实跑）

买 2 件 → 发货签收 → 售后 Agent 提交退 2 件 → 客服批准 → 第一批 1 件合格 → 第二批 1 件损坏 → 登记一次模拟退款。

| 观察点 | 期望 | 实测 |
| --- | --- | --- |
| 提交后 | 列表/详情都是「待客服处理」，详情没有收货与退款节点 | ✅ |
| 批准后 | 「待收货」，批准 2 件 / 已收 0 件 / 还差 2 件，退款节点提示"收满后才能登记" | ✅ |
| 第一批 1 件合格 | 「部分收货」，已收 1/2、还差 1；库存 8 → 9 | ✅ |
| 第二批 1 件损坏 | 「已全部收货，尚未登记模拟退款」，已收 2/2、还差 0；库存**仍是 9** | ✅ |
| 登记退款前 | 详情出现 `[data-refund-pending]`，页面不出现"已退款" | ✅ |
| 登记退款后 | 「已登记模拟退款」，能追到 `RF…` 单号，金额 `¥598.00`（299 × 2），写明"没有真实资金流动" | ✅ |
| 额度 | 收货与退款全程不恢复可退额度（始终 2 已占用 / 0 剩余） | ✅ |
| 隐私 | 买家详情不出现 `music-admin-1` | ✅ |

### 7.2 支线（驳回是终止）

再买 1 件 → 提交退货 → 客服驳回：

- 列表与详情都显示「已驳回」，详情显示驳回原因；
- `[data-step="receipt"]` 与 `[data-step="refund"]` **不存在**；
- 页面不出现"待收货""待模拟退款"；
- 库存不因驳回变化（8 → 8）。

### 7.3 Agent 侧

同一笔单问「到哪一步了？有模拟退款单号吗？金额多少？还差几件没收到？」：

- 引用 `RF…` 单号、金额 `¥598.00`、`remainingQty = 0`；
- 没有把模拟登记说成资金到账（否定句不算说错，见第 8 节）。

### 7.4 脚本结果

`web/tools/e2e-aftersale-progress.mjs`，全新 H2 库一次跑通 **48/48**。

---

## 8. 一次被断言抓到的措辞问题

第一轮 47/48，唯一失败在"Agent 没有把模拟退款说成资金到账"。看回答：

> 不代表钱已退至您的支付账户，也**不代表已到账**。

这是**正确的否定句**，但断言写的是 `!answer.includes('已到账')`，于是判成失败。

B3 时期同样的坑在**页面文案**上（见 `docs/shop-b3.md` 第 9.2 节），当时的解法是改文案。
但 Agent 的回答是模型生成的，改不了。所以这次改断言：

```js
// 按分句判断：出现"已到账"且同一分句里没有任何否定词，才算说错
clauses.some((c) => /(已到账|钱已退回|已原路退回|已打款)/.test(c) && !/[不未非没无别]/.test(c))
```

这比子串匹配更接近那条不变量本身："**不要把模拟退款说成资金到账**"，
而不是"不要出现这四个字"。第二轮 48/48。

---

## 9. 可复跑命令

```bash
# 1) 本轮直接相关的后端测试（H2）
cd /d/mk-agent
./mvnw test -Dtest='AfterSaleStageIntegrationTest,AfterSaleRefundIntegrationTest,AfterSaleReceiptIntegrationTest'
./mvnw test -Dtest='AfterSaleDecisionIntegrationTest,AfterSaleAgentToolTest'
./mvnw test -Dtest='AfterSaleOldSchemaMigrationTest,ShopIdentityDisabledIntegrationTest'

# 2) 后端全量（不联网：去掉模型密钥）
env -u MKAGENT_MODEL_API_KEY ./mvnw test

# 3) 前端
cd web && npm run typecheck && npx vitest run --no-file-parallelism && npm run build

# 4) 浏览器验收（后端 demo + 前端 dev 都起，**每次用一个全新 H2 库**）
cd /d/mk-agent
SPRING_DATASOURCE_URL='jdbc:h2:file:./tmp/c1-e2e-db/mk-agent;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH' \
  ./mvnw -Pdemo spring-boot:run -Dspring-boot.run.profiles=demo
cd web && npm run dev
cd web && node tools/e2e-aftersale-progress.mjs
```

浏览器脚本前置条件：`SKU-KEYBOARD-K87` 可售 ≥ 3 件（主线 2 + 支线 1）。
第 0 步先读库存，不足就直接给结论；库存变化一律按**相对变化**断言，
所以重复跑也不会因为"不是从 10 开始"而假失败。

---

## 10. 限制与未验证项

- **没有通知**：阶段变化不会推送，买家只能刷新看到。
- **没有真实到账**：`REFUNDED` 只代表"生成了一条退款记录"，不接支付平台、没有回执、没有对账文件。
- **没有物流**：收货批次由仓库录入，系统不知道包裹在哪。
- **不做跨单看板**：阶段只按单展示，没有"我所有退货的平均处理时长"这类统计。
- **多实例**：本轮是纯读模型，不涉及新的并发写；收货/退款的并发正确性仍由 B2/B3 的行锁与唯一键保证。
- **真实认证**：`X-User-Id` 仍是 demo 边界；买家页面虽然不显示操作人 ID，但接口层没有脱敏字段，生产必须接入真实身份与操作审计。
