# 音乐业务权限矩阵（M2c）

> 面向 M2c 里程碑：把"谁能看到什么"从注释里搬到可执行的代码与可验证的测试里。
> 配套文档：[`music-agent.md`](music-agent.md)（数据模型与工具层）、
> [`music-idempotency-research.md`](music-idempotency-research.md)（写工具幂等）。

---

## 1. 一句话结论

**当前没有真实认证。** 用户身份来自请求头 `X-User-Id`，
客户端可以随意伪造；管理员身份虽然必须查 `music_user` 表，
但"谁的请求"这一步仍然是可伪造的。

因此本批所有权限设计都建立在一个前提上：
**数据层永远按服务端固化的 userId 过滤**，而不是"先查出来再判断能不能看"。
接入真实认证（JWT / Session）时只需要替换
`UserContextInterceptor` 的解析逻辑，本页的矩阵不需要改。

---

## 2. 角色

| 角色 | 判定方式 | 说明 |
| --- | --- | --- |
| 普通用户 `USER` | `music_user` 表存在该 `user_id`，且 `role != ADMIN` | 只读自己的歌单 + 公开歌单 + 公共曲库 |
| 管理员 `ADMIN` | `music_user.role` 忽略大小写等于 `ADMIN` | 只额外拥有**聚合统计**，**不能**读任何人的歌单或播放历史 |
| 未知用户 `UNKNOWN` | `music_user` 表查不到 / userId 为空 | **无权限**，不回落成普通用户 |

判定入口：`MusicRoleResolver`。

`UNKNOWN` 为什么不能回落成 `USER`：回落等于"随便填一个 X-User-Id 就能用只读工具"，
把越权从"需要伪造一个已知身份"降级成"随便写"。宁可失败关闭。

---

## 3. 权限矩阵

### 3.1 工具 × AgentType

| 工具 | 类型 | `MUSIC_USER` | `MUSIC_ADMIN` | 数据范围 |
| --- | --- | :---: | :---: | --- |
| `search_songs` | 只读 | ✅ | ✅ | 公共曲库（不按用户过滤） |
| `get_song_detail` | 只读 | ✅ | ✅ | 公共曲库 |
| `get_user_playlists` | 只读 | ✅ | ❌ | `WHERE p.user_id = <当前用户>` |
| `get_playlist_songs` | 只读 | ✅ | ❌ | `p.user_id = <当前用户> OR p.is_public = TRUE` |
| `get_play_stats` | 只读聚合 | ❌ | ✅ | 全量播放日志的聚合，**无用户标识** |
| `get_top_songs` | 只读聚合 | ❌ | ✅ | 全量播放日志的聚合，**无用户标识** |
| `terminate_task` | 控制 | ✅ | ✅ | 无数据访问 |
| `create_playlist` | **写** | ❌ | ❌ | M3 才考虑开放，且必须走人工确认 |
| `add_songs_to_playlist` | **写** | ❌ | ❌ | 同上 |

写工具**不在任何白名单里**。它们存在于代码中（M2d），
但模型在任何 AgentType 下都看不到——这是"能力不存在"级别的防护，
不是"提示词里说不要用"。

### 3.2 白名单装配位置

| 层 | 位置 | 作用 |
| --- | --- | --- |
| 工具 Bean 分组 | `AgentToolProvider` 的 `toolBeansByType` | 每个 AgentType 一个数组，数组里没有 = 模型看不到 |
| Spring Bean | `ToolRegistryConfig#musicUserToolCallbacks` / `#musicAdminToolCallbacks` | 供未来 MusicAgent 注入 |
| 重试配置 | `mkagent.tool-retry.retryable-tools` | 只有 `web_search,web_scrape`，写工具永不被自动重试 |

白名单粒度是 **Bean**，这是 M2c 把 `MusicQueryTool` 拆成
`MusicQueryTool`（曲库）+ `MusicPlaylistQueryTool`（歌单）的原因：
管理员不该读歌单，而"拿全集再排除几个"的减法式白名单
在新增工具时会被悄悄突破。

---

## 4. 三道防线

以 `get_play_stats` 为例，从外到内：

| # | 防线 | 失效后果 | 验证方式 |
| --- | --- | --- | --- |
| 1 | **白名单**：工具不在 `MUSIC_USER` 的数组里 | 模型连工具名都看不到，无法请求 | `MusicToolWhitelistIntegrationTest`（精确集合 + 桩模型强行请求） |
| 2 | **角色校验**：工具内部再查 `music_user.role` | 配错类型 / 复用 Bean 时仍然拒绝 | `AdminAnalyticsToolTest`（普通用户、未知用户、无上下文均 `ok=false` 且仓库零调用） |
| 3 | **返回脱敏**：SQL 不 SELECT 用户标识 | 即使越权也拿不到"谁" | `AdminAnalyticsToolTest`（断言 JSON 无 `user_id` / `userId`） |

第 2 道不是重复劳动：白名单是按 `AgentType` 配的，
而 `AgentType` 由调用方决定。一旦有人新增一个入口却复用了
`MUSIC_ADMIN` 的工具数组，白名单就形同虚设。

---

## 5. 接口层约定（尚未实现）

计划中的 `/ai/music/**` 必须遵守：

| 情况 | 响应 |
| --- | --- |
| 缺少 `X-User-Id` | **401**（与现有 `/ai/manus/chat` 一致，属于 4xx） |
| `music_user` 表查不到该用户 | **403** |
| 角色不满足 | **403** |

**不得使用 `UserContextHolder.getOrDefault()`**：它会在缺头时返回 `anonymous`，
让"未认证"悄悄变成一个合法身份。接口层应使用
`UserContextHolder.get()` + 显式判空。

> 现状：`/ai/music/**` 还不存在（M2e 才接入 MusicAgent）。
> 本批只在工具层落地了同样的语义：无上下文 → 失败关闭，
> 未知用户 → 统计工具返回 `ok=false`。

---

## 6. 已知缺口

| 缺口 | 影响 | 计划 |
| --- | --- | --- |
| 无真实认证，`X-User-Id` 可伪造 | 任何人可冒充任意 userId（含管理员） | M3 接入 JWT / Session，只替换拦截器 |
| 管理员能看全量聚合 | 数据量极小时，聚合结果可能反推出个体行为 | 需要业务方定义最小统计口径（如 n < 5 不返回） |
| 播放历史未开放 | 用户暂时无法查"我最近听了什么" | M3 随权限模型一起开放 `get_play_history` |
| 事件文案未做 i18n | 中英混排界面会露出中文 | 待办，见 `music-agent.md` 第 7 节 |
| 写工具无人工确认 | 一旦误开放即直接落库 | M3 的审批链路（`@RequiresApproval` 已就位） |

---

## 7. 测试对应表

| 测试 | 断言 |
| --- | --- |
| `MusicRoleResolverTest` | 未知用户 → UNKNOWN；脏角色值不升权；无上下文 → UNKNOWN |
| `AgentToolWhitelistTest` | 两种音乐类型的工具名集合精确相等（单元） |
| `MusicToolWhitelistIntegrationTest` | 同上（真实装配）+ 管理员无歌单工具 + 强行请求统计工具零数据访问 |
| `MusicPlaylistAccessRuleTest` | 他人私有拒 / 他人公开可读 / 本人私有可读 / 管理员读他人拒 |
| `AdminAnalyticsToolTest` | 非管理员 `ok=false` 且仓库零调用；越界夹取；返回无用户标识 |
| `MusicToolSchemaTest` | 所有工具 schema 不含 `userId` / `user_id` |
