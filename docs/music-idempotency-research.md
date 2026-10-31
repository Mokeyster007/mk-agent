# 写工具幂等键：toolCallId 能否传到工具执行处？

> 结论先行：**Spring AI 1.0.0 不能。** `ToolCallback` 与 `ToolCallingManager`
> 都不接收 `toolCallId`，工具方法体里拿不到它。
> 因此幂等键必须由服务端从"可观测的事实"派生，
> 本文给出派生方案、冲突分析与已知缺口。
>
> 本文只做调研，M2 不实现（`music_tool_idempotency` 只建表，M3 启用）。

---

## 1. 问题

写工具（建歌单、加歌、删除等）必须幂等：
同一个用户意图重复到达时，只能产生一次副作用。
早期路线图设想"工具带 `idempotencyKey` 参数"，
后调整为**服务端派生**——不把幂等键暴露给模型。
那么服务端手里最有辨识度的值就是 `toolCallId`（模型每次工具调用的唯一 ID）。
问题变成：**它能不能传到工具执行处？**

---

## 2. 依据（javap 反编译 spring-ai-model-1.0.0.jar）

### 2.1 `ToolCallback` 的方法签名里没有 toolCallId

```
public interface ToolCallback {
  ToolDefinition getToolDefinition();
  default ToolMetadata getToolMetadata();
  String call(String toolInput);                                   // 无 id
  default String call(String toolInput, ToolContext toolContext);   // 无 id
}
```

工具方法体只能看到两样东西：参数 JSON 和 `ToolContext`。

### 2.2 `ToolCallingManager` 也没有逐调用的 id 入参

```
public interface ToolCallingManager {
  List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions);
  ToolExecutionResult executeToolCalls(Prompt, ChatResponse);      // 整轮，无 id
}
```

### 2.3 `toolCallId` 只用在"拼回执"上

`DefaultToolCallingManager#executeToolCall` 的字节码里，
`AssistantMessage$ToolCall.id()` 的唯一用途是构造回执：

```
new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), toolResultData)
```

回调调用点传入的是 `(toolInput, toolContext)`，**没有 id**。

### 2.4 `ToolContext` 里也没有 id

`buildToolContext(Prompt, AssistantMessage)` 的内容只有两项：

| key | 内容 |
| --- | --- |
| （options.toolContext 的键值） | 仅当 `ToolCallingChatOptions.getToolContext()` 非空时才复制 |
| `TOOL_CALL_HISTORY` | 执行工具前的完整对话历史（含带 toolCallId 的 AssistantMessage） |

理论上可以翻 `TOOL_CALL_HISTORY` 的最后一条 AssistantMessage 找到本轮所有
`toolCall.id()`，但**仍然无法确定"当前正在执行的是哪一个"**
（框架是顺序执行，但没有任何"当前下标"传给回调）。

### 2.5 而且本项目刻意不用 `toolContext`

`ToolCallback.call(String, ToolContext)` 的**接口默认实现**是
"上下文非空就抛 `UnsupportedOperationException`（Tool context is not supported!）"。
`toolContext` 是 Prompt 级的，一旦设置，框架对所有回调都走两参版本，
只实现 `call(String)` 的回调会被打断。实测：

```
call(input, null)      → 走 call(input)，正常
call(input, 空 Map)    → 走 call(input)，正常
call(input, 非空 Map)  → UnsupportedOperationException
```

所以本项目的每任务状态统一走 `AgentRunContext` + ThreadLocal
（见 `ToolTargetExhaustionTracker`、`AgentRunContextHolder`），
连 `TOOL_CALL_HISTORY` 这条路也一并放弃了。

---

## 3. 替代方案

### 方案 A（推荐）：`runId + step + toolName + 规范化参数哈希`

```
idempotency_key = sha256(userId | toolName | canonicalJson(args))
request_hash    = 同一个哈希（落库便于排查"为什么判成重复"）
```

工具执行期间可通过 `AgentRunContextHolder.current()` 拿到 `runId` 与
`currentStep`；`toolInput` 由框架直接传入。

### 方案 B：自定义 `ToolCallingManager`

实现 `ToolCallingManager`，内部委托 `DefaultToolCallingManager`
或照抄它的循环，从而在每次 `call` 之前知道 `toolCall.id()`。

代价：把框架 ~80 行内部逻辑复制到项目里，Spring AI 升级时
（1.1 已计划调整 tool 执行链路）必须重新对齐；收益只是"键里多了个 id"。
**不推荐**，除非将来写工具需要"按 toolCallId 关联前端审批"。

### 方案 C：`TOOL_CALL_HISTORY` + 调用序号

从 ToolContext 历史里取本轮 `toolCalls`，再用一个每步递增的计数器定位当前调用。
依赖"框架严格按顺序、每步只执行一轮"，属于隐式契约，
框架一改就静默错位。**不推荐**。

---

## 4. 冲突分析（方案 A）

### 4.1 同一步内同名工具、参数完全相同

`DefaultToolCallingManager` 是**同一线程顺序执行**的（`executeToolCall` 内部
`for` 循环累加 `List<ToolResponse>`），所以"并行"实际是串行：

- 第 1 次：插入幂等记录 → 执行 → 回填结果；
- 第 2 次：命中同一条记录 → 直接回放结果，不重复执行。

这正是我们想要的语义（模型把同一件事说两遍，副作用只发生一次）。
若将来真出现并发（框架换成并行执行），唯一主键会抛
`DuplicateKeyException`，按"捕获 → 回查 → 回放"处理即可。

**参数不同则不冲突**：把同一首歌加到两个歌单，`playlistId` 不同 → 键不同。

### 4.2 模型重试

`RetryTemplate` 重试的是**模型调用**，发生在工具执行之前，
不会让工具执行两次；若重试后模型给出不同的工具调用，
参数不同 → 键不同，副作用本就该各算一次。

### 4.3 工具层重试（`RetryableToolCallback`）

同一个 `toolInput` 被重试 N 次 → 键完全相同。
因此写工具**不能**进入 `retryable-tools` 白名单
（现在只包 `web_search` / `web_scrape` 两个只读工具），
否则重试会命中自己的幂等记录并回放"第一次失败"的结果。

### 4.4 跨步重复

模型在第 2 步又把同样的写操作发一遍 → 键相同 → 回放，不重复执行。
这是方案 A 相对"把 step 放进键里"的关键优势：
**step 放进键里会让跨步重复失效**。

### 4.5 跨任务重复提交（已知缺口）

客户端超时后重发整个请求 → 新 `runId`，但 `userId + toolName + args` 不变
→ 键相同 → 回放上一次结果。这**恰好**是期望行为，
代价是"用户真的想再执行一次同样的写操作"会被挡住。

缓解方式（M3 落地时二选一）：

| 方式 | 说明 |
| --- | --- |
| TTL | 幂等记录保留 24h，过期后可重复执行（需定时清理任务） |
| 显式失效 | 删除类工具成功时清掉对应新增记录的键，允许"删了再加" |

推荐 **TTL + 删除类工具显式失效**：前者覆盖绝大多数误重发，
后者覆盖"删了再加"这种合法场景。

---

## 5. 对 M3 的落地约束

1. 幂等键**不进工具参数**（模型不可见、不可控），由服务端派生；
2. 写工具**不得**加入 `mkagent.tool-retry.retryable-tools`；
3. `music_tool_idempotency` 的写入用"先插入（`ON CONFLICT DO NOTHING`）
   → 查不到自己的记录就说明是重复请求 → 回放 `result_json`"；
4. `request_hash` 与 `idempotency_key` 分开存：
   前者用于人工排查"为什么判成重复"，后者是主键的一部分；
5. 参数规范化必须稳定：字段排序、空白折叠、数字统一为字符串，
   否则同一意图会算出两个键；
6. 升级 Spring AI 前先复核 `ToolCallback` / `ToolContext` 是否新增了
   toolCallId —— 若新增，方案 A 可以降级为"键 = toolCallId"，
   冲突分析里的 4.1 / 4.4 也就不再需要。
