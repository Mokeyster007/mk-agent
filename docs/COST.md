# 成本控制说明（COST.md）

本文记录这个项目"哪里会花钱、怎么把钱花在刀刃上"，以及每个取舍的代价。
所有数字与配置都以仓库内代码/配置为准，不写未经实测的估算值。

成本来源只有三类：

1. **启动成本**：应用一启动就去建 RAG 向量库 → 调用 Embedding + 对话模型；
2. **失败成本**：一次 400 参数错误被重试 10 次、每次指数退避到 180s → 白等几分钟还照样失败；
3. **评测成本**：离线评测跑真实模型 + 真实搜索 + LLM judge，一次全量就是真金白银。

---

## 1. 启动成本：RAG 延迟初始化 + 入库幂等

| 措施 | 效果 | 代价 |
| --- | --- | --- |
| `mkagent.rag.init-mode=lazy`（默认） | 启动阶段零模型调用，向量库在首次检索时构建 | 首次检索会慢一点；构建失败时该次请求返回 503 |
| 稳定 chunk ID（`文件名#序号-sha256前16位`）+ sidecar 索引 | 内容未变的文档不再重复 Embedding | 多一个索引文件要维护，索引损坏时整体重建 |
| 模型名 / 维度不一致 → 全量重建 | 换模型后不会出现"新旧向量混在一个库里"的脏数据 | 换模型那一次必须全量重算 |

细节见 `docs/agent-learning/` 与 `RagKnowledgeBaseBuilder` / `RagStorePersistence`。

---

## 2. 失败成本：模型调用重试策略

### 2.1 策略到底从哪里来（源码链路）

`DashScopeChatModel` 自己不决定重试，它只是接收一个 `RetryTemplate`：

```text
spring-ai-autoconfigure-retry
  SpringAiRetryAutoConfiguration#retryTemplate(SpringAiRetryProperties)
      ↑ 参数绑定 spring.ai.retry.*
      ↓
spring-ai-alibaba-starter-dashscope
  DashScopeChatAutoConfiguration#dashscopeChatModel(RetryTemplate, ...)
      ↓
  DashScopeChatModel.Builder#retryTemplate(...)
```

也就是说：改 `spring.ai.retry.*` 只是"调参数"，语义仍然是"只重试 `TransientAiException`"。
框架默认值是 **maxAttempts=10、指数退避（2s 起、×5、上限 180s）**——
对一个交互式 Agent 完全不可接受：一次 400 会白白等上几分钟，
最后还可能先撞上 Agent 总超时，把"参数错误"伪装成 `TIMED_OUT`。

### 2.2 本项目覆盖后的策略

`config/ModelRetryConfig` 直接定义同名 `RetryTemplate` Bean。
Spring AI 的自动配置带 `@ConditionalOnMissingBean`，会整体让位，
所以最终生效的是下面这套：

| 配置项（`mkagent.model-retry.*`） | 默认值 | 说明 |
| --- | --- | --- |
| `max-attempts` | `3` | 含首次调用，即"首次 + 最多 2 次重试" |
| `initial-backoff` | `1s` | 首次重试前等待 |
| `multiplier` | `2.0` | 指数退避倍数 |
| `max-backoff` | `2s` | 单次退避上限，最坏总等待约 3s |
| `retry-on-timeout` | `true` | `ResourceAccessException`（网络超时）是否重试 |

重试判定：

| 失败类型 | 是否重试 | 典型场景 |
| --- | --- | --- |
| `TransientAiException`（5xx、429） | 是，最多 3 次 | 服务端抖动、限流 |
| `ResourceAccessException`（网络超时） | 是（可关） | 连接超时、读超时 |
| `NonTransientAiException`（400/401/403/欠费） | **否，立即失败** | 参数写错、Key 失效、额度耗尽 |

另外设置了 `throwLastExceptionOnExhausted(true)`：重试耗尽后抛"最后一次的真实异常"，
而不是 `ExhaustedRetryException` 包装，这样 `ToolCallAgent` 才能据此给出可读的 ERROR 文案
（检查配置/额度 vs 稍后重试）。

> 注意：`application-local.yml` 里的本地覆盖不属于仓库配置，本文不描述其取值。

### 2.3 429 依赖 `spring.ai.retry.on-http-codes`

Spring AI 的 `ResponseErrorHandler` 把 **4xx（含 429）一律归为 `NonTransientAiException`**。
按 2.2 的策略，429 会被"立即失败"，一次限流就等于一次失败。

所以 `application.yml` 里显式配置了：

```yaml
spring:
  ai:
    retry:
      on-http-codes:
        - 429
```

让 429 被归类为 `TransientAiException`，与 5xx 走同一条可重试路径。

这两者分工必须分清：

- `spring.ai.retry.on-http-codes` 决定 **异常分类**（429 算不算"可自愈"）；
- `mkagent.model-retry.*` 决定 **重试次数与退避**。

只配后者不配前者 → 429 仍然不重试；只配前者不配后者 → 429 会重试 10 次、最长等到 180s。
两者必须同时存在才是"有限次、快速失败"。

### 2.4 取舍：Embedding 共用同一个 `RetryTemplate`

`DashScopeEmbeddingModel` 与 `DashScopeChatModel` 注入的是**同一个** `RetryTemplate` Bean，
因此 Embedding 也走"3 次、1s→2s"这套交互式策略。这是一个有意识的取舍：

**好处**

- 只有一套配置、一套重试日志（`ModelRetryLoggingListener` 一行 WARN，堆栈在 DEBUG），
  排查时不会出现"对话重试 3 次、向量重试 10 次"的两套行为；
- 批量入库最怕长时间挂住：单个 chunk 失败最多 3 次，不会把一次启动/入库拖到几分钟；
- `EmbeddingModel#dimensions()` 的默认实现会真的发一次 Embedding 请求，
  也被同一策略约束，不会在启动探针上悄悄重试 10 次。

**代价**

- Embedding 是"幂等、批量、对延迟不敏感"的场景，本可以更激进（比如 5 次、更长退避）；
  现在被压到 3 次，偶发连续 5xx 会让一批文档入库失败；
- 兜底只有两条：`RagAvailability` 的 sticky unavailable（本轮不再重试，避免越试越贵），
  以及下次启动/下次重建时的增量补齐（见第 1 节）。

**如果将来要分开**：再定义一个 `RetryTemplate` Bean 并用 `@Qualifier` 注入给
`EmbeddingModel` 即可（Spring AI 自动配置只兜底"缺失时才创建"），
但会引入第二套配置项与第二套日志口径，目前收益不足以抵消复杂度，故不做。

### 2.5 别把工具重试和模型重试混为一谈

| | 模型重试（`mkagent.model-retry`） | 工具重试（`mkagent.tool-retry`） |
| --- | --- | --- |
| 触发层 | HTTP/模型调用 | 工具执行（`RetryableToolCallback`） |
| 次数 | 3（含首次） | 3（含首次） |
| 退避 | 1s → 2s 指数，封顶 2s | 200ms 起，×2 |
| 范围 | 所有模型调用 | 只对 `retryable-tools`（默认 `web_search,web_scrape`） |
| 失败识别 | 异常类型 | 异常 + 失败文案关键词（`failure-markers`） |

两层都重试同一件事时会相乘（3 × 3），这是刻意的：模型层重试的是"模型服务抖动"，
工具层重试的是"工具实现/上游站点抖动"，两者不是同一个故障源。
但工具失败文案必须与可重试语义对齐（`网页抓取失败：` 可重试、`网页抓取被拒绝：` 不重试），
否则 SSRF 拒绝也会被当成抖动重试，纯属烧钱。

---

## 3. 评测成本：live 隔离 + 整轮预算

### 3.1 默认绝不花钱

真实模型 / 真实搜索 / 真实数据库的测试统一标注 `@Tag("live")`，
`maven-surefire-plugin` 默认 `excludedGroups=live`：

```bash
mvn test                 # 不跑任何 live 测试
mvn -Plive test          # 打开 live 分组（会真实花钱）
```

`LiveTestIsolationUnitTest` 会断言"默认排除 live"与"已知 live 测试都带标签"，
防止新加的付费测试漏标签后悄悄进全量测试。

### 3.2 整轮预算

单条用例的 `maxSteps` / `maxToolCalls` 只能约束单条，管不住整轮。
因此 `mkagent.eval` 增加了整轮预算：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `max-total-tokens` | `2000000` | Agent 模型 + LLM judge 的 Token 总上限，0 = 不限 |
| `max-duration-seconds` | `1800` | 整轮墙钟时长上限，0 = 不限 |
| `max-cases` | `0` | 最多跑多少条（0 = 全部），用于冒烟跑 |

语义是**软停止**：任一项超限后不再启动新用例，已执行的用例结果照常进报告，
未执行的记为"预算跳过"，并在报告里明确写出"指标只覆盖已执行用例"。

---

## 4. 还在计划中的降本项

以下尚未实现，先留位置，避免把"计划"写成"已完成"：

- Token 降本：网页抓取正文截断（`mkagent.tools.web-scrape.max-chars`）、
  工具结果裁剪（`ToolResultTrimmer`，默认关闭）；
- 搜索缓存 + 评测 RECORD/REPLAY（同一用例重复跑时不重复调用搜索 API）。

---

## 5. 自查清单

- [ ] 新增调用模型的测试，是否标了 `@Tag("live")` 并加入 `LiveTestIsolationUnitTest` 清单？
- [ ] 新增模型调用，是否走容器里的 `RetryTemplate`（而不是自己 `new` 一个默认策略）？
- [ ] 新增 4xx 语义（如 408/425），是否需要补进 `spring.ai.retry.on-http-codes`？
- [ ] 新增批量模型调用，是否会被"3 次 × 2s"放大成不可接受的启动/请求耗时？
- [ ] 新增工具失败文案，是否落在"可重试"与"不可重试"两个前缀之一？
