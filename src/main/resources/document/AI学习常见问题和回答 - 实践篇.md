# AI 学习常见问题和回答 - 实践篇
#### Java 开发者如何用 Spring AI 快速上手 AI 应用开发？
Spring AI 是 Spring 官方出品的 AI 框架，深度集成 Spring Boot，让 Java 开发者用熟悉的自动配置、依赖注入和 application.yml 就能接入大模型。最小闭环只需三步：第一步，引入依赖与配置，例如通过 spring-ai-alibaba-starter-dashscope 接入通义千问，在 application.yml 中配置 API Key；第二步，发起第一次对话，注入 ChatClient 后一行 `chatClient.prompt().user("你好").call().content()` 即可拿到模型回复；第三步，逐步叠加核心能力——对话记忆（MessageChatMemoryAdvisor）、工具调用（ToolCallback）、RAG 检索增强（QuestionAnswerAdvisor 或 RetrievalAugmentationAdvisor）。学习建议：不要试图一次学完全部抽象，先把"一次对话、多轮记忆、工具调用"这个三角跑通，这是任何 Agent 的骨架，后续加 RAG、加多智能体都只是在这个骨架上叠加。
推荐资源：[Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)，配合 [Spring AI Alibaba](https://sca.aliyun.com/ai/) 可快速接入国产模型，是 Java 开发者的首选实践路径。

---

#### 如何用 Spring AI 从零搭建一个 RAG 知识库问答系统？
完整链路分五步。第一步，准备文档：把领域知识整理成 Markdown 或 PDF 放入项目资源目录；第二步，加载与分片：使用 MarkdownDocumentReader 等读取器把文档拆分为合适大小的片段，并附加元数据（如文件名、类别标签），便于后续按标签过滤检索范围；第三步，向量化入库：通过嵌入模型（如 DashScope text-embedding）把片段转为向量存入向量库，轻量场景用内存版 SimpleVectorStore，生产场景用 pgvector、Milvus 等持久化方案，注意嵌入模型维度必须与向量库表结构匹配；第四步，检索增强：用 RetrievalAugmentationAdvisor 组装文档检索器与查询增强器，可配置相似度阈值与 topK；第五步，查询优化：在检索前用查询改写（QueryRewrite）把口语化提问规范化，检索后可用重读（ReReading）策略提升回答准确度。上线后持续观察检索召回质量，针对性调整分块大小与 topK。
推荐资源：[Spring AI 完整学习路线：从 Java 开发到 AI Agent 的进阶之路](https://m.blog.csdn.net/dreamcatcher1314/article/details/161124558)，含 RAG 搭建与调优的系列实战教程。

---

#### 如何为智能体（Agent）添加工具调用能力？
工具调用的本质是：把可调用的能力以"函数描述 + 参数 Schema"的形式告诉大模型，模型根据用户意图决定何时调用、传什么参数，框架执行后把结果返回给模型继续推理。在 Spring AI 中的实践步骤：第一步，用 @Tool 注解或 ToolCallback 定义工具，参数用清晰的字段名与描述，模型靠这些描述理解工具用途；第二步，通过 ToolCallbackProvider 或 toolCallbacks() 把工具注册给 ChatClient；第三步，为模型配置系统提示词，说明可用工具与使用场景；第四步，复杂任务采用 ReAct 模式（推理 + 行动循环）：模型先思考计划，调用工具获得观察结果，再决定下一步，直到任务完成。工程注意事项：工具要做幂等与超时控制，敏感操作需要人工确认；对外部不稳定工具可增加重试与降级机制。进阶方向是 MCP（Model Context Protocol），用统一协议把外部工具服务接入任意智能体。
推荐资源：[GitHub：AI Agents from Zero 智能体实战指南](https://github.com/didilili/ai-agents-from-zero)，系统覆盖 Tool Calling、ReAct 与多智能体协作的完整实践。

---

#### 如何让 AI 应用记住多轮对话内容？
多轮对话记忆有三种主流实现，按需选择。第一种，内存记忆：用 MessageWindowChatMemory 把最近 N 轮对话保存在内存中，实现最简单，但进程重启即丢失，适合演示与轻量场景；第二种，持久化记忆：将对话序列化保存到文件或数据库（如用 Kryo 序列化写入本地文件），重启后可恢复会话，适合单机生产环境；第三种，共享存储记忆：把会话数据存入 Redis 等共享存储，支持多实例部署下的会话一致性。工程上的关键实践：为每个会话分配稳定的 conversationId，前端同一聊天窗口必须始终传相同 ID；控制记忆窗口大小，上下文过长会推高成本并稀释重点，必要时对历史进行摘要压缩；敏感对话内容落盘前要做脱敏处理。在 Spring AI 中，只需通过 MessageChatMemoryAdvisor 把 ChatMemory 挂到 ChatClient 的 advisor 链即可生效。
推荐资源：[Spring AI 官方文档·Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)，详解会话记忆的抽象与多种存储实现。

---

#### AI 应用上线后如何排查回答质量差、幻觉等问题？
按"检索—增强—生成"三段链路逐层定位。先查检索层：回答与问题不相关，多半是召回片段质量差，检查分块是否把完整语义切碎、嵌入模型与问题语言是否匹配、相似度阈值是否过高导致漏召回；可在日志中打印每次检索命中的片段来验证。再查增强层：模型无视检索内容，可能是提示词没有要求"仅基于以下资料回答"，或上下文组装顺序让资料被忽略；可让模型输出引用来源来强制对齐。最后查生成层：模型风格或格式不对，用系统提示词与结构化输出（如让模型返回指定 JSON 结构）来约束。幻觉治理的组合拳：要求模型在资料不足时明确说"不知道"、给出引用出处、对关键答案增加二次校验。同时建立评测习惯：整理一批标准问答集，每次改动提示词或分块策略后回归测试，用准确率与召回率指标衡量效果，而不是凭感觉调参。
推荐资源：[Spring AI 进阶系列：从 RAG 优化到企业级 AI 应用](https://blog.csdn.net/dreamcatcher1314/article/details/162214221)，覆盖 RAG 调优、评测与可观测性的工程实践。
