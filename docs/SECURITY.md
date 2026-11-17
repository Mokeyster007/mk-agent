# 安全加固说明（SECURITY.md）

本文档记录本项目的密钥管理、历史清理、提交前扫描与网络安全加固约定。

> 约定：**文档中一律不写真实密钥，只写位置、类型与占位符。**
> 需要真实值时，只放在仓库根的 `config/application-local.yml`
> （已被 `.gitignore` 忽略、不在 classpath 上、不会被打包）或运行环境变量里。
> `src/main/resources/application-local.yml` 已入库，**只允许 `${ENV:default}` 占位符**。

---

## 0. 结论速览

| 项目 | 当前状态 | 谁来做 |
|---|---|---|
| 高德 Key 明文写在 `mcp-servers.json` | **已移除**，改为子进程继承环境变量 | 你：去控制台轮换该 Key |
| `application-local.yml` 中的 4 处密钥 | 真实值已迁到 `config/application-local.yml`（不入库、不打包）；classpath 上只留占位符 | 你：逐一轮换 |
| 密钥是否进过 git 历史 | **未发现**（见第 2 节核查结论） | 无需强推 |
| `tmp/` 是否进过 git 历史 | **未发现** | 无需清理历史 |
| 提交前密钥扫描 | 已提供 gitleaks 方案 + 单测兜底 | 建议本地装 gitleaks |
| SSRF | 已加 `UrlSafetyValidator` | 见第 6 节残留风险 |

---

## 1. 密钥轮换（必须手动做）

### 1.1 需要轮换的密钥清单（只列位置，不列值）

| # | 位置 | 类型 | 说明 |
|---|---|---|---|
| 1 | `src/main/resources/application-local.yml` | PostgreSQL 密码 | 数据库账号口令 |
| 2 | `src/main/resources/application-local.yml` | DashScope `api-key`（`sk-` 前缀） | 大模型调用凭证 |
| 3 | `src/main/resources/application-local.yml` | 另一个 `api-key` | 第三方服务凭证 |
| 4 | `src/main/resources/application-local.yml` | 再一个 `api-key` | 第三方服务凭证 |
| 5 | `src/main/resources/mcp-servers.json` | 高德地图 Key（32 位十六进制） | **本次已从文件移除**，但仍需轮换 |

> 第 5 项虽然已从工作区文件中删除，但它在磁盘上明文存在过相当长时间，
> 仍应视为已泄露并轮换。

### 1.2 轮换步骤

1. **高德开放平台**：登录控制台 → 应用管理 → 找到对应 Key → 删除旧 Key → 新建 Key。
2. **阿里云百炼 / DashScope**：控制台 → API-KEY 管理 → 禁用旧 Key → 创建新 Key。
3. **PostgreSQL**：`ALTER USER <user> WITH PASSWORD '<新口令>';`
4. 其余两个第三方 `api-key`：到各自控制台重新签发。

### 1.3 轮换后怎么放

- 数据库口令、DashScope Key：写进 `config/application-local.yml`（`file:./config/`，
  不入库、不打包）或环境变量。**不要再写回 `src/main/resources/application-local.yml`**
  ——该文件已入库，只允许 `${ENV:default}` 占位符，写进去等于随包分发。
- 高德 Key：**不要写回 `mcp-servers.json`**，改用环境变量（见第 5 节）。

---

## 2. Git 历史清理（预防性，本项目当前不需要强推）

### 2.1 核查结论

已执行以下核查，结果均为空：

```bash
# 1) mcp-servers.json 是否被提交过
git log --all --oneline -- src/main/resources/mcp-servers.json
# 输出为空 → 从未提交（该文件在 .gitignore 中）

# 2) 密钥字符串是否出现在任何历史提交中
git log --all -S "<高德 Key 原文>" --oneline
# 输出为空 → 历史中检索不到

# 3) tmp/ 是否被提交过
git log --all --oneline -- tmp
# 输出为空 → 从未提交
```

**结论：本仓库历史中未发现泄露的密钥，不需要改写历史，也不需要强制推送。**

### 2.2 若将来发现密钥进了历史（备查命令）

**方案 A：git filter-repo（推荐）**

```bash
# 安装：pip install git-filter-repo

# 1) 备份仓库（filter-repo 会改写全部提交）
cp -r mk-agent mk-agent.bak

# 2) 整文件移除
git filter-repo --path src/main/resources/mcp-servers.json --invert-paths

# 3) 或按内容替换（把泄露的字符串替换成占位符）
#    secrets.txt 每行格式：  原文==>***REMOVED***
git filter-repo --replace-text secrets.txt
```

**方案 B：BFG**

```bash
java -jar bfg.jar --delete-files mcp-servers.json
java -jar bfg.jar --replace-text secrets.txt
git reflog expire --expire=now --all
git gc --prune=now --aggressive
```

### 2.3 改写历史之后

```bash
git push --force --all
git push --force --tags
```

**强制推送会重写提交 ID，必须通知所有协作者重新克隆**：

```bash
# 协作者侧
git clone <repo-url> mk-agent-new
# 不要在原仓库上 pull/rebase，否则会把旧对象再带回来
```

同时提醒协作者：旧的本地克隆、CI 缓存、Fork 仍保留旧历史，需要一并清理。

---

## 3. tmp/ 与个人内容

### 3.1 现状

- `.gitignore` 已加入 `tmp/`（此前是不带斜杠的 `tmp`）。
- 核查结论：`git log --all -- tmp` 为空，**tmp/ 从未进入过 git 历史**。
  因此 `tmp/file/shanghai_date_plan.txt` 等含个人内容的文件没有泄露风险。

### 3.2 已被跟踪时的处理

`git rm --cached` 只对**已被跟踪**的文件生效。
本项目当前 `tmp/` 未被跟踪，执行下面命令会提示 `pathspec did not match`，
属于预期结果（已在提交记录中说明）：

```bash
# 如果 tmp/ 曾被跟踪，必须执行这一步才会真正停止跟踪
git rm -r --cached tmp
git commit -m "chore: stop tracking tmp/"
```

注意：`git rm --cached` 只是让 git 不再跟踪，**不会删除本地文件**。

### 3.3 个人内容清理

`tmp/` 下包含会话记忆（`tmp/chat-memory/*.kryo`）、下载文件与临时产物，
其中可能有个人内容。这部分需要你手动确认后清理，程序不会自动删除。

---

## 4. 提交前密钥扫描（gitleaks）

### 4.1 安装

```bash
# Windows（scoop）
scoop install gitleaks

# 或直接下载 release 二进制
# https://github.com/gitleaks/gitleaks/releases
```

### 4.2 使用

```bash
# 扫描全部历史
gitleaks detect --source . -v

# 只扫描暂存区（提交前用这个，最快）
gitleaks protect --staged -v

# 生成报告
gitleaks detect --source . --report-format json --report-path gitleaks.json
```

### 4.3 建议配置为 pre-commit 钩子

在仓库根目录新建 `.pre-commit-config.yaml`：

```yaml
repos:
  - repo: https://github.com/gitleaks/gitleaks
    rev: v8.18.0
    hooks:
      - id: gitleaks
```

然后：

```bash
pip install pre-commit
pre-commit install
```

### 4.4 项目内兜底

除 gitleaks 外，仓库内还有一个单元测试持续兜底：

`src/test/java/com/example/mkagent/security/ResourceSecretScanUnitTest.java`

它会扫描 `src/main/resources` 下的所有文本文件，命中以下任一规则即失败：

1. 32 位十六进制字符串（疑似 API Key）；
2. `sk-` 前缀密钥；
3. `api-key` / `apiKey` / `api_key` 被赋了非占位符的值。

> 说明：`application-local.yml` 被**排除**在扫描之外。
> 原因是它含本机真实密钥、必须留在本机才能启动项目，
> 且已被 `.gitignore` 忽略、永远不会进入版本库。

---

## 5. MCP 启动方式（密钥走环境变量）

### 5.1 为什么不用 `${AMAP_MAPS_API_KEY}` 占位符

已核实 Spring AI **1.0.0** 的实现：
`McpStdioClientProperties#resourceToServerParameters()` 只是
`new ObjectMapper().readValue(...)` 直接反序列化 JSON，
**没有任何 `Environment` / `PropertyResolver` 参与**。

也就是说，写 `${AMAP_MAPS_API_KEY}` 不会被展开，
会被原样当作 Key 传给 MCP 服务，导致调用必然失败。
因此本项目改为：**JSON 里不放 Key，由子进程继承父进程环境变量。**

### 5.2 启动方式

MCP stdio 子进程由 `ProcessBuilder` 拉起，默认继承父进程环境变量；
`mcp-servers.json` 中 `env` 为空对象，因此会继承下面设置的值。

**Windows PowerShell：**

```powershell
$env:AMAP_MAPS_API_KEY = "<你的高德 Key>"
./mvnw spring-boot:run
```

**Windows CMD：**

```bat
set AMAP_MAPS_API_KEY=<你的高德 Key>
mvnw.cmd spring-boot:run
```

**Linux / macOS：**

```bash
export AMAP_MAPS_API_KEY="<你的高德 Key>"
./mvnw spring-boot:run
```

### 5.2.1 示例文件 `mcp-servers.example.json`

`src/main/resources/mcp-servers.json` 被 `.gitignore` 忽略（含本机密钥），
所以它**不会进版本库**，别人克隆仓库后拿不到这份配置。

为此仓库里同时提供了一份**不含任何真实值**的模板：

```
src/main/resources/mcp-servers.example.json
```

用途与用法：

1. 它是可提交、可 review 的"配置结构说明"，只描述有哪些 MCP 服务、
   用什么命令启动，`env` 一律为空对象；
2. 新环境初始化时复制一份即可：

   ```bash
   cp src/main/resources/mcp-servers.example.json src/main/resources/mcp-servers.json
   ```

3. 复制出来的 `mcp-servers.json` 已在 `.gitignore` 中，
   **不要**把密钥写进它；密钥一律走环境变量（见 5.2）；
4. 修改示例文件时**绝不能**填入真实 Key——`ResourceSecretScanUnitTest`
   会扫描该目录，一旦出现 32 位十六进制 / `sk-` 前缀 / `api-key` 明文赋值就会失败。

### 5.3 跨平台注意

`mcp-servers.json` 中 `amap-maps` 的 `command` 目前是 `npx.cmd`（Windows 专用）。
**迁移到 Linux 服务器时必须改为 `npx`**，否则子进程无法启动。

### 5.4 当前状态说明

`application.yml` 中 `spring.ai.mcp.client.stdio.servers-configuration`
目前是**注释状态**，即 `mcp-servers.json` 尚未被加载，
当前只有 SSE 连接（`http://localhost:8127`）处于启用状态。

本次修复仍按规范整理好了该文件，待你启用 stdio 时可直接使用。

---

## 6. SSRF 与 DNS rebinding 残留风险

### 6.1 已做的防护

`WebScrapingTool` 抓取前会调用 `UrlSafetyValidator`：

1. 只允许 `http` / `https`；
2. 必须有 host，且 `localhost` 无条件拒绝；
3. 用 `InetAddress.getAllByName` 解析出**全部** IP，
   任意一条命中以下条件即拒绝：
   - 回环（127.0.0.0/8、::1）
   - 链路本地（169.254.0.0/16、fe80::/10）
   - 私有（10/8、172.16/12、192.168/16）
   - 通配（0.0.0.0、::）
   - 组播（224/4、ff00::/8）
   - IPv6 唯一本地（fc00::/7）
   - 0.0.0.0/8、169.254.169.254（云元数据）、100.64.0.0/10（CGNAT，含阿里云 100.100.100.200）
   - IPv4 映射的 IPv6（`::ffff:127.0.0.1`）按 IPv4 规则再判一次
4. 不自动跟随重定向（`followRedirects(false)`）：
   3xx 时把 `Location` 解析成绝对地址后**重新走同一套校验**，
   通过才把最终 URL 交还给模型，不通过直接拒绝且不回显该地址；
5. 非 2xx / 3xx 的错误页不返回正文，只返回 HTTP 状态码。

### 6.2 残留风险：DNS rebinding

**风险描述：**
校验阶段用 `getAllByName` 解析得到的是公网 IP，
但 jsoup 真正建立连接时会**再解析一次**域名。
攻击者可以控制一个 TTL 极短的域名：
第一次解析返回公网 IP（通过校验），第二次解析返回 `127.0.0.1`（实际连接内网）。

**为什么本次没有彻底解决：**
彻底解决需要"校验哪个 IP 就连哪个 IP"，即自定义连接工厂把连接目标钉死在
已校验的 IP 上，同时手动设置 `Host` 头。
但这样会破坏 HTTPS 的 SNI 与证书校验（证书绑定的是域名，不是 IP），
实现成本与兼容风险都较高，超出本次范围。

**缓解建议（按优先级）：**

1. 生产环境把抓取工具放到**独立的、无内网访问权限的网络命名空间 / 容器**里运行；
2. 出站流量只允许 80/443，且禁止访问 RFC1918 与云元数据网段（网络层 ACL）；
3. 如确需在应用层解决，再实现自定义 `SocketFactory` 固定已校验 IP +
   显式 `Host` 头（HTTPS 需自行处理 SNI）。

**当前判定：** 项目仅在本机运行，风险可接受；
部署到云服务器前必须至少落实缓解建议 1 或 2。

---

## 7. 配置分层与密钥外置（当前方案）

### 7.1 三层结构

| 层 | 文件 / 来源 | 是否入库 | 是否进 jar | 放什么 |
|---|---|---|---|---|
| ① 公共默认 | `src/main/resources/application.yml` | 是 | 是 | 端口、预算、重试、限流、RAG、评测开关、模型名等**非敏感**配置 |
| ② 本地差异 | `src/main/resources/application-local.yml` | 是 | 是 | 本地才有差异的**非敏感**值 + `${ENV:default}` 占位符 |
| ③ 真实密钥 | 环境变量，或 `config/application-local.yml` | 否 | 否 | 模型 / 搜索 / 数据库口令 / Pexels 凭据 |

关键点：② 里**没有任何真实值**，所以它进 jar 是安全的；③ 既不入库、也不在
`src/main/resources` 下，因此**不会被打包**。

### 7.2 属性 → 环境变量对照

| 环境变量 | 属性 | 读取方（代码位置） |
|---|---|---|
| `MKAGENT_MODEL_API_KEY` | `spring.ai.dashscope.api-key` | `DashScopeChatModelConfig`（经 `DashScopeConnectionProperties`，前缀 `spring.ai.dashscope` 已用 javap 核对） |
| `MKAGENT_MODEL_API_KEY` | `mkagent.model.api-key` | `OpenAiCompatibleChatModelConfig`（仅当配了 `mkagent.model.base-url`） |
| `MKAGENT_SEARCH_API_KEY` | `search-api.api-key` | `WebSearchTool` |
| `MKAGENT_DB_PASSWORD` | `spring.datasource.password` | Spring Boot 数据源自动配置 |
| `MKAGENT_PEXELS_API_KEY` | `pexels.api-key` | 图片搜索子模块（主程序不读） |

可选（覆盖非敏感默认值）：`MKAGENT_DB_URL`、`MKAGENT_DB_USERNAME`、`MKAGENT_CHAT_MODEL`。

> **关于 `AI_DASHSCOPE_API_KEY`（SDK 原生变量）**：`DashScopeConnectionUtils`
> 只在 apiKey 为 **null** 时才回退它，而本项目的模板把属性解析成**空串**
> （非 null），所以库自己那条回退实际不会触发。兼容性由模板的嵌套占位符提供：
> `spring.ai.dashscope.api-key: ${MKAGENT_MODEL_API_KEY:${AI_DASHSCOPE_API_KEY:}}`
> ——`MKAGENT_*` **未定义**时才退回 SDK 变量，两者都没有才是空串。
> 解析顺序已用 `javap -c` 核对字节码，不依赖文档描述。
>
> 凭据校验放在 `resolveConnectionProperties` **之前**（`DashScopeChatModelConfig`）：
> 库内部会先抛自己的 `DashScope API key must be set`，校验放后面等于死代码。
> `ModelCredentialConfigTest` 守住"缺失即启动失败且报错点名环境变量"。

### 7.2.1 来源覆盖 vs 占位符后备（实测结论）

这两件事经常被写成一句"环境变量优先级最高"，那是错的。实测
（`ModelCredentialSourcePriorityTest`，全部假值、不发起请求）：

| 场景 | 属性**来源标签**（谁定义了这个属性） | 解析结果 | 结论 |
|---|---|---|---|
| 只有 `MKAGENT_MODEL_API_KEY` | `Config resource 'class path resource [application-local.yml]' via location 'optional:classpath:/'` | 该变量值 | **占位符后备**，不是来源覆盖 |
| 只有 `AI_DASHSCOPE_API_KEY` | 同上（classpath 模板） | 该变量值 | 嵌套占位符的第二层后备 |
| 主变量缺失 + SDK 变量有值 | 同上 | SDK 变量值 | 回退生效 |
| 主变量**存在但为空串** + SDK 变量有值 | 同上 | **空串** → 启动失败 | 空 ≠ 缺失，**不回退** |
| 只有外部 `config/application-local.yml` | `Config resource 'file [<path>/config/application-local.yml]' via location 'file:<path>/config/'` | 外部文件里的值 | 外部文件压过 classpath 模板 |
| 自定义别名 `MKAGENT_MODEL_API_KEY` + 外部同名属性 | 外部文件（`file [...]`） | **外部文件的值**，别名不参与解析 | **来源覆盖**：模板占位符被整条替换 |

由此得到两条可操作规则：

1. **外部 `config/application-local.yml` 里写死属性后，失效的是"别名占位符"，不是环境变量这一层。**
   外部文件直接定义了 `spring.ai.dashscope.api-key`，模板里的
   `${MKAGENT_MODEL_API_KEY:...}` 整条被替换，该占位符不再参与解析，
   所以别名变量不再有任何作用。
   但**直接绑定目标属性**的环境变量（松散绑定名 `SPRING_AI_DASHSCOPE_API_KEY`）
   是独立的属性源，按 Spring Boot 的属性源顺序（`systemEnvironment` 高于
   config data 文件）生效，仍能压过外部文件里的字面量。
   一句话：自定义别名只是占位符的输入，本身不是一个属性源。
   （这一条依据 Spring Boot 的属性源顺序，本仓库测试集未单独覆盖。）
2. **空串不等于没配。** `${A:${B:}}` 只在 A 未定义时用 B；`export A=` 会让结果变成空串，
   最终以"未配置模型凭据"启动失败——这是预期行为，报错会点名 `MKAGENT_MODEL_API_KEY`。

命令行参数（`--spring.ai.dashscope.api-key=...`）优先级最高，仅用于临时排查；
`--spring.config.additional-location` 指定的目录同样按"外部文件"参与上面的比较。

### 7.3 四种运行方式各自怎么给配置

| 场景 | 配置来源 | 是否需要真实凭据 |
|---|---|---|
| 本地 IDE 运行 | 环境变量（Run Configuration）或 `config/application-local.yml`（工作目录须为仓库根） | 需要 |
| `./mvnw test`（默认） | `@HermeticSpringBootTest` → `test` profile → `src/test/resources/application-test.yml`（H2 + 占位密钥） | **不需要** |
| 真实评测（`-Plive` + `-Dmkagent.eval.music.enabled=true`） | `@ActiveProfiles("local")` → classpath 模板 + 环境变量 / `config/application-local.yml` | 需要 |
| `java -jar app.jar` | 环境变量，或 `--spring.config.additional-location=file:/etc/mk-agent/`（目录下放 `application-local.yml`） | 需要 |

### 7.4 为什么不用 Maven resource filtering

`spring-boot-starter-parent` 默认对 `application*.yml` 打开 filtering（分隔符 `@...@`）。
一旦有人在配置里写 `@MKAGENT_MODEL_API_KEY@`，构建时会把环境变量里的密钥
**展开并写回** `target/classes`，再原样打进 jar：源码干净、产物带明文。
因此 `pom.xml` 显式声明了 `<resources><filtering>false</filtering></resources>`，
`${ENV:default}` 只在运行时由 Spring 解析。

`ResourceSecretScanUnitTest#classpathCopyOfLocalConfigIsNotRewrittenByTheBuild`
逐字节比对源码与 `target/classes` 上的同名文件，防止 filtering 被重新打开。

### 7.5 检查清单

- [ ] `git check-ignore -v config/application-local.yml` 有输出（真实值文件确实被忽略）
- [ ] `git check-ignore src/main/resources/application-local.yml` **无输出、退出码 1**（模板必须入库）。
      注意别用 `-v`：它会打印 `.gitignore` 里那条 `!` 例外规则并返回 0，看起来像"被忽略了"
- [ ] `./mvnw test -Dtest=ResourceSecretScanUnitTest` 通过（`src/main/resources` 无明文密钥）
- [ ] `./mvnw test -Dtest=StartupSmokeTest` 通过（隔离 H2 启动、无 MCP/Embedding、正常关闭）
- [ ] `./mvnw test -Dtest=ModelCredentialSourcePriorityTest` 通过（来源标签与空值语义）
- [ ] 临时清空凭据启动一次：报错应点名 `MKAGENT_MODEL_API_KEY` / `MKAGENT_SEARCH_API_KEY`，
      而不是库原生的 `DashScope API key must be set`（后者说明校验位置被改到了 resolve 之后）
- [ ] 打包后 `unzip -p target/*.jar BOOT-INF/classes/application-local.yml` 只有 `${...}` 占位符
- [ ] 启动日志 / 健康端点（`/api/actuator/health`）不出现完整密钥（健康端点只回显前 6 位）
- [ ] 确认密钥没有被写进 `.mvn/jvm.config`、启动脚本、`pom.xml` 或 IDE 共享配置
- [ ] 轮换第 1 节清单中的全部密钥后，只更新 `config/application-local.yml` 或环境变量
- [ ] 若曾把 `config/application-local.yml` 发给他人 / 上传网盘，按"已泄露"处理并立即轮换

---

## 8. 其他

- 工具内部执行开关的手动验证步骤见
  [`docs/agent-learning/06-tool-execution-and-network-safety.md`](agent-learning/06-tool-execution-and-network-safety.md)。
- 所有日志、数据库、SSE 事件均不得包含密钥、系统提示词原文与工具参数原文；
  新增代码请沿用现有的脱敏约定（`ToolEventMessageProvider`）。
