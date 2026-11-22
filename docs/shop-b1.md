# 商城售后 B1：客服批准 / 驳回退货申请

> 本轮只做到 **「客服对一张已提交的退货申请给出终态」**。
> 状态只有三种：`SUBMITTED` → `APPROVED` / `REJECTED`，已决策的单不可回退、不可再次转换。
> **不碰可售库存、不写退货入库流水、不产生真实退款**；仓库验收、退款、Redis / MQ / 外部 ERP 都不在本轮。
>
> **后续**：B2 已实现「仓库收货 / 验货 / 可售库存回补」，见 [`shop-b2.md`](shop-b2.md)。
> B1 的行为在 B2 里一行未改（批准仍保留额度、驳回仍释放额度）。

---

## 1. 范围

| 做了 | 没做（留给后续阶段） |
| --- | --- |
| 客服列表 / 详情 / 批准 / 驳回（4 个接口，商城后台角色守卫） | 仓库验收、退货入库、库存回补 |
| 状态机 `SUBMITTED → APPROVED` / `SUBMITTED → REJECTED`，条件更新兜底 | 退款（真实资金流）、对账、ERP 推送 |
| 驳回释放本单占用的额度（同一事务） | 客服工单流转、审批流、通知 |
| 一单一决的决策记录（决策人 / 时间 / 结果 / 原因） | 真实认证（`X-User-Id` 仍是 demo 边界） |
| 买家在自己详情里看到最新状态与驳回原因 | 客服角色细分（主管 / 客服 / 只读） |
| 旧库迁移：状态与事件 CHECK 的 `DROP` + `ADD`、新表 `CREATE IF NOT EXISTS` | Redis / MQ / 外部 ERP |

A1 的买家路径（多轮、刷新恢复、提交）**一行未改**，也没有放宽音乐守卫。

---

## 2. 状态机

```
                 ┌──────────────────────────────┐
                 │  shop_aftersale_return.status │
                 └──────────────────────────────┘

  买家提交（A1）
        │
        ▼
  ┌───────────┐   客服批准：保留原额度     ┌────────────┐
  │ SUBMITTED │ ────────────────────────▶ │  APPROVED  │  终态
  │ (占额度)  │                            └────────────┘
  │           │   客服驳回：释放本单数量     ┌────────────┐
  │           │ ────────────────────────▶ │  REJECTED  │  终态
  └───────────┘                            └────────────┘
        ▲                                        │
        └──────────── 不存在回退 / 二次转换 ───────┘
```

**"谁赢得决策权"只有一个判据**：

```sql
UPDATE shop_aftersale_return
   SET status = ?, updated_at = CURRENT_TIMESTAMP
 WHERE id = ? AND status = 'SUBMITTED'
```

返回 0 行 = 别人先决策了（或这一单本来就不是待处理）。此时**不写任何东西**，
重读一次最新状态，抛 409（同向 `ALREADY_APPROVED` / `ALREADY_REJECTED`，相反 `DECISION_CONFLICT`）。

> 为什么不是"先插决策记录、靠 `uk(return_id)` 撞唯一键"：
> 撞唯一键意味着已经写了一部分再回滚，错误原因也只能靠猜。
> 条件更新在**语句层面**就分出了胜负，唯一约束只是最后一道保险。

---

## 3. 接口与权限

`context-path=/api`，全部在 `/api/shop/admin/aftersale` 下：

| 方法 | 路径 | 说明 | 失败码 |
| --- | --- | --- | --- |
| `GET` | `/returns?status=&page=&size=` | 列表，`status` 省略 = 全部 | 400 `INVALID_STATUS` |
| `GET` | `/returns/{returnNo}` | 详情（含决策记录、事件流） | 404 `RETURN_NOT_FOUND` |
| `POST` | `/returns/{returnNo}/approve` | 批准，无请求体 | 409 |
| `POST` | `/returns/{returnNo}/reject` | 驳回，body `{"reason": "..."}` | 400 `INVALID_REJECT_REASON` / 409 |

### 3.1 操作者身份只来自服务端

- 每个方法**第一行**就是 `accessGuard.requireAdmin()`，没有"先读 body 再判断"的分支；
- 请求体里**没有**操作人字段：`RejectRequest` 只有一个 `reason`；
- 写库的 `decided_by` / `event.actor_id` 取自 `UserContextHolder`（`X-User-Id` 经拦截器解析后的身份），
  不从 body、不从模型输出、不从任何前端角色字段取。

守卫顺序（`ShopAccessGuard`）：

```
缺身份                        → 401 SHOP_IDENTITY_REQUIRED
demo 关闭 + 无受信身份         → 403 SHOP_IDENTITY_NOT_CONFIGURED   ← 先于角色判断
角色不是 ADMIN                → 403 NOT_ADMIN
```

**关闭 demo 时后台私有读写一律不放行**：`mkagent.shop.demo-identity-enabled=false` 时，
四个接口全部返回 `SHOP_IDENTITY_NOT_CONFIGURED`，不写决策记录（`ShopIdentityDisabledIntegrationTest` 断言）。

### 3.2 客服能处理的范围

单店 demo，客服看的是**全店待处理售后**，不套买家所有权规则（否则客服只能看自己买的单）：

- 列表/详情不做 `buyer_id` 过滤，但每条都带 `buyerId`，界面显示是谁的单；
- 买家侧接口仍然是 `buyer_id` 过滤：买家 A 打开买家 B 的退货单 → 404（不是 403，避免暴露单号存在性）；
- 普通买家（只有 `shop_user` 身份）打开客服页：接口 403 `NOT_ADMIN`，页面只显示"当前身份不能处理售后"。

---

## 4. 数据与事务

### 4.1 四样东西各管一件事

| 载体 | 职责 | 谁写 |
| --- | --- | --- |
| `shop_aftersale_return.status` | 业务结论（现在是什么状态） | 条件 UPDATE，一单只成功一次 |
| `shop_aftersale_decision` | 不可变的决策事实：结果 / 原因 / 决策人 / 时间 | 决策成功后插一条，`uk(return_id)` |
| `shop_aftersale_event` | 动作序列（谁在什么时候做了什么），界面"处理记录"读它 | 一个动作一条 |
| `shop_aftersale_quota.reserved_qty` | 计数快照，可被 `CHECK` 校验 | 只有 A1 新申请 `+`、B1 驳回 `−` |

不变式（测试里用 `quotaConsistencyError` 断言，不靠肉眼）：

```
reserved_qty = Σ( status ∈ {SUBMITTED, APPROVED} 的 return.quantity )
0 <= reserved_qty <= quantity_total
```

**批准为什么仍占额度**：批准只代表"同意退"，实物还在买家手里。额度要在仓库验收之后才释放，
所以 `APPROVED` 继续占着；只有 `REJECTED` 才在决策那一刻释放。

### 4.2 锁顺序（全工程统一，不允许反着来）

```
1. shop_order_item 一行     SELECT ... FOR UPDATE         ← 与 A1 新申请同一把锁
2. shop_aftersale_return    条件 UPDATE ... WHERE status='SUBMITTED'
3. shop_aftersale_quota     条件 UPDATE ... WHERE reserved_qty >= ?
4. shop_aftersale_decision  INSERT（uk(return_id) 兜底）
5. shop_aftersale_event     INSERT（uk(return_id, event_type) 兜底）
```

第 1 步与 A1 的 `AfterSaleWriter` **完全一致**（先订单行、再退货单），
所以"驳回"与"同订单行的新申请"排同一把锁，不会互相看到对方的中间态。
批准本身不改额度，仍然拿这把锁——这样"批准 / 驳回 / 新申请"三者只有一个共同排队点，
不需要分别证明三组两两之间不会死锁。

拿锁之后**必须重读**状态与额度：预检（不加锁）只是为了避免白拿锁，不能当作判断依据。

### 4.3 事务边界

`AfterSaleDecisionWriter#approve` / `#reject` 各自 `@Transactional`，一个方法 = 一个事务：

```
预检(404 / 已决策 409)
  → 锁订单行
  → 重读
  → [驳回] 读额度并校验 occupied >= quantity（否则 409 RETURN_QUOTA_INCONSISTENT）
  → 条件状态转换（0 行 → 409）
  → [驳回] 条件释放额度（0 行 → 409，整笔回滚）
  → 插决策记录
  → 插事件
  → 重读返回
```

**释放数量从原退货单读**（`fresh.quantity()`），不来自请求、不来自前端。
任何一步失败整笔回滚：不会留下"状态已改但额度没退"或"额度退了但没有决策记录"。

### 4.4 重复与并发决策的响应口径

| 场景 | HTTP | 码 | 写入 | 额度 |
| --- | --- | --- | --- | --- |
| 同一单重复驳回 | 409 | `ALREADY_REJECTED` | 无 | 不变（只释放过一次） |
| 同一单重复批准 | 409 | `ALREADY_APPROVED` | 无 | 不变 |
| 已批准再驳回 / 已驳回再批准 | 409 | `DECISION_CONFLICT` | 无 | 不变 |
| 两个并发驳回 | 一个 200，另一个 409 | `ALREADY_REJECTED` | 只有一条决策、一条事件 | 只释放一次 |
| 批准与驳回并发 | 一个 200，另一个 409 | 视赢家 | 只有一条决策 | 批准赢不释放；驳回赢只释放一次 |
| 驳回与同订单行新申请并发 | 都成功（顺序不同结果不同） | — | 各自记录 | 不为负、不超已购 |

所有 409 都是"可经详情核对"的：客户端拿到 409 后读详情即可看到决策人、时间、结果、原因。

---

## 5. 数据表变化（`db/schema.sql`）

### 5.1 新表 `shop_aftersale_decision`

| 列 | 说明 |
| --- | --- |
| `return_id` | 外键到 `shop_aftersale_return`，`UNIQUE`（一单一决） |
| `decision` | `CHECK (decision IN ('APPROVED','REJECTED'))` |
| `reason` | 批准必须为 `NULL`，驳回必须非空：一条 `CHECK` 钉死，不靠前端校验 |
| `decided_by` | 服务端身份上下文里的操作者 |
| `decided_at` | 决策时间（`DEFAULT CURRENT_TIMESTAMP`） |

### 5.2 扩展两个 CHECK

```sql
-- 状态：SUBMITTED → APPROVED / REJECTED
ALTER TABLE shop_aftersale_return
    DROP CONSTRAINT IF EXISTS ck_shop_aftersale_return_status;
ALTER TABLE shop_aftersale_return
    ADD CONSTRAINT ck_shop_aftersale_return_status
        CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED'));

-- 事件：一个动作一个事件，驳回释放额度同属一个动作，不额外写 RELEASED
ALTER TABLE shop_aftersale_event
    DROP CONSTRAINT IF EXISTS ck_shop_aftersale_event_type;
ALTER TABLE shop_aftersale_event
    ADD CONSTRAINT ck_shop_aftersale_event_type
        CHECK (event_type IN ('SUBMITTED', 'APPROVED', 'REJECTED'));
```

### 5.3 新索引

`idx_shop_aftersale_return_status(status, created_at)`（客服按状态分页）、
`idx_agent_run_conversation_type(conversation_id, agent_type)`（会话领域回填）。

---

## 6. 旧库迁移方法

**只改 `CREATE TABLE IF NOT EXISTS` 是不够的**：老库上表已经存在，新的 `CHECK` 永远不会生效。
本轮的迁移分三件事，全部幂等（`spring.sql.init.mode=always` 每次启动都跑）：

| 对象 | 老库上的问题 | 迁移做法 | 为什么这样做 |
| --- | --- | --- | --- |
| 状态 / 事件 `CHECK` | 老约束只允许 `SUBMITTED`，写 `APPROVED` 会被拒 | `DROP CONSTRAINT IF EXISTS` + `ADD CONSTRAINT` | 幂等；顺带校验老数据合法性（有脏数据时启动即暴露） |
| `shop_aftersale_decision` | 表不存在 | `CREATE TABLE IF NOT EXISTS` | 纯新增，不碰老数据 |
| `agent_conversation.agent_type` | 老会话该列为 `NULL` 或默认 `MUSIC_USER`，售后会话会被当成音乐会话 | `ConversationDomainBackfill`（`ApplicationRunner`）按 `agent_run.agent_type` 事实回填 | **无法用默认值表达"这是售后"**：新增列的默认值只能有一个 |

### 6.1 会话领域字段为什么必须回填

A1 给 `agent_conversation` 加了 `agent_type`（`MUSIC_USER` / `AFTER_SALES`）。
老库里已经存在的售后会话没有这个信息，如果只加列不处理，它们会被归成音乐会话，
买家点开售后对话就是 404（领域不符与不存在共用同一句 404）。

回填语句（幂等，只改"有售后 run 但被标成音乐"的行）：

```sql
UPDATE agent_conversation c
   SET agent_type = 'AFTER_SALES'
 WHERE c.agent_type = 'MUSIC_USER'
   AND EXISTS (SELECT 1 FROM agent_run r
                WHERE r.conversation_id = c.conversation_id
                  AND r.agent_type = 'AFTER_SALES')
```

`ConversationDomainBackfillTest` 先用测试**证明缺陷存在**（回填前售后入口 404），再证明修复；
覆盖"老售后会话被回填 / 纯音乐会话不动 / 没有 run 的会话不动 / 重复执行不变 / 混合 run 判为售后"。
回填失败只 `WARN`，不影响启动（宁可少回填，也不要让应用起不来）。

### 6.2 迁移本身怎么验证的

`AfterSaleOldSchemaMigrationTest`（4 个用例）把库**退回 A1 的形状**再迁移，而不是"在新库上建表"：

| 用例 | 断言 |
| --- | --- |
| 老库原样跑不了 B1 | 写 `APPROVED` 被老 `CHECK` 拒绝；写 `REJECTED` 事件被拒；决策表不存在 |
| 迁移后老单能批准 | 老数据仍是 `SUBMITTED`；批准 200；决策记录落库；额度保留 |
| 迁移后老单能驳回 | 额度 1 → 0（可退 2）；决策原因落库；库存与流水不变 |
| 迁移幂等 | 连跑三次 `schema.sql`：退货单不增多、决策不重复、约束确实已换新 |

第一条用例是这次迁移的"证据"：它证明**如果只改 `CREATE TABLE IF NOT EXISTS`，老库就是坏的**。


---

## 7. 前端

| 文件 | 作用 |
| --- | --- |
| `views/AfterSaleAdminView.vue` | `/shop/admin/aftersale`：全部 / 待处理 / 已批准 / 已驳回四个 Tab，行内批准 / 驳回（驳回内联填原因，未填时按钮禁用）、详情抽屉 |
| `components/AfterSaleAdminPanel.vue` | 买家、决策记录、批准 / 驳回操作、事件流 |
| `stores/aftersaleAdmin.ts` | 列表 / 分页 / 详情 / 决策；`busyReturnNo` 防连点；409 时先刷新详情与列表**再**写错误文案（顺序反了会被刷新擦掉） |
| `views/AfterSaleView.vue` | 买家侧：状态 chip 按 tone 着色，详情里新增"客服处理结果"块（含驳回原因） |
| `components/AfterSaleReturnPanel.vue` | 买家侧详情面板：状态色 + 客服处理结果 |
| `api/aftersale.ts` | 四个后台接口 + `AfterSaleDecisionVO` |

错误码 → 文案集中在 `stores/aftersale.ts#describeAfterSaleError`，
新增 `ALREADY_APPROVED` / `ALREADY_REJECTED` / `DECISION_CONFLICT` / `INVALID_REJECT_REASON` /
`RETURN_QUOTA_INCONSISTENT` / `INVALID_STATUS` / `NOT_ADMIN`。

---

## 8. 可复跑命令

```bash
# 1) 后端单元 / 集成（默认套件，排除 @Tag("pg")）
cd /d/mk-agent && env -u MKAGENT_MODEL_API_KEY ./mvnw -o test

# 2) 只跑 B1 相关
./mvnw -o test -Dtest='AfterSaleDecisionIntegrationTest,AfterSaleOldSchemaMigrationTest,ConversationDomainBackfillTest'

# 3) 真实 PostgreSQL 专项（需要 Docker Desktop 已启动）
./mvnw -o -Ppg test -Dtest='AfterSaleDecisionPostgresTest'
./mvnw -o -Ppg test -Dtest='AfterSalePostgres*,ShopPostgres*'   # A0 / A1 回归

# 4) 前端
cd web && npm run typecheck && npm run test -- --run && npm run build

# 5) 浏览器验收（后端 demo + 前端 dev 都要起）
cd web && node tools/e2e-aftersale-decision.mjs
```

浏览器脚本的前置条件：`SKU-KEYBOARD-K87` 可售 ≥ 3 件。
demo 的**下单不锁库存**（刻意如此，好让"发货时库存不足"可演示），所以库被别的脚本买空后，
下单照样成功、要到发货才 409 `INSUFFICIENT_STOCK`。脚本第 0 步会先读一次库存并直接给出结论，
不会让你对着 20 秒超时猜原因。**建议每个脚本用一个全新 H2 库**：

```bash
./mvnw -o -Pdemo spring-boot:run -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:h2:file:./tmp/b1-e2e-db/mk-agent;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
```

---

## 9. 实跑结果

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 全量默认套件（不继承模型密钥） | `env -u MKAGENT_MODEL_API_KEY ./mvnw -o test` | **917 项，0 失败 0 错误，BUILD SUCCESS**（A1 收尾时基线 894 项，本轮 +23） |
| B1 决策（H2） | `./mvnw -o test -Dtest=AfterSaleDecisionIntegrationTest` | 13/13 |
| 旧库迁移（H2，退回 A1 形状再迁移） | `./mvnw -o test -Dtest=AfterSaleOldSchemaMigrationTest` | 4/4 |
| 会话领域回填 | `./mvnw -o test -Dtest=ConversationDomainBackfillTest` | 5/5 |
| demo 关闭时的后台守卫 | `ShopIdentityDisabledIntegrationTest` | 8/8（含新增"客服售后四个接口一律 403、无决策记录"） |
| **真实 PostgreSQL 16：B1 专项** | `./mvnw -o -Ppg test -Dtest=AfterSaleDecisionPostgresTest` | **5/5**（并发驳回 / 批准+驳回 / 驳回+新申请 / 故障注入回滚 / 真库顺序重复驳回） |
| **真实 PostgreSQL 16：A0 + A1 回归** | `./mvnw -o -Ppg test -Dtest='AfterSalePostgres*,ShopPostgres*'` | **18/18**（A1 5 + A0 8 + A0 禁用身份 5） |
| 前端 | `npm run typecheck` / `npm run test -- --run` / `npm run build` | 22 文件 / 185 用例通过，类型与构建通过 |
| 浏览器 B1 | `node tools/e2e-aftersale-decision.mjs` | **27/27**（全新 H2 库） |
| 浏览器 B1（同库连跑 3 次） | 同上 | 3 次都 27/27 —— 脚本不依赖"库里没有售后记录"这种初始状态 |
| 浏览器 A1 三段 + A0 | `e2e-aftersale.mjs` / `e2e-aftersale-form.mjs` / `e2e-aftersale-shopbuyer.mjs` / `e2e-shop.mjs` | 24/24、15/15、26/26、19/19 |

### 9.0 真实 PostgreSQL 上的四个并发结论

`AfterSaleDecisionPostgresTest` 不是在 H2 上"看起来能过"，而是在一次性 PG16 容器里用两个独立连接抢：

| 用例 | 断言 |
| --- | --- |
| 两个并发驳回 | 一个 200、一个 409；只有 1 条决策、1 条 `REJECTED` 事件；额度只减一次 |
| 批准 + 驳回并发 | 只有一个终态、一份决策；批准赢 → 额度不变；驳回赢 → 只释放一次 |
| 驳回 与 同订单行新申请并发 | 不断言"谁先"，只断言不变式：`reserved_qty` 不为负、不超过已购、与未驳回申请之和一致 |
| 额度已释放后让后续写入失败 | 状态 / 额度 / 决策 / 事件全部回到操作前，库存不变（`@MockitoSpyBean` 在 `insertDecision` 抛异常，回调里先读到 `reserved_qty` 已减，证明"释放确实发生了，然后被回滚"） |


### 9.1 一次被误判为"回归"的失败

批量连跑五个浏览器脚本时，B1 脚本在第 3 步超时，控制台有一条 `409`。查后端日志：

```
业务异常：status=409, code=INSUFFICIENT_STOCK,
message=库存不足：SKU-KEYBOARD-K87 当前可售 0 件，本单需要 1 件。
```

**不是 B1 的缺陷**：demo 的下单**不锁库存**（刻意如此，好让"发货时库存不足"这条路径可演示），
所以前面几个脚本把键盘买空后，下单照样成功，直到发货才 409。
脚本因此加了两件事：

1. 第 0 步先读可售库存，不足 3 件就直接给出结论（而不是让人对着 20 秒超时猜）；
2. 写操作统一走 `clickAndWait`：既等"成功提示文案变了"（避免把上一次的提示当成这次的成功），
   也捕获错误提示与 4xx 响应体，失败时把原因直接打出来。

加上这两条后，在同一个库上连跑三次都是 27/27；把库存买到只剩 1 件时，
脚本输出的是「环境不满足：SKU-KEYBOARD-K87 当前可售 1 件，本脚本至少要 3 件」。


---

## 10. 未验证项

- **多实例部署**：本轮是单进程 + 数据库行锁，没有分布式锁。多实例下并发正确性仍由
  数据库条件更新保证（不依赖 JVM 内锁），但"两个实例同时决策"的压测没有做。
- **客服角色细分**：只有 `ADMIN` 一种角色，没有"客服 / 主管 / 只读"的分级。
- **通知与工单**：决策后没有站内信、没有推送，买家只能刷新看到。
- 真实认证（`X-User-Id` 仍是 demo 边界）：生产必须接入真实身份，否则 `X-User-Id` 本身就是伪造入口。

> 真实 PostgreSQL 的并发与故障注入**已跑通**（见 9.0）；本轮开工时 Docker Desktop 未运行，
> 启动后补跑，A0 / A1 / B1 三组 PG 专项共 23 项全绿。

---

## 11. B2 已实施（详见 [`shop-b2.md`](shop-b2.md)）

本节原来是"设计建议 —— 只列，不实施"，B2 落地后保留对照，说明实际做法与当初设想的差异：

| 当初的建议 | 实际做法 | 为什么 |
| --- | --- | --- |
| 单独建入库单，不在退货单上加列 | `shop_aftersale_receipt`，一单多批 | 采纳 |
| 收货是新的额度释放点 | **不释放**：收货不改变 `reserved_qty` | 收进来的件数已经把这份退货资格用掉了；放回去等于允许同一件被退第二次 |
| 状态机扩成 `APPROVED → RECEIVED` | 主状态仍是三态，进度**算出来** | 避免"已批准但部分收货"这种既不是状态也不是事实的值；也不会让 `WHERE status='APPROVED'` 漏单 |
| 库存回补与退款分开事务 | 库存回补与**收货记录**同一事务；退款仍未做 | 收货与入库必须同生共死；退款是资金动作，等后续阶段独立事务 |
| 覆盖"批准了但一直没收到货" | 进度 `NONE` 天然表达；`remainingQty` 可见 | 采纳（只展示，不做超时催办） |
