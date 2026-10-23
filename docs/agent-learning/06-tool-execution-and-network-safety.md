# 06 - 工具执行与网络抓取的安全加固

> 本文档与当前代码库完全一致，面向已有 Java / Spring Boot 基础、正在学习 Agent 生产化改造的开发者。
> 所有类名、方法名、配置项均来自本项目真实实现，无伪代码。

---

## 1. 本次要解决的两个问题

### 1.1 工具到底由谁执行

本项目的 `ToolCallAgent` 采用**手动执行**模式：

```
think()  →  chatModel.call(prompt)           // 只拿模型的 ToolCall，不执行工具
act()    →  toolCallingManager.executeToolCalls(...)  // 真正执行 Java 方法
```

但 Spring AI 的 `ChatModel` 实现默认可能**自己**把工具跑一遍再返回。
如果框架内部执行一次、我们再执行一次，就会出现：

| 后果 | 说明 |
| --- | --- |
| 重复副作用 | 写文件、发请求、扣费用类工具被执行两次 |
| 预算失真 | `maxToolCalls` 统计的是我们执行的那一次，实际调用次数翻倍 |
| 结果错乱 | 回填给模型的历史里出现两份工具结果，模型容易混淆 |

### 1.2 网络抓取工具的三个洞

`WebScrapingTool` 直接面向模型生成的 URL，属于典型 SSRF 入口：

1. 只用字符串黑名单（`localhost` / `127.0.0.1` / `169.254.*`），
   域名解析到内网就能绕过；
2. `followRedirects(false)` 之后没有处理 3xx，
   公网 URL 302 到内网时缺少二次校验；
3. `ignoreHttpErrors(true)` 会把 4xx / 5xx 的错误页正文当成正常内容返回。

---

## 2. 改动一：显式关闭模型内部工具执行

### 2.1 代码

`ToolCallAgent` 把重复构造的 options 收敛为一个私有方法：

```java
private ToolCallingChatOptions buildToolCallingOptions() {
    return ToolCallingChatOptions.builder()
            .toolCallbacks(availableTools)
            .internalToolExecutionEnabled(false)
            .build();
}
```

`think()` 与 `act()` 都调用它，避免两处写法漂移。

### 2.2 为什么 `false` 而不是 `true`

- `false` 表示：**模型只负责返回 ToolCall，不负责执行**；
- 执行职责唯一收敛到 `act()` 里的 `ToolCallingManager`；
- 这样 `ctx.addToolCallCount(...)`、`tool_start` / `tool_result` 事件、
  `ToolResponseMessage` 回填都只有一条路径，语义清晰、便于审计。

### 2.3 单元测试能证明什么、不能证明什么

`ToolCallAgentToolExecutionSwitchTest` 用桩 `ChatModel` 验证了：

- 传给模型的 `Prompt.options` 中 `getInternalToolExecutionEnabled()` 为 `false`；
- 桩返回带 `ToolCall` 的响应时 `response.hasToolCalls()` 为 `true`；
- 该阶段计数器工具**没有被调用**（计数为 0）；
- 进入 `act()` 后工具**恰好被执行一次**。

**但桩只能证明"我们传了什么参数"，无法证明真实模型实现是否尊重这个参数。**
真实链路的验证必须手动做一次。

---

## 3. 手动验证步骤（必须做一次）

> 目的：确认真实调用里工具**没有被执行两次**。

### 3.1 准备

1. 确认 `application-local.yml` 中 DashScope Key 可用；
2. 把日志级别调到 INFO（默认已是 INFO）；
3. 准备一个只调用一次工具的简单任务，例如让 MkManus 搜索一个关键词后 `terminate_task`。

### 3.2 观察日志

启动后发起一次任务，在日志里检索以下两条关键字：

```
模型请求工具：name=...
工具实际执行完成：runId=..., toolName=...
```

### 3.3 判定标准

| 观察结果 | 结论 |
| --- | --- |
| 同一次工具调用只出现 **1 条**"模型请求工具" + **1 条**"工具实际执行完成" | ✅ 修复生效 |
| 同一次工具调用出现 **2 条**"工具实际执行完成" | ❌ 内部执行未被关闭，需排查模型实现 |
| 完全没有"模型请求工具"但工具被调用了 | ❌ 说明执行发生在模型层内部，配置未生效 |

另外核对 `agent_run` 表里该次任务的 `tool_call_count`：
它应与日志中"工具实际执行完成"的条数**完全一致**。

### 3.4 如果验证不通过

1. 确认 `buildToolCallingOptions()` 是 `think()` / `act()` 的唯一入口；
2. 确认所用模型实现（如 `DashScopeChatModel`）确实读取
   `ToolCallingChatOptions#getInternalToolExecutionEnabled()`；
3. 若该实现忽略此开关，需要在 `think()` 之后丢弃响应中的工具执行副作用，
   或改用不注册 `ToolCallback` 的调用方式（仅把工具定义以文本形式注入提示词）。

---

## 4. 重要提醒：评测基线要在本次修复之后取

`internalToolExecutionEnabled(false)` 属于**行为显式化**：
如果此前模型实现真的在内部执行过工具，那么修复前后
"工具调用次数、Token 消耗、任务耗时"都会变化。

因此：

> **做 Agent 评测对比时，基线必须在本次修复之后重新采集。**
> 用修复前的旧基线对比，会把"修复带来的差异"误判成"模型能力变化"。

评测模块见 [`docs/agent-eval.md`](../agent-eval.md)。

---

## 5. 改动二：网络抓取的三层防护

### 5.1 第一层：SSRF 校验（`UrlSafetyValidator`）

校验时机在**发起请求之前**，规则见
[`docs/SECURITY.md`](../SECURITY.md) 第 6.1 节。

关键点：用 `getAllByName` 取**全部**解析结果，任意一条落在内网即拒绝。
只判第一条（`getByName`）会被"多 A 记录"绕过。

### 5.2 第二层：重定向不自动跟随

```java
Jsoup.connect(url).followRedirects(false).ignoreHttpErrors(true).execute();
```

拿到 3xx 后：

1. 取 `Location`，用 `baseUri.resolve(location)` 转成绝对地址；
2. **重新走一遍 `UrlSafetyValidator.validate(...)`**；
3. 通过 → 返回"页面发生重定向，请使用最终 URL：xxx"，让模型自己决定是否再抓；
4. 不通过 → 返回"重定向目标地址不被允许"，**且不回显该地址**。

为什么不自动跟随：自动跟随等于把"目标是否安全"交给 HttpClient，
而 HttpClient 不做 SSRF 校验。

### 5.3 第三层：非 2xx 不返回正文

```java
if (statusCode < 200 || statusCode >= 300) {
    return "网页抓取失败：HTTP 状态码 " + statusCode;
}
```

`ignoreHttpErrors(true)` 只是让 jsoup 不抛异常，
**必须自己判状态码**，否则 404 页面、网关错误页、登录页都会被当成正文喂给模型。

---

## 6. 测试清单

| 测试类 | 覆盖内容 | 是否联网 |
| --- | --- | --- |
| `ToolCallAgentToolExecutionSwitchTest` | options 开关、think 不执行工具、act 只执行一次 | 否 |
| `UrlSafetyValidatorTest` | 11 组内网 / 保留地址、非 HTTP 协议、无 host、公网放行 | 否（注入解析器） |
| `WebScrapingToolHttpHandlingUnitTest` | 4xx/5xx 不返回正文、3xx 不跟随、内网重定向被拒 | 仅本机回环 |

`WebScrapingToolHttpHandlingUnitTest` 用 JDK 自带 `HttpServer` 在 `127.0.0.1` 起桩服务，
不访问任何外部网络。

---

## 7. 遗留问题

1. **DNS rebinding 未彻底解决**（详见 `docs/SECURITY.md` 6.2）：
   校验与连接是两次独立解析，理论上可被短 TTL 域名绕过；
2. **抓取工具的失败文案会触发重试**：
   返回 `网页抓取失败：HTTP 状态码 404` 会命中 `ToolRetryWrapper` 的失败标记，
   导致 404 也被重试（默认 3 次）。对只读抓取无害，但会浪费少量请求；
3. **重定向只解析一层**：
   模型拿到最终 URL 后再抓，如果该地址又返回 3xx，会再次走同样的流程，
   不会无限跟随（每次都由模型决定），但会多消耗一轮对话。
