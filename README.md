# mk-agent

一个基于 **Spring AI + Spring AI Alibaba（DashScope）** 的 Java Agent 学习项目：从最简单的聊天应用出发，逐步实现了一个具备 **ReAct 循环、工具调用、SSE 流式输出、状态机、任务取消、弹性限流、任务持久化** 的通用智能体（MkManus），并配套 RAG 知识库问答与 MCP 工具生态。

> 定位：个人学习项目，代码中有大量中文注释解释原理，适合一起"手撕"学习。

---

## 技术栈

| 类别 | 技术 |
| --- | --- |
| 语言 / 运行时 | Java 21 |
| 框架 | Spring Boot 3.4.5、Spring MVC、WebFlux（仅流式响应） |
| AI | Spring AI 1.0.0、Spring AI Alibaba 1.0.0.2（DashScope 通义千问） |
| 协议 | MCP（Model Context Protocol） |
| 数据 | PostgreSQL + pgvector（向量检索）、MyBatis-Plus（任务记录） |
| 文档 | Knife4j / SpringDoc OpenAPI |
| 其他 | Lombok、Hutool、Jsoup、iText（PDF 生成）、Kryo（对话记忆序列化） |

---

## 核心功能

### 1. MkManus 通用智能体

分层架构，每一层只关心一件事：

```
BaseAgent          Agent Loop 骨架：循环控制、步数/工具预算、超时、状态流转、注册/清理
  └─ ReActAgent    Think-Act 模式：think() 决策 + act() 执行
      └─ ToolCallAgent   工具调用：解析模型 ToolCall、执行工具、结果写回上下文
          └─ MkManus     具体智能体：系统提示词 + 工具集配置
```

- **任务状态机**：`IDLE / RUNNING / SUCCEEDED / FAILED / CANCELLED / TIMED_OUT / MAX_STEPS_REACHED`，
  终态一经写入不可覆盖（`AgentRunContext.transitionTo` 统一入口）
- **异步 + SSE**：Agent Loop 在 `agent-*` 后台线程池执行，通过 `SseEmitter` 实时推送
  `status / run_id / step / tool_start / tool_result / final_answer / done / error / cancelled` 事件
- **主动取消**：运行中任务注册到进程内注册表（`AgentTaskRegistry`），
  通过 `POST /api/ai/manus/{runId}/cancel` 取消；配合执行线程显式中断 + 循环头中断检测，
  取消后不再进入新的 step
- **任务持久化**：任务记录落库（`agent_run` 表），支持按 runId 查询与分页查询历史

### 2. 弹性与成本控制（resilience）

| 机制 | 说明 |
| --- | --- |
| 全局并发保护 | `AgentConcurrencyGuard`（Semaphore），限制同时运行的 Agent 任务数 |
| 用户级限流 | `AgentRequestRateLimiter`，每分钟固定窗口限流，超限返回 429 |
| 工具安全重试 | `ToolRetryWrapper`，只对白名单中的只读幂等工具（如搜索/抓取）自动重试 |
| 模型健康检查 | `AiModelHealthIndicator`，接入 Spring Actuator `/health` |

### 3. chatApp 恋爱咨询助手 + RAG 知识库

- 基础对话（含文件级对话记忆 `FileBasedChatMemory`）、结构化输出（恋爱报告）
- 三种 RAG 知识库构建方式对比：
  1. 本地 Markdown 分片 + 内存向量库（SimpleVectorStore）
  2. 阿里云百炼云端知识库 Advisor
  3. 自定义检索增强：查询改写（`QueryRewriter`）+ 关键词元数据富集（`MyKeywordEnricher`）+ 元数据过滤 + 上下文增强
- pgvector 云端向量库配置（1536 维、HNSW 索引、余弦距离）

### 4. 工具与 MCP

内置工具（`tools/`）：网络搜索、网页抓取、文件读写、PDF 生成、资源下载、任务终止。

MCP 生态：

- `mk-image-search-mcp-server`（仓库内子模块）：图片搜索 MCP Server，
  支持 stdio / SSE 两种传输，对接 Pexels 图库
- 主程序通过 `spring-ai-starter-mcp-client` 接入 MCP 工具

---

## 项目结构

```
mk-agent
├── src/main/java/com/example/mkagent
│   ├── agent/          # Agent 核心：BaseAgent/ReActAgent/ToolCallAgent/MkManus、任务注册与取消
│   ├── app/            # chatApp 恋爱咨询助手
│   ├── rag/            # RAG 三种构建方式 + pgvector 配置
│   ├── tools/          # 内置工具（搜索/抓取/文件/PDF/下载）
│   ├── resilience/     # 并发保护、限流、工具重试
│   ├── service/        # AgentRun 任务记录服务（MyBatis-Plus）
│   ├── controller/     # AiController（对话/Agent/取消）、AgentRunController（历史查询）
│   ├── context/        # 用户身份（X-User-Id 请求头）
│   ├── model/          # AgentState 状态机、AgentRunContext、事件模型
│   ├── chatmemory/     # 基于文件的对话记忆（Kryo 序列化）
│   └── demo/           # 三种调用方式对比 demo（SDK/Spring AI/LangChain4j）
├── src/main/resources
│   ├── document/       # RAG 知识库原始 Markdown
│   ├── db/schema.sql   # agent_run 建表脚本（幂等）
│   └── mcp-servers.json        # 本地 MCP 服务配置（含密钥，已忽略不上传）
├── mk-image-search-mcp-server/ # 图片搜索 MCP Server 子模块
├── docs/agent-learning/        # 学习文档（状态机与任务取消等）
└── src/test/           # 单元测试 + 集成测试（不依赖真实模型与外部服务）
```

---

## 快速开始

### 环境要求

- JDK 21、Maven（可用仓库自带的 `mvnw`）
- Node.js（如需运行高德地图 MCP）
- PostgreSQL（可先用本地库，建表脚本会自动执行）

### 1. 配置本地密钥

新建 `src/main/resources/application-local.yml`（已被 git 忽略，不会上传）：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/your_db
    username: your_user
    password: your_password

  ai:
    dashscope:
      api-key: 你的DashScope密钥

search-api:
  api-key: 你的搜索API密钥
```

图片搜索子模块的 Pexels 密钥：设置环境变量 `PEXELS_API_KEY`，
或子模块本地配置 `pexels.api-key`。

### 2. 启动

```bash
# 主程序（端口 8123，上下文路径 /api）
./mvnw spring-boot:run

# 图片搜索 MCP Server（端口 8127，可选）
cd mk-image-search-mcp-server && ./mvnw spring-boot:run
```

### 3. 接口文档

启动后访问：`http://localhost:8123/api/doc.html`（Knife4j）

---

## API 一览

| 接口 | 说明 |
| --- | --- |
| `GET /api/ai/chat_app/chat/sync` | 同步对话 |
| `GET /api/ai/chat_app/chat/sse` | 流式对话（Flux 直出） |
| `GET /api/ai/chat_app/chat/events` | 流式对话（ServerSentEvent 包装） |
| `GET /api/ai/chat_app/chat/sse/emitter` | 流式对话（SseEmitter 手动推送） |
| `GET /api/ai/manus/chat?message=...` | Agent 任务（SSE），需请求头 `X-User-Id` |
| `POST /api/ai/manus/{runId}/cancel` | 主动取消运行中的 Agent 任务 |
| `GET /api/ai/runs/{runId}` | 查询任务详情 |
| `GET /api/ai/runs/page` | 分页查询任务历史 |

Agent 任务 SSE 事件流示例：

```
event:status     Agent 已开始执行
event:run_id     任务ID（用于后续取消/查询）
event:step       每一步的执行摘要
event:final_answer  最终回答
event:done       [DONE]
```

---

## 测试

测试全部隔离真实模型与外部服务（DashScope 自动配置被排除，模型由可控的假实现替换）：

```bash
# 状态机单元测试（11 个：成功/超时/取消/预算/清理幂等/注册移除）
./mvnw test "-Dtest=AgentStateMachineUnitTest"

# 取消接口集成测试（真实 HTTP + 假模型）
./mvnw test "-Dtest=AgentTaskCancelIntegrationTest"

# 异步 SSE 工具调用全链路集成测试
./mvnw test "-Dtest=MkManusAsyncSseIntegrationTest"
```

---

## 学习文档

`docs/agent-learning/` 目录记录每次功能演进的"手撕"过程：

- `02-agent-state-and-task-cancel.md`：Agent 状态机、任务注册表、主动取消与超时处理
  （含状态流转图、四条路径时序图、面试题与常见问题排查表）

---

## 安全说明

- 所有密钥通过本地配置文件 / 环境变量注入，仓库中不含任何明文密钥
- `application-local.yml`、`mcp-servers.json` 均已加入 `.gitignore`

---

## License

仅供学习交流。
