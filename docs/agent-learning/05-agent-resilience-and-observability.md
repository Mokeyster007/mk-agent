# 05 - Agent 弹性与可观测性：并发控制、限流、Usage、工具重试与健康检查

> 本文档与当前代码库完全一致，面向已有 Java / Spring Boot 基础、正在学习 Agent 生产化改造的开发者。
> 所有类名、方法名、配置项均来自本项目真实实现，无伪代码。

---

## 1. 本次功能目标

### 1.1 为什么 Agent 不能无限并发

| 风险 | 说明 |
| --- | --- |
| 成本放大 | 每个 Agent 任务都要多轮调用付费大模型，并发无上限时成本线性放大 |
| 下游限流 | 模型服务商（如 DashScope）本身有 QPS / 并发配额，打爆后所有请求一起失败 |
| 线程耗尽 | Agent 任务大量时间阻塞在模型 HTTP 调用上，线程池被长任务占满，普通接口跟着遭殃 |
| 无法降级 | 没有"满了就拒绝"的闸门，只能眼睁睁看着队列越堆越长、延迟越来越高 |

### 1.2 为什么要限流

并发闸门管的是"全局总量"，限流管的是"单个用户的份额"：

- 一个脚本用户一秒钟发 100 个 Agent 请求，就能把 8 个并发许可全部占住，其他用户全部被拒；
- 限流把单用户频率压到合理范围（本项目默认每分钟 10 次），保证资源公平；
- 限流拒绝要**快**（在 Controller 层、创建 SSE 连接之前），不能等任务排队。

### 1.3 为什么要记录成本（Usage）

- Token 是 Agent 的直接成本，不记录就无法对账、无法发现异常消耗；
- 有了 `runId → model → prompt/completion/total tokens → 耗时` 的落库记录，
  才能回答"这次任务花了多少钱、慢在哪一步"；
- 也是后续按模型计费、按用户配额的必要数据基础。

### 1.4 本次五大目标

1. **全局并发控制**：Semaphore 上限 8，满了返回 `当前智能体任务较多，请稍后重试。`，任何终态都释放许可；
2. **用户请求限流**：每分钟 10 次，原子计数，友好提示 + 建议等待秒数，不影响普通接口；
3. **模型 Usage**：每轮模型调用采集 tokens 与耗时，随终态落库 `agent_run`；
4. **工具重试**：只对只读幂等工具（web_search / web_scrape）指数退避重试，高风险工具绝不重试；
5. **健康检查**：Actuator health 检查 DB 与模型配置（轻量级，不发真实模型请求），只暴露 health。

---

## 2. 实现前项目状态：AgentExecutor 与线程池能控制什么、不能控制什么

改造前项目已有 `agentExecutor` 线程池（见 `AgentExecutorConfig`），但它的能力有明确边界：

| 能力 | 线程池 | 说明 |
| --- | --- | --- |
| 限制同时执行的线程数 | ✅ 能 | 核心/最大线程数决定 |
| 限制"同时存活的 Agent 任务数" | ❌ 不能 | 任务阻塞在模型 HTTP 调用上也占着一个线程，且线程数 ≠ 业务上允许的任务数 |
| 拒绝超量请求并给出业务提示 | ❌ 不能 | 线程池满了只会走拒绝策略（抛异常或丢弃），不是业务语义 |
| 按用户限流 | ❌ 不能 | 线程池不感知用户身份 |
| 统计 Token 成本 | ❌ 不能 | 与线程调度完全无关 |

改造前还有这些具体不足：

| 不足 | 后果 |
| --- | --- |
| 无并发闸门 | 任意多任务同时跑，成本与下游限流风险不可控 |
| 无限流 | 单用户脚本可独占全部资源 |
| `ChatResponse` 的 usage 被直接丢弃 | 每轮模型调用的 Token 消耗无任何记录 |
| 工具失败不重试 | 网络搜索这类临时性抖动直接变成任务失败 |
| 无健康检查 | 部署后无法被探针感知，DB / 模型配置错误发现太晚 |

---

## 3. 核心概念

### 3.1 Semaphore：全局并发闸门

`Semaphore` 维护 N 个许可：`tryAcquire()` 拿不到立即返回 `false`（不阻塞），
`release()` 归还一个。**关键特性：许可可以在 A 线程获取、B 线程释放**——
本项目正是"Web 线程获取、`agent-*` 后台线程释放"。

真实代码见 [AgentConcurrencyGuard](file:///D:/mk-agent/src/main/java/com/example/mkagent/resilience/AgentConcurrencyGuard.java)：

```java
this.semaphore = new Semaphore(maxConcurrency, true);   // 公平模式，先到先得

public boolean tryAcquire() {
    boolean acquired = semaphore.tryAcquire();          // 不阻塞等待
    if (!acquired) {
        log.warn("Agent 并发已满，拒绝新任务：maxConcurrency={}, available={}",
                maxConcurrency, semaphore.availablePermits());
    }
    return acquired;
}
```

为什么用 `tryAcquire` 而不是 `acquire`：拿不到就立刻拒绝（429），
绝不让请求在队列里堆积——堆积只会把延迟拖长，最后还是失败。

**许可不泄漏的纪律**：只有成功获取的一方才能释放，且只释放一次。
`BaseAgent` 用"获取后紧跟 try/finally"的结构保证这一点（见第 6 节）。

### 3.2 固定窗口限流与原子性

本项目当前没有 Redis 依赖，限流用**进程内固定窗口**实现，
接口抽象保留 Redis 扩展点（见 3.3）。

固定窗口算法：把时间切成 60 秒的窗口，每个限流键在当前窗口内最多放行 N 次。
核心难点是**并发计数的原子性**——"读计数 → 判断 → 加一"三步若被打断就会超发。

真实代码见 [InMemoryAgentRateLimiter](file:///D:/mk-agent/src/main/java/com/example/mkagent/resilience/InMemoryAgentRateLimiter.java)，
用 `AtomicReference<long[]>` + CAS 循环把三步变成一个原子操作：

```java
while (true) {
    long[] current = window.state.get();           // [窗口编号, 计数]
    long[] next = (current[0] != windowIndex)
            ? new long[]{windowIndex, 1}           // 新窗口：重置为 1
            : new long[]{windowIndex, current[1] + 1};
    if (window.state.compareAndSet(current, next)) {
        if (next[1] <= maxRequests) return RateLimitResult.allow(maxRequests);
        rollback(window, windowIndex);             // 超限：归还名额再拒绝
        return RateLimitResult.reject(maxRequests, waitSeconds);
    }
    // CAS 失败说明有并发竞争，重读重试
}
```

固定窗口的已知局限：窗口切换瞬间理论上可能出现 2 倍流量（滑动窗口可解决），
对"防脚本刷接口"的场景完全够用。

#### 3.2.1 内存回收（惰性清理）与它的并发语义

`windows` 是 `ConcurrentHashMap<String, Window>`，键是限流键（约等于用户数）。
如果只增不删，进程跑久了就会随着用户量线性膨胀，所以必须清理。

清理策略是**惰性 + 双触发**：

| 触发条件 | 说明 |
| --- | --- |
| 计数触发 | 每累计 `cleanupEveryAcquires`（默认 256）次 `tryAcquire` 扫一次 |
| 时间触发 | 距上次清理超过一个窗口就扫一次 |
| 执行者 | 用 `lastCleanupMillis` 的 CAS 抢锁，同一时刻只有一个线程真正扫描 |

清理只移除"窗口编号早于当前窗口"的键，并用 `computeIfPresent` 在回调里**重新读取状态**
再决定是否返回 `null`（返回 `null` 即删除该 entry）。

真正的难点是**清理与计数的交错**。假设线程 A 刚在旧窗口对象上完成 CAS 自增，
清理线程恰好把这个对象从 map 里删掉，那么 A 的自增就写在了"孤儿对象"上。
代码在 CAS 成功后追加了一次成员校验：

```java
if (windows.get(rateLimitKey) != window) {          // 窗口已被清理
    Window liveWindow = windows.putIfAbsent(rateLimitKey, window);
    if (liveWindow != null) {                       // 已有别的线程建了新窗口
        window = liveWindow;                        // 切到新窗口，重新计数
        continue;
    }
    // 放回成功：自增仍然有效
}
```

三种情况分别的结果：

| 情况 | 计数最终落在哪 | 结果 |
| --- | --- | --- |
| 窗口没被清理 | 原窗口对象 | 正常放行 / 拒绝 |
| 被清理，但 `putIfAbsent` 放回成功 | 仍是原窗口对象（自增保留） | 正常放行 / 拒绝 |
| 被清理，且已有新窗口 | 新窗口对象（重新从 1 开始计数） | 多一次 CAS 重试，请求不丢 |

**结论：不会多放行。** 依据是——只有自增确实落在"当前登记在 map 中的窗口"上时，
代码才会返回 `allow`；凡是自增被丢弃的路径都不会返回任何判定结果，而是 `continue` 重试。
**也不会少放行**：丢弃自增后请求会在新窗口重新计数一次，不会凭空消失。
代价最多是多一轮 CAS。

对应的并发测试见 `InMemoryAgentRateLimiterCleanupUnitTest`：
200 轮、每轮 20 个键同时抢，断言每个键的放行数 `<= maxRequests`，
并反向断言至少有一个键用满配额（防止测试变成"永远不放行"的空跑）。

### 3.3 Redis 限流（扩展点）

若项目引入 Redis，标准做法是 `INCR key` + `EXPIRE key 60`（两条命令存在竞态窗口），
更严谨的是单条 Lua 脚本保证原子：

```lua
local count = redis.call('INCR', KEYS[1])
if count == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
if count > tonumber(ARGV[2]) then
    return -1                       -- 被限流
end
return tonumber(redis.call('TTL', KEYS[1]))
```

本项目的切换方式：`AgentRequestRateLimiter` 是接口，
新增一个 `RedisAgentRateLimiter implements AgentRequestRateLimiter`，
用 `@Primary` 或条件装配替换 Bean 即可，`AiController` 一行不用改。
限流键解析集中在 `AiController.checkAgentRateLimit` 一处
（`user:{userId}` 优先，无身份时临时回退 `ip:{remoteAddr}`），
后续接入真实用户体系也只改这一处。

### 3.4 Token Usage：Spring AI 1.0.0 的真实接口

Spring AI 1.0.0 中 `ChatResponse.getMetadata()` 返回 `ChatResponseMetadata`，
其中 `getUsage()` 返回 `org.springframework.ai.chat.metadata.Usage`：

```java
public interface Usage {
    Integer getPromptTokens();      // 注意是 Integer，可能为 null
    Integer getCompletionTokens();  // 注意是 Integer，可能为 null
    Object getNativeUsage();        // 服务商原生 usage 对象
}
```

两个坑：

1. **返回值是 `Integer` 不是 `long`**：部分模型/场景不返回 usage，必须空安全；
2. **流式调用默认拿不到 usage**：DashScope 等服务商的流式响应只在最后一个分片携带
   usage，且需要显式开启 `stream_options.include_usage`。
   本项目 Agent 路径用非流式 `chatModel.call(prompt)`，可稳定获得 usage；
   流式聊天路径**不统计 usage**，避免为拿 usage 改变 SSE 订阅逻辑、破坏现有输出。

### 3.5 指数退避与幂等

**指数退避**：第 n 次失败后等待 `initialBackoff * multiplier^(n-1)`
（本项目 200ms → 400ms），比固定间隔更友好：临时抖动快速恢复，
持续故障不再密集打击下游。

**幂等**：同一操作执行一次与执行多次效果相同。重试只允许用于幂等工具：

| 工具 | 是否幂等 | 是否允许重试 |
| --- | --- | --- |
| `web_search` / `web_scrape` | 是（只读查询） | ✅ |
| `write_file` / `download_resource` | 否（有副作用） | ❌ |
| `generate_pdf` | 否（生成文件） | ❌ |
| `terminate_task` | 否（改变任务状态） | ❌ |

对非幂等工具重试的后果：重复写文件、重复下载、重复扣费。

### 3.6 Actuator HealthIndicator

Spring Boot Actuator 的 `/actuator/health` 聚合所有 `HealthIndicator`：
任一组件 DOWN 则整体 DOWN。数据源健康检查由
`spring-boot-starter-actuator` + 数据源自动提供（`components.db`）。

自定义指示器只需实现接口：

```java
@Component("aiModel")                    // 组件名 → components.aiModel
public class AiModelHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // 只检查配置可用性，绝不发起真实模型请求
        return Health.up().withDetail("apiKey", maskKey(apiKey)).build();
    }
}
```

**为什么健康检查不能调真实模型**：一次真实调用产生 Token 费用、依赖外网、
拖慢探针（探针超时会被 K8s 误杀实例）。只检查"配置是否就绪"即可。

---

## 4. 请求全链路流程图

```mermaid
flowchart TD
    A[客户端 GET /api/ai/manus/chat] --> B{用户身份检查}
    B -- 缺失 --> B1[401 缺少用户身份]
    B -- 通过 --> C{限流检查<br/>user:userId 或 ip:addr}
    C -- 超限 --> C1[429 请求过于频繁<br/>请在约 N 秒后重试]
    C -- 放行 --> D{并发闸门 tryAcquire}
    D -- 无许可 --> D1[429 当前智能体任务较多<br/>请稍后重试]
    D -- 获取成功 --> E[创建 SseEmitter<br/>注册任务 → agent-* 线程]
    E --> F[Agent Loop]
    F --> G{模型调用}
    G --> H[recordUsage 采集<br/>model / tokens / 耗时]
    H --> I{需要工具?}
    I -- 是 --> J{工具在白名单?}
    J -- 是 web_search 等 --> K[RetryableToolCallback<br/>失败指数退避重试 ≤3 次]
    J -- 否 高风险工具 --> L[只执行一次 不重试]
    K --> F
    L --> F
    I -- 否 最终回答 --> M[终态 SUCCEEDED]
    G -- 异常 --> N[终态 FAILED]
    F -- 取消/超时 --> O[终态 CANCELLED/TIMED_OUT]
    M --> P[recordFinish 落库<br/>含 usage 累计]
    N --> P
    O --> P
    P --> Q[finally 释放并发许可<br/>Semaphore 支持跨线程释放]
```

文本版：

```
请求 → 用户身份 → 限流（429 提示+等待秒数）→ 并发闸门（429 业务文案）
→ SSE + agent-* 线程执行 → 每轮模型调用采集 Usage
→ 白名单工具失败自动重试 / 高风险工具只执行一次
→ 任意终态 → recordFinish 落库（tokens/耗时）→ finally 释放许可
```

---

## 5. 文件变更表格

### 5.1 新增文件

| 文件 | 职责 |
| --- | --- |
| `src/main/java/com/example/mkagent/resilience/AgentConcurrencyGuard.java` | Semaphore 全局并发闸门（公平模式，默认 8） |
| `src/main/java/com/example/mkagent/resilience/AgentRequestRateLimiter.java` | 限流接口（Redis 扩展点） |
| `src/main/java/com/example/mkagent/resilience/InMemoryAgentRateLimiter.java` | 进程内固定窗口限流（CAS 原子） |
| `src/main/java/com/example/mkagent/resilience/RateLimitResult.java` | 限流结果（allowed / limit / waitSeconds） |
| `src/main/java/com/example/mkagent/resilience/RetryableToolCallback.java` | 工具重试装饰器（指数退避 + 中断感知） |
| `src/main/java/com/example/mkagent/resilience/ToolRetryWrapper.java` | 按白名单包装工具（非白名单原样返回） |
| `src/main/java/com/example/mkagent/health/AiModelHealthIndicator.java` | 模型配置轻量级健康检查（不发真实请求） |
| `src/test/java/com/example/mkagent/resilience/InMemoryAgentRateLimiterUnitTest.java` | 限流单元测试（含 50 线程并发原子性验证） |
| `src/test/java/com/example/mkagent/resilience/RetryableToolCallbackUnitTest.java` | 工具重试单元测试（含真实 ToolCallingManager） |
| `src/test/java/com/example/mkagent/resilience/AgentConcurrencyLimitIntegrationTest.java` | 并发上限 + 许可释放集成测试 |
| `src/test/java/com/example/mkagent/resilience/AgentRateLimitIntegrationTest.java` | 限流集成测试（真实 HTTP） |
| `src/test/java/com/example/mkagent/resilience/ToolRetryIntegrationTest.java` | 工具重试集成测试（真实 Agent 链路） |
| `src/test/java/com/example/mkagent/health/AgentHealthIntegrationTest.java` | 健康端点集成测试 |

### 5.2 修改文件

| 文件 | 变更 |
| --- | --- |
| `pom.xml` | 新增 `spring-boot-starter-actuator`；新增 `project.build.sourceEncoding=UTF-8`（修复中文常量被 GBK 编译成乱码） |
| `agent/BaseAgent.java` | 注入并发闸门；`run()` 最外层 finally 释放；`runStream()` 异步 finally + 同步段 catch 双保险释放 |
| `agent/MkManus.java` | 构造器注入 `AgentConcurrencyGuard` |
| `agent/ToolCallAgent.java` | 模型调用计时 + `recordUsage` 采集 tokens（Integer 空安全） |
| `model/AgentRunContext.java` | 新增 usage 累计字段 + `addModelUsage` |
| `entity/AgentRunEntity.java` | 新增 model / prompt_tokens / completion_tokens / total_tokens |
| `service/AgentRunRecorder.java` | `recordFinish` 条件写入 usage（totalTokens=0 保持 NULL） |
| `model/vo/AgentRunVO.java` | 新增 usage 字段与映射 |
| `resources/db/schema.sql` | 新增 4 列 + `ALTER TABLE ADD COLUMN IF NOT EXISTS` 存量升级 |
| `controller/AiController.java` | `/ai/manus/chat` 接入限流（仅 Agent 接口） |
| `config/AgentToolProvider.java` | 工具经 `ToolRetryWrapper` 包装后输出 |
| `exception/GlobalExceptionHandler.java` | 新增 `NoResourceFoundException` 处理（未暴露端点返回 404 而非 500） |
| `resources/application.yml` | Actuator 只暴露 health；`mkagent.agent/rate-limit/tool-retry` 三组配置 |
| `config/AgentToolWhitelistTest.java` | 构造器适配新增的 `ToolRetryWrapper` 参数 |
| `support/FakeChatModel.java` | 响应携带 fake-model + 固定 usage（10/5）元数据 |

---

## 6. 核心代码讲解

### 6.1 并发许可的获取与释放（BaseAgent）

同步路径：获取后紧跟 try/finally，finally 覆盖成功/失败/取消/超时所有分支：

```java
public String run(String userPrompt) {
    // ...参数校验
    acquireConcurrencyPermit();          // 拿不到直接抛 BusinessException(429)
    try {
        // 原有全部逻辑（内部还有自己的 try/catch/finally）
    } finally {
        releaseConcurrencyPermit();      // 任何终态后必然执行
    }
}
```

流式路径稍复杂——**获取在 Web 线程，释放在后台线程**：

```java
public SseEmitter runStream(String userPrompt) {
    acquireConcurrencyPermit();
    try {
        // 创建 emitter、注册任务……
        CompletableFuture.runAsync(() -> {
            try {
                // Agent Loop
            } finally {
                recordRunFinish(...);
                releaseConcurrencyPermit();   // 后台线程释放（Semaphore 支持）
            }
        }, agentExecutor);
        return emitter;
    } catch (RuntimeException e) {
        releaseConcurrencyPermit();           // 提交前失败（如线程池拒绝）
        throw e;
    }
}
```

为什么同步段还需要 catch 释放：如果异步任务**提交成功**，释放由后台线程负责；
如果在提交前抛异常（如线程池拒绝），后台 finally 不会执行，必须由同步段释放，
否则许可永久泄漏。

### 6.2 限流接入点（AiController）

```java
private void checkAgentRateLimit(HttpServletRequest request) {
    String userId = UserContextHolder.get();
    // 限流键解析：后续切换真实用户体系的唯一扩展点
    String rateLimitKey = (userId != null && !userId.isBlank())
            ? "user:" + userId : "ip:" + request.getRemoteAddr();

    RateLimitResult result = agentRequestRateLimiter.tryAcquire(rateLimitKey);
    if (!result.allowed()) {
        throw new BusinessException(429,
                "请求过于频繁，请在约 " + result.waitSeconds()
                        + " 秒后重试（限制：每分钟最多 "
                        + result.limit() + " 次 Agent 请求）。");
    }
}
```

注意顺序：`requireUserId()` → `checkAgentRateLimit()` → `mkManus.runStream()`，
限流发生在创建 SSE 连接与获取并发许可之前，被限流的请求成本几乎为零。
普通聊天接口（`/ai/chat_app/**`）与查询接口（`/ai/runs/**`）完全不经过它。

### 6.3 Usage 采集（ToolCallAgent）

```java
long modelCallStart = System.currentTimeMillis();
ChatResponse response = chatModel.call(prompt);
long modelCallCost = System.currentTimeMillis() - modelCallStart;
recordUsage(ctx, response, modelCallCost);
```

```java
private void recordUsage(AgentRunContext ctx, ChatResponse response, long cost) {
    try {
        Usage usage = response.getMetadata().getUsage();
        long promptTokens = safeTokens(usage == null ? null : usage.getPromptTokens());
        long completionTokens = safeTokens(usage == null ? null : usage.getCompletionTokens());
        long totalTokens = promptTokens + completionTokens;
        ctx.addModelUsage(response.getMetadata().getModel(),
                promptTokens, completionTokens, totalTokens, cost);
    } catch (Exception e) {
        log.warn("模型 Usage 采集失败（不影响任务主流程）", e);
    }
}
```

三个设计点：

1. `safeTokens(Integer)`：usage 可能整体为 null，单个字段也可能为 null，全部按 0 处理；
2. 外层 try-catch：**采集失败绝不中断任务**，Usage 是观测数据不是主流程；
3. 落库语义：`totalTokens = 0` 时 `recordFinish` 不写 Token 列（保持 NULL），
   区分"模型未提供 usage"与"真实消耗为 0"。

### 6.4 工具重试装饰器（RetryableToolCallback）

```java
for (int attempt = 1; attempt <= maxAttempts; attempt++) {
    if (Thread.currentThread().isInterrupted()) {
        throw new RuntimeException(new InterruptedException(
                "工具重试被任务取消中断：" + toolName));
    }
    try {
        String result = invocation.invoke();
        if (!isFailureResult(result)) {
            return result;                       // 成功（可能是重试后成功）
        }
        lastFailure = new RuntimeException("工具返回失败结果：" + truncate(result));
    } catch (RuntimeException e) {
        lastFailure = e;
    }
    retryCount.incrementAndGet();                // 记录重试次数
    if (attempt == maxAttempts) break;           // 最后一次失败不再等待
    sleepBeforeRetry(backoffMillis);             // 指数退避（可被中断）
    backoffMillis = (long) (backoffMillis * multiplier);
}
throw lastFailure;                               // 耗尽后抛出最终失败原因
```

两个容易踩的细节：

1. **失败识别用 `contains` 不是 `startsWith`**：`@Tool` 方法经 `MethodToolCallback`
   执行时，返回值会被序列化为 JSON 字符串（外层包裹双引号），
   失败文案不在字符串最开头。用前缀匹配会导致重试静默失效；
2. **中断感知**：任务被取消后，重试循环立即退出，
   不会出现"任务已取消还在重试等待"的资源浪费。

工具定义完全透传（`getToolDefinition()` 返回被装饰者的定义），
模型看到的工具名、描述、参数毫无变化，重试对模型无感知。

### 6.5 健康检查配置

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health        # 只暴露 health，不含 env/beans/heapdump
  endpoint:
    health:
      show-details: always     # 生产可改 never 进一步收敛
```

`GET /api/actuator/health` 响应结构：

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "aiModel": {
      "status": "UP",
      "details": { "apiKey": "sk-ws-***", "chatModel": "...", "model": "qwen-plus" }
    }
  }
}
```

---

## 7. 手撕实现步骤

1. **并发闸门**：新建 `AgentConcurrencyGuard`（Semaphore + `@Value` 配置上限）；
2. **接入 BaseAgent**：`run()` 最外层 try/finally；`runStream()` 异步 finally + 同步段 catch；
3. **注入闸门**：`MkManus` 构造器加参数 → `setConcurrencyGuard`；
4. **限流接口**：`AgentRequestRateLimiter` + `RateLimitResult` record；
5. **内存实现**：`InMemoryAgentRateLimiter`（CAS 固定窗口，超限回滚名额）；
6. **接入 Controller**：只在 `/ai/manus/chat` 调用，键为 `user:` 优先 `ip:` 回退；
7. **Usage 采集**：`ToolCallAgent` 模型调用计时 + 空安全提取 + `ctx.addModelUsage`；
8. **Usage 落库**：`AgentRunContext` 累计字段 → `schema.sql` 加列（含 `ALTER IF NOT EXISTS`）
   → Entity/Recorder/VO 三件套；
9. **重试装饰器**：`RetryableToolCallback`（异常 + 失败文案双识别，指数退避，中断感知）；
10. **白名单包装**：`ToolRetryWrapper` 按 `retryable-tools` 配置，`AgentToolProvider.getTools()` 出口包装；
11. **健康检查**：pom 加 actuator、yml 只暴露 health、自定义 `AiModelHealthIndicator`；
12. **编码防御**：pom 声明 `project.build.sourceEncoding=UTF-8`（中文常量不乱码）；
13. **测试**：单元（限流原子性 / 重试边界）+ 集成（HTTP 全链路六类验证）。

---

## 8. 配置说明

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `mkagent.agent.max-concurrency` | `8` | 全局最大并发 Agent 任务数（Semaphore 许可数） |
| `mkagent.rate-limit.enabled` | `true` | 限流总开关 |
| `mkagent.rate-limit.max-requests` | `10` | 单用户每窗口最多 Agent 请求数 |
| `mkagent.rate-limit.window-seconds` | `60` | 限流窗口长度 |
| `mkagent.tool-retry.enabled` | `true` | 工具重试总开关 |
| `mkagent.tool-retry.max-attempts` | `3` | 最大尝试次数（含首次），绝不无限重试 |
| `mkagent.tool-retry.initial-backoff-millis` | `200` | 首次重试前等待毫秒 |
| `mkagent.tool-retry.multiplier` | `2.0` | 退避倍数（200 → 400 → …） |
| `mkagent.tool-retry.retryable-tools` | `web_search,web_scrape` | 允许重试的工具白名单 |
| `mkagent.tool-retry.failure-markers` | `web_search:搜索工具执行失败,...` | 吞异常失败文案识别关键词（包含匹配） |
| `management.endpoints.web.exposure.include` | `health` | Actuator 只暴露 health |
| `management.endpoint.health.show-details` | `always` | 生产建议改 `never` |

---

## 9. 测试命令和结果

所有测试均不触网、不调用真实模型、不依赖外部数据库（H2 内存库 PostgreSQL 兼容模式）。

```powershell
# 单元测试
.\mvnw.cmd test -Dtest=InMemoryAgentRateLimiterUnitTest
.\mvnw.cmd test -Dtest=RetryableToolCallbackUnitTest

# 集成测试（逐个运行，PowerShell 下避免逗号列表）
.\mvnw.cmd test -Dtest=AgentConcurrencyLimitIntegrationTest
.\mvnw.cmd test -Dtest=AgentRateLimitIntegrationTest
.\mvnw.cmd test -Dtest=ToolRetryIntegrationTest
.\mvnw.cmd test -Dtest=AgentHealthIntegrationTest

# 全量回归（注意：日志不要写到 target 目录内，否则 clean 阶段删不掉）
.\mvnw.cmd clean test
```

**全量回归结果**：`Tests run: 67, Failures: 0, Errors: 0, Skipped: 0 —— BUILD SUCCESS`（2 分 14 秒）。
存量工具链测试（MkManusAsyncSseIntegrationTest、AgentTaskCancelIntegrationTest、AgentToolWhitelistTest、
各工具单测、chatApp / RAG 测试等）全部继续通过，本次改造未破坏任何既有能力。

### 9.1 六类验证点与测试对应

| 验证点 | 测试 | 结果 |
| --- | --- | --- |
| 1. 并发上限生效 | `thirdRequestRejectedWhenConcurrencyFull`：上限调为 2，2 任务挂起后第 3 请求 429 且含指定文案 | ✅ 4/4 通过 |
| 2. 完成/失败/取消后许可释放 | `permitsNotLeakedAfterSequentialRuns` / `permitReleasedAfterFailure` / `permitReleasedAfterCancel` | ✅ |
| 3. 单用户超频被限流 | `singleUserLimitedAfterMaxRequests`（3 放行 + 第 4 次 429 含等待秒数）、`otherUserNotAffectedByOneUsersLimit`、`nonAgentEndpointNotAffectedByRateLimit` | ✅ 3/3 通过 |
| 4. 可重试工具临时失败后成功 | 单元 `retryOnExceptionThenSucceed` / `retryOnFailureMarkerTextThenSucceed`；集成 `retryableToolSucceedsAfterTransientFailure`（工具恰好调用 2 次，任务正常产出 final_answer） | ✅ 通过 |
| 5. 不可重试工具不被重复执行 | `highRiskToolIsNeverWrapped`（assertSame 原样返回）+ `nonRetryableToolExecutedExactlyOnceThroughToolCallingManager`（真实 ToolCallingManager 执行恰好 1 次，失败结果以 ToolResponseMessage 写回） | ✅ 通过 |
| 6. health 端点反映依赖状态 | `healthEndpointReflectsDependencyStatus`（整体/db/aiModel 均 UP，apiKey 脱敏）+ `sensitiveActuatorEndpointsAreNotExposed`（env/heapdump 404） | ✅ 2/2 通过 |
| 补充：Usage 落库 | `AgentRunPersistenceIntegrationTest#runPersistsModelUsage`（fake-model，prompt=20 / completion=10 / total=30） | ✅ 通过 |

### 9.2 关键日志证据

限流生效：

```
WARN  InMemoryAgentRateLimiter : Agent 请求被限流：key=user:user-rl-query, limit=3, waitSeconds=58
WARN  GlobalExceptionHandler   : 业务异常：status=429, message=请求过于频繁，请在约 58 秒后重试（限制：每分钟最多 3 次 Agent 请求）。
```

工具重试生效：

```
WARN  RetryableToolCallback : 工具执行失败，准备重试：tool=web_search, attempt=1/3, backoff=1ms, reason=工具返回失败结果："搜索工具执行失败：模拟网络超时"
INFO  RetryableToolCallback : 工具重试成功：tool=web_search, attempt=2/3, totalRetries=1
```

Usage 落库：

```
INFO  AgentRunRecorder : AgentRun 终态已持久化：runId=..., state=SUCCEEDED, model=fake-model, totalTokens=30
```

---

## 10. 常见问题表

| 问题 | 现象 | 原因 | 解决 |
| --- | --- | --- | --- |
| 许可泄漏 | 可用许可只减不增，最终所有请求被 429 | 获取后某条异常路径没有释放（获取与释放不在配对的 try/finally 中） | 获取后紧跟 `try { ... } finally { release(); }`；异步任务"提交前失败"必须由同步段释放；可用许可数纳入监控告警 |
| 限流非原子 | 高并发下实际放行数超过阈值 | "读计数→判断→加一"三步被打断 | 进程内用 CAS（本项目）；Redis 用单条 `INCR+EXPIRE` 或 Lua 脚本，禁止应用层先读后写 |
| 重复执行高风险工具 | 文件被写两次、重复下载 | 把写操作类工具放进了重试白名单 | 白名单默认只含只读工具；新增工具必须证明幂等才能加入 |
| 重试静默失效 | 失败文案出现了却没重试 | `MethodToolCallback` 把返回值序列化成 JSON（外层双引号），`startsWith` 匹配不上 | 用 `contains` 包含匹配（本项目已修正） |
| 流式 Usage 缺失 | 流式聊天没有 Token 记录 | 服务商流式响应默认不带 usage（需 `stream_options` 且只在最后分片返回） | Agent 路径用非流式 `call()` 稳定采集；流式路径明确不统计，不为 usage 破坏 SSE |
| 中文常量乱码 | 失败标记、限流提示运行时对不上 | 中文 Windows 默认 GBK 编译，UTF-8 源码中的中文常量被编译成乱码 | pom 显式声明 `project.build.sourceEncoding=UTF-8` |
| 未暴露端点返回 500 | `/actuator/env` 报 500 而非 404 | `@RestControllerAdvice` 的 `Exception` 兜底处理器拦截了 `NoResourceFoundException` | 为 `NoResourceFoundException` 单独处理，保留其自带状态码 |
| 健康检查拖垮探针 | 探针超时、实例被误杀 | 健康检查发起了真实模型/外网请求 | 只做配置可用性静态检查（本项目 `AiModelHealthIndicator`） |

---

## 11. 面试题

**Q1：为什么用 Semaphore 而不是直接调小线程池来控制 Agent 并发？**

线程池限制的是"同时执行的线程数"，不是"业务允许的任务数"；且线程池满了走拒绝策略抛 `RejectedExecutionException`，不是业务语义。Semaphore 提供"获取不到立刻拒绝 + 友好文案 + 跨线程释放"，与线程池解耦：线程池管执行资源，闸门管业务配额。

**Q2：异步任务里许可的获取和释放分别在哪个线程？为什么不会出错？**

Web 线程获取（`runStream` 同步段），`agent-*` 后台线程在 finally 释放。`Semaphore` 的许可只是计数器，不绑定获取线程，天然支持跨线程释放。风险在于"提交异步任务前抛异常"——此时后台 finally 不会执行，所以同步段还要有 catch 释放兜底。

**Q3：固定窗口限流和滑动窗口限流的区别？你的实现如何升级成滑动窗口？**

固定窗口在窗口切换瞬间理论上允许 2 倍流量（前后两个窗口各满额）。升级方案：① 进程内用"当前窗口 + 上一窗口按剩余时间加权"；② Redis 用 ZSET 存每次请求时间戳（`ZREMRANGEBYSCORE` + `ZCARD`），或用多个小窗口桶累加。本项目接口化设计（`AgentRequestRateLimiter`）允许直接换实现。

**Q4：为什么不能对 `write_file` 这类工具自动重试？如果业务坚持要重试怎么办？**

写操作非幂等，重试可能产生重复文件、重复扣费等不可逆副作用。若业务坚持：① 让工具自身实现幂等（如带幂等键、先查后写）；② 用"恰好一次"语义（写入前检查目标状态）；③ 把决策权交给模型——失败结果写回上下文，由模型决定是否再次调用（本项目对非白名单工具正是这种语义）。

**Q5：Spring AI 里如何拿到 Token Usage？有哪些坑？**

`chatResponse.getMetadata().getUsage()`。坑：① `getPromptTokens()` 返回 `Integer` 可能为 null，必须空安全；② 流式响应默认不携带 usage（DashScope 需开启 `stream_options.include_usage` 且只在最后分片返回）；③ 不是所有模型都返回，落库要区分"未提供"（NULL）与"为 0"。

**Q6：指数退避中为什么要加随机抖动（jitter）？本项目为什么没加？**

大量客户端同时重试会形成"重试风暴"，抖动把重试时间打散。本项目是单进程内的工具级重试（并发量小、退避总时长 < 1 秒），抖动收益有限；若升级为分布式客户端重试（如调用模型 API 的重试），必须加抖动。

**Q7：Actuator 生产环境怎么配置才安全？**

① `exposure.include` 白名单制，只暴露 health（必要时加 prometheus）；② `show-details` 设 `never` 或 `when-authorized`；③ 健康检查实现不发真实外部请求；④ 敏感端点（env/heapdump）绝不暴露——它们可导出密钥与内存数据；⑤ 如需暴露更多信息，用 Spring Security 限制端点访问权限。

**Q8：一个用户的脚本刷爆了接口，你的系统会怎么反应？**

第一道：限流（每分钟 10 次）在 Controller 层拒绝，成本几乎为零；第二道：即使绕过限流（如换大量 IP），并发闸门保证最多 8 个任务同时执行，多余请求被 429 快速拒绝；同时 Usage 落库能定位异常消耗来源。三层防线：限流保公平、并发保总量、观测保定位。

---

## 12. 后续优化

| 方向 | 说明 |
| --- | --- |
| 分布式限流 | 多实例部署时进程内限流失效，替换为 `RedisAgentRateLimiter`（Lua 原子脚本），接口已预留 |
| 动态配额 | 限流阈值 / 并发上限接入配置中心（Nacos/Apollo）热更新，按用户等级差异化配额 |
| 按模型计费 | 基于 `agent_run` 的 usage 数据 × 模型单价表计算成本，输出按用户 / 按天账单 |
| Prometheus + Grafana | Micrometer 暴露并发占用、限流拒绝数、工具重试数、Token 消耗速率，配合告警 |
| 滑动窗口 / 令牌桶 | 限流算法升级，消除窗口切换瞬间的 2 倍流量问题 |
| 并发闸门分布式化 | 多实例下用 Redis + Lua 实现全局信号量，或网关层统一限流 |

---

## 13. 模型调用失败治理（真实事故复盘）

一次真实运行暴露了三个问题：模型地址配错 → DashScope 返回 HTTP 400 `InvalidParameter`
→ 调用被反复重试 → 任务既没有立刻失败，日志也被堆栈刷满。

### 13.1 谁在真正决定重试策略（先定位，再改）

调用链（反编译 `spring-ai-alibaba-autoconfigure-dashscope-1.0.0.2` 核实）：

```
DashScopeChatAutoConfiguration#dashscopeChatModel(RetryTemplate retryTemplate, ...)
        └── DashScopeChatModel.Builder#retryTemplate(retryTemplate)
                └── RetryTemplate 来自 spring-ai-autoconfigure-retry 的
                    SpringAiRetryAutoConfiguration#retryTemplate(SpringAiRetryProperties)
                        └── 参数绑定：spring.ai.retry.*
```

结论：

1. `DashScopeChatModel` **自己不构造**重试策略，只接收容器里的 `RetryTemplate`；
2. 该 Bean 的默认值是 `maxAttempts=10` + 指数退避（2s 起、×5、上限 180s），
   并且只对 `TransientAiException` 生效；
3. 仓库里原本**没有任何** `spring.ai.retry` / 自定义 `RetryTemplate` 配置，
   所以线上行为完全取决于框架默认值——一次 400 要等好几分钟，
   最后还可能先撞上 Agent 的 2 分钟总超时（状态变成 `TIMED_OUT` 而不是 `FAILED`）。

> 若日志里出现 `FixedBackOffPolicy`、间隔约 15 秒，那不是 Spring AI 1.0.0 的默认值
> （默认构造的是 `ExponentialBackOffPolicy`；`RetryUtils.SHORT_RETRY_TEMPLATE`
> 是固定 100ms）。它来自 `DashScopeImageModel` 的构造器——该构造器会对传进来的
> `RetryTemplate` **原地**调用 `setRetryPolicy(new SimpleRetryPolicy(10))` 与
> `setBackOffPolicy(fixedBackOff(15000))`，把共享实例改成"10 次 + 15 秒 + 连
> `Exception` 都重试"。第一次修复之所以没生效，就是因为这一点，见 13.7。

### 13.2 现在的策略：只重试"可能自愈"的失败

`ModelRetryConfig` 定义同名 `RetryTemplate` Bean。Spring AI 的自动配置带
`@ConditionalOnMissingBean`，会整体让位，所以策略由项目掌握。

| 失败类型 | 典型状态码 | 是否重试 | 说明 |
| --- | --- | --- | --- |
| `NonTransientAiException` | 400 / 401 / 403 / 欠费 | ❌ 一次都不重试 | 重试没有意义，直接失败 |
| `TransientAiException` | 5xx、429 | ✅ 最多 3 次 | 1s → 2s 指数退避，封顶 2s |
| `ResourceAccessException` | 网络超时 | ✅ 最多 3 次 | 可用 `retry-on-timeout=false` 关闭 |
| 其它异常 | — | 不接管 | 保持原有传播语义 |

两个容易踩的细节：

1. **429 默认会被归为 4xx**（`ResponseErrorHandler` 把 4xx 映射成 `NonTransientAiException`），
   所以在 `application.yml` 里显式配置了 `spring.ai.retry.on-http-codes: 429`，
   让它和 5xx 走同一条可重试路径；
2. 重试耗尽后必须抛**真实异常**而不是 `ExhaustedRetryException`，
   否则上层无法区分"配置错"和"临时故障"，因此设置了
   `throwLastExceptionOnExhausted(true)`。

### 13.3 快速失败：不要让用户等到超时

`ToolCallAgent.think()` 在 `chatModel.call()` 外面加了一层翻译：

```java
catch (RuntimeException e) {
    ModelCallException modelCallException = toModelCallException(e);
    if (modelCallException != null) {
        ctx.transitionTo(AgentState.FAILED);          // 立即终态
        sendEvent(ctx, AgentEventType.ERROR, modelCallException.getUserMessage()); // 脱敏提示
        throw modelCallException;
    }
    throw e;
}
```

- `toModelCallException` 沿 cause 链识别 `NonTransientAiException` / `TransientAiException`，
  并兜底识别未经过 Spring AI 映射的 `HttpClientErrorException` / `HttpServerErrorException` /
  `ResourceAccessException`（不同版本客户端可能绕过 `ResponseErrorHandler`）；
- 文案只有两句：`模型服务调用失败，请检查配置或额度`（配置类）、
  `模型服务暂时不可用，请稍后重试`（临时类）；
- `BaseAgent` 识别到 `ModelCallException` 时**不再**推送通用错误事件，避免前端收到两条 `error`；
- 数据库里落的是 `模型服务调用失败（NonTransientAiException：HTTP 400 - ...）`，
  有类型、可聚合，仍然不带堆栈。

### 13.4 SSE 超时：不要往已断开的连接里写 JSON

`AsyncRequestTimeoutException` / `AsyncRequestNotUsableException` 意味着
连接已由容器接管或已经断开。若仍走通用异常处理器，会尝试写 JSON 错误体，
二次抛出 `HttpMessageNotWritableException`，把一次正常超时刷成 ERROR 堆栈。

`GlobalExceptionHandler` 对这两类异常单独处理：**方法返回 `void`**，
Spring MVC 视为"已处理、无需响应体"，只记一行 WARN。

### 13.5 日志降噪

`ModelRetryLoggingListener` 替换框架自带的监听器：

- WARN 只有一行：`模型调用失败：attempt=N, error=异常类型 - 单行摘要`；
- 完整堆栈降到 DEBUG；
- 摘要先折叠换行、再打码（`sk-***`、`api-key=***`）、最后截断到 300 字符。

### 13.6 验证

```bash
./mvnw -o test -Dtest='ModelRetryPolicyTest,ModelRetryWiringTest,ModelRetryLoggingListenerTest,ToolCallAgentModelFailureTest,GlobalExceptionHandlerAsyncTimeoutTest,DashScopeChatModelRetryWiringTest,DashScopeImageModelRetryPoisoningTest,ModelRetryProductionWiringTest'
```

| 测试类 | 覆盖 |
| --- | --- |
| `ModelRetryPolicyTest` | 400 只调 1 次；5xx/超时重试到 3 次；退避是指数且封顶；总耗时有界 |
| `ModelRetryWiringTest` | 项目 Bean 覆盖 Spring AI 默认（10 → 3）；429 可重试、400/401 不可重试 |
| `ModelRetryLoggingListenerTest` | 单行摘要、截断、密钥打码 |
| `ToolCallAgentModelFailureTest` | 400 → 1 次调用、FAILED、脱敏 error 事件、耗时远小于超时 |
| `GlobalExceptionHandlerAsyncTimeoutTest` | 异步超时不写响应体、不触碰已 committed 响应 |

### 13.7 复盘：为什么第一版修复没生效

第一版只做了一件事：定义同名 `RetryTemplate` Bean，指望
`DashScopeChatAutoConfiguration` 通过 `@ConditionalOnMissingBean` 让位后把它注入给模型。
启动日志也打印了 `maxAttempts=3, exponential`。但真实运行仍然是
"400 被重试 8 次、每次间隔 15 秒、`FixedBackOffPolicy`"。

定位过程（顺序很重要，值得复用）：

1. **先怀疑"注入错了"，但枚举 Bean 后发现只有一个** `retryTemplate`，
   且 Bean 定义确实来自项目的 `ModelRetryConfig`；
2. **再怀疑"我读到的策略不是模型手里的那份"** —— 用反射读
   `DashScopeChatModel.retryTemplate` 字段，拿到实例快照；
3. **关键一步：在 `BeanPostProcessor#postProcessAfterInitialization` 里打快照**。
   同一个对象（`identityHashCode` 相同）在初始化完成时是
   `3 次 / exponential`，测试运行时却变成 `10 次 / 固定 15 秒` ——
   说明它是在**初始化之后被别的代码改掉的**，而不是创建时就错了；
4. 于是在 classpath 上搜"谁会调 `setRetryPolicy` / `setBackOffPolicy`"，
   唯一命中 `DashScopeImageModel`，反编译其构造器后完全对上：

```java
SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(10);
FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
backOffPolicy.setBackOffPeriod(15000L);
retryTemplate.setRetryPolicy(retryPolicy);      // ← 改的是传进来的实例，不是副本
retryTemplate.setBackOffPolicy(backOffPolicy);
```

图片模型 Bean 在对话模型之后创建，一构造就把共享模板改掉了。
启动日志打印的是构建时的快照，所以"日志看起来完全正确"。

**教训**

- "配置正确" ≠ "运行时对象正确"。日志要打印**实例**状态，且最好在启动后
  （`ApplicationRunner`）再打印一次，而不是只在构造时打印；
- 一个可变的共享对象 + 第三方库"借用即改写"= 隐形的全局状态污染。
  `RetryTemplate` 只有 setter、没有 getter，天生适合被这样误改；
- 修复要选"结构性"的，而不是"再调一次参数"：
  1. 对话模型不再注入容器 Bean，改为 `ModelRetryConfig.build(...)` 构建私有实例，
     经 `DashScopeChatModel.Builder#retryTemplate(...)` 显式传入；
  2. 容器 Bean 改为 **prototype**，Embedding / Image 各拿一个实例，谁改都不影响别人；
  3. 启动自检 `ModelRetryDiagnostics` 在发现 `FixedBackOffPolicy` 或
     `maxAttempts > 5` 时直接 WARN，并点名这个已知成因。

**对应的回归测试**

| 测试类 | 覆盖 |
| --- | --- |
| `DashScopeImageModelRetryPoisoningTest` | 复现库的原地改写；断言对话模型私有模板不受影响 |
| `DashScopeChatModelRetryWiringTest` | 加载 DashScope 对话自动配置 + `MockRestServiceServer` 返回 400：请求只发 1 次、`ToolCallAgent` 秒级 FAILED |
| `ModelRetryProductionWiringTest` | 完整上下文：容器 Bean 仍为 `3 次 / 指数`，且对话模型持有另一个实例 |

---

## 14. SSE 连接超时缓冲：emitter 超时 = agent 超时 + buffer

### 14.1 问题：超时事件发不出去

`runStream()` 里 `SseEmitter` 的超时原本就是 `timeout.toMillis()`，
与 Agent 的 timeout **同一时刻**到期。而 Agent 超时之后还要做三件事：

1. `finishIfStillRunning` 判定 `TIMED_OUT`；
2. 推送 `status` 事件（"任务运行超时，已强制停止。"）；
3. 推送 `done` 并 `emitter.complete()`。

容器的异步超时回调（`AsyncContext` 超时）与这三件事是竞态关系：
一旦容器先到，连接被收掉，后面的事件全部写不进去
（`sendEvent` 会吞掉 `IOException` / `IllegalStateException`，所以**日志里看不出来**）。
用户侧表现就是"请求突然断了，什么都没收到"。

### 14.2 方案：把"连接还能活多久"往后推

```
emitter 超时 = agent timeout + sse-buffer
```

| 项 | 默认值 | 说明 |
| --- | --- | --- |
| `mkagent.agent.manus.timeout` | `2m` | Agent 内部**仍然**按它判 `TIMED_OUT`（不变） |
| `mkagent.agent.manus.sse-buffer` | `30s` | 只放宽连接存活时间 |
| 合法范围 | `1s ~ 5m` | 越界 WARN 并回退 `30s` |

两个边界值的理由：

- **下限 1s**：缓冲小于一个网络往返时，超时事件仍然会在路上被掐断，等于没加；
- **上限 5m**：缓冲过大 = 任务早结束了连接还挂着，容器线程与 socket 白占，
  只是把资源泄漏的时间拉长。

缓冲逻辑放在 `BaseAgent`（`setSseBuffer` + `resolveEmitterTimeout()`），
`MkManus` 用 `@Value` 方法注入 —— 后续 `MusicAgent` 只要也调 `setSseBuffer` 就能复用，
不需要在构造器上加参数（`EvalAgent` 是手动 `super(...)` 的，加参数会逼它硬编码）。

`resolveEmitterTimeout()` 单独抽出来的原因：`SseEmitter` **没有**读 timeout 的接口，
不抽成纯函数就只能靠"真跑一次超时"来验证，慢且不稳定。

溢出保护：`setTimeout` 不校验上限，两个大值相加会溢出成负数，
而**负数在 `SseEmitter` 里表示"永不过期"** —— 一次配置笔误就变成连接永不释放。

### 14.3 ⚠️ 部署前提：反代超时必须大于 emitter 超时

nginx / 负载均衡的读超时（`proxy_read_timeout`，nginx 默认 **60s**）
必须**大于** emitter 超时（默认 `2m + 30s = 150s`），否则：

1. 反代先在 60s 掐断连接；
2. Agent 还在跑，事件全丢；
3. 表现与"没加缓冲"完全一样，但日志里一切正常——极难排查。

```nginx
location /api/ai/ {
    proxy_buffering off;            # SSE 必须关闭缓冲
    proxy_read_timeout 180s;        # > emitter 超时（150s）
    proxy_send_timeout 180s;
}
```

调整 `manus.timeout` 时，这三个值要一起改。

### 14.4 验证

| 测试 | 覆盖 |
| --- | --- |
| `SseEmitterTimeoutTest` | 默认 150s；自定义 buffer / timeout 参与计算；0s / 10m / null 回退；1s 与 5m 边界；溢出不退化为负数 |
| `SseTimeoutEventDeliveryTest` | 真实容器：`timeout=500ms` + `buffer=5s`，桩模型每次睡 1.2s 且只请求工具 → 断言 `任务运行超时，已强制停止。` 与 `done` 都送达 |

`SseTimeoutEventDeliveryTest` 是**有区分度**的：把 `new SseEmitter(...)` 改回
`timeout.toMillis()` 后它立刻失败，客户端只收到 `status` + `run_id` 两条事件，
这正是修复前线上的表现。
