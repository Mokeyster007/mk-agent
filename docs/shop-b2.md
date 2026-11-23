# 商城售后 B2：仓库退货收货、验货与可售库存回补

> 本轮把链路补到 **「订单 → 售后申请 → 客服批准 → 仓库收货入库」** 的闭环。
> 一张**已批准**的退货单可以**分多批**收货，每批登记实收 / 合格 / 损坏；
> **只有合格数量回补可售库存**，损坏只留记录、不建残次库存。
> 仍然**不产生退款**、不发通知、不做多仓、不接 Redis / MQ / 外部 ERP。

---

## 1. 范围

| 做了 | 没做（留给后续阶段） |
| --- | --- |
| 收货接口 `POST /shop/admin/aftersale/returns/{returnNo}/receipts`（后台角色守卫） | 退款（真实资金流）、对账、发票 |
| 一张单分多批收货，实收 / 合格 / 损坏 / 备注一次登记 | 残次库存 / 质检工单 / 二次上架 |
| 收货进度（未收货 / 部分收货 / 全部收货）从数据算出来 | 修改退货单主状态（仍是三态） |
| 只有合格数量 `+available` 并写一条 `RETURN_INBOUND` 流水 | 通知（站内信 / 推送）、物流对接 |
| 收货幂等（同键同参回放、同键不同参 409） | 多仓 / 分仓库存、库存调拨 |
| 收货记录 + 进度 + 库存 + 流水**同一事务** | 真实认证（`X-User-Id` 仍是 demo 边界） |
| 旧库升级：流水表加 `dedupe_key` 并回填，替换旧唯一索引 | Redis / MQ / 外部 ERP |

A0 的发货出库、A1 的提交、B1 的批准 / 驳回**行为一行未改**（回归见第 8 节）。

---

## 2. 状态与进度：两个东西不要混

### 2.1 主状态仍是三态

```
shop_aftersale_return.status ∈ { SUBMITTED, APPROVED, REJECTED }
```

收货**不改**这个字段。理由：这个字段表达的是**客服结论**；收货是批准之后仓库做的物理动作。
塞进同一个字段会得到"已批准但部分收货"这种既不是状态也不是事实的值，
还会让 `WHERE status='APPROVED'` 这类查询突然漏掉一批单。

### 2.2 进度是算出来的

```
approvedQty = return.quantity                 （只有 APPROVED 才谈进度）
receivedQty = SUM(receipt.received_qty)
remaining   = max(0, approvedQty - receivedQty)

code = NONE(未收货) / PARTIAL(部分收货) / COMPLETE(全部收货)
```

不存进度列。收货记录在，进度就永远和事实一致；存一份独立进度，就多了一个会和事实打架的地方。

进度对买家、客服、售后 Agent 都可见，但**只有客服能写**（见 3.3）。

---

## 3. 接口与权限

`context-path=/api`，收货接口在 `/api/shop/admin/aftersale` 下：

| 方法 | 路径 | 说明 | 失败码 |
| --- | --- | --- | --- |
| `POST` | `/returns/{returnNo}/receipts` | 登记一批收货 / 验货，成功 **201** | 400 / 403 / 404 / 409 |

请求体（**没有操作人字段**）：

```json
{
  "receivedQty": 1,
  "qualifiedQty": 1,
  "damagedQty": 0,
  "note": "外箱压瘪，内件完好",
  "clientRequestId": "req-…"
}
```

响应是**收货后的完整详情**：`progress` + `receipts[]`（每批带它产生的 `movement`）+ 事件流 + 决策记录。
页面提交后不必再发一次请求去拼"这一批入了多少库"。

### 3.1 校验顺序（越靠前越省事）

```
400  body 为空 / receivedQty <= 0 / 合格+损坏 != 实收 / 备注超 200 字 / clientRequestId 不合法
403  不是 ADMIN（accessGuard.requireAdmin() 是第一行）
404  退货单不存在
409  RETURN_NOT_APPROVED    待审批 / 已驳回
409  RECEIPT_OVER_APPROVED  本次实收 + 累计实收 > 批准数量
409  RECEIPT_REQUEST_CONFLICT  同一个 clientRequestId 换了参数
409  RECEIPT_WRITE_CONFLICT    非幂等的唯一键冲突（收货单号重号）
```

`INVENTORY_ROW_MISSING`（库存行不存在）来自库存层，同样让整笔回滚。

### 3.2 数量约束写进了数据库

```sql
CONSTRAINT ck_shop_aftersale_receipt_qty CHECK (
    received_qty > 0
    AND qualified_qty >= 0
    AND damaged_qty >= 0
    AND qualified_qty + damaged_qty = received_qty
)
```

服务端先校验一次（给出人话），数据库再兜一次（挡住任何绕过服务端的写入）。
"累计不超过批准数量"是跨行约束，数据库表达不了，靠行锁 + 重读（见 4.3）。

### 3.3 谁能看、谁能写

| 角色 | 能做什么 |
| --- | --- |
| 商城后台（ADMIN） | 收货、验货、看全部单的进度与记录 |
| 买家本人 | **只看**自己的收货进度与每批记录（列表 + 详情 + 售后 Agent 工具） |
| 买家看别人的单 | 404（与"不存在"同一句文案，不暴露单号存在性） |

买家侧 Agent 工具（`list_my_return_requests` / `get_my_order_detail`）**新增了只读的收货字段**
（`receivedQty` / `remainingQty` / `receiptProgress` / `receiptProgressLabel`），
**没有新增任何写工具**：模型拿不到收货、验货、改库存的能力。

`mkagent.shop.demo-identity-enabled=false` 时收货接口一律 403 `SHOP_IDENTITY_NOT_CONFIGURED`，
且不留收货记录（`ShopIdentityDisabledIntegrationTest` 断言）。

---

## 4. 数据与事务

### 4.1 新表 `shop_aftersale_receipt`

| 列 | 说明 |
| --- | --- |
| `receipt_no` | 对外单号 `RC + yyyyMMddHHmmss + 4 位十六进制`，`UNIQUE` |
| `return_id` | 外键 → `shop_aftersale_return` |
| `order_item_id` | 外键 → `shop_order_item`（追溯原订单行，也是锁的对象） |
| `received_qty` / `qualified_qty` / `damaged_qty` | 本批实收 / 合格 / 损坏，`CHECK` 钉死自洽 |
| `note` | 验货备注（可空，≤ 200 字） |
| `request_key` | 收货动作的幂等键，`UNIQUE` |
| `request_hash` | 参数指纹（同键不同参的判据） |
| `operator_id` | 收货人（服务端身份） |
| `created_at` | 收货时间 |

索引：`idx_shop_aftersale_receipt_return(return_id, created_at)`（按单取批次）。

### 4.2 库存流水怎么追溯到收货记录

流水表 `shop_inventory_movement` 新增两列：

| 列 | 出库 | 退货入库 |
| --- | --- | --- |
| `receipt_id` | `NULL` | 指向 `shop_aftersale_receipt.id`（**外键**） |
| `dedupe_key` | `OUT:{order_item_id}` | `IN:{receipt_id}` |

于是"这一批收进来的货到底入了几件"是**强引用**，不是推断：

```sql
SELECT * FROM shop_inventory_movement WHERE receipt_id = ?   -- 整批损坏时 0 行
```

详情接口就是按这个关系把 `movement` 挂在每批收货上。整批损坏时 `movement = null`，
页面显示"整批损坏：没有入库流水，可售库存未变"。

### 4.3 锁顺序（沿用同一把锁）

```
1. shop_order_item 一行   SELECT ... FOR UPDATE      ← 与 A1 新申请、B1 决策同一把锁
2. shop_aftersale_return  重读（必须 APPROVED）
3. shop_aftersale_receipt 重读累计实收（SUM），判断不超收
4. shop_aftersale_receipt INSERT（uk(request_key) 兜底）
5. shop_inventory         条件 UPDATE available = available + ?（0 行 → 409 回滚）
6. shop_inventory_movement INSERT（uk(dedupe_key) 兜底）
```

**为什么必须拿订单行锁**：「累计实收 ≤ 批准数量」是跨行约束。
没有串行化点时，两个请求会同时读到"已收 1 / 批准 2"，各自认为还能收 1 件，最后收到 3 件。
锁住订单行后，第二批在第一步就排队，拿到锁时读到的是第一批提交后的累计值。

**为什么用订单行而不是退货单行**：全工程只有一个排队点，A1 新申请、B1 决策、B2 收货
排同一把锁，不需要分别证明三组两两之间不会死锁。预检（不加锁）只用于避免白拿锁，不作判断依据。

### 4.4 事务边界

`AfterSaleReceiptWriter#receive` 一个 `@Transactional`，一个方法 = 一个事务：

```
预检（404 / 非 APPROVED 409）         ← 事务外，省一次拿锁
  ── 事务开始 ──
  锁订单行 → 重读状态 → 重读累计实收 → 校验不超收
  → INSERT 收货记录
  → [合格 > 0] UPDATE 可售库存 + INSERT RETURN_INBOUND 流水
  ── 提交 ──
```

`InventoryService.applyInbound` 是 `Propagation.MANDATORY`：
**没有外层事务就报错**。这不是风格问题——允许库存自己开事务，就等于允许
"收货记了、库存没加"这种中间态出现在库里。

任何一步失败整笔回滚：收货记录、库存增量、流水一起消失。
（`AfterSaleReceiptPostgresTest#failureAfterInboundRollsBackEverything` 在
`insertMovement` 注入异常，回调里先读到 `available` 已加，证明"加过、然后被回滚"。）

### 4.5 幂等与并发口径

| 场景 | HTTP | 写入 | 库存 |
| --- | --- | --- | --- |
| 同键 + 同参数（页面重试 / 双击） | 201 | 返回**原**收货记录，零写入 | 不变 |
| 同键 + 不同参数（改了数量或备注） | 409 `RECEIPT_REQUEST_CONFLICT` | 无 | 不变 |
| 不同键 + 累计在上限内（正常分批） | 201 | 各自一条收货记录 | 各自入库 |
| 不同键 + 会超收 | 409 `RECEIPT_OVER_APPROVED` | 无 | 不变 |
| 两个请求同键并发 | 一个 201，一个 201（回放） | 只有一条记录 | 只加一次 |
| 两个请求争最后一件 | 一个 201，一个 409 | 只有一条记录 | 只加一次 |
| 已驳回 / 待审批 | 409 `RETURN_NOT_APPROVED` | 无 | 不变 |

幂等键 = `sha256("v1|aftersale-receipt|u{len}:{userId}|k{len}:{clientRequestId}")`，
与 A1 表单、B1 决策同一套构造规则；`request_hash` 覆盖单号 + 三个数量 + 归一化后的备注。

**同键并发为什么要"事务外回查"**：输的那一方整体回滚后，
必须换一个新事务去读赢家的结果——PostgreSQL 上"事务已中止"会连 `SELECT` 都拒绝。
所以参数校验、幂等预检、`DuplicateKeyException` 之后的回查都在事务外，
只有"写事实"那一段在事务里。

### 4.4 B3 阶段回补：最后一批收货的幂等边界（缺陷 + 修法）

**缺陷**：拿锁后的顺序原本是"先判超收、再插收货记录"，而幂等键只在事务外预检过。
两个同键同参请求都可能在预检时看不到记录（赢家还没提交），而这一批**正好把批准数量收满**：

```
A: 预检无记录 → 拿锁 → 累计实收 0，1+0 ≤ 1 → 插入 → 提交（累计实收 = 1）
B: 预检无记录（A 未提交）→ 拿锁 → 累计实收 1，1+1 > 1 → 409 RECEIPT_OVER_APPROVED
```

B 其实只是一次重试，却被告知"超收"。调用方无法区分"我多收了"和"这一批已经成功"。

**修法**（最小改动，只调顺序）：拿锁后**先**按 `request_key` 查同次操作是否已落库，
命中就核对 `request_hash` 并回放原记录，**未命中才**判状态与可收数量：

```java
AfterSaleReturn fresh = repository.findReturnByNo(returnNo).orElseThrow(...);

Optional<StoredReceipt> existing = repository.findReceiptByRequestKey(requestKey);
if (existing.isPresent()) {
    return replay(existing.get(), requestHash);   // 重试 → 回放原记录
}

requireApproved(fresh);                            // 新收货 → 才判状态与数量
int alreadyReceived = repository.sumReceivedQty(fresh.id());
if (alreadyReceived + receivedQty > fresh.quantity()) { ... 409 ... }
```

`replay(...)` 抽成 `static`，事务外预检与锁内回放共用同一份实现（哈希不一致 → 409
`RECEIPT_REQUEST_CONFLICT`），两处口径不会漂移。

**语义变得干净**：同一次操作的重试 → 回放；真的想多收 → 409。

**定向验收**（`AfterSaleReceiptPostgresTest`）：

| 场景 | 期望 | 用例 |
| --- | --- | --- |
| 同键同参并发 + 这一批正好收满 | 两次都 201、指向同一条记录、只入库一次、额度不变 | `concurrentSameKeyWritesOnce` |
| 同键不同参 | 409 `RECEIPT_REQUEST_CONFLICT`，零写入 | `AfterSaleReceiptIntegrationTest` |
| 不同键争最后一件 | 一个 201、一个 409 `RECEIPT_OVER_APPROVED`，累计不超批准数量 | `concurrentReceiptsCannotOverReceive` |

---

## 5. 旧库升级（不清库）

### 5.1 必须换掉旧唯一索引

A0 的流水表上有：

```sql
CREATE UNIQUE INDEX uk_shop_inventory_movement_source
    ON shop_inventory_movement (order_item_id, movement_type);
```

这条索引在 B2 是**错的**：同一张退货单的第二批入库
（`order_item_id` 相同、`movement_type` 都是 `RETURN_INBOUND`）会被它挡在门外。
先用测试证明缺陷存在（第一批成功、第二批 500），再改结构。

### 5.2 迁移三件事（全部幂等）

```sql
-- 1) 加列（老库上表已存在，只改 CREATE TABLE IF NOT EXISTS 不会生效）
ALTER TABLE shop_inventory_movement ADD COLUMN IF NOT EXISTS dedupe_key VARCHAR(96);
ALTER TABLE shop_inventory_movement ADD COLUMN IF NOT EXISTS receipt_id BIGINT;

-- 2) 回填：老出库流水由 order_item_id 推导，其余用主键兜底
UPDATE shop_inventory_movement
   SET dedupe_key = 'OUT:' || order_item_id
 WHERE dedupe_key IS NULL AND movement_type = 'OUTBOUND_SHIPMENT' AND order_item_id IS NOT NULL;

UPDATE shop_inventory_movement
   SET dedupe_key = 'LEGACY:' || id
 WHERE dedupe_key IS NULL;

ALTER TABLE shop_inventory_movement ALTER COLUMN dedupe_key SET NOT NULL;

-- 3) 换索引：先建新唯一索引，再丢掉旧的（顺序反了会出现"没有唯一约束"的窗口）
CREATE UNIQUE INDEX IF NOT EXISTS uk_shop_inventory_movement_dedupe
    ON shop_inventory_movement (dedupe_key);
DROP INDEX IF EXISTS uk_shop_inventory_movement_source;
```

`LEGACY:{id}` 兜底是为了**升级中断**的场景：万一上一条 `UPDATE` 没跑完，
剩下的行也不能算出同一个键（否则唯一索引建不起来，启动直接失败）。

### 5.3 迁移怎么验证的

1. `AfterSaleOldSchemaMigrationTest` 新增用例：把流水表**退回 A0 的形状**
   （删新列、删新索引、恢复旧唯一索引、写一条老出库流水），跑迁移，断言
   老流水的 `dedupe_key = 'OUT:{order_item_id}'`、且**同一订单行能落两笔退货入库**。
2. 真实旧库（B1 阶段留下的 H2 文件）直接启动 B2 后端：启动无报错，旧单照常展示，
   并且在旧库上**真实收了一批货**——库存 1 → 2，新流水 `RETURN_INBOUND +1 / receipt_id=1`，
   老流水 6 条原样保留（`dedupe_key` 回填为 `OUT:1…OUT:6`），
   旧索引 `uk_shop_inventory_movement_source` 已消失，新索引
   `uk_shop_inventory_movement_dedupe` 存在。

---

## 6. 页面

| 文件 | 作用 |
| --- | --- |
| `views/AfterSaleAdminView.vue` | 列表行：已批准的单多一个「收货」按钮；点开是本批上限 / 实收 / 合格 / 损坏 / 备注；行内还显示 `部分收货 1/2` |
| `components/AfterSaleAdminPanel.vue` | 详情抽屉：收货进度条（批准 / 已收 / 还可收）+ 每批收货记录（含它对应的流水）+ 决策记录 + 事件流 |
| `views/AfterSaleView.vue` | 买家列表：已批准的单显示「仓库收货：全部收货 2/2 件，还可收 0 件」 |
| `components/AfterSaleReturnPanel.vue` | 买家详情：收货进度 + 每批记录（实收 / 合格 / 损坏 / 仓库备注），**不显示流水与库存数字** |
| `stores/aftersaleAdmin.ts` | `receive()`：前端只挡"明显不合法"（实收 ≤ 0、合格 + 损坏 ≠ 实收），409 时先刷新详情与列表**再**写错误文案 |
| `views/ShopAdminView.vue` | 流水列表区分出库 / 入库，正数用 `ok` 色，标题从"最近出库流水"改为"最近库存流水" |

前端联动规则：三个数量框里**最后编辑的那个说话**，另外两个跟着算
（改实收 → 合格被夹到 `[0, 实收]`，损坏 = 实收 − 合格）。这样操作员看到的永远是自洽的数字，
不会"填完三个框再被告知不合法"。实收还会被夹在本批上限内。

**写操作成功后抽屉会打开**（显示最新进度与这一批的记录），抽屉带遮罩，
所以下一批之前要先关掉它——这是 B1 起就有的交互，B2 沿用。
进入页面时会主动清掉上一次留下的抽屉，避免遮住列表右侧的按钮。

---

## 7. 测试

### 7.1 H2 集成（`AfterSaleReceiptIntegrationTest`，13 例）

| 用例 | 断言 |
| --- | --- |
| 部分收货 | 合格 1 件 → `available +1`，1 条 `RETURN_INBOUND +1`，进度 `PARTIAL 1/2` |
| 整批损坏 | 收货记录在、`available` 不变、**0 条流水** |
| 两批收完 | 进度 `COMPLETE 2/2`，库存只加合格的那 1 件 |
| 不同批次各自入库 | 同一订单行两笔入库流水（**这条在旧索引下会失败**） |
| 待审批 / 已驳回 | 409 `RETURN_NOT_APPROVED`，零写入 |
| 超收 | 409 `RECEIPT_OVER_APPROVED`，累计不变 |
| 数量不自洽 | 400（实收 0 / 合格 + 损坏 ≠ 实收） |
| 同键同参重放 | 返回同一 `receiptNo`，库存与流水都不变 |
| 同键不同参 | 409 `RECEIPT_REQUEST_CONFLICT`，零写入 |
| 买家调用 | 403 `NOT_ADMIN`，不留记录 |
| 买家可见性 | 本人可见进度与批次；别人的单 404 |
| 客服列表 | 列表与详情都有进度（列表一次批量算，不做 N+1） |

### 7.2 真实 PostgreSQL（`AfterSaleReceiptPostgresTest`，4 例）

| 用例 | 断言 |
| --- | --- |
| 两个请求争最后一件 | 一个成功、一个 409；累计不超过批准数量 |
| 同键并发 | 只落一条记录、只加一次库存；**这一批正好收满时两次都成功**（B3 阶段修复，见 4.4） |
| 两批都在上限内并发 | 各自入库，互不覆盖 |
| 库存已加后写流水失败 | 收货记录、库存、流水一起回滚（回调里先读到"已加过"） |

---

## 8. 实跑结果

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 全量默认套件 | `env -u MKAGENT_MODEL_API_KEY ./mvnw test` | **931 项**，除 1 项与本轮无关的环境相关失败外全绿（见下） |
| B2 收货（H2） | `./mvnw test -Dtest=AfterSaleReceiptIntegrationTest` | **13/13** |
| 旧库迁移（含新用例） | `./mvnw test -Dtest=AfterSaleOldSchemaMigrationTest` | **5/5** |
| demo 关闭时的守卫 | `ShopIdentityDisabledIntegrationTest` | **8/8**（含"收货也一律 403、不留记录"） |
| **真实 PostgreSQL 16：B2 专项** | `./mvnw -Ppg test -Dtest=AfterSaleReceiptPostgresTest` | **4/4** |
| **真实 PostgreSQL 16：A0 / A1 / B1 回归** | `./mvnw -Ppg test -Dtest='AfterSalePostgres*,ShopPostgres*,AfterSaleDecisionPostgresTest'` | **23/23** |
| 前端 | `npm run typecheck` / `npx vitest run` / `npm run build` | 类型通过、**191 用例 / 22 文件**通过、构建通过 |
| 浏览器 B2 主链路 | `node tools/e2e-aftersale-receipt.mjs` | **36/36**（全新 H2 库，键盘可售 10 件起） |
| 浏览器 B1 回归 | `node tools/e2e-aftersale-decision.mjs` | **27/27** |
| 真实旧库升级 | 在 B1 留下的 H2 文件上启动 + 收货 | 启动无错、旧单可收、库存 1→2、老流水原样 |

> 全量套件里那 1 项失败是 `ModelCredentialConfigTest#templatePlaceholderFallsBackToSdkEnvironmentVariable`：
> 它断言"没有配置密钥时 SDK 环境变量为空"，而本机 shell 里**导出了真实密钥**。
> 用 `env -u MKAGENT_MODEL_API_KEY` 单独重跑该测试 → 通过。与本轮改动无关。

### 8.1 浏览器主链路的实际数字

脚本跑出来的就是需求里那条链路（`stock0 = 10`）：

```
库存 10  →  买 2 件并发货签收  →  8
退货 2 件（额度 2/0）→ 客服批准（额度仍 2/0）
第一批：实收 1 / 合格 1 / 损坏 0  →  9     ← 1 条 RETURN_INBOUND +1
第二批：实收 1 / 合格 0 / 损坏 1  →  9     ← 没有流水
买家刷新：全部收货 2/2，还可收 0 件，额度仍 2/0
```

同时断言：全库只有 **1 条** `RETURN_INBOUND`、delta 是 `+1`；
用页面发出的同一份请求体**原样重放**一次 → 返回同一条收货记录、库存 9→9、流水 2→2。

### 8.2 一次被误判的失败（记录一下）

首次跑浏览器脚本时卡在「点收货按钮」，报 `Timeout`，日志里只有
`locator resolved to <button …>`。原因是**批准成功后详情抽屉自动打开**，
抽屉的遮罩（`shell__scrim`）盖住了列表右侧——「收货」按钮正好在那儿。
不是权限、不是禁用、不是接口失败，是**遮罩吃掉了点击**。

修法两处：进入页面时清掉上一次留下的抽屉；脚本里写操作后先关抽屉再点下一处。
第二次跑才暴露出真正的问题只是断言文案写错（后端 `COMPLETE` 的标签是「全部收货」，
我写成了「已收满」——而「已收满」恰好是按钮文案，差点让断言"假通过"）。

---

## 9. 可复跑命令

```bash
# 1) 后端默认套件（排除 @Tag("pg")；不继承本机模型密钥）
cd /d/mk-agent && env -u MKAGENT_MODEL_API_KEY ./mvnw test

# 2) 只跑 B2 相关
./mvnw test -Dtest='AfterSaleReceiptIntegrationTest,AfterSaleOldSchemaMigrationTest'

# 3) 真实 PostgreSQL 专项（需要 Docker Desktop 已启动）
./mvnw -Ppg test -Dtest='AfterSaleReceiptPostgresTest'
./mvnw -Ppg test -Dtest='AfterSalePostgres*,ShopPostgres*,AfterSaleDecisionPostgresTest'

# 4) 前端
cd web && npm run typecheck && npx vitest run && npm run build

# 5) 浏览器验收（后端 demo + 前端 dev 都要起，建议每次一个全新 H2 库）
./mvnw -Pdemo spring-boot:run -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:h2:file:./tmp/b2-e2e-db/mk-agent;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
cd web && npm run dev
cd web && node tools/e2e-aftersale-receipt.mjs
```

浏览器脚本前置条件：`SKU-KEYBOARD-K87` 可售 ≥ 2 件（脚本第 0 步会先读一次，
不足就直接给出结论，不让你对着超时猜）。demo 的**下单不锁库存**（刻意如此），
所以库被别的脚本买空后下单照样成功、要到发货才 409 `INSUFFICIENT_STOCK`。

---

## 10. 限制与未验证项

- **本轮不做退款**：B2 停在"货收进来了、可售库存回补了"。退款是资金动作，
  应该与收货**分开事务**（退款失败不该让收货回滚）——B3 已按这条边界实现「模拟退款」，
  见 [`docs/shop-b3.md`](shop-b3.md)：独立表、独立事务、不动库存与额度。
- **不建残次库存**：损坏数量只留在收货记录里，没有"残次品仓"、没有二次上架流程。
- **单仓**：没有多仓 / 分仓，`shop_inventory` 仍是"一个 SKU 一行"。
- **多实例**：并发正确性由数据库行锁 + 条件更新保证（不依赖 JVM 内锁），
  但"两个实例同时收同一单"的压测没做。
- **收货不可撤销**：收错了只能靠后续补单 / 人工对账，本轮没有"冲正"入口。
- **通知**：收货后没有站内信 / 推送，买家只能刷新看到。
- **真实认证**：`X-User-Id` 仍是 demo 边界，生产必须接入真实身份。
