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

但这条链路并不可靠：**模板实例可能被别人改掉**，见 2.2。

### 2.2 事故复盘：策略会被库代码"原地改写"

只把 `RetryTemplate` 定义成 Bean 是不够的。真实运行出现过：
同一个 HTTP 400（`NonTransientAiException`）被重试 8 次、每次间隔约 15 秒，
堆栈落在 `DashScopeChatModel.internalCall` + `FixedBackOffPolicy`，
而启动日志明明打印着 `maxAttempts=3 / exponential`。

根因在 `spring-ai-alibaba-core` 的 `DashScopeImageModel` 构造器（反编译 1.0.0.2 确认，非推测）：

```java
SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(10);
FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
backOffPolicy.setBackOffPeriod(15000L);
retryTemplate.setRetryPolicy(retryPolicy);      // ← 改的是传进来的那个实例
retryTemplate.setBackOffPolicy(backOffPolicy);  // ← 不是副本
```

于是"容器里只有一个 `RetryTemplate` Bean"这件事本身成了 bug 的放大器：
对话模型先创建、图片模型后创建，图片模型一构造，
对话模型手里的模板（同一个对象）就变成了"10 次 + 固定 15 秒 + 连 `Exception` 都重试"。
启动日志打印的是构建时的快照，所以从日志上看不出它被改过——
这也是这次排查绕远路的直接原因：**要断言实例，不要只看配置。**

两道防线：

1. **对话模型持有私有实例**：`config/DashScopeChatModelConfig` 不注入容器 Bean，
   而是用 `ModelRetryConfig.build(...)` 自行构建，再经
   `DashScopeChatModel.Builder#retryTemplate(...)` 显式传入；Bean 名仍为
   `dashscopeChatModel`，让自动配置的 `@ConditionalOnMissingBean` 整体让位。
2. **容器 Bean 改为 prototype**：Embedding、Image 等消费方各拿一个实例，
   谁改写都不影响别人；策略仍统一来自 `mkagent.model-retry.*`，
   差别只是"不再共享同一个可变对象"。

回归测试与自检：

| 位置 | 作用 |
| --- | --- |
| `DashScopeImageModelRetryPoisoningTest` | 复现库的原地改写行为，并断言对话模型的私有模板不受影响 |
| `DashScopeChatModelRetryWiringTest` | 加载 DashScope 对话自动配置 + `MockRestServiceServer` 让 text-generation 返回 400，断言请求只发 1 次、`ToolCallAgent` 秒级 FAILED |
| `ModelRetryProductionWiringTest` | 完整上下文里断言容器 Bean 仍是 `maxAttempts=3` 指数退避，且对话模型持有的是另一个实例 |
| `ModelRetryDiagnostics` | 启动时打印"容器有哪些模板、模型实际持有哪个"，发现 `FixedBackOffPolicy` 或 `maxAttempts > 5` 直接 WARN 并点名该已知成因 |

### 2.3 本项目覆盖后的策略

`config/ModelRetryConfig` 直接定义同名 `RetryTemplate` Bean（prototype，原因见 2.2）。
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

### 2.4 429 依赖 `spring.ai.retry.on-http-codes`

Spring AI 的 `ResponseErrorHandler` 把 **4xx（含 429）一律归为 `NonTransientAiException`**。
按 2.3 的策略，429 会被"立即失败"，一次限流就等于一次失败。

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

### 2.5 Embedding 与对话模型：同一套策略、不同实例

`DashScopeEmbeddingModel` 与对话模型用的是**同一套策略**（`mkagent.model-retry.*`），
但各自持有独立实例：容器 Bean 是 prototype（见 2.2），对话模型则是自己构建的私有实例。
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

**如果将来要分开**：新增一个 `@Qualifier` 限定的 `RetryTemplate` Bean，
单独注入给 `EmbeddingModel` 即可（Spring AI 自动配置只兜底"缺失时才创建"），
但会引入第二套配置项与第二套日志口径，目前收益不足以抵消复杂度，故不做。

### 2.6 别把工具重试和模型重试混为一谈

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

### 2.7 重试耗尽之后：`return` 而不是 `throw`

`mkagent.tool-retry.on-exhausted` 决定耗尽后的行为，默认 `return`：

| 值 | 行为 | 任务终态 |
| --- | --- | --- |
| `return`（默认） | 返回一条脱敏失败文案作为工具结果 | 任务继续，由模型决定下一步 |
| `throw` | 向上抛异常 | 任务直接 `FAILED`（旧行为，仅用于回滚） |

真实运行里触发过这个问题：`web_scrape` 抓 GitHub 超时，三次尝试耗尽后抛异常，
异常从 `ToolCallingManager` 一路冒到 Agent Loop，整个任务被判 `FAILED`——
用户只想要一个页面正文，结果连"换个地址再试"的机会都没有。

`return` 模式下模型看到的是：

```
工具 web_scrape 多次尝试仍失败：Read timed out。请换用其他方式，或在无法完成时调用 terminate_task
```

文案有三个要素缺一不可：**哪个工具**失败、**简短原因**（单行、脱敏、截断到 120 字符）、
**下一步可以怎么做**。少了第三点，模型很容易原地重试同一个工具。

成本口径不受影响：失败的那次调用**仍然计入** `toolCallCount` 与 `maxToolCalls`，
所以"最多 12 次工具调用"的预算没有被这个改动放大；
`ERROR` 日志也只记一行（换行与堆栈全部折叠）。

### 2.8 站点级熔断：`mkagent.tool-retry.max-exhausted-per-target`

`return` 解决了"一次失败不要终止任务"，但没有解决**同一个站点被反复撞**。
真实数据里有一条 `MAX_STEPS_REACHED`、**28308 Token** 的任务：
模型看到"多次尝试仍失败"后换个路径、换个查询词，继续打同一个打不开的主机，
把 8 步预算全部烧在一个 URL 上。

因此按 **(工具名, 目标主机)** 统计耗尽次数，默认阈值 2：

| 次数 | 行为 |
| --- | --- |
| 第 1、2 次耗尽 | 正常返回失败文案（同 2.7），计数 +1 |
| 达到阈值后 | **不再执行底层工具**，直接返回熔断文案，`WARN` 记一行 |

模型看到的是：

```
该站点连续失败，请换用其他来源或在无法完成时调用 terminate_task
（工具 web_scrape，目标 github.com，已连续重试耗尽 2 次，本次未再执行）
```

三个设计取舍：

1. **按主机而不是按 URL**：`github.com/a` 与 `github.com/b` 算同一个目标。
   "站点打不开"是主机级现象，按路径区分会让熔断形同虚设。
2. **中间成功一次不清零**：目标不是"连续失败次数"，而是"别再撞这个站点"。
   偶发一次成功不代表站点已恢复。
3. **解析不出主机时退化为按工具名计数**：`web_search` 的参数只有 `query`，
   没有主机概念；搜索通道连续两次耗尽，继续搜只是继续失败。

熔断的调用**仍然计入** `toolCallCount`，预算口径不变。
设成 `0` 或负数可关闭该保护（线上临时回滚用）。

计数存在 `AgentRunContext`（每任务一份），由 `ToolCallAgent.act()`
在执行工具期间绑定到当前线程。为什么不放在工具回调的成员变量里：
回调是 Spring 单例，`MkManus` 也是单例，实例字段会让
"上一个任务抓 GitHub 失败"影响"下一个任务"。

> 为什么不用 `ToolCallingChatOptions.toolContext` 传这个状态：
> 它是 **Prompt 级** 的，一旦非空，框架会对所有工具回调调用
> `call(String, ToolContext)`，而该接口的默认实现是"上下文非空就抛
> `UnsupportedOperationException`（Tool context is not supported!）"。
> 实测：`call(input, null)` 与 `call(input, 空 Map)` 正常，非空 Map 直接抛。
> 只实现 `call(String)` 的回调会被打断，因此改用 act() 内的线程绑定。

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

### 3.3 测试 JVM 参数（为什么不放在命令行）

原因不是省内存，而是**避免把环境问题误读成测试失败**：可用物理内存偏紧时，
JVM 会因为原生内存（不是堆）分配失败直接崩掉，表现为

- `Native memory allocation (malloc) failed ... for Chunk::new`
- `The forked VM terminated without properly saying goodbye`
- 或者 Maven 进程自己消失，输出停在半路、连 `BUILD` 都没有

这类"中途猝死"看起来像测试挂了，其实一行断言都没失败过。所以要约束的是**两个** JVM：

| 位置 | 配置 | 作用 |
| --- | --- | --- |
| Maven 进程本身 | `.mvn/jvm.config`（`-Xmx512m -XX:+UseSerialGC -XX:TieredStopAtLevel=1`） | 默认堆按物理内存 1/4 算，会把机器吃干；压到 512m 即可构建本项目 |
| surefire fork | `pom.xml` 属性 `mkagent.test.jvm-args`（同上一组参数） | 测试 JVM 不预留 G1 分区空间，且只用 C1——崩溃点是 C2 编译器的原生内存池（`Chunk::new`），跑测试不需要 C2 |

两者都写进仓库，`./mvnw test` 直接可用，不需要每次手敲参数。

覆盖方式（**改属性，不要用 `-DargLine`**）：

```bash
./mvnw test -Dmkagent.test.jvm-args="-Xmx1g"
```

> 为什么不用 `-DargLine`：它会让 surefire 忽略 POM 里配置好的那一行，
> 属于"绕过配置"而不是"覆盖参数"，容易在换机器时再次踩坑。

顺带一个相关坑：`spring-boot-starter-test` 带来的 Mockito 默认用 inline mock maker，
需要在 JVM 启动后自我 attach 一个 agent。这一步在内存紧张时会失败
（`Could not initialize plugin: interface org.mockito.plugins.MockMaker`），
而 Spring Boot 的 `ResetMocksTestExecutionListener` 对**每个** `@SpringBootTest` 都会触发它——
也就是说任何集成测试都可能莫名其妙地红。项目里显式指定了

```text
src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker  ->  mock-maker-subclass
```

改用子类 mock，不再需要 agent attach，测试结果也就与内存压力无关了。
代价是不能 mock `final` 类/方法（当前测试没有这种需求）。

---

## 4. 模型分层：按用途选模型（OpenAI 兼容接口）

### 4.1 为什么要分层

一个 Agent 里不同调用的性价比要求完全不同：

| 用途 | 典型调用 | 模型诉求 | 配置项 |
| --- | --- | --- | --- |
| `agent` | 主推理链、工具决策 | 强，贵一点也值 | `mkagent.model.agent` |
| `auxiliary` | 评测评委、查询改写 | 便宜、够用、快 | `mkagent.model.auxiliary` |
| `vision` | 看图 | 多模态 | `mkagent.model.vision` |

三者共用一套 `base-url` / `api-key`，只按用途换模型名；
`auxiliary` / `vision` 的 `name` 留空时**回退到 agent 的模型名**。

### 4.2 开关：一个属性决定用哪套客户端

```yaml
mkagent:
  model:
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    api-key: ${MKAGENT_MODEL_API_KEY:}
    agent:     { name: qwen-plus }
    auxiliary: { name: qwen-turbo, temperature: 0.0 }
    vision:    { name: qwen-vl-plus }
```

- **不配** `base-url`：走 `DashScopeChatModelConfig`（DashScope 原生客户端），行为与之前完全一致；
- **配了** `base-url`：走 `OpenAiCompatibleChatModelConfig`（OpenAI 兼容客户端）。

两者由 `OpenAiCompatibleConditions` 保证互斥，容器里任何时刻只有一个主对话模型，
Bean 名统一为 `dashscopeChatModel`（历史名字，**实现由配置决定**，看启动日志里的
`purpose` / `model` / 实现类，不要靠 Bean 名判断用的是哪家）。

> 为此 `application.yml` 排除了 `DashScopeChatAutoConfiguration`：
> 它会按"类型 `DashScopeChatModel` 是否缺失"再决定要不要造一个模型，
> 与"谁来决定重试模板"纠缠不清。模型装配现在完全由项目代码负责，
> 第三方自动配置只保留 Embedding / Image 部分。

### 4.3 成本上的实际收益

- 评测评委从"用主模型打分"换成 `auxiliary` 小模型：单次 judge 成本通常降一个数量级；
- 一轮评测里 judge 调用次数约等于用例数，这部分省下来是线性的；
- `vision` 只在多模态场景计费，平时不参与主链路。

### 4.4 一条硬约束：每个模型持有私有 `RetryTemplate`

三个模型各自调用 `ModelRetryConfig.build(...)` 构建自己的模板，**绝不注入容器 Bean**。
原因见 2.2：`DashScopeImageModel` 会原地改写传进去的 `RetryTemplate`。
一旦共享，任何"借用即改写"的库都能改掉别处的策略，而启动日志仍然显示正确值。

对应测试（断言实例，而不是只看配置）：

| 测试 | 断言 |
| --- | --- |
| `OpenAiChatModelWiringTest` | 三个模型的模板是三个不同对象、都不是容器 Bean；容器模板被原地改写后三者策略不变 |
| `OpenAiChatModelFallbackTest` | name 回退到 agent，但实例与模板仍独立；一个模型名都没有时启动即失败 |
| `OpenAiCompatibleBadRequestTest` | 400 只发 1 次请求、秒级抛 `NonTransientAiException` |

---

## 5. 工具配置、输出上限与日志治理

### 5.1 搜索引擎：`search-api.engine`

`WebSearchTool` 构造请求时把 `engine` 传给 searchapi.io，默认 `baidu`。

| 场景 | 建议值 | 原因 |
| --- | --- | --- |
| 中文资讯、国内站点、通用查询 | `baidu`（默认） | 中文召回好，配额口径与既有行为一致 |
| 英文官方文档（Spring AI / OpenAI / MDN / GitHub） | `google` | 百度对英文技术文档的索引覆盖与排序明显偏弱，换 `google` 后同样一条查询的**有效结果更多**，模型少绕弯路 |

为什么这和成本有关：搜不到正确文档 → 模型反复改写关键词重搜 → 每次都是一次
`web_search` 调用 + 一轮模型调用。选错引擎的代价不是单价，而是**轮数**。

切换方式（不需要改代码）：

```yaml
search-api:
  engine: google
```

`api-key` 属于密钥，只放在 `application-local.yml`，不进版本库。

### 5.2 抓取超时：`mkagent.tools.web-scrape.timeout-ms`

默认 `10000`（10 秒）。GitHub、MDN 这类境外站点首字节返回慢，
10 秒容易稳定超时，进而触发工具重试耗尽（见 2.7）。

```yaml
mkagent:
  tools:
    web-scrape:
      timeout-ms: 30000
```

非正数会被忽略并回退到默认值，避免误配成 `0` 导致每次抓取必然失败。

### 5.3 日志治理：把 INFO 留给"每步都要看"的东西

真实运行里日志被两类内容淹没，两处都改了：

| 位置 | 旧行为 | 新行为 |
| --- | --- | --- |
| `当前提供给模型的工具` | 每个 step 都 INFO，8 步 × 3 工具 = 24 行 | 只在**第一步** INFO 打一次摘要，其余 DEBUG |
| `工具实际执行完成` | INFO 全量打印原始结果（网页正文上千字符） | INFO 截断到 **200 字符**（并标出原始长度），完整内容只在 DEBUG |

工具清单在整个任务期间不会变化，重复打印只是噪声；
而工具结果的长度与 token 量同阶，按 INFO 全量落盘会让日志体积失控。
需要排查"模型为什么没选某个工具"或"工具到底返回了什么"时，把
`com.example.mkagent.agent` 调到 DEBUG 即可。

### 5.4 工具结果必须先解码再分析

`@Tool` 方法的返回值经 `MethodToolCallback` 序列化后是 **JSON 字符串字面量**：
外层带双引号、内部换行转义成 `\n`。

```
原始返回值：  标题：A\n链接：B\n---\n标题：C
responseData："标题：A\n链接：B\n---\n标题：C"   ← 首尾引号 + 两个字面的 \ 和 n
```

所有对 `responseData` 做 `startsWith` / `split` / `contains` 的代码都会静默失效——
这就是"搜索结果条数永远是 1"的根因（`split("\n---\n")` 只能得到 1 段）。

统一走 `ToolResultTexts.decode(raw)`：是 JSON 字符串字面量就解码，否则原样返回，
对两种形态都安全。**新增任何读取 `responseData` 的代码，第一步都是 decode。**

### 5.5 抓取输出上限与"页面链接"区块：`mkagent.tools.web-scrape.max-chars`

默认 `12000` 字符（正文 + 链接区块合计）。

真实运行里出现过**引用幻觉**：`web_scrape` 只返回正文，模型手里没有任何 URL，
于是按页面标题和导航文字自己拼了一个链接。抓一次 GitHub 页面 ≈ 上万 token，
拼出来的链接却指向不存在的地址——用户点开是 404。

现在 `web_scrape` 在正文之后追加一个"页面链接"区块：

```
页面链接（均来自本页实际链接，可直接引用）：
1. Spring AI Reference：https://docs.spring.io/spring-ai/reference/
2. ...
```

| 约束 | 取值 | 原因 |
| --- | --- | --- |
| 条数上限 | 50 | 再多也只是把 token 花在导航栏上 |
| 协议 | 仅 `http` / `https` | 过滤 `javascript:` / `mailto:` / 锚点 |
| 去重 | 按绝对 URL | 同一目标多处出现只留一条 |
| 相对路径 | 转成绝对 URL | 模型拿到相对路径没法引用 |

**空间不足时链接优先**：正文超限时先截正文，链接区块整体保留。
正文尾部丢了不影响"引用是否正确"，链接丢了就直接导致幻觉。

配套的系统提示词规则（`MkManus` 第 5 条）：回答里的任何 URL 必须来自本次
`web_search` / `web_scrape` 的实际返回结果，不得根据标题或导航文字拼接；
工具结果里没有可用链接时，明确说"未获得可引用的链接"。

### 5.6 最终回答：`terminate_task` 的 `final_answer`

真实运行里任务 `SUCCEEDED`，用户却只拿到一句"任务结束：任务结束已确认"——
模型在同一轮既写了说明文字又调用了 `terminate_task`，
而旧逻辑只把**工具结果摘要**当最终回答，模型写的正文被整段丢掉。

`terminate_task` 现在多了一个 `final_answer` 参数（上限 4000 字符，
超出截断并标注），`ToolCallAgent` 按四级优先级取值：

| 优先级 | 来源 | 什么时候命中 |
| ---: | --- | --- |
| 1 | `terminate_task` 的 `final_answer` 参数 | 模型按提示词规范写入 |
| 2 | 同一轮 assistant 文本 | 模型把答案写在正文里、工具只用来收尾 |
| 3 | `ctx` 上已有的 `finalAnswer` | 之前的步骤已经确定过答案，不要被覆盖 |
| 4 | 工具执行摘要 | 前三级都空时的兜底（旧行为） |

SSE 的 `final_answer` 事件、`agent_run.final_answer` 字段、同步 `run()` 的返回值
都读同一个 `ctx.getFinalAnswer()`，而写入点只有 `ToolCallAgent.act()` 一处，
所以三者天然一致——不需要三处各写一份再靠测试对齐。

成本影响：`final_answer` 让模型把"已经写过的说明"复述一次，
多花一次输出 token；但换来的是**用户真的拿到答案**，
省掉的是"再问一遍"的整轮成本。

### 5.7 引用真实性检查：`CitationFaithfulnessChecker`

评测侧的确定性检查（不依赖 LLM judge）：从最终回答里抽出所有 URL，
逐个断言"在本任务的工具结果或用户输入中出现过"，命中不了直接判失败。

失败文案会列出具体是哪些 URL 凭空出现：

```
最终回答引用了工具结果与用户输入中都不存在的 URL：https://example.com/fake
```

配套用例 `CITATION-01`（要求给出官方文档链接）。这条检查的价值在于：
它把"引用幻觉"从"看起来像真的"变成"可复现的失败"，
而不是等用户点开 404 才发现。

---

## 6. 任务轨迹与预算收尾

### 6.1 工具轨迹落库：`agent_run.tool_trace`

`agent_run` 新增一列 `tool_trace TEXT`，内容是**按执行顺序**的工具名序列，
逗号分隔：

```
web_search,web_search,web_scrape,terminate_task
```

用途：回答"这个任务到底做了什么"，而不用把整段对话捞出来看。
成本排查时能一眼看出"8 步里有 6 步都在 web_scrape 同一个站点"。

四条约束：

| 约束 | 说明 |
| --- | --- |
| 只存工具名 | **不含任何参数或结果原文**——搜索词、文件路径、密钥不会进库 |
| 按执行顺序 | 复用 `AgentRunContext.toolTrace`，`ToolCallAgent.act()` 每执行完一个工具追加一次 |
| 失败也记 | 失败的工具调用同样产生 `ToolResponseMessage`，模型看到了失败结果，它就是轨迹的一部分；一次调用只记一条，不按重试次数重复 |
| 进度与终态都写 | 最后一轮 step 之后不会再触发 `recordProgress`，只在终态写会丢掉"最后一个工具" |

幂等升级（存量表必须靠 ALTER，`CREATE TABLE IF NOT EXISTS` 不会补列）：

```sql
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS tool_trace TEXT;
```

`SchemaScriptIdempotencyTest` 连续执行两次 `schema.sql`，
并模拟"旧版表 + 新脚本"的升级路径，保证应用启动（`sql.init.mode=always`）不会因为列已存在而起不来。

`AgentRunVO` 同步返回该字段，前端可直接画出任务轨迹。

### 6.2 剩余步数提示：`mkagent.agent.budget-warning-steps`

默认 `2`。剩余步数 ≤ 该值时，在**发送给模型的副本**里追加一条系统提示：

```
剩余步数不足，请基于已有信息给出最终回答并调用 terminate_task，不要再搜索新资料
```

剩余步数 = `maxSteps - 当前步 + 1`（含当前这一步）。以 `maxSteps=8` 为例，
第 7 步（剩 2）和第 8 步（剩 1）会提示，正好覆盖最后两步。

**只追加到副本，不修改 `ctx.messages`**，理由有三：

1. 这是"本轮策略"，不是任务事实；写进历史会污染后续所有轮次；
2. 评测会读取 ctx 的消息做断言，历史必须保持干净；
3. 模型看到的历史与实际发生过的对话不一致时，问题极难排查。

设为 `0` 或负数关闭。

### 6.3 预算耗尽后的强制收尾：`mkagent.agent.force-final-answer`

默认 `true`。预算耗尽（`MAX_STEPS_REACHED` / 工具次数上限）且**尚无最终回答**时，
额外执行一次**不带工具**的模型调用，让模型基于已有上下文写一个尽力而为的回答：

```
(因执行预算耗尽，以下为基于已获取信息的尽力回答)
根据已抓到的页面，Spring AI 的 ChatClient 支持……
```

为什么值得多花这一次调用：预算耗尽时模型手里通常已经有搜索结果和网页正文，
只是没来得及写结论。旧行为返回的是"安全停止：达到最大步骤数……"这句系统摘要，
用户等于什么答案都没拿到——上下文里的信息全白攒了。

四条硬约束：

| 约束 | 说明 |
| --- | --- |
| 状态不变 | 仍是 `MAX_STEPS_REACHED`，这是成本事实，不能伪装成 `SUCCEEDED` |
| 不带工具 | Prompt 不传 `toolCallbacks`，且模型默认选项里的工具在装配时被清空（见 6.5） |
| Token 计入 Usage | 这次调用确实花钱，必须出现在 `agent_run` 的 Token 统计里 |
| 失败必须降级 | 拿不到文本时写确定性兜底，绝不留空 `finalAnswer`（见 6.4） |

SSE 侧配套改动：`final_answer` 事件不再只对 `SUCCEEDED` 发送，
`MAX_STEPS_REACHED` 且有答案时也发——否则强制收尾的答案永远到不了前端。

关闭（`false`）则完全保持旧行为：**不新增调用，也不写兜底**。
两件事是同一个承诺（"预算耗尽后不再产出任何东西"），拆开做会让开关没法一句话说清；
评测侧（`mkagent.eval.force-final-answer`，默认 `false`）也据此保持原始策略可比。

### 6.4 收尾拿不到文本时的确定性兜底

真实冒烟（runId `755b5dea-80e2-4962-aa30-b83affdf67c1`）暴露：收尾调用返回了
27 个 completion token 却**没有文本**，于是 `final_answer` 为空 ——
SSE 只发了 `status` → `done`，`agent_run.final_answer` 是 NULL，用户什么都没拿到。

现在收尾失败（空串 / null 文本 / 只有 tool_call / 空 generations / 无 output / 抛异常）
统一写一条确定性回答：

```
因执行预算耗尽，本次任务已停止，未能生成完整回答。
本次已执行 8 步、调用工具 8 次。
请缩小任务范围后重试。
```

- 用"执行预算"而不是"步数预算"：`MAX_STEPS_REACHED` 也可能由工具调用次数上限触发；
- 只含步数与工具调用次数，**不拼**工具原文、URL、参数、异常或模型内部细节；
- 只做一次，不重试、不做多轮总结。但底层 `RetryTemplate`
  （`mkagent.model-retry.*`，只对 5xx/429/超时生效）仍可能重发 HTTP 请求，
  所以"一次业务调用"**不等于**"一个 HTTP 请求"，文档不要写成后者；
- 取消/超时不走兜底：终态一旦不是 `MAX_STEPS_REACHED`，或线程已被中断，一律不写；
- 收尾调用**期间**被中断：在当前已核对的业务路径中，SSE `onTimeout`
  是该阶段的中断来源（取消接口走不到这里：`AgentTaskCanceller` 看到
  `state.isTerminal()` 就直接返回 409）。
  不宣称已穷尽所有外部线程中断来源。
  异常按中断语义原样抛给 `BaseAgent`，但 `MAX_STEPS_REACHED` 在收尾之前就已经是终态，
  `failOrCancel` 里的 `transitionTo(CANCELLED)` 会被终态保护拒绝。
  所以**最终状态仍是 `MAX_STEPS_REACHED`**（`ctx`、SSE、`agent_run.state` 三处一致），
  `final_answer` 保持 NULL，SSE 在 `status` 之后以一条脱敏 `error` 结束、**没有** `done`；
  `agent_run.error_message` 会写上脱敏摘要（异常类型 + 短消息，不含堆栈）。
  也就是说：预算耗尽的任务**可能同时**有 `state = MAX_STEPS_REACHED` 和
  `error_message != NULL`——按 `error_message IS NULL` 过滤"干净预算耗尽"会漏掉这一类，
  但不要因此改终态：终态是成本事实，错误摘要只解释"收尾为什么没产出"；
  不要写成"变成 CANCELLED"——那只是异常分支的意图，不是终态；
- 收尾响应里若同时有文本和 tool_call：采用文本，但**绝不执行**任何工具。

### 6.5 为什么"Prompt 里不带工具"不等于"请求里没有工具"

`DashScopeChatModel#buildRequestPrompt` 先做一次 `ModelOptionsUtils.merge`
（runtime 里为 null 的字段回落到 `defaultOptions`），然后执行：

```java
merged.setToolCallbacks(ToolCallingChatOptions.mergeToolCallbacks(
        runtime.getToolCallbacks(), defaultOptions.getToolCallbacks()));
merged.setToolNames(ToolCallingChatOptions.mergeToolNames(
        runtime.getToolNames(), defaultOptions.getToolNames()));
```

而这两个方法的语义是"runtime 为空就返回 defaults"。所以只要模型默认选项
（`spring.ai.dashscope.chat.options`）里带工具，**任何一次** Prompt 都会把工具
合并回请求——包括收尾那次；在 Prompt 侧把 `toolCallbacks` 置空是拦不住的
（空列表恰好触发回退到 defaults）。

`DashScopeChatOptions` 里有四个字段会进请求，**必须一起清**：

| 字段 | 怎么进请求 |
| --- | --- |
| `toolCallbacks` | 由 `ToolCallingManager.resolveToolDefinitions` 解析成 `tools` |
| `toolNames` | 同上（按名字解析） |
| `tools` | 已解析好的 `FunctionTool` 列表；`createRequest` 只在"解析结果非空"时覆盖它，否则原样进请求 |
| `toolChoice` | 直接进 `ChatCompletionRequestParameter.tool_choice` |

只清前两个是不够的：只配 `tools` + `toolChoice`（不配 `toolNames`）时，收尾请求
依然会带工具和 `tool_choice`——`ForcedFinalAnswerRequestShapeTest` 里有一条用例
专门刻画这个形状。

`toolContext` **不清**：它不进请求、也不对外声明工具，而且两条路径都设了
`internalToolExecutionEnabled(false)`，框架侧根本不会执行工具。

因此 `DashScopeChatModelConfig#stripDefaultTools` 在装配时清空默认工具
（四个字段一起清；`tools` 置 `null` 而不是空列表——请求 DTO 标了
`@JsonInclude(NON_NULL)`，`null` 才是"请求里没有这个字段"；
真的清掉东西时告警，避免"配置了却静默失效"）。

`ForcedFinalAnswerRequestShapeTest` 用**真实出站 JSON** 锁住四件事：
常规轮有 `parameters.tools`、收尾轮没有、默认选项带 `toolCallbacks` 时会被合并回来、
默认选项带 `tools`/`toolChoice` 时同样会被合并回来（所以必须在配置层清）。

影响面（改动前后都核对过）：

- `chatProperties.getOptions()` 返回的是属性对象**本身**（`getOptions` 字节码就是
  `getfield` + `areturn`，没有拷贝），所以清理是**原地**改这个配置 Bean。
  本项目读它的只有本配置类和 `ConfiguredModelNameResolver`（只读 `model`）；
- 模型名、`temperature`、`maxTokens` 等字段不动，`stripDefaultTools` 只碰四个工具字段；
- 所有需要工具的调用方（`MkManus`、`MusicAgent`、`EvalAgent`）都在运行时通过
  `ToolCallingChatOptions.toolCallbacks(...)` 显式传入白名单，常规工具轮不受影响；
- OpenAI 兼容路径（`mkagent.model.base-url` 生效时）不绑定
  `spring.ai.openai.chat.options`，默认选项里从来没有工具，无需同样的清理。

---

## 7. 还在计划中的降本项

以下尚未实现，先留位置，避免把"计划"写成"已完成"：

- Token 降本：工具结果裁剪（`ToolResultTrimmer`，默认关闭）——
  网页抓取正文截断（`max-chars`）已经实现，见 5.5；
- 搜索缓存 + 评测 RECORD/REPLAY（同一用例重复跑时不重复调用搜索 API）。

---

## 8. 自查清单

- [ ] 新增调用模型的测试，是否标了 `@Tag("live")` 并加入 `LiveTestIsolationUnitTest` 清单？
- [ ] 新增模型调用，是否走容器里的 `RetryTemplate` Bean 或 `ModelRetryConfig.build(...)`（而不是自己 `new` 一个默认策略）？
- [ ] 新增模型客户端时，是否确认它**不会原地改写**传入的 `RetryTemplate`（`DashScopeImageModel` 就是反例）？
- [ ] 新增模型 Bean，是否给了它**独立的** `RetryTemplate` 实例（而不是共享某个可变对象）？
- [ ] 新增"短调用/低价值"模型调用，是否优先用 `auxiliary` 而不是主模型（见第 4 节）？
- [ ] 新增 4xx 语义（如 408/425），是否需要补进 `spring.ai.retry.on-http-codes`？
- [ ] 新增批量模型调用，是否会被"3 次 × 2s"放大成不可接受的启动/请求耗时？
- [ ] 新增工具失败文案，是否落在"可重试"与"不可重试"两个前缀之一？
- [ ] 新增读取 `ToolResponse.responseData` 的代码，是否先经过 `ToolResultTexts.decode`（见 5.4）？
- [ ] 新增对 `responseData` 的 `startsWith` / `split` / `contains`，是否在解码后的字符串上做？
- [ ] 新增工具，是否确认重试耗尽后返回失败文案（而不是抛异常终止任务）？失败调用是否仍计入 `toolCallCount`？
- [ ] 新增日志，是否按"每步都要看 → INFO / 仅排查时看 → DEBUG"分级？工具原始结果是否截断？
- [ ] 需要查英文官方文档的任务，是否把 `search-api.engine` 调成了 `google`（见 5.1）？
- [ ] 新增工具失败文案，是否区分了"这次试了但失败"（`多次尝试仍失败`）与"根本没再执行"（`该站点连续失败`，见 2.8）？
- [ ] 新增可能耗尽重试的工具，是否确认它走 `RetryableToolCallback`（否则站点级熔断对它不生效）？
- [ ] 改动 `ToolCallAgent.act()` 的工具执行段，是否保留 `bind` / `finally` 成对出现的站点计数器绑定？
- [ ] 新增工具返回值字段，是否仍在 `max-chars` 上限内（正文 + 链接区块合计，见 5.5）？
- [ ] 新增"给用户的最终回答"路径，是否写入 `ctx.setFinalAnswer(...)` 而不是另起一个返回通道（见 5.6）？
- [ ] 新增提示词规则要求模型输出 URL 时，是否同时确认 `CitationFaithfulnessChecker` 能覆盖（见 5.7）？
- [ ] 新增落库字段，是否确认 `schema.sql` 里既有 CREATE 也有幂等 `ALTER ... ADD COLUMN IF NOT EXISTS`（见 6.1）？
- [ ] 新增落库内容，是否只存工具名/枚举/脱敏摘要，不含参数、结果原文或密钥（见 6.1）？
- [ ] 新增"发送给模型的临时上下文"（提示、约束、few-shot），是否只加在副本上，没有污染 `ctx.messages`（见 6.2）？
- [ ] 新增提前终止路径，是否考虑"预算耗尽但已有信息可用"时给用户一个尽力回答（见 6.3）？
- [ ] 新增收尾/补答逻辑，是否保持真实终态（如 `MAX_STEPS_REACHED`）而不是改成 `SUCCEEDED`？
