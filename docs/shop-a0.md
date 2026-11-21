# 商城 A0：最小商城 + 订单 + 发货出库

> 项目定位：「轻量商城 + 电商售后 Agent + 仓储库存联动」。
> 本文只覆盖 **A0**。A1（售后 Agent）与 B（客服审批 / 仓库验收）只有边界设计，
> 见第 10 节，**未实施**。

---

## 0. 本轮做了什么、刻意没做什么

**做了**

- 买家侧：商品列表 → 立即购买 → 我的订单 / 订单详情（真实浏览器可跑通）。
- 后台侧：按状态分页看订单、发货（演示出库）、标记签收、核对库存余额与出库流水。
- 后端模块 `catalog / order / inventory` 三层，六张 `shop_*` 新表，全部幂等建表。
- 演示付款：`shop_order.payment_mode = 'DEMO'`，页面明确标注"无真实资金交易"。

**没做（且本轮不打算做）**

| 不做的事 | 原因 |
| --- | --- |
| 购物车 / 优惠券 / 促销 / 地址簿 | 与"验证订单-库存联动"无关，只会稀释状态机 |
| 真实支付、退款、物流单号、承运商 | 本轮是演示付款；接真实资金会把验收重点从"库存一致性"挪走 |
| 第三方 ERP / WMS 同步 | 明确排除 |
| 通用审批引擎 | B 段只做"客服批准/驳回"这一个具体动作，不抽象成引擎 |
| 预占库存（reserve） | 明确要求"下单不预占"，所以连预留字段都没加 |
| 售后表 / 退货单 / 验收表 | 资格规则与验收规则没定下来之前建表，等于把没想清楚的状态机固化进库 |
| 多仓、多 SKU 行/单、多规格 | 单仓单行，`uk_shop_order_item_order` 用唯一索引钉死 |
| 在 A0 页面加聊天 / 售后确认卡 / 退货入库页 | 明确排除 |

**音乐业务**：代码、表、路由、页面全部保留，一行没删。商城是**并列的新入口**
（`/shop`、`/shop/admin`），没有塞进歌曲页或播放页。

---

## 1. 模块关系

```
                     ┌──────────────────────────────┐
  浏览器  /shop ────▶ │ controller.ShopController     │ 买家侧（商品/下单/我的订单）
                     └──────────────┬───────────────┘
                                    │  ShopAccessGuard（身份 + 角色）
                     ┌──────────────▼───────────────┐
  浏览器 /shop/admin ▶│ controller.ShopAdminController│ 后台侧（列表/发货/签收/库存）
                     └──────────────┬───────────────┘
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        ▼                           ▼                           ▼
  shop.catalog                shop.order                  shop.inventory
  ShopProduct                 ShopOrderService            InventoryService
  ShopProductRepository       ShopOrderRepository         InventoryRepository
        │                           │                           │
        └───────────────────────────┴───────────────────────────┘
                                    ▼
                        shop_* 六张表（JdbcTemplate，条件更新 + 行锁）

  shop.ShopAccessGuard ──▶ shop.ShopRoleResolver ──▶ shop_user（角色唯一可信来源）
  shop.ShopProperties  ──▶ mkagent.shop.*（身份开关 / 数量上限 / 页大小）
  shop.ShopSeedInitializer（@Profile("!prod")）──▶ 商品 / 库存 / 演示身份
```

**依赖方向是单向的**：`order → inventory`（发货时扣减），`order → catalog`（读价格、
读上架状态）。`inventory` 不认识 `order`，只接收 `orderId / orderItemId` 两个来源标识。
这样 B 段的"退货入库"可以复用 `InventoryService`，不必反过来让库存知道售后。

**Agent 不在图里**。A0 的任何一条链路都不经过 `BaseAgent` / `ToolCallAgent` /
模型，第 8 节的 `ShopModelIndependenceTest` 与 `ShopStartsWithoutModelCredentialTest`
是这条边界的证据。

---

## 2. 数据表

全部在 `src/main/resources/db/schema.sql` 末尾（`-- 商城（A0：最小商城 + 订单 + 发货出库）`
之后），命名空间 `shop_`，与 `music_*` 完全隔离，语句幂等，PostgreSQL / H2(PG 模式) 通用。

| 表 | 作用 | 关键约束 |
| --- | --- | --- |
| `shop_product` | 商品（一行 = 一个可售 SKU，单仓单规格） | `uk_shop_product_sku`、`price_cents >= 0`、`status IN ('ACTIVE','INACTIVE')` |
| `shop_order` | 销售订单 | `uk_shop_order_no`、`status IN ('PENDING_SHIPMENT','SHIPPED','SIGNED')`、`payment_mode IN ('DEMO')` |
| `shop_order_item` | 订单明细（**成交快照**：商品名、单价、数量、行小计） | `uk_shop_order_item_order`（一单一行的硬约束）、FK → `shop_order`、`quantity > 0` |
| `shop_inventory_balance` | 库存余额（单仓，按 SKU 一行） | **`CHECK (available >= 0)`** —— 最后一道防线 |
| `shop_inventory_movement` | 库存流水（只增不改） | `movement_type IN ('OUTBOUND_SHIPMENT')`、`quantity_delta <> 0`、**`UNIQUE (order_item_id, movement_type)`** |
| `shop_user` | 商城角色 | `role IN ('BUYER','ADMIN')`，与 `music_user` **互不推导** |

几个刻意的选择：

- **快照落在订单行上**。商品改名/调价后历史订单必须还原"当时买的是什么、多少钱"，
  只存 `product_id` 再 join 商品表，会让历史订单随商品一起变形。
- **金额一律整数分**（`*_cents`），不用 `DECIMAL`/浮点。展示用
  `BigDecimal.valueOf(cents, 2)`，不用 `cents / 100.0`（后者会打印出 `298.99999999999994`）。
- **`movement_type` 的 CHECK 是故意写死的**。B 段加"退货入库"时必须显式
  `DROP` + `ADD` 这条约束——让"新增业务类型"成为一件看得见的事，
  而不是随手写个新字符串就能插进去。
- **没有 `shop_return_*` 表**。A1/B 的表等规则定了再加，见第 10 节。

---

## 3. 订单状态机

```
                    POST /shop/orders（买家，演示付款）
                                │
                                ▼
                    ┌───────────────────────┐
                    │  PENDING_SHIPMENT     │  待发货
                    │  库存：未预占、未扣减  │
                    └───────────┬───────────┘
                                │ POST /shop/admin/orders/{no}/ship
                                │ 事务内：锁订单行 → 扣库存 → 写流水 → 改状态
                                │ 库存不足 → 409，状态不变、无流水
                                ▼
                    ┌───────────────────────┐
                    │       SHIPPED         │  已发货   ← 此时才扣库存、才有出库流水
                    └───────────┬───────────┘
                                │ POST /shop/admin/orders/{no}/sign
                                │ 只写 signed_at / signed_by，不动库存
                                ▼
                    ┌───────────────────────┐
                    │        SIGNED         │  已签收   ← A1 的售后起算点
                    └───────────────────────┘
```

**只有三条边，没有回退、没有取消**。这条状态机由 `ck_shop_order_status` 钉在数据库里，
应用层写错一个字符串会直接失败，而不是产生一个"谁也不认识"的状态。

重复发货 / 重复签收**不是状态机的边**，而是幂等返回：
`200 + applied=false`，库存与流水都不动。

---

## 4. 接口与权限

服务端 context-path `/api`，端口 8123。

### 4.1 买家侧

| 方法 | 路径 | 身份 | 说明 |
| --- | --- | --- | --- |
| GET | `/shop/session` | 可选 | 当前身份 / 角色 / `canOrder` / `canOperate`，**不抛异常** |
| GET | `/shop/products` | 不需要 | 商品列表，含售罄商品（`purchasable` 仍为 `true`，见 5.1） |
| POST | `/shop/orders` | 需要 | 下单，返回 201 + 订单详情 |
| GET | `/shop/orders` | 需要 | 我的订单（最多 50 条） |
| GET | `/shop/orders/{orderNo}` | 需要 | 我的订单详情（含出库流水） |

下单请求体**只有两个字段**：

```json
{ "productId": 2001, "quantity": 2 }
```

`PlaceOrderRequest` 里没有 `price` / `buyerId` / `unitPriceCents` 字段——**字段不存在，
客户端就传不进来**。多传会被 Jackson 忽略，服务端从商品表读单价、从身份取买家。
（`ShopOrderFlowIntegrationTest` 专门断言"多传的 `buyerId` / `unitPriceCents` 不生效"。）

### 4.2 后台侧

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/shop/admin/orders?status=&page=&size=` | 按状态分页；未知状态 → 400 `INVALID_STATUS`；`size` 夹紧 1..100 |
| GET | `/shop/admin/orders/{orderNo}` | 任意订单详情（含流水） |
| POST | `/shop/admin/orders/{orderNo}/ship` | 发货；重复发货 200 `applied=false`；库存不足 409 |
| POST | `/shop/admin/orders/{orderNo}/sign` | 标记签收；未发货 409 `ORDER_NOT_SHIPPED` |
| GET | `/shop/admin/inventory` | 库存余额 + 最近 50 条流水（放在一个响应里，便于一眼对上） |

`ShopAdminController` 的**每个方法第一行**都是 `accessGuard.requireAdmin()`。
权限不写在业务里，也不靠前端隐藏按钮。

### 4.3 权限矩阵

| 身份 | 看商品 | 下单 | 看自己的订单 | 看别人的订单 | 发货 / 签收 / 库存 |
| --- | --- | --- | --- | --- | --- |
| 无 `X-User-Id` | ✅ | 401 | 401 | — | 401 |
| 有 header，`shop_user` 无此人 | ✅ | 403 `SHOP_USER_NOT_PROVISIONED` | 403 | — | 403 `NOT_ADMIN` |
| `BUYER` | ✅ | ✅ | ✅ 仅本人 | **404**（不是 403） | 403 `NOT_ADMIN` |
| `ADMIN` | ✅ | ✅（管理员也能买） | ✅ | ✅（后台入口） | ✅ |

三条细节：

1. **别人的订单返回 404 而不是 403**：403 等于告诉对方"这个订单号真实存在"，
   404 让"不存在"和"不是你的"无法区分。
2. **`shop_user` 是唯一可信来源**。`X-User-Id` 只提供"你是谁"，不提供"你是什么角色"；
   请求头、请求参数、提示词里声明角色一律无效（没有对应字段可绑）。
3. **禁用演示身份时先拒绝再查角色**。`mkagent.shop.demo-identity-enabled=false`
   且 `trusted-identity=false` 时，受保护写操作直接 403
   `SHOP_IDENTITY_NOT_CONFIGURED`，而不是"你没有角色"——后者会误导人去查角色表。

### 4.4 稳定错误码（前端按 `code` 分支，不解析中文文案）

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `INVALID_QUANTITY` / `INVALID_PRODUCT` / `INVALID_REQUEST` / `INVALID_STATUS` / `AMOUNT_OVERFLOW` | 参数问题 |
| 401 | `UNAUTHENTICATED` | 缺 `X-User-Id` |
| 403 | `SHOP_IDENTITY_NOT_CONFIGURED` / `SHOP_USER_NOT_PROVISIONED` / `NOT_ADMIN` | 身份/角色 |
| 404 | `PRODUCT_NOT_FOUND` / `ORDER_NOT_FOUND` | 不存在或不属于你 |
| 409 | `INSUFFICIENT_STOCK` / `ORDER_NOT_SHIPPED` / `ORDER_STATUS_CONFLICT` / `DATA_CONFLICT` | 业务冲突 |
| 503 | `MODEL_UNAVAILABLE` | 占位模型被调用（与商城无关，但商城接口不受影响） |

---

## 5. 关键设计决定

### 5.1 下单不预占库存 —— 连"顺手扣一下"的分支都不存在

`ShopOrderService.placeOrder` 只写 `shop_order` + `shop_order_item`，
**没有任何一行碰库存表**。这不是靠自觉，而是靠"没有那个分支"。

由此推出一个容易被误解的行为：`ShopProduct.purchasable()` **只看上架状态，不看库存**。

```java
public boolean purchasable() {
    return "ACTIVE".equals(status);
}
```

如果按"库存 > 0 才能买"来判定，浏览器上就永远走不到"库存不足导致发货失败"这条路径，
而那条路径正是本轮要验收的东西。所以页面的处理是：售罄商品**仍可下单**，
但明确提示"仍可下单，但发货时可能因库存不足失败"。

### 5.2 发货是一个事务，四件事要么全成要么全不成

`ShopOrderService.ship` 标了 `@Transactional`，顺序是刻意的：

1. `SELECT ... FOR UPDATE` 锁订单行（`lockByOrderNo`）→ 状态不是待发货就直接返回
   `applied=false`，**不碰库存**；
2. `InventoryService.applyOutbound` → 原子条件扣减
   `UPDATE shop_inventory_balance SET available = available - ? WHERE sku = ? AND available >= ?`，
   **0 行 = 库存不足**，抛 409，整体回滚；
3. 插入一笔 `quantity_delta = -quantity` 的 `OUTBOUND_SHIPMENT` 流水；
4. `UPDATE shop_order SET status='SHIPPED' ... WHERE id=? AND status='PENDING_SHIPMENT'`，
   0 行 → 409 `ORDER_STATUS_CONFLICT`，整体回滚（连已经扣掉的库存一起回滚）。

`InventoryService.applyOutbound` 上的 `@Transactional(propagation = MANDATORY)` 是关键：
它把"在事务外扣库存"从**约定**变成**运行时错误**。没有事务调用它，Spring 直接抛异常。

### 5.3 重复发货与并发争抢：三层防线

| 层 | 挡什么 | 实现 |
| --- | --- | --- |
| 订单行锁 | 同一订单的并发发货 | `SELECT ... FOR UPDATE`，第二个请求排队后看到状态已是 `SHIPPED`，返回 `applied=false` |
| 原子条件更新 | 不同订单争抢最后库存 | `WHERE available >= ?`，0 行即失败；**不是**"先查再减" |
| 数据库约束 | 应用层写错时的兜底 | `CHECK (available >= 0)` + `UNIQUE (order_item_id, movement_type)` |

**没有用前端禁用按钮兜底**：并发测试直接对 HTTP 端口发请求（`ShopApiClient`），
绕过了所有页面逻辑，仍然只扣一次、只写一笔流水。

### 5.4 金额：整数分，端到端

`price_cents` / `total_cents` / `unit_price_cents` / `line_cents` 全是 `BIGINT`。
行小计用 `Math.multiplyExact`，溢出转 400 `AMOUNT_OVERFLOW`，不让脏数据进库。
对外文案 `"¥" + BigDecimal.valueOf(cents, 2).toPlainString()`。

### 5.5 身份：只借 `X-User-Id` 的"你是谁"，不借"你是什么角色"

现状没有真实认证，`X-User-Id` 可伪造。因此：

- 默认 `demo-identity-enabled=false` + `trusted-identity=false` → **受保护写操作一律 403**，
  商品浏览仍可用。这是共享部署的安全默认值。
- 本地演示显式打开 `demo-identity-enabled=true`（`application-demo.yml` 已打开）。
- 角色只从 `shop_user` 查，`music_user` 的角色不推导到商城，反之亦然。
- 同一组 userId（`music-user-1/2`、`music-admin-1`）被两个模块复用，
  因为前端只有一个 `mkagent.dev.userId` 开关；**但角色表是两张**，
  "能听歌"不代表"能发货"。

### 5.6 模型不可用不影响商城

- `mkagent.model.allow-missing-credentials=true` 且对话模型凭据为空时，
  注册 `UnavailableChatModel` 占位（同名 Bean `dashscopeChatModel`），
  调用即抛 503 `MODEL_UNAVAILABLE`，**绝不返回假回答**；不打开这个开关时行为不变（缺凭据仍然启动失败）。
- 顺带摘掉了 DashScope **语音**自动配置（`DashScopeAudioSpeechAutoConfiguration` /
  `DashScopeAudioTranscriptionAutoConfiguration`）：本项目零引用，而它们在 api-key 为空时
  会由库直接抛 `IllegalArgumentException` 让整个应用起不来。
  这是"去掉没人用却会硬失败的装配"，不是重构模型体系。
- **仍然存在的耦合（明确限制）**：`Embedding` / `Image` / `Rerank` 自动配置同样硬依赖
  api-key。生产保留它们（`Embedding` 是 RAG 依赖，`Image` 与既有 RetryTemplate
  污染诊断相关）。所以"完全零凭据启动"的完整部署仍需自行关掉 RAG / Embedding；
  本轮只保证**对话模型**无凭据或不可用时商城可启动、可下单、可发货。

---

## 6. 本地启动、演示身份、种子数据

### 6.1 启动

```bash
# 后端（demo profile：打开演示身份；H2 文件库，不碰音乐 demo 库）
./mvnw -o spring-boot:run -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:h2:file:./tmp/shop-demo-db/mk-agent;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"

# 前端（Vite 5173，代理 /api → 8123）
cd web && npm install && npm run dev
```

> 已有 PostgreSQL 时不用指定 `spring.datasource.url`，直接跑 `application-local.yml` 即可；
> 建表脚本幂等，启动时自动执行。

**不需要任何模型凭据**就能跑通商城闭环。要跑 Agent 功能才需要
`MKAGENT_MODEL_API_KEY`（或 `SPRING_AI_DASHSCOPE_API_KEY`），见 README 的密钥分层说明。

### 6.2 演示身份

页面左下角有身份下拉（只在 `demo-identity-enabled=true` 时出现）：

| 身份 | 商城角色 | 用途 |
| --- | --- | --- |
| `music-user-1` | `BUYER` | 主验收用的买家 A |
| `music-user-2` | `BUYER` | 买家 B（验证"看不到别人的订单"） |
| `music-admin-1` | `ADMIN` | 后台发货 / 签收 / 看库存 |

### 6.3 种子数据（`ShopSeedInitializer`，`@Profile("!prod")`，幂等）

| 商品 id | SKU | 名称 | 价格 | 初始可售 |
| --- | --- | --- | --- | --- |
| 2001 | `SKU-KEYBOARD-K87` | 机械键盘 K87 | ¥299.00 | **10** |
| 2002 | `SKU-MOUSE-M2` | 静音鼠标 M2 | ¥129.00 | 5 |
| 2003 | `SKU-CABLE-C1` | USB-C 数据线 C1 | ¥19.00 | **0**（演示售罄/库存不足） |

初始化器**只在首次建库存**：重启不会把库存重置回 10。
演示到一半重启后端，订单和库存必须还在——否则"刷新后能看到持久化状态"就无从谈起。
需要重置时用测试夹具（`ShopTestFixture.reset`）或手工 SQL。

### 6.4 配置项

```yaml
mkagent:
  shop:
    demo-identity-enabled: ${MKAGENT_SHOP_DEMO_IDENTITY:false}  # 本地演示才开
    trusted-identity: ${MKAGENT_SHOP_TRUSTED_IDENTITY:false}    # 接入真实登录态后才开
    max-quantity-per-order: 99
    admin-page-size: 20
  model:
    allow-missing-credentials: ${MKAGENT_MODEL_ALLOW_MISSING_CREDENTIALS:false}
```

---

## 7. 浏览器复现步骤（主验收路径）

1. `http://localhost:5173/` → 顶部「商城」（或在地址栏直接 `/shop`）。
2. 身份选 `music-user-1`。商品区能看到 3 个商品；数据线显示「暂时无货」但**仍可下单**。
3. 键盘卡片点 `+` 到 **2** → 「立即购买」。
4. 页面出现订单号（形如 `SO20261121164900-1A2B`）、「待发货」、`¥598.00`、
   「演示付款 · 无真实资金交易」，并提示**尚未扣减库存**。
5. 切身份到 `music-admin-1` → 左侧「商城后台」→ 找到该订单 → 「发货」。
6. 后台库存区：键盘 `10 → 8`，流水区恰好 1 条 `-2 / OUTBOUND_SHIPMENT`，关联该订单。
7. 点「标记签收」→ 状态「已签收」，库存仍为 8。
8. **刷新页面**：订单仍是「已签收」，出库记录仍在（读的是持久化状态，不是内存）。
9. 切回 `music-user-1` → 我的订单里能看到该订单「已签收」和 1 条出库记录。

失败分支（值得手动看一次）：

- 用数据线（库存 0）下单 → 成功（待发货）→ 后台发货 → 409
  「库存不足：… 订单仍为待发货，库存与流水均未变动」，订单**仍是待发货**。
- 已签收订单再点发货 → 200 + 「该订单已签收，本次未重复发货」。
- 未发货订单点签收 → 409 「只有已发货的订单才能签收」。
- 买家 B 打开买家 A 的订单号 → 404。

---

## 8. 测试实跑结果

> 全部在 **H2（MODE=PostgreSQL）** 上验证。**PostgreSQL 未实跑**，见第 9 节。

### 8.1 后端全量（本轮最后一次运行，改动全部落盘后）

```bash
env -u MKAGENT_MODEL_API_KEY -u AI_DASHSCOPE_API_KEY ./mvnw -o test
# Tests run: 841, Failures: 0, Errors: 0, Skipped: 0
# BUILD SUCCESS   Total time: 05:08 min
```

### 8.2 商城专项（6 个测试类，30 个用例，全绿）

| 测试类 | 用例 | 覆盖的验收点 |
| --- | --- | --- |
| `ShopOrderFlowIntegrationTest` | 12 | 主流程（10 → 下单后仍 10 → 发货后 8、恰好 1 笔 `-2` → 签收仍 8、刷新可见）；价格/买家由服务端决定；重复发货只扣一次；库存不足保持待发货且 0 流水；未发货不能签收；重复签收 `signedAt` 不变；买家 A 读不到 B 的订单；买家不能走后台；缺身份 401；未授权身份 403；非法数量/商品 400/404 且 0 订单；列表 `movements` 为 `null` vs 详情为数组 |
| `ShopOrderConcurrencyTest` | 3 | 同一订单并发发货 → 恰好 1 个 `applied`、库存 8、1 笔流水；两单争最后 1 件 → 1 成功 + 1 个 409，库存 0，失败单仍待发货且 0 流水；3 件库存两单各 2 → 1 成功，库存 1 |
| `ShopAccessControlIntegrationTest` | 6 | 买家/管理员/匿名 session 语义；管理员也能下单；角色不能自报（403 `NOT_ADMIN`）；未知身份在后台入口不泄露"用户是否存在" |
| `ShopIdentityDisabledIntegrationTest` | 6 | `demo-identity-enabled=false` 时：商品仍公开；session 报 `DISABLED`；下单/读单/发货/库存全部 403 `SHOP_IDENTITY_NOT_CONFIGURED`，且订单表 0 行 |
| `ShopModelIndependenceTest` | 1 | 容器里塞一个**每次调用都抛异常**的 `@Primary ChatModel`，商城下单→发货→签收照样跑通，库存与流水正确 |
| `ShopStartsWithoutModelCredentialTest` | 2 | 对话模型凭据为空 + 开关打开 → 容器里是 `UnavailableChatModel`，调用即 503 `MODEL_UNAVAILABLE`；商城闭环仍可用 |

并发用例**不是 MockMvc**：`ShopApiClient` 用 `java.net.http.HttpClient` 打真实端口
（`RANDOM_PORT`），每个请求有自己的连接和事务。MockMvc 共用一个线程/事务，
测不出并发行为——那样得到的"通过"是假的。

### 8.3 前端

```bash
cd web
npm run typecheck                                   # vue-tsc --noEmit，0 错误
npx vitest run src/stores/shop.spec.ts src/components/ShopOrderDetailPanel.spec.ts
# 2 files, 17 passed
npx vitest run                                      # 全量：19 files, 150 passed
npm run build                                       # 构建成功（产出 ShopView / ShopAdminView / shop store 三个 chunk）
```

覆盖：下单成功后刷新商品+订单；库存不足只报错、不打开详情；禁用身份时不发下单请求；
重复发货显示"未重复执行"且**不当作错误**；发货失败后先刷新再回写错误文案；
签收后替换该行并刷新库存；状态筛选重置到第 1 页；`reset` 清空；错误码 → 文案映射。

### 8.4 真实浏览器 E2E

```bash
cd web && node tools/e2e-shop.mjs
# 19/19 passed
```

Playwright-core + 本机 Chrome，真点真刷新。最近一次实测输出（库存起点是上一轮跑剩下的 8，
脚本用相对值断言，所以可重复跑）：

```
PASS  商品列表渲染 3 个商品 — cards=3
PASS  顶部明确标注演示付款
PASS  种子键盘可售数量可读 — before=可售 8 件
PASS  数量步进到 2 件 — qty=2
PASS  下单成功并返回订单号 — 下单成功：SO20261122005621-CE9E，¥598.00。当前为待发货，尚未扣减库存。
PASS  下单后库存不变（下单不预占、不扣库存） — before=8 after=8
PASS  发货成功并说明扣减 — 已发货：扣减 SKU-KEYBOARD-K87 2 件，并写入出库流水
PASS  发货后库存正好减少购买数量 — before=8 after=6
PASS  订单详情里只有 1 笔出库流水 — count=1
PASS  流水数量为 -2 且类型为发货出库 — -2 / SKU-KEYBOARD-K87 / OUTBOUND_SHIPMENT · music-admin-1
PASS  签收后库存不变 — afterShip=6 afterSign=6
PASS  刷新后订单仍为已签收（持久化）
PASS  买家刷新后看到已签收
PASS  买家能看到该订单的出库记录 — moves=1
PASS  浏览器控制台没有错误
```

在**全新种子库**（键盘库存 10）上首跑时，同一脚本打印的是
`下单后 before=10 after=10` → `发货后 before=10 after=8` → `签收后 afterShip=8 afterSign=8`，
即第 5 节表格里的主验收数字。

库存断言用的是**相对值**（`before - BUY_QUANTITY`）而不是硬编码 10，
所以重复跑不会因为库里已有订单而假失败。

### 8.5 三种验证的边界

| 类型 | 手段 | 能证明什么 | 不能证明什么 |
| --- | --- | --- | --- |
| 自动化功能测试 | Spring Boot 集成测试（H2） | 状态机、权限、幂等、错误码 | 真实数据库的锁行为 |
| 数据库并发测试 | 真实 HTTP + `CountDownLatch` + 4 线程（H2） | 条件更新与行锁在**并发下**只扣一次、不为负 | PostgreSQL 的 `FOR UPDATE` / 隔离级别行为 |
| 浏览器验收 | Playwright + 真实 Chrome | 页面、刷新持久化、文案区分 | 高并发、故障注入 |

---

## 9. 限制（明确未验证 / 未实现）

1. **PostgreSQL 未实跑**。全部测试与 E2E 都在 H2（MODE=PostgreSQL）上。
   H2 的 `SELECT ... FOR UPDATE` 与 `CHECK` 语义与 PG 接近，但**不等于**已验证；
   PG 上的行锁等待、`SERIALIZABLE` 冲突、`identity` 行为都需要一次真库回归。
2. **没有真实认证**。`X-User-Id` 可伪造。`demo-identity-enabled` / `trusted-identity`
   只是"要不要信任占位身份"的开关，**不是安全边界**。共享部署必须两个都关。
3. **完全零凭据启动仍不成立**。`Embedding` / `Image` / `Rerank` 自动配置硬依赖 api-key，
   生产保留它们（RAG / 既有诊断依赖）。本轮只隔离了**对话模型**与**语音**。
4. **单仓、单 SKU 行/单、无多规格、无购物车、无真实支付/退款/物流**。
   `uk_shop_order_item_order` 是硬约束，改多行需要先改这条索引。
5. **买家订单列表最多 50 条，不做分页**；后台分页上限 100。
6. **签收由后台标记，不是买家自己点**。这是刻意的：让买家自报签收，等于把 A1 的
   售后起算点交给买家控制。真实业务里买家确认收货应走买家侧，届时 `signed_by` 的语义要一起改。
7. **`shop_inventory_movement` 只允许 `OUTBOUND_SHIPMENT`**。B 段加入库必须显式改 CHECK。
8. **未做性能压测**。当前设计（订单行锁 + 单行库存条件更新）在热点 SKU 上会串行化，
   单仓单行的场景够用；SKU 级热点吞吐需要另行评估。

---

## 10. A1 最小接入设计（只设计，不实施）

> A1 = 电商售后 Agent：用 A0 的已签收订单，查本人订单、追问商品与数量、
> 校验可退资格、查已有申请、预览并确认后创建退货单。
> **本节不产生任何代码和表。**

### 10.1 资格校验的输入：全部来自 A0 已有事实

| 输入 | A0 来源 | 用途 |
| --- | --- | --- |
| 买家身份 | `shop_order.buyer_id` / `ShopAccessGuard.requireBuyerIdentity()` | 归属校验，**不接受工具参数传入** |
| 签收时间 | `shop_order.signed_at`（签收时写入，之后不变） | 售后起算点 + 时限窗口 |
| 原订单行 | `shop_order_item`（`sku` / `product_name` / `unit_price_cents` / `quantity`） | 可退数量上限、退款金额上限、商品快照 |
| 订单状态 | `shop_order.status = 'SIGNED'` | 未签收不允许申请 |
| 已占用额度 | A1 新增的退货申请表（见 10.5） | 可退额度 = 已购 − 已占用 |

资格判定写成**一个纯函数 + 一次查询**，与 Agent 无关：

```text
eligible(orderItem, signedAt, now, occupiedQty, policy) =
      order.status == SIGNED
   && order.buyerId == 当前身份
   && now - signedAt <= policy.window          // 例如 7 天
   && policy.productReturnable(sku)            // 例如虚拟商品/定制件不可退
   && orderItem.quantity - occupiedQty >= 1
```

**同一个函数必须同时给"普通售后表单"和"Agent 工具"用**（A1 的硬要求）。
因此它属于 `shop.aftersale` 的领域服务，不属于任何 Agent 类——
Agent 只是调用方，不能有第二份判定逻辑。

### 10.2 买家身份从哪来

复用 A0 的 `ShopAccessGuard.requireBuyerIdentity()`，在**进入 Agent 请求时**解析一次，
冻结进 `AgentRunContextHolder`（与音乐工具同一套做法），工具侧**没有 `buyerId` 参数**。

三条不做的事：

- 不让模型从对话里"推断"买家（模型可以被用户说服）；
- 不接受工具参数里的 `buyerId`（等于让模型决定查谁的订单）；
- 不让前端把 buyerId 塞进 SSE 参数。

查询类工具（查我的已签收订单、查该订单已有申请）只按上下文里的身份过滤，
SQL 里带 `buyer_id = ?`，与 A0 的 `findByOrderNoAndBuyer` 同一模式。

### 10.3 复用 Agent 循环 / 会话 / SSE / 提交确认

**可直接复用**（A0 已确认存在且未被本轮改动）：

| 能力 | 复用方式 |
| --- | --- |
| `ToolCallAgent` 循环 | 新增 `AgentType.AFTER_SALES`，复用同一骨架，不改 `BaseAgent` |
| 工具白名单 | `AgentToolProvider` 按 `AgentType` 注册 Bean；售后工具只挂到 `AFTER_SALES` |
| 会话与多轮 | `agent_conversation` / `agent_conversation_message` 现成；"追问商品和数量"就是普通多轮 |
| SSE | 现成事件流（`status/step/tool_start/tool_result/final_answer/done/error`），前端已有消费逻辑 |
| 人工确认 | `@RequiresApproval` + `ApprovalToolPolicy`（`ContextRefreshedEvent` 扫描，fail-closed） |
| 参数快照 | 审批卡里带工具名 + 参数快照；确认后按**快照**执行，而不是重新问模型 |
| 幂等 | `IdempotencyKeys`：`sha256("v2|u{len}:{userId}|r{runId}|s{step}|c{len}:{toolCallId}")` + `request_hash` 一致性校验 |

**必须新写的最小部分**：一个 `preview_return`（只读，算额度与金额，返回结构化预览）
和一个 `submit_return`（`@RequiresApproval`，真正落单）。预览与提交**共用同一个
领域服务**，预览只是"不写库地跑一遍同样的校验"，避免预览说能退、提交却退不了。

工具清单（建议，A1 再定稿）：

```text
list_my_signed_orders     只读   我的已签收订单（分页）
get_my_order_detail       只读   单个订单 + 订单行快照 + 已有申请
check_return_eligibility  只读   按订单行算可退额度（时限/数量/商品规则）
list_my_return_requests   只读   已有申请（防重复提交、回答"我上次那单"）
preview_return            只读   预览：退几件、退多少钱、为什么不能退
submit_return             写     需要批准；批准后落库
```

### 10.4 为什么确认之后必须**再校验一次**额度

确认卡上的"可退 2 件"是**生成快照那一刻**的事实。用户点"确认"与工具真正执行之间，
世界会变：

1. 同一个用户在另一个标签页/另一个会话里已经提交过一笔；
2. 另一个人（同一账号）也在申请；
3. 后台刚把该订单的退货窗口关掉，或商品被标记为不可退；
4. 上一笔申请被客服批准，额度已被占用。

模型给的预览**不是授权凭证**，它只是"当时的计算结果"。所以 `submit_return` 的执行体
必须在一个事务里重新跑 10.1 的判定，并且用**数据库条件更新**抢占额度，而不是信任快照。

结论：**预览用于让用户理解，校验用于让系统正确**。二者共用同一个函数，但**执行时重算**。

### 10.5 同一次确认重试 vs 不同申请并发争抢额度

这两件事必须用**两套不同的机制**，混用会导致"重试变成两笔申请"或"两笔申请被当成一次"。

**(a) 同一次确认的重试 → 幂等键**

确认卡的每次执行都带着 `runId + step + toolCallId`，`IdempotencyKeys` 派生出的 key 相同：

```
第一次：写退货单 + 写幂等记录（key, request_hash）
重试：  同 key 命中记录 → 校验 request_hash 一致 → 直接返回第一次的结果
```

所以"用户连点两次确认"或"网络重试"**不会产生第二张退货单**。
`request_hash` 的作用是防止同 key 不同参数（那说明是代码/参数 bug，应当报错而不是静默复用）。

**(b) 不同申请并发争抢同一额度 → 条件更新 + 约束**

不同会话的 `runId`/`toolCallId` 不同 → 幂等键不同 → 它们**本来就是两笔独立申请**。
要保证"两笔加起来不超过已购数量"，用 A0 已验证的同一套手法：

```sql
-- 在订单行上占额度：原子条件更新，0 行即额度不足
UPDATE shop_order_item
   SET reserved_return_qty = reserved_return_qty + ?
 WHERE id = ? AND reserved_return_qty + ? <= quantity;
```

再加一条数据库兜底约束（`CHECK (reserved_return_qty >= 0 AND reserved_return_qty <= quantity)`），
让"应用层写错"变成约束冲突而不是脏数据——与 `CHECK (available >= 0)` 同构。

另外两条：

- **额度按订单行加锁，不按用户加锁**：锁用户会让同一个人的两笔正常申请互相阻塞，
  而真正要保护的资源是"这一行的可退数量"。
- **幂等键与额度是两件事**：幂等键管"同一请求重复到达"，条件更新管"不同请求抢同一份额度"。
  只有幂等键 → 两笔并发都成功，超额；只有条件更新 → 重试会重复占额度。

### 10.6 与 B 段的边界（A1 不做，但要提前说清）

三个动作**不能混成一个**：

| 动作 | 谁做 | 结果 |
| --- | --- | --- |
| 买家确认提交退货申请 | 买家（A1） | 生成退货单，状态"待审核"，**不动库存** |
| 客服批准 / 驳回 | 客服（B） | 状态"已批准/已驳回"；批准**仍不动库存** |
| 仓库收货验货 | 仓库（B） | 合格品 → `RETURN_INBOUND` 入库流水（`available` 增加）；损坏品 → 不增加可售库存 |

也就是说：**"批准"不等于"库存回来了"**。库存只在验货合格后变化，
这正好与 A0 的"发货才扣库存"对称——两个方向都只在**实物移动**时动库存。

---

## 11. 变更文件清单（本轮）

**新增（后端）**

```
src/main/java/com/example/mkagent/shop/ShopProperties.java
src/main/java/com/example/mkagent/shop/ShopRole.java
src/main/java/com/example/mkagent/shop/ShopRoleResolver.java
src/main/java/com/example/mkagent/shop/ShopAccessGuard.java
src/main/java/com/example/mkagent/shop/ShopSeedInitializer.java
src/main/java/com/example/mkagent/shop/api/ShopViews.java
src/main/java/com/example/mkagent/shop/catalog/ShopProduct.java
src/main/java/com/example/mkagent/shop/catalog/ShopProductRepository.java
src/main/java/com/example/mkagent/shop/order/ShopOrderStatus.java
src/main/java/com/example/mkagent/shop/order/ShopOrder.java
src/main/java/com/example/mkagent/shop/order/ShopOrderItem.java
src/main/java/com/example/mkagent/shop/order/ShopOrderSummary.java
src/main/java/com/example/mkagent/shop/order/ShopOrderDetail.java
src/main/java/com/example/mkagent/shop/order/OrderActionResult.java
src/main/java/com/example/mkagent/shop/order/ShopOrderRepository.java
src/main/java/com/example/mkagent/shop/order/ShopOrderService.java
src/main/java/com/example/mkagent/shop/inventory/InventoryMovement.java
src/main/java/com/example/mkagent/shop/inventory/InventoryBalanceView.java
src/main/java/com/example/mkagent/shop/inventory/InventoryRepository.java
src/main/java/com/example/mkagent/shop/inventory/InventoryService.java
src/main/java/com/example/mkagent/controller/ShopController.java
src/main/java/com/example/mkagent/controller/ShopAdminController.java
src/main/java/com/example/mkagent/config/UnavailableChatModel.java
src/main/java/com/example/mkagent/config/MissingModelCredentialCondition.java
```

**修改（后端）**

```
src/main/resources/db/schema.sql               + 商城六张表与 COMMENT
src/main/resources/application.yml             + mkagent.shop.*；排除 DashScope 语音自动配置
src/main/resources/application-demo.yml        + shop.demo-identity-enabled: true
src/main/java/.../config/DashScopeChatModelConfig.java  占位模型分支（条件化真实 Bean）
src/main/java/.../exception/GlobalExceptionHandler.java + DataIntegrityViolationException → 409
src/test/resources/application-test.yml        + 商城测试开关；补齐测试环境排除列表
```

**新增（测试）**：`src/test/java/com/example/mkagent/shop/` 下 8 个文件 =
6 个测试类 + 2 个支撑类（`ShopApiClient` 真实 HTTP 客户端、`ShopTestFixture` 夹具/重置）。

**新增（前端）**

```
web/src/api/shop.ts
web/src/stores/shop.ts
web/src/stores/shop.spec.ts
web/src/components/ShopNav.vue
web/src/components/ShopOrderDetailPanel.vue
web/src/components/ShopOrderDetailPanel.spec.ts
web/src/views/ShopView.vue
web/src/views/ShopAdminView.vue
web/tools/e2e-shop.mjs
```

**修改（前端）**：`web/src/router.ts`（`/shop`、`/shop/admin` 两条路由）、
`web/src/views/TaskWorkspaceView.vue`（顶栏加「商城」入口）。

**没有删除任何音乐文件、路由或数据。**
