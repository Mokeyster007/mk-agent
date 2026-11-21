# 播放 MVP 改动审阅包（脱敏）

> 范围：本轮「播放 MVP」的交付 A（不依赖模型的播放器）+ 交付 B（Agent 接入与最小 EQ）。
> 基线：工作区未提交改动（含上一轮会话层改动，本文只覆盖播放相关部分）。
> 校验：后端 780 tests / 0 failures；前端 110 tests；`vue-tsc` 与 `vite build` 通过；页面级 E2E 23/23。

**脱敏说明**

| 项 | 处理 |
| --- | --- |
| 签名密钥 | 只出现配置占位符 `${MKAGENT_PLAYBACK_SIGNING_SECRET:}`，仓库内无任何真实密钥 |
| 签名播放地址 | 只出现令牌的**构造代码**与格式 `v1.<payload>.<hmac>`；无一条可用的完整地址 |
| 本地数据库 | 未包含 `tmp/demo-db`、H2 文件、SQL 转储或任何表数据导出 |
| 用户/曲目数据 | 只有自生成的测试音夹具（1901/1902/1903）与虚构种子数据 |

**目录**

- [0. 先确认的两点](#0-先确认的两点)
- [1. 文件清单：diff 覆盖了什么](#1-文件清单diff-覆盖了什么)
- [2. 新增文件全文（`git diff` 不包含）](#2-新增文件全文git-diff-不包含)
- [3. 修改文件的 diff](#3-修改文件的-diff)
- [4. 测试](#4-测试)
- [5. 复现与校验命令](#5-复现与校验命令)
- [6. 本文没有包含的东西](#6-本文没有包含的东西)

---

## 0. 先确认的两点

### 0.1 签名链接能不能转发？——**能，窗口等于 TTL（默认 300 秒）**

结论先说：**任何拿到这条 URL 的人，在有效期内都能在任意浏览器里播放，不需要登录、不需要 `X-User-Id`。**

原因在鉴权模型的边界上，不在实现 bug：

```java
// PlaybackController.media()
Optional<PlaybackSigner.Claims> claims = signer.verify(token);   // 只证明"令牌是我们签的"
...
long songId = claims.get().songId();
String userId = claims.get().userId();                            // 只用来查"这个 user 能不能播这首"
Optional<TrackSource> maybeSource = sourceRepository.findBestSource(songId);
if (maybeSource.isEmpty() || !maybeSource.get().accessibleBy(userId)) { ... 403 ... }
```

`verify()` 校验的是 HMAC，证明「令牌未被篡改且未过期」；它**不证明**「发起这次 GET 的人就是令牌里的 userId」。整个 `/media/{token}` 没有任何一处读取请求方身份（无 cookie、无 session、无 `Sec-Fetch-Site`、无 Referer 校验）。这是刻意取舍——`<audio src>` 加不上自定义请求头，要免登录就必须把身份签进 URL。

| 拿到链接的人能做 | 不能做 |
| --- | --- |
| 在有效期内播放**这一首** | 改成别的 `songId`（HMAC 覆盖 payload，改了必 403） |
| 转给第三方，对方同样能播 | 改 `userId` 冒充别人（同上） |
| 反复拖进度条（Range 多次请求） | 延长有效期（`e` 在签名内） |
| 用 curl 直接下整段 | 访问签发者本来无权的曲目（如 1903 对 `music-user-1`） |

影响面 = **单曲 × ≤ TTL × 任何拿到链接的人**。当前默认 TTL 300 秒，`PlaybackSigner` 把配置钳制在 30~3600 秒。

已在的缓解：短 TTL、`Cache-Control: private, no-store`（中间层不缓存）、`X-Content-Type-Options: nosniff`、`Content-Type` 白名单只允许 `audio/*`。

**两处措辞需要修正**（这是文档问题，不是代码问题）：

`PlaybackService.resolveDirectAudio` 的注释写「媒体地址一旦泄露，只有同一个用户能继续用它读文件」，`application.yml` 里写「泄露后也只能被**同一个用户**在很短时间内继续使用」。这两句容易被读成"别人用不了"，实际含义是"令牌绑定的 userId 决定了能读哪首，不限制谁来读"。建议改成：

> 地址泄露后，任何人可在 TTL 内播放这一首；令牌里的 userId 只决定"能播哪首"，不校验请求方身份。

如果要把窗口压小，按代价排序：

| 方案 | 效果 | 代价 / 坑 |
| --- | --- | --- |
| TTL 降到 60s + 前端提前 15s 预解析 | 窗口压到 1 分钟，播放不中断 | `player.ts` 已有 `expiresAt` 与 `refreshSource`，约 10 行 |
| 一次性令牌（用过即废） | 理论上最强 | **不可行**：`<audio>` 会因 Range/seek 对同一 URL 多次请求，拖进度条时第二次就 403 |
| 校验 Referer / `Sec-Fetch-Site` | 挡住"粘到地址栏" | 挡不住 curl；Referer 可被抑制，会误伤部分客户端 |
| 绑定 UA / IP | 增加转发成本 | NAT、移动网络切换会误伤；UA 可伪造 |
| 正版音频走 `PROVIDER_SDK` / DRM | 真正的解法 | 需要平台授权，本轮明确不做 |

**建议**：把默认 TTL 从 300 降到 60，并在 README 里显式写"链接在 TTL 内可转发"。当前只有自生成测试音，风险为零；一旦挂上正版音频，这条就是真问题。

### 0.2 过期的 `PLAYING` 能不能误导后续收藏？——**能**

`PlaybackService.nowPlaying()` 只过滤了 `STOPPED`，**完全不看 `updated_at`**：

```java
return sources.findNowPlaying(userId)
        .filter(row -> !STATE_STOPPED.equals(row.state()))   // ← 只有这一道过滤
        .flatMap(row -> { ... });
```

而 `STOPPED` 只在这几种情况写入：用户点播放器上的「关闭播放器」（`player.ts` 的 `stop()`）、曲目自然播完（`ended`）、用户主动 `pause` 后…不，`pause` 写的是 `PAUSED`。**关标签页、浏览器崩溃、断电、手机杀后台，都不上报**——`player.ts` 里没有 `beforeunload` / `visibilitychange` 处理。

于是三天前那一行会永久停在 `PLAYING`。完整链路：

```
3 天前播放过 1902，关掉标签页（未上报）
  → music_now_playing: (music-user-1, 1902, 'PLAYING', updated_at=3 天前)
用户今天说"把正在播放的这首加进歌单"
  → get_now_playing  → {songId:1902, title:"测试音 B（低音）", state:"PLAYING"}   ← 服务端"确认"了
  → 模型据此说"正在播放的是《测试音 B》"
  → add_songs_to_playlist(songId=1902) → 暂停等审批
  → 审批卡显示"向歌单添加歌曲《深夜通勤》，共 1 首：测试音 B（低音）"
  → 用户批准 → 写入
```

所以准确的说法是：**会误导，但不是静默写入**。假前提进了模型的话术，但落库前还有审批卡把真实曲名摆给用户看。风险等级取决于用户是否看审批卡——而"用户不看卡直接点批准"恰恰是最常见的行为。

已覆盖的（这部分设计是对的）：前端不能凭空声称某首歌在播，`updateNowPlaying` 会复核曲目存在 + 音源可播 + 归属，`STOPPED` 视为无当前曲目。缺的只是**时间维度**。

修复不需要改表（`updated_at` 已经在写），推荐"心跳 + 窗口"：

| 改动点 | 内容 | 规模 |
| --- | --- | --- |
| `web/src/stores/player.ts` | `onPlaying` 起一个 60s 定时器重报 `PLAYING`；`pause` / `ended` / `stop` / `load` 清掉定时器 | ~15 行 |
| `PlaybackService.nowPlaying()` | `PLAYING` 且 `updated_at < now - 180s` → 视为没有当前曲目；新增配置 `mkagent.playback.now-playing-ttl`（默认 180s） | ~6 行 |
| 测试 | `PlaybackNowPlayingIntegrationTest` 加 1 条（把 `updated_at` 改到过去，断言 `get_now_playing` 失败）；`player.spec.ts` 加 1 条（播放中周期性重报） | 2 条 |

两个必须说清的点：

- **心跳不会刷爆 `music_play_log`**：`updateNowPlaying` 里写日志的判据是 `startedPlaying`（换歌、或从非 `PLAYING` 转来），重复上报同一个 `PLAYING` 不写日志。这点我确认过，可以放心加。
- **只做服务端窗口（不改前端）也行**，但会变成"刚播完就关页面仍有 30 分钟假窗口"。推荐心跳方案。

这两点我都**没有擅自改动**，因为一个改协议（TTL 默认值 + 文档措辞），一个改前后端契约（心跳）。等你确认后我按上面的范围改，并补对应测试。

---

## 1. 文件清单：diff 覆盖了什么

`git diff` 只覆盖**已跟踪文件**。本轮绝大多数代码是新增文件，因此**不在 diff 里**——下面分开列。

### 1.1 修改的已跟踪文件（在 diff 中）

| 文件 | 本轮改动 |
| --- | --- |
| `src/main/resources/db/schema.sql` | +2 表：`music_track_source`、`music_now_playing` |
| `src/main/resources/application.yml` | +`mkagent.playback` 三配置项 |
| `src/main/java/.../config/AgentToolProvider.java` | `MUSIC_USER` 白名单加入 `PlaybackTool` |
| `src/main/java/.../agent/MusicAgent.java` | +`PLAYBACK_CONTRACT` / `PLAYBACK_WRITE_CONTRACT`；版本 v3→v4、v3→v6 |
| `src/main/java/.../tools/DefaultToolEventMessageProvider.java` | 两个播放工具的开始/完成文案 |
| `src/main/java/.../music/MusicSeedInitializer.java` | `@Order(0)`；幂等判据改 `WHERE id = 1001` |
| `src/test/.../agent/MusicPromptCapabilityTest.java` | 版本号 + 播放契约断言 |
| `src/test/.../agent/MusicToolWhitelistIntegrationTest.java` | 5→7 个工具 |
| `src/test/.../agent/MusicAgentConfigIntegrationTest.java` | 5→7 个工具 |
| `src/test/.../config/AgentToolWhitelistTest.java` | 构造参数 + 7 个工具 |
| `src/test/.../music/MusicSeedInitializerTest.java` | 60 首断言限定到 1001~1060 |
| `src/test/.../music/MusicWriteLoopIntegrationTest.java` | 新增「正在播放 → 加歌」审批用例；抽出 `startTaskAndWaitApproval` |

> 注：`schema.sql` 的 diff 前半段（`agent_conversation*`、`agent_run.conversation_id`）属于**上一轮**会话层改动，与本轮无关，我在 3.1 里标注了分界。
> 另外：`MusicWriteLoopIntegrationTest.java` 在仓库里是 CRLF，我编辑时被写成 LF，导致裸 diff 膨胀到 1213 行。已恢复 CRLF，现在真实改动是 171 行。

### 1.2 新增文件（**不在** `git diff` 中）

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `src/main/java/.../playback/PlaybackSigner.java` | 175 | HMAC 签名与验签（本文 §2.1） |
| `src/main/java/.../controller/PlaybackController.java` | 322 | 解析 / 候选 / 当前曲目 / 媒体 Range（§2.2） |
| `src/main/java/.../playback/PlaybackService.java` | 426 | 音源解析、权限、可播性、当前曲目（§2.3） |
| `src/main/java/.../playback/MediaFileStore.java` | 124 | `object_key` → 可读资源（§2.4） |
| `web/src/stores/player.ts` | 600 | 播放器状态机与单例（§2.5） |
| `web/src/audio/equalizer.ts` | 154 | 前级 + 三段 EQ + 真旁路（§2.6） |
| `src/main/java/.../playback/PlaybackMode.java` | 45 | 四种交付模式，解析失败降级 `UNAVAILABLE`（§2.7） |
| `src/main/java/.../playback/TrackSource.java` | 58 | 音源映射记录 + `accessibleBy`（§2.7） |
| `src/main/java/.../playback/TrackSourceRepository.java` | 177 | 映射查询、当前曲目读写、播放日志（§2.7） |
| `src/main/java/.../playback/PlaybackFixtureInitializer.java` | 138 | 测试音夹具 1901/1902/1903（§2.7） |
| `src/main/java/.../tools/PlaybackTool.java` | 117 | `check_playback` / `get_now_playing`（§2.7） |
| `web/src/api/playback.ts` | — | 三个播放接口的前端封装 |
| `web/src/components/PlaybackBar.vue` | — | 常驻播放器（独立一行） |
| `web/src/components/EqPanel.vue` | — | 调音弹层，不支持时禁用并给原因 |
| `web/src/components/PlayCard.vue` | — | Agent 点歌卡（只渲染服务端候选） |
| `web/src/components/AppShell.spec.ts` | — | 三行布局、播放器不在滚动区 |
| `web/src/stores/player.spec.ts` | — | 11 条状态机用例 |
| `web/src/components/PlayCard.spec.ts` | — | 4 条候选渲染用例 |
| `src/main/resources/media/test-tone-a.wav` | 320044 B | 测试音 A（A 大调琶音，10s） |
| `src/main/resources/media/test-tone-b.wav` | 320044 B | 测试音 B（低八度小调，10s） |
| `tools/generate-test-audio.py` | — | 生成上面两个 WAV（纯标准库） |
| `web/tools/e2e-playback.mjs` | — | 页面级 23 项断言 |
| `web/tools/seed-playback-fixture.sql` | — | demo 会话夹具（不含真实用户数据） |
| `src/test/.../playback/*.java` | 5 个类 | §4.1 |

---

## 2. 新增文件全文（`git diff` 不包含）

### 2.1 `PlaybackSigner.java`

```java
package com.example.mkagent.playback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * 媒体地址签名。
 *
 * <p>为什么必须签名而不是"带个 songId 就能取"：
 * {@code <audio src>} **不能**附加业务接口的自定义身份头（X-User-Id 加不上去），
 * 所以媒体请求无法走请求头鉴权。可选方案只有两种：
 * <ol>
 *   <li>把文件放进公开静态目录——受保护文件等于公开，直接排除；</li>
 *   <li>把"身份 + 曲目 + 过期时间"签进 URL，服务端验签后再读文件。</li>
 * </ol>
 * 这里用第二种：签名覆盖 songId、userId、过期时间，任何一项被改都会验签失败，
 * 因此不能靠改 URL 换别人的歌，也不能无限期复用旧地址。
 *
 * <p>令牌格式：{@code v1.<base64url(payload)>.<base64url(hmac)>}，
 * payload 是 JSON（{@code {"s":songId,"u":userId,"e":epochSecond}}）。
 * 用 JSON 而不是分隔符拼接：userId 是外部可控字符串，
 * 含分隔符时拼接方案会被解析成不同的字段。
 *
 * <p>密钥来源：{@code mkagent.playback.signing-secret}。
 * 没配置时**随机生成**（进程内有效）：重启后旧地址全部失效，
 * 这对本地演示是可接受的，而且比"写死一个默认密钥"安全得多。
 * 多实例部署必须显式配置同一个密钥，否则 A 实例签的地址在 B 实例验不过。
 */
@Component
public class PlaybackSigner {

    private static final Logger log = LoggerFactory.getLogger(PlaybackSigner.class);

    private static final String VERSION = "v1";

    private static final String ALGORITHM = "HmacSHA256";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final byte[] secret;

    private final long ttlSeconds;

    public PlaybackSigner(
            @Value("${mkagent.playback.signing-secret:}") String configuredSecret,
            @Value("${mkagent.playback.url-ttl-seconds:300}") long ttlSeconds
    ) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            this.secret = generated;
            log.warn("未配置 mkagent.playback.signing-secret，已生成进程内随机密钥："
                    + "重启后旧的媒体地址会失效（多实例部署必须显式配置同一密钥）");
        } else {
            this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }

        // 上限一小时：再长就失去"短期地址"的意义
        this.ttlSeconds = Math.max(30, Math.min(ttlSeconds, 3600));
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    /**
     * 为某用户签一个某首歌的媒体地址令牌。
     */
    public IssuedToken issue(long songId, String userId) {
        long expiresAt = Instant.now().getEpochSecond() + ttlSeconds;

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("s", songId);
        payload.put("u", userId);
        payload.put("e", expiresAt);

        String encodedPayload = base64Url(payload.toString().getBytes(StandardCharsets.UTF_8));
        String signature = base64Url(hmac(encodedPayload));

        return new IssuedToken(VERSION + "." + encodedPayload + "." + signature, expiresAt);
    }

    /**
     * 验签并解出声明。
     *
     * <p>任何异常都返回 empty（失败关闭）：过期、签名不符、格式错误、
     * 版本不认识，对调用方都是同一件事——这个地址不能用。
     * 不区分原因是为了不给探测者额外信息。
     */
    public Optional<Claims> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            return Optional.empty();
        }

        String encodedPayload = parts[1];
        byte[] expected = hmac(encodedPayload);

        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        // 定长比较：避免按字节短路泄露签名前缀
        if (!MessageDigest.isEqual(expected, actual)) {
            return Optional.empty();
        }

        try {
            JsonNode payload = MAPPER.readTree(
                    new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8));

            long songId = payload.path("s").asLong(-1);
            String userId = payload.path("u").asText(null);
            long expiresAt = payload.path("e").asLong(0);

            if (songId <= 0 || userId == null || userId.isBlank()) {
                return Optional.empty();
            }
            if (expiresAt <= Instant.now().getEpochSecond()) {
                return Optional.empty();
            }

            return Optional.of(new Claims(songId, userId, expiresAt));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            /*
             * 算法或密钥不可用属于装配错误，不该静默降级成"没有签名"。
             * 抛出去让调用方 500，比签出一个谁都能伪造的地址安全。
             */
            throw new IllegalStateException("媒体地址签名不可用", e);
        }
    }

    private static String base64Url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** 签发的令牌 + 过期时间（前端据此在过期前重新解析）。 */
    public record IssuedToken(String token, long expiresAtEpochSecond) {
    }

    /** 验签后的声明。 */
    public record Claims(long songId, String userId, long expiresAtEpochSecond) {
    }
}
```

### 2.2 `PlaybackController.java`

```java
package com.example.mkagent.controller;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.music.MusicAccessGuard;
import com.example.mkagent.playback.MediaFileStore;
import com.example.mkagent.playback.PlaybackService;
import com.example.mkagent.playback.PlaybackSigner;
import com.example.mkagent.playback.TrackSource;
import com.example.mkagent.playback.TrackSourceRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 播放接口：解析 → 取流。
 *
 * <p>两个接口的鉴权方式**不同**，这是刻意的：
 * <ul>
 *   <li>{@code /resolve}、{@code /candidates}、{@code /now-playing} 走请求头
 *       {@code X-User-Id}（与其它业务接口一致）；</li>
 *   <li>{@code /media/{token}} 走**签名令牌**：{@code <audio src>} 无法附加自定义请求头，
 *       所以身份、曲目、过期时间被签进地址，服务端验签后再读文件。</li>
 * </ul>
 *
 * <p>媒体交付的三条实测要求（都实现了，见 PlaybackMediaTest）：
 * <ol>
 *   <li>{@code Content-Type} 如实返回 {@code audio/wav}；</li>
 *   <li>{@code Accept-Ranges: bytes}，{@code Range} 请求返回 206 + {@code Content-Range}，
 *       这是浏览器拖动进度条的前提；</li>
 *   <li>非法区间返回 416 而不是 200，避免播放器把整文件当成"从 0 开始"。</li>
 * </ol>
 *
 * <p>不把受保护文件放进公开静态目录：媒体只在 classpath {@code media/} 或
 * 配置的 {@code mkagent.playback.media-dir} 下，只能经本类读取。
 */
@RestController
@RequestMapping("/ai/music/playback")
public class PlaybackController {

    private static final Logger log = LoggerFactory.getLogger(PlaybackController.class);

    /** 分块写出的缓冲区大小；媒体文件小，8KB 足够，也不会一次占太多堆。 */
    private static final int BUFFER_SIZE = 8192;

    private final MusicAccessGuard accessGuard;

    private final PlaybackService playbackService;

    private final PlaybackSigner signer;

    private final TrackSourceRepository sourceRepository;

    private final MediaFileStore mediaStore;

    public PlaybackController(
            MusicAccessGuard accessGuard,
            PlaybackService playbackService,
            PlaybackSigner signer,
            TrackSourceRepository sourceRepository,
            MediaFileStore mediaStore
    ) {
        this.accessGuard = accessGuard;
        this.playbackService = playbackService;
        this.signer = signer;
        this.sourceRepository = sourceRepository;
        this.mediaStore = mediaStore;
    }

    /**
     * 解析点播请求。
     *
     * <p>请求体只有 songId（与可选的 playlistId）——**没有** url、path、provider 字段：
     * 音源地址只能由服务端产生，前端自带地址的请求在这里就没有对应的入参。
     */
    @PostMapping("/resolve")
    public PlaybackService.ResolveResult resolve(@RequestBody ResolveRequest body) {
        String userId = accessGuard.requireUser();

        if (body == null || body.songId() == null || body.songId() <= 0) {
            throw new BusinessException(400, "INVALID_SONG_ID", "songId 必须是正整数");
        }

        return playbackService.resolve(userId, body.songId(), body.playlistId());
    }

    /**
     * 会话内可播放的候选（Agent 点歌卡的数据来源）。
     *
     * <p>数据只来自会话已存的业务引用（真实工具结果），不做模型文本解析。
     */
    @GetMapping("/candidates")
    public List<PlaybackService.Candidate> candidates(
            @RequestParam String conversationId
    ) {
        String userId = accessGuard.requireUser();
        return playbackService.candidates(userId, conversationId);
    }

    /**
     * 上报当前曲目（客户端在真实 playing/pause/ended 事件后调用）。
     */
    @PostMapping("/now-playing")
    public PlaybackService.NowPlayingView updateNowPlaying(@RequestBody NowPlayingRequest body) {
        String userId = accessGuard.requireUser();

        if (body == null || body.songId() == null || body.songId() <= 0) {
            throw new BusinessException(400, "INVALID_SONG_ID", "songId 必须是正整数");
        }

        return playbackService.updateNowPlaying(userId, body.songId(), body.state());
    }

    /**
     * 当前曲目（没有则 204）。
     *
     * <p>用 204 而不是 200 + null：前端据此区分"没有当前曲目"和"接口返回了空对象"。
     */
    @GetMapping("/now-playing")
    public ResponseEntity<PlaybackService.NowPlayingView> nowPlaying() {
        String userId = accessGuard.requireUser();
        return playbackService.nowPlaying(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 媒体交付：支持 Range，用于进度拖拽与 seek。
     *
     * <p>不使用 {@code ResourceRegion} + 消息转换器：
     * 手写区间写出更直观（状态码、Content-Range、Content-Length 都在一处），
     * 也便于用真实 HTTP 断言 206 / 416。
     */
    @GetMapping("/media/{token}")
    public void media(
            @PathVariable String token,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {

        Optional<PlaybackSigner.Claims> claims = signer.verify(token);

        if (claims.isEmpty()) {
            /*
             * 统一 403 + 一句人话：过期与签名不符不区分，
             * 否则可以拿返回码探测"这个 songId 是否存在"。
             */
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    "MEDIA_TOKEN_INVALID", "媒体地址已失效，请重新点击播放");
            return;
        }

        long songId = claims.get().songId();
        String userId = claims.get().userId();

        Optional<TrackSource> maybeSource = sourceRepository.findBestSource(songId);
        if (maybeSource.isEmpty() || !maybeSource.get().accessibleBy(userId)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    "TRACK_FORBIDDEN", "无权播放该曲目");
            return;
        }

        Optional<MediaFileStore.MediaHandle> maybeHandle = mediaStore.open(maybeSource.get());
        if (maybeHandle.isEmpty()) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND,
                    "MEDIA_MISSING", "音源文件不可用");
            return;
        }

        MediaFileStore.MediaHandle handle = maybeHandle.get();
        long length = handle.length();

        /*
         * 这几个头必须在写 body 之前设置：一旦开始写，响应就提交了。
         * no-store：短期地址 + 私有内容，不让中间层缓存。
         */
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentType(handle.contentType());

        List<HttpRange> ranges = parseRanges(rangeHeader);

        if (ranges == null) {
            // Range 头格式非法：按 RFC 7233 回 416，并告知当前总长度
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
            return;
        }

        if (ranges.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLengthLong(length);

            if (!"HEAD".equalsIgnoreCase(request.getMethod())) {
                try (InputStream in = handle.resource().getInputStream()) {
                    copy(in, response.getOutputStream(), length);
                }
            }
            return;
        }

        /*
         * 多段 Range（bytes=0-1,5-6）浏览器播放器不会发，也不值得实现：
         * 只服务第一段，其余忽略。返回单段是合法的（RFC 允许服务器简化）。
         */
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(length);
        long end = range.getRangeEnd(length);

        if (start >= length || start > end) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
            return;
        }

        long count = end - start + 1;

        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
        response.setContentLengthLong(count);

        if ("HEAD".equalsIgnoreCase(request.getMethod())) {
            return;
        }

        try (InputStream in = handle.resource().getInputStream()) {
            skipFully(in, start);
            copy(in, response.getOutputStream(), count);
        }
    }

    /**
     * 解析 Range 头。
     *
     * @return 空列表表示"没有 Range 或 Range 为空"，返回 null 表示格式非法
     */
    private static List<HttpRange> parseRanges(String rangeHeader) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return List.of();
        }
        try {
            return HttpRange.parseRanges(rangeHeader);
        } catch (IllegalArgumentException e) {
            log.debug("非法的 Range 头：{}", rangeHeader);
            return null;
        }
    }

    /**
     * 跳过前 start 字节。
     *
     * <p>{@code InputStream.skip} 允许返回小于请求值（尤其网络流），
     * 这里必须循环，否则会从错误的偏移开始播——听起来像"拖进度条拖不准"。
     */
    private static void skipFully(InputStream in, long start) throws IOException {
        long remaining = start;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                // 兜底：skip 不动时用 read 推进，避免死循环
                if (in.read() < 0) {
                    throw new IOException("媒体流提前结束");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static void copy(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = count;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        out.flush();
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
        response.getWriter().flush();
    }

    /** 点播请求体。刻意只有两个字段：地址不能由客户端提供。 */
    public record ResolveRequest(Long songId, Long playlistId) {
    }

    /** 当前曲目上报。 */
    public record NowPlayingRequest(Long songId, String state) {
    }
}
```

### 2.3 `PlaybackService.java`

```java
package com.example.mkagent.playback;

import com.example.mkagent.conversation.ConversationRefs;
import com.example.mkagent.conversation.ConversationService;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.music.MusicCatalogRepository;
import com.example.mkagent.music.MusicCatalogRepository.SongView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 播放解析：把"曲目 ID"变成"能不能播、怎么播"。
 *
 * <p>职责边界（参考 Twilight Echo 的分层，但不移植原生音频引擎）：
 * <ul>
 *   <li><b>音源解析</b>（本类）：曲目 → 音源映射 → 交付方式与短期地址；</li>
 *   <li><b>播放控制</b>（前端）：播放/暂停/进度/上一首下一首/队列；</li>
 *   <li><b>音频处理</b>（前端 Web Audio）：前级增益 + 三段 EQ + 旁路；</li>
 *   <li><b>实际输出</b>（浏览器）：解码与声卡输出。</li>
 * </ul>
 * 服务端不做解码、不做转码、不代理第三方流——本轮也不引入实时转码。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>用户身份只来自服务端上下文（{@code X-User-Id}），不接受请求体里的 userId；</li>
 *   <li>音源地址只由服务端签发，前端**不能**自带任意 URL 或对象路径；</li>
 *   <li>没有映射 / 映射不可用 / 无权播放，都返回**明确的不可播原因**，
 *       而不是一个空地址让播放器报"未知错误"。</li>
 * </ol>
 */
@Service
public class PlaybackService {

    private static final Logger log = LoggerFactory.getLogger(PlaybackService.class);

    /** 「正在播放」状态：客户端在真实事件后上报，服务端复核。 */
    public static final String STATE_PLAYING = "PLAYING";
    public static final String STATE_PAUSED = "PAUSED";
    public static final String STATE_STOPPED = "STOPPED";

    /** 播放日志的来源标记：区分"播放器真实播放"与将来的其他入口。 */
    private static final String PLAY_SOURCE_PLAYER = "PLAYER";

    private final MusicCatalogRepository catalog;

    private final TrackSourceRepository sources;

    private final MediaFileStore mediaStore;

    private final PlaybackSigner signer;

    private final ConversationService conversationService;

    /**
     * 应用 context-path（默认 /api）。
     *
     * <p>签名地址必须是"浏览器可直接 GET 的完整路径"，
     * 而前端代码里已经带 /api 前缀。这里从配置读，避免在业务代码里写死。
     */
    private final String contextPath;

    public PlaybackService(
            MusicCatalogRepository catalog,
            TrackSourceRepository sources,
            MediaFileStore mediaStore,
            PlaybackSigner signer,
            ConversationService conversationService,
            @Value("${server.servlet.context-path:}") String contextPath
    ) {
        this.catalog = catalog;
        this.sources = sources;
        this.mediaStore = mediaStore;
        this.signer = signer;
        this.conversationService = conversationService;
        this.contextPath = contextPath == null ? "" : contextPath;
    }

    /**
     * 解析一首歌能不能播。
     *
     * @param playlistId 可选：从某个歌单里点播时传入，用于校验歌单归属
     *                   （歌单是私有的，拿别人的歌单 ID 不该能点播其中的歌）
     */
    public ResolveResult resolve(String userId, long songId, Long playlistId) {
        SongView song = catalog.findSong(songId);
        if (song == null) {
            throw new BusinessException(404, "SONG_NOT_FOUND", "曲目不存在：" + songId);
        }

        if (playlistId != null) {
            requireOwnedPlaylist(userId, playlistId);
        }

        Optional<TrackSource> maybeSource = sources.findBestSource(songId);

        if (maybeSource.isEmpty()) {
            /*
             * 种子曲目（1001~1060）没有音源映射：它们继续可查询、可进歌单，
             * 但必须**明确不可播**，不能拿测试音冒充。
             */
            return ResolveResult.unavailable(
                    song,
                    "NO_SOURCE",
                    "这首曲子暂无可用音源（曲库只提供元数据，未收录音频）"
            );
        }

        TrackSource source = maybeSource.get();

        if (!source.accessibleBy(userId)) {
            /*
             * 存在映射但属于别人：这是**权限**问题，不是"不可播"。
             * 用 403 与"暂无音源"区分开，页面才能给出正确提示。
             */
            throw new BusinessException(403, "TRACK_FORBIDDEN", "无权播放该曲目：" + songId);
        }

        return switch (source.mode()) {
            case DIRECT_AUDIO -> resolveDirectAudio(song, source, userId);
            case UNAVAILABLE -> ResolveResult.unavailable(
                    song, "SOURCE_UNAVAILABLE", "该音源已被标记为不可用");
            case PROVIDER_SDK, EXTERNAL_LINK -> ResolveResult.unavailable(
                    song,
                    "PROVIDER_NOT_CONNECTED",
                    "该来源需要官方 SDK 授权，当前未接入（不代理第三方流）"
            );
        };
    }

    private ResolveResult resolveDirectAudio(SongView song, TrackSource source, String userId) {
        Optional<MediaFileStore.MediaHandle> handle = mediaStore.open(source);

        if (handle.isEmpty()) {
            /*
             * 映射在、文件不在：这是配置错误，必须让页面显示"音源不可用"，
             * 而不是让播放器拿到 404 后报一个笼统的网络错误。
             */
            log.warn("音源映射存在但媒体不可读：songId={}, key={}", song.id(), source.objectKey());
            return ResolveResult.unavailable(
                    song, "MEDIA_MISSING", "音源文件不可用，请联系管理员检查媒体目录");
        }

        /*
         * 签名必须绑定**当前请求的用户**，不是映射的 owner：
         * 媒体地址一旦泄露，只有同一个用户能继续用它读文件。
         */
        PlaybackSigner.IssuedToken token = signer.issue(song.id(), userId);

        return new ResolveResult(
                true,
                SongInfo.of(song),
                PlaybackMode.DIRECT_AUDIO.name(),
                source.provider(),
                source.format(),
                handle.get().contentType(),
                contextPath + "/ai/music/playback/media/" + token.token(),
                token.expiresAtEpochSecond(),
                /*
                 * seek / range 由服务端**确实实现**的能力决定（见 PlaybackMediaController），
                 * eq 只对受控来源开放：第三方流不承诺可处理。
                 */
                new Capabilities(true, true, source.processable()),
                null,
                null
        );
    }

    /**
     * 只回答"能不能播"，不签发地址。
     *
     * <p>实现上直接复用 {@link #resolve}：宁可多签一个立刻丢弃的令牌，
     * 也不复制一份权限判定——两处判定一旦漂移，就会出现
     * "解析说能播、点播 403"这种最难排查的不一致。
     */
    public Playability checkPlayable(String userId, long songId) {
        try {
            ResolveResult result = resolve(userId, songId, null);
            return new Playability(
                    result.playable(), result.format(), result.reasonCode(), result.reason());
        } catch (BusinessException e) {
            return new Playability(false, null, e.getCode(), e.getMessage());
        }
    }

    /**
     * 歌单归属校验：与页面「我的歌单」同一口径（只认本人歌单）。
     */
    private void requireOwnedPlaylist(String userId, long playlistId) {
        boolean owned = catalog.findUserPlaylists(userId).stream()
                .anyMatch(view -> view.id() != null && view.id() == playlistId);

        if (!owned) {
            throw new BusinessException(
                    404, "PLAYLIST_NOT_FOUND", "歌单不存在或无权查看：" + playlistId);
        }
    }

    /**
     * 会话内可播放的候选（Agent 点歌卡的数据来源）。
     *
     * <p>只从**会话已存的业务引用**（{@link ConversationRefs}，由真实工具结果写入）
     * 取曲目，不从模型回答里解析 ID——模型可以写错，ID 必须来自数据库。
     *
     * <p>每首都要重新做一次可播性与权限判定：
     * 引用是历史快照，音源可能已被撤下，用户也可能换了。
     */
    public List<Candidate> candidates(String userId, String conversationId) {
        ConversationService.ConversationRow row =
                conversationService.requireOwned(userId, conversationId);

        ConversationRefs refs = ConversationRefs.fromJson(row.refsJson());
        if (refs.songs().isEmpty()) {
            return List.of();
        }

        List<Long> ids = refs.songs().stream().map(ConversationRefs.SongRef::id).toList();
        Map<Long, TrackSource> bestSources = sources.findBestSources(ids);

        List<Candidate> result = new ArrayList<>();
        for (ConversationRefs.SongRef ref : refs.songs()) {
            SongView song = catalog.findSong(ref.id());
            if (song == null) {
                continue;
            }

            TrackSource source = bestSources.get(ref.id());
            boolean playable = source != null
                    && source.mode() == PlaybackMode.DIRECT_AUDIO
                    && source.accessibleBy(userId)
                    && mediaStore.open(source).isPresent();

            result.add(new Candidate(
                    song.id(),
                    song.title(),
                    song.artist(),
                    song.album(),
                    song.durationSeconds(),
                    playable,
                    playable ? source.format() : null,
                    playable ? null : unavailableReason(source, userId)
            ));
        }
        return result;
    }

    private String unavailableReason(TrackSource source, String userId) {
        if (source == null) {
            return "暂无可用音源";
        }
        if (!source.accessibleBy(userId)) {
            return "无权播放该曲目";
        }
        if (source.mode() != PlaybackMode.DIRECT_AUDIO) {
            return "该来源未接入";
        }
        return "音源文件不可用";
    }

    /**
     * 上报「当前曲目」。
     *
     * <p>客户端只在**真实 playing 事件**之后调用，服务端仍然要复核：
     * 1. 曲目存在；2. 音源存在且可播；3. 当前用户有权限。
     * 否则前端可以声称任意歌曲"正在播放"，把越权曲目塞进歌单。
     *
     * @param state PLAYING / PAUSED / STOPPED
     */
    public NowPlayingView updateNowPlaying(String userId, long songId, String state) {
        String normalized = normalizeState(state);

        SongView song = catalog.findSong(songId);
        if (song == null) {
            throw new BusinessException(404, "SONG_NOT_FOUND", "曲目不存在：" + songId);
        }

        Optional<TrackSource> maybeSource = sources.findBestSource(songId);
        if (maybeSource.isEmpty() || !maybeSource.get().accessibleBy(userId)) {
            throw new BusinessException(403, "TRACK_FORBIDDEN", "无权播放该曲目：" + songId);
        }

        Optional<NowPlayingView> previous = nowPlaying(userId);

        sources.saveNowPlaying(userId, songId, normalized);

        /*
         * 只在"真的开始播放"时写播放日志：
         * PAUSED / STOPPED 不是一次播放；同一首歌反复暂停继续也不该刷日志。
         */
        boolean startedPlaying = STATE_PLAYING.equals(normalized)
                && previous.map(p -> p.songId() != songId || !STATE_PLAYING.equals(p.state()))
                .orElse(true);

        if (startedPlaying) {
            sources.logPlay(userId, songId, PLAY_SOURCE_PLAYER);
        }

        return new NowPlayingView(
                song.id(), song.title(), song.artist(), normalized,
                Instant.now().getEpochSecond()
        );
    }

    /**
     * 当前曲目（服务端可确认的播放事实）。
     *
     * <p>STOPPED 视为"没有当前曲目"：用户已经停了，再说"正在播放"就是假话。
     */
    public Optional<NowPlayingView> nowPlaying(String userId) {
        return sources.findNowPlaying(userId)
                .filter(row -> !STATE_STOPPED.equals(row.state()))
                .flatMap(row -> {
                    SongView song = catalog.findSong(row.songId());
                    if (song == null) {
                        return Optional.empty();
                    }
                    return Optional.of(new NowPlayingView(
                            song.id(), song.title(), song.artist(),
                            row.state(),
                            row.updatedAt() == null ? 0 : row.updatedAt().getEpochSecond()
                    ));
                });
    }

    private static String normalizeState(String state) {
        if (state == null) {
            return STATE_PAUSED;
        }
        String upper = state.trim().toUpperCase();
        return switch (upper) {
            case STATE_PLAYING -> STATE_PLAYING;
            case STATE_PAUSED -> STATE_PAUSED;
            case STATE_STOPPED -> STATE_STOPPED;
            default -> throw new BusinessException(
                    400, "INVALID_PLAYBACK_STATE", "未知的播放状态：" + state);
        };
    }

    /** 解析结果。 */
    public record ResolveResult(
            boolean playable,
            SongInfo song,
            String mode,
            String provider,
            String format,
            String contentType,
            /** 短期媒体地址（相对路径，含 context-path）；不可播时为 null */
            String streamUrl,
            /** 地址过期时间（epoch 秒）；不可播时为 null */
            Long expiresAt,
            Capabilities capabilities,
            /** 不可播原因码（稳定，前端只认这个）；可播时为 null */
            String reasonCode,
            /** 不可播的人话说明；可播时为 null */
            String reason
    ) {

        static ResolveResult unavailable(SongView song, String code, String reason) {
            return new ResolveResult(
                    false, SongInfo.of(song), PlaybackMode.UNAVAILABLE.name(),
                    null, null, null, null, null,
                    new Capabilities(false, false, false),
                    code, reason
            );
        }
    }

    /** 曲目信息（播放器显示用，不含任何内部路径）。 */
    public record SongInfo(
            long id,
            String title,
            String artist,
            String album,
            Integer durationSeconds,
            Integer releaseYear
    ) {
        static SongInfo of(SongView view) {
            return new SongInfo(
                    view.id(), view.title(), view.artist(), view.album(),
                    view.durationSeconds(), view.releaseYear());
        }
    }

    /** 播放器能力：只描述**这个来源**确认支持的能力，不做营销式声明。 */
    public record Capabilities(boolean seek, boolean range, boolean eq) {
    }

    /** 会话内候选曲目。 */
    public record Candidate(
            long songId,
            String title,
            String artist,
            String album,
            Integer durationSeconds,
            boolean playable,
            String format,
            String unavailableReason
    ) {
    }

    /** 当前曲目。 */
    public record NowPlayingView(
            long songId,
            String title,
            String artist,
            String state,
            long updatedAtEpochSecond
    ) {
    }

    /** "能不能播"的结论（不含地址，供工具层使用）。 */
    public record Playability(
            boolean playable,
            String format,
            String reasonCode,
            String reason
    ) {
    }
}
```

### 2.4 `MediaFileStore.java`

```java
package com.example.mkagent.playback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 把 {@code object_key} 解析成可读取的媒体资源。
 *
 * <p>两种位置，按配置选择：
 * <ul>
 *   <li>配置了 {@code mkagent.playback.media-dir} → 该目录下的文件
 *       （本地演示把测试音放在这里，或将来挂载自有音频）；</li>
 *   <li>未配置 → classpath 下的 {@code media/}（随 jar 打包的测试夹具）。</li>
 * </ul>
 *
 * <p>为什么不用 {@code src/main/resources/static/}：
 * 那是 Spring Boot 默认的公开静态目录，放进去等于任何人拿到 URL 就能下载，
 * 与"服务端鉴权后签短期地址"直接冲突。媒体放在 {@code media/} 下，
 * 只能经 {@code /ai/music/playback/media/{token}} 读。
 *
 * <p>路径校验是硬要求：{@code object_key} 来自数据库（当前由种子写入），
 * 但一旦将来有管理入口，它就是外部可控字符串。
 * 这里只接受"字母数字 + 点 + 横线 + 下划线 + 斜杠"的相对路径，
 * 且规范化后必须仍在根目录内——{@code ../} 一律拒绝。
 */
@Component
public class MediaFileStore {

    private static final Logger log = LoggerFactory.getLogger(MediaFileStore.class);

    /**
     * 允许的字符集：不含反斜杠、冒号、空格，天然排除 Windows 盘符与 URL 协议前缀。
     */
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9._/-]{1,255}$");

    /**
     * 只允许音频类型：即使映射表被写脏，也不会让签名地址变成 HTML/脚本的出口。
     */
    private static final Pattern SAFE_CONTENT_TYPE = Pattern.compile("^audio/[A-Za-z0-9.+-]{1,40}$");

    private final Path mediaRoot;

    public MediaFileStore(@Value("${mkagent.playback.media-dir:}") String mediaDir) {
        this.mediaRoot = (mediaDir == null || mediaDir.isBlank())
                ? null
                : Paths.get(mediaDir).toAbsolutePath().normalize();
    }

    /**
     * 解析媒体文件。
     *
     * @return 文件存在且类型合法时返回句柄；否则 empty（调用方给出"不可播"原因）
     */
    public Optional<MediaHandle> open(TrackSource source) {
        if (source == null || source.objectKey() == null || source.objectKey().isBlank()) {
            return Optional.empty();
        }

        String key = source.objectKey().trim();
        if (!SAFE_KEY.matcher(key).matches() || key.contains("..")) {
            log.warn("拒绝非法的媒体定位：songId={}, key={}", source.songId(), key);
            return Optional.empty();
        }

        String contentType = source.contentType() == null ? "" : source.contentType().trim();
        if (!SAFE_CONTENT_TYPE.matcher(contentType).matches()) {
            log.warn("拒绝非音频 Content-Type：songId={}, type={}", source.songId(), contentType);
            return Optional.empty();
        }

        Resource resource = mediaRoot == null
                ? new ClassPathResource("media/" + key)
                : fileResource(key);

        if (resource == null || !resource.exists()) {
            log.warn("媒体文件不存在：songId={}, key={}, root={}",
                    source.songId(), key, mediaRoot == null ? "classpath:media/" : mediaRoot);
            return Optional.empty();
        }

        try {
            long length = resource.contentLength();
            if (length <= 0) {
                return Optional.empty();
            }
            return Optional.of(new MediaHandle(resource, length, contentType));
        } catch (Exception e) {
            log.warn("读取媒体长度失败：songId={}, reason={}",
                    source.songId(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 文件模式下额外确认"规范化后仍在根目录内"。
     *
     * <p>正则已经挡住了 {@code ..}，这里是第二道：符号链接可以绕过纯字符串检查。
     */
    private Resource fileResource(String key) {
        Path candidate = mediaRoot.resolve(key).normalize();
        if (!candidate.startsWith(mediaRoot)) {
            return null;
        }
        if (!Files.isRegularFile(candidate)) {
            return null;
        }
        return new FileSystemResource(candidate);
    }

    /** 已解析的媒体文件。 */
    public record MediaHandle(Resource resource, long length, String contentType) {
    }
}
```

### 2.5 `web/src/stores/player.ts`

```ts
/**
 * 播放器状态（全局单例）。
 *
 * 为什么状态放在 store 而不是组件里：
 * 1. 「切会话不断播」的前提是**播放器不随会话组件卸载**——
 *    音频元素由本模块创建并持有，组件只读状态、只发指令；
 * 2. 全站只能有一个 `<audio>`：两个元素同时出声是这类页面最常见的 bug；
 * 3. 状态机（loading / playing / paused / ended / error）只在事件回调里推进，
 *    组件不"猜"当前是否在播。
 *
 * 三个容易出错的地方，这里都显式处理：
 * - **快速切歌**：每次 load 递增 `loadSeq`，迟到的解析结果直接丢弃；
 * - **自动播放被拦**：`play()` 的 NotAllowedError 单独成一个状态
 *   （`autoplayBlocked`），界面给"点击播放"入口，而不是报"播放失败"；
 * - **地址过期**：媒体地址是短期的，过期后一次重新解析即可继续，
 *   不该把用户踢回"播放失败"。
 */
import { computed, reactive } from 'vue'
import { apiUrl } from '@/api/client'
import { ApiError, describeError } from '@/api/errors'
import { reportNowPlaying, resolvePlayback } from '@/api/playback'
import {
  createEqualizer,
  FLAT_EQ,
  type EqSettings,
  type Equalizer,
} from '@/audio/equalizer'

/** 队列里的一首。解析成功后才会补齐 streamUrl / format 等字段。 */
export interface PlayerTrack {
  songId: number
  title: string
  artist: string
  album?: string | null
  durationSeconds?: number | null
  /** 解析后填：音源格式（只展示已知格式，不推断音质） */
  format?: string | null
  mode?: string | null
  streamUrl?: string | null
  /** 地址过期时间（epoch 秒） */
  expiresAt?: number | null
  /** 该来源是否允许调音（服务端 capabilities.eq） */
  eqAvailable?: boolean
}

export type PlayerStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'ended' | 'error'

export interface PlayerError {
  /** 稳定原因码：NO_SOURCE / TRACK_FORBIDDEN / MEDIA_MISSING / MEDIA_ERROR / NETWORK */
  code: string
  message: string
}

/**
 * 对 `<audio>` 的最小抽象：只为了能在 jsdom 里测状态机。
 *
 * jsdom 的 HTMLMediaElement 不实现 play()，直接依赖它会让"快速切歌"
 * 这类关键行为无法回归。真实环境注入 `new Audio()`。
 */
export interface AudioLike {
  src: string
  currentTime: number
  duration: number
  paused: boolean
  volume: number
  muted: boolean
  play(): Promise<void>
  pause(): void
  load(): void
  addEventListener(type: string, listener: () => void): void
  removeEventListener(type: string, listener: () => void): void
}

export interface PlayerDeps {
  createAudio: () => AudioLike
  resolve: typeof resolvePlayback
  report: typeof reportNowPlaying
  createEq?: () => Equalizer | null
}

interface PlayerState {
  queue: PlayerTrack[]
  index: number
  status: PlayerStatus
  currentTime: number
  duration: number
  error: PlayerError | null
  /** 浏览器拦截了自动播放：需要用户点一下 */
  autoplayBlocked: boolean
  volume: number
  muted: boolean
  /** 环境是否支持 Web Audio（不支持则整个 EQ 入口禁用） */
  eqSupported: boolean
  eq: EqSettings & { enabled: boolean }
}

const DEFAULT_VOLUME = 0.85

function initialState(): PlayerState {
  return {
    queue: [],
    index: -1,
    status: 'idle',
    currentTime: 0,
    duration: 0,
    error: null,
    autoplayBlocked: false,
    volume: DEFAULT_VOLUME,
    muted: false,
    eqSupported: false,
    eq: { ...FLAT_EQ, enabled: false },
  }
}

export function createPlayer(deps: PlayerDeps) {
  const state = reactive<PlayerState>(initialState())

  const audio = deps.createAudio()
  audio.volume = DEFAULT_VOLUME

  const eq: Equalizer | null = (deps.createEq ?? createEqualizer)()
  state.eqSupported = !!eq

  /** 每次 load 递增；迟到的解析结果按这个值丢弃 */
  let loadSeq = 0
  /** 正在为"地址过期"做一次自动重解析，避免错误事件风暴 */
  let refreshing = false
  /** 已经上报过的 (songId, state)，避免重复请求 */
  let lastReported = ''

  const current = computed<PlayerTrack | null>(() => state.queue[state.index] ?? null)
  const hasTrack = computed(() => !!current.value)
  const isPlaying = computed(() => state.status === 'playing')
  const canSeek = computed(() => !!current.value?.streamUrl && state.duration > 0)

  /** 当前曲目是否允许调音：环境支持 + 服务端对该来源确认可处理 */
  const eqAvailable = computed(
    () => state.eqSupported && !!current.value?.eqAvailable,
  )

  /* ==================== 事件 ==================== */

  function onPlaying(): void {
    if (!current.value) {
      return
    }
    state.status = 'playing'
    state.autoplayBlocked = false
    state.error = null
    report(current.value.songId, 'PLAYING')
  }

  function onPause(): void {
    // 切歌 / 结束也会触发 pause：只有"本来在播"才视为用户暂停
    if (state.status === 'playing') {
      state.status = 'paused'
      if (current.value) {
        report(current.value.songId, 'PAUSED')
      }
    }
  }

  function onEnded(): void {
    if (!current.value) {
      return
    }
    if (state.index + 1 < state.queue.length) {
      void load(state.index + 1, true)
      return
    }
    state.status = 'ended'
    state.currentTime = state.duration
    report(current.value.songId, 'STOPPED')
  }

  function onTimeUpdate(): void {
    state.currentTime = Number.isFinite(audio.currentTime) ? audio.currentTime : 0
    if (Number.isFinite(audio.duration) && audio.duration > 0) {
      state.duration = audio.duration
    }
  }

  function onLoadedMetadata(): void {
    if (Number.isFinite(audio.duration) && audio.duration > 0) {
      state.duration = audio.duration
    }
  }

  function onMediaError(): void {
    const track = current.value
    if (!track) {
      return
    }

    /*
     * 过期是最常见的"播放中突然报错"：短地址到期后浏览器再请求就是 403。
     * 这里自动重新解析一次（保留进度），而不是让用户手动重试。
     */
    if (isExpired(track) && !refreshing) {
      void refreshSource(track)
      return
    }

    state.status = 'error'
    state.error = {
      code: 'MEDIA_ERROR',
      message: '音频加载失败：可能是媒体地址已失效或文件不可用。',
    }
  }

  audio.addEventListener('playing', onPlaying)
  audio.addEventListener('pause', onPause)
  audio.addEventListener('ended', onEnded)
  audio.addEventListener('timeupdate', onTimeUpdate)
  audio.addEventListener('loadedmetadata', onLoadedMetadata)
  audio.addEventListener('error', onMediaError)

  /* ==================== 内部 ==================== */

  function isExpired(track: PlayerTrack): boolean {
    return !!track.expiresAt && Date.now() / 1000 >= track.expiresAt - 5
  }

  function report(songId: number, nextState: 'PLAYING' | 'PAUSED' | 'STOPPED'): void {
    const key = `${songId}:${nextState}`
    if (key === lastReported) {
      return
    }
    lastReported = key

    /*
     * 上报失败不影响播放：它只服务"把正在播放的歌加入歌单"这条链路，
     * 用户听歌这件事不应该因为一次统计请求失败而中断。
     */
    void deps.report(songId, nextState).catch(() => undefined)
  }

  async function refreshSource(track: PlayerTrack): Promise<void> {
    refreshing = true
    const resumeAt = state.currentTime
    const wasPlaying = state.status === 'playing'

    try {
      const resolved = await deps.resolve(track.songId)
      if (!resolved.playable || !resolved.streamUrl) {
        state.status = 'error'
        state.error = {
          code: resolved.reasonCode ?? 'MEDIA_UNAVAILABLE',
          message: resolved.reason ?? '音源不可用',
        }
        return
      }

      applyResolution(track, resolved)
      audio.src = apiUrl(resolved.streamUrl)
      audio.load()
      audio.currentTime = resumeAt

      if (wasPlaying) {
        await playAudio()
      }
    } catch (error) {
      state.status = 'error'
      state.error = {
        code: 'MEDIA_ERROR',
        message: describeError(error),
      }
    } finally {
      refreshing = false
    }
  }

  function applyResolution(
    track: PlayerTrack,
    resolved: Awaited<ReturnType<typeof resolvePlayback>>,
  ): void {
    track.streamUrl = resolved.streamUrl
    track.format = resolved.format
    track.mode = resolved.mode
    track.expiresAt = resolved.expiresAt
    track.eqAvailable = resolved.capabilities.eq
    track.title = resolved.song.title || track.title
    track.artist = resolved.song.artist || track.artist
    if (resolved.song.durationSeconds) {
      track.durationSeconds = resolved.song.durationSeconds
    }
  }

  async function playAudio(): Promise<void> {
    try {
      eq?.resume()
      await audio.play()
    } catch (error) {
      const name = (error as { name?: string })?.name

      if (name === 'NotAllowedError') {
        /*
         * 浏览器的自动播放策略：必须有用户手势。
         * 这不是错误，是"需要你点一下"，所以单独一个状态。
         */
        state.autoplayBlocked = true
        state.status = 'paused'
        return
      }

      state.status = 'error'
      state.error = {
        code: 'MEDIA_ERROR',
        message: '无法开始播放：浏览器拒绝了这次播放请求。',
      }
    }
  }

  async function load(index: number, autoplay: boolean): Promise<void> {
    const track = state.queue[index]
    if (!track) {
      return
    }

    const seq = ++loadSeq

    state.index = index
    state.status = 'loading'
    state.error = null
    state.currentTime = 0
    state.duration = track.durationSeconds ?? 0
    lastReported = ''

    // 先把上一首停掉：否则两首歌会短暂叠在一起
    audio.pause()

    try {
      const resolved = await deps.resolve(track.songId)

      // 迟到的结果：用户已经切到别的歌了，丢弃
      if (seq !== loadSeq) {
        return
      }

      if (!resolved.playable || !resolved.streamUrl) {
        state.status = 'error'
        state.error = {
          code: resolved.reasonCode ?? 'NOT_PLAYABLE',
          message: resolved.reason ?? '这首曲子暂时不能播放',
        }
        return
      }

      applyResolution(track, resolved)

      // 第一次真正播放时才接入处理图：AudioContext 需要用户手势
      eq?.attach(audio as unknown as HTMLAudioElement)
      applyEqSettings()

      audio.src = apiUrl(resolved.streamUrl)
      audio.load()

      if (autoplay) {
        await playAudio()
      }
    } catch (error) {
      if (seq !== loadSeq) {
        return
      }

      state.status = 'error'
      state.error = mapResolveError(error)
    }
  }

  function mapResolveError(error: unknown): PlayerError {
    if (error instanceof ApiError) {
      if (error.kind === 'forbidden') {
        return { code: 'TRACK_FORBIDDEN', message: '无权播放这首曲目。' }
      }
      if (error.kind === 'not_found') {
        return { code: 'SONG_NOT_FOUND', message: '曲目不存在或已下架。' }
      }
      return { code: 'RESOLVE_FAILED', message: describeError(error) }
    }
    return { code: 'NETWORK', message: '网络异常，无法解析播放地址。' }
  }

  function applyEqSettings(): void {
    if (!eq) {
      return
    }
    const enabled = state.eq.enabled && !!current.value?.eqAvailable
    eq.apply(state.eq, !enabled)
  }

  /* ==================== 对外动作 ==================== */

  async function playQueue(tracks: PlayerTrack[], startIndex = 0): Promise<void> {
    if (!tracks.length) {
      return
    }
    const index = Math.min(Math.max(startIndex, 0), tracks.length - 1)
    state.queue = tracks.map((track) => ({ ...track }))
    await load(index, true)
  }

  async function toggle(): Promise<void> {
    const track = current.value
    if (!track) {
      return
    }

    if (state.status === 'playing') {
      audio.pause()
      state.status = 'paused'
      report(track.songId, 'PAUSED')
      return
    }

    if (state.autoplayBlocked) {
      state.autoplayBlocked = false
    }

    if (!track.streamUrl) {
      // 解析失败 / 还没解析：重新走一次
      await load(state.index, true)
      return
    }

    if (state.status === 'ended') {
      audio.currentTime = 0
      state.currentTime = 0
    }

    await playAudio()
  }

  /** 手动下一首：最后一首之后回到第一首 */
  async function next(): Promise<void> {
    if (!state.queue.length) {
      return
    }
    const nextIndex = (state.index + 1) % state.queue.length
    await load(nextIndex, true)
  }

  /** 上一首：播过 3 秒先回到本曲开头（与常见播放器一致） */
  async function previous(): Promise<void> {
    if (!state.queue.length) {
      return
    }
    if (state.currentTime > 3) {
      seek(0)
      return
    }
    const prevIndex = (state.index - 1 + state.queue.length) % state.queue.length
    await load(prevIndex, true)
  }

  function seek(seconds: number): void {
    if (!current.value?.streamUrl) {
      return
    }
    const target = Math.max(0, Math.min(seconds, state.duration || seconds))
    try {
      audio.currentTime = target
      state.currentTime = target
    } catch {
      // 某些浏览器在 metadata 之前会抛 InvalidStateError：忽略即可
    }
  }

  /** 跳到队列中的某一首 */
  async function jumpTo(index: number): Promise<void> {
    if (index < 0 || index >= state.queue.length) {
      return
    }
    await load(index, true)
  }

  /** 停止并清空队列（用户主动"关闭播放器"） */
  function stop(): void {
    const track = current.value
    loadSeq += 1
    audio.pause()
    audio.src = ''
    state.queue = []
    state.index = -1
    state.status = 'idle'
    state.currentTime = 0
    state.duration = 0
    state.error = null
    state.autoplayBlocked = false
    if (track) {
      report(track.songId, 'STOPPED')
    }
  }

  function setVolume(value: number): void {
    const volume = Math.max(0, Math.min(1, value))
    state.volume = volume
    audio.volume = volume
    if (volume > 0 && state.muted) {
      state.muted = false
      audio.muted = false
    }
  }

  function toggleMute(): void {
    state.muted = !state.muted
    audio.muted = state.muted
  }

  /** 更新 EQ 参数并立即生效（旁路由 enabled 控制） */
  function setEq(patch: Partial<EqSettings & { enabled: boolean }>): void {
    if (!eqAvailable.value && patch.enabled !== false) {
      // 当前来源不支持调音：不允许把开关打开，避免"看起来在调、实际没生效"
      state.eq = { ...state.eq, ...patch, enabled: false }
      applyEqSettings()
      return
    }
    state.eq = { ...state.eq, ...patch }
    applyEqSettings()
  }

  /** 一键旁路 / 恢复 */
  function toggleEqBypass(): void {
    setEq({ enabled: !state.eq.enabled })
  }

  function resetEq(): void {
    state.eq = { ...FLAT_EQ, enabled: state.eq.enabled && eqAvailable.value }
    applyEqSettings()
  }

  /** 仅供测试/退出时清理 */
  function dispose(): void {
    audio.removeEventListener('playing', onPlaying)
    audio.removeEventListener('pause', onPause)
    audio.removeEventListener('ended', onEnded)
    audio.removeEventListener('timeupdate', onTimeUpdate)
    audio.removeEventListener('loadedmetadata', onLoadedMetadata)
    audio.removeEventListener('error', onMediaError)
    eq?.dispose()
  }

  return {
    state,
    current,
    hasTrack,
    isPlaying,
    canSeek,
    eqAvailable,
    playQueue,
    playTrack: (track: PlayerTrack) => playQueue([track], 0),
    toggle,
    next,
    previous,
    seek,
    jumpTo,
    stop,
    setVolume,
    toggleMute,
    setEq,
    toggleEqBypass,
    resetEq,
    dispose,
    /** 测试可见：底层音频元素（真实环境就是页面里唯一的 <audio>） */
    audioElement: audio,
  }
}

export type Player = ReturnType<typeof createPlayer>

/**
 * 全站唯一的播放器实例。
 *
 * 音频元素在模块加载时就创建（`new Audio()` 不依赖 DOM 挂载），
 * 因此切会话、切路由都不会中断播放。
 */
export const player: Player = createPlayer({
  createAudio: () => new Audio() as unknown as AudioLike,
  resolve: resolvePlayback,
  report: reportNowPlaying,
})

/*
 * 把音频元素挂进 DOM（无 controls、不参与布局）。
 *
 * 为什么挂进去：
 * 1. 元素不挂在文档上时，某些浏览器在页面切到后台会回收媒体资源；
 * 2. 调试与端到端验收需要一个稳定入口（`[data-testid="mk-audio"]`）
 *    读取 currentTime / paused，否则只能靠"听起来像在播"。
 * 它仍然只有一个，仍然不属于任何组件，卸载界面不会带走它。
 */
if (typeof document !== 'undefined') {
  const element = player.audioElement as unknown as HTMLAudioElement
  if (element && typeof element.setAttribute === 'function') {
    element.setAttribute('data-testid', 'mk-audio')
    element.setAttribute('preload', 'metadata')
    element.style.display = 'none'
    document.body.appendChild(element)
  }
}
```

### 2.6 `web/src/audio/equalizer.ts`

```ts
/**
 * 最小 EQ：前级增益 + 三段（低架 / 峰值 / 高架）+ 一键旁路。
 *
 * 只做**受控来源**（服务端 `capabilities.eq = true`，即自有音频）。
 * 第三方流一律不开放——不是技术做不到，而是没有授权也没有稳定的格式承诺，
 * 界面上标一个能调但实际不生效的 EQ，比不做更糟。
 *
 * 三条实现约束：
 * 1. `MediaElementAudioSourceNode` 对同一个 `<audio>` **只能创建一次**，
 *    重复创建会抛 InvalidStateError，所以这里用 `attached` 守住；
 * 2. `AudioContext` 必须在用户手势之后才允许出声，
 *    因此创建后立即 `resume()`，并在每次 play 时再 `resume()` 一次；
 * 3. 旁路是**真的断开重连**，不是把三个增益设成 0：
 *    后者仍然过一遍滤波器，相位会有细微变化，称不上旁路。
 *
 * 不声称 Bit-Perfect / DAC 独占 / 自动升频：浏览器链路做不到，
 * 这些词只会变成无法验证的营销话术。
 */
export interface EqSettings {
  /** 前级增益（dB），负值用于防削波 */
  preamp: number
  /** 低频搁架（200 Hz）增益（dB） */
  low: number
  /** 中频峰值（1 kHz, Q=0.9）增益（dB） */
  mid: number
  /** 高频搁架（4 kHz）增益（dB） */
  high: number
}

export const FLAT_EQ: EqSettings = { preamp: 0, low: 0, mid: 0, high: 0 }

export interface Equalizer {
  /** 把某个 `<audio>` 接进处理图；重复调用只生效一次 */
  attach(element: HTMLAudioElement): boolean
  apply(settings: EqSettings, bypass: boolean): void
  resume(): void
  dispose(): void
}

type AudioContextCtor = new () => AudioContext

function audioContextCtor(): AudioContextCtor | null {
  const w = window as unknown as {
    AudioContext?: AudioContextCtor
    webkitAudioContext?: AudioContextCtor
  }
  return w.AudioContext ?? w.webkitAudioContext ?? null
}

/**
 * @returns 环境不支持 Web Audio 时返回 null（调用方据此禁用 EQ 入口）
 */
export function createEqualizer(): Equalizer | null {
  const Ctor = audioContextCtor()
  if (!Ctor) {
    return null
  }
  // 单独取一个非空引用：闭包里不会保留外层 if 的收窄结果
  const Context = Ctor

  let ctx: AudioContext | null = null
  let source: MediaElementAudioSourceNode | null = null
  let preamp: GainNode | null = null
  let low: BiquadFilterNode | null = null
  let mid: BiquadFilterNode | null = null
  let high: BiquadFilterNode | null = null
  let bypassed = false

  function build(element: HTMLAudioElement): boolean {
    try {
      ctx = new Context()
      source = ctx.createMediaElementSource(element)

      preamp = ctx.createGain()
      low = ctx.createBiquadFilter()
      low.type = 'lowshelf'
      low.frequency.value = 200

      mid = ctx.createBiquadFilter()
      mid.type = 'peaking'
      mid.frequency.value = 1000
      mid.Q.value = 0.9

      high = ctx.createBiquadFilter()
      high.type = 'highshelf'
      high.frequency.value = 4000

      source.connect(preamp)
      preamp.connect(low)
      low.connect(mid)
      mid.connect(high)
      high.connect(ctx.destination)

      return true
    } catch {
      // 例如同一个元素被重复接入，或环境不支持：回退为直通播放
      ctx = null
      source = null
      return false
    }
  }

  return {
    attach(element: HTMLAudioElement): boolean {
      if (source) {
        return true
      }
      return build(element)
    },

    apply(settings: EqSettings, bypass: boolean): void {
      if (!ctx || !source || !preamp || !low || !mid || !high) {
        return
      }

      // 旁路：断开处理链，直接送到输出
      if (bypass !== bypassed) {
        bypassed = bypass
        source.disconnect()
        if (bypass) {
          source.connect(ctx.destination)
        } else {
          source.connect(preamp)
        }
      }

      preamp.gain.value = dbToLinear(settings.preamp)
      low.gain.value = settings.low
      mid.gain.value = settings.mid
      high.gain.value = settings.high
    },

    resume(): void {
      if (ctx && ctx.state === 'suspended') {
        void ctx.resume()
      }
    },

    dispose(): void {
      try {
        source?.disconnect()
      } catch {
        // 已断开时忽略
      }
      void ctx?.close()
      ctx = null
      source = null
    },
  }
}

function dbToLinear(db: number): number {
  return Math.pow(10, db / 20)
}
```

### 2.7 其余新增源码（读上面 6 个会用到）

`PlaybackMode`、`TrackSource`、`TrackSourceRepository`、`PlaybackFixtureInitializer`、`PlaybackTool`：

```java
package com.example.mkagent.playback;

/**
 * 音源交付方式。
 *
 * <p>四种模式是**表达能力**，不是"都已经接通"的清单：
 * 本轮只真实实现 {@link #DIRECT_AUDIO}（服务端鉴权后签名短期地址 + Range 读取）。
 * 另外三种先占位，接口能如实返回它们，但没有任何一条映射使用它们——
 * 没有真实授权联调之前，不宣称已接入官方 SDK。
 *
 * <ul>
 *   <li>{@link #DIRECT_AUDIO}：自有/受控音频文件，服务端按 Range 交付；</li>
 *   <li>{@link #PROVIDER_SDK}：将来由官方 SDK 换取播放凭证（未实现）；</li>
 *   <li>{@link #EXTERNAL_LINK}：跳转到外部页面播放（未实现，不代理第三方流）；</li>
 *   <li>{@link #UNAVAILABLE}：明确不可播，必须给出原因。</li>
 * </ul>
 */
public enum PlaybackMode {

    DIRECT_AUDIO,

    PROVIDER_SDK,

    EXTERNAL_LINK,

    UNAVAILABLE;

    /**
     * 解析数据库里的字符串；未知值按 {@link #UNAVAILABLE} 处理（失败关闭）。
     *
     * <p>不让未知值抛异常：一条脏映射不应该让整个解析接口 500，
     * 它只应该让"这首歌"不可播。
     */
    public static PlaybackMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNAVAILABLE;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNAVAILABLE;
        }
    }

    /**
     * 这种模式是否可能真的出声（用于候选列表的初筛）。
     */
    public boolean isDeliverable() {
        return this == DIRECT_AUDIO || this == PROVIDER_SDK || this == EXTERNAL_LINK;
    }
}
```

```java
package com.example.mkagent.playback;

/**
 * 一条"曲目 → 音源"映射（{@code music_track_source} 的一行）。
 *
 * <p>{@code objectKey} 是**服务端内部标识**：它是媒体目录（或 classpath）下的相对路径，
 * 只用于本地读取。它绝不进入任何接口响应——对外只给签名后的短期地址，
 * 否则前端拿到路径就能绕过鉴权直接猜文件。
 *
 * @param songId       曲目 ID（沿用 music_song 的主键，不新建曲库）
 * @param provider     音源提供方标识（例如 {@code local-test-fixture}）
 * @param mode         交付方式
 * @param format       已知音源格式（例如 {@code WAV}），只用于如实展示
 * @param objectKey    服务端内部定位
 * @param contentType  实际响应的 Content-Type
 * @param ownerUserId  非空表示仅该用户可播；为空表示所有已开通用户可播
 * @param processable  是否允许客户端做音频处理（EQ）
 * @param testAudio    是否为技术夹具测试音
 */
public record TrackSource(
        long songId,
        String provider,
        PlaybackMode mode,
        String format,
        String objectKey,
        String contentType,
        String ownerUserId,
        boolean processable,
        boolean testAudio
) {

    /**
     * 当前用户能否播这条映射。
     *
     * <p>空 owner 表示公共可播；非空必须**完全相等**——
     * 不做前缀匹配、不忽略大小写，避免 {@code music-user-1} 与
     * {@code music-user-10} 互相串号。
     */
    public boolean accessibleBy(String userId) {
        return ownerUserId == null || ownerUserId.isBlank() || ownerUserId.equals(userId);
    }
}
```

```java
package com.example.mkagent.playback;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 音源映射与「正在播放」的读写。
 *
 * <p>查询全部走 JdbcTemplate（与 {@code MusicCatalogRepository} 同一分工）：
 * 联表 / IN 查询用 JdbcTemplate 更直观，也便于在 H2 的 PostgreSQL 兼容模式下验证。
 *
 * <p>三条约束：
 * <ol>
 *   <li>只返回映射，不返回文件路径给调用方以外的地方；</li>
 *   <li>按 {@code mode} 优先级挑选（DIRECT_AUDIO 优先），
 *       一首歌有多条映射时行为可预测；</li>
 *   <li>「正在播放」写入用 UPDATE→INSERT 两段，不用 MERGE：
 *       MERGE 的方言在 H2 与 PostgreSQL 之间不一致，而这条写入没有并发压力
 *       （一个用户只有一个播放器）。</li>
 * </ol>
 */
@Repository
public class TrackSourceRepository {

    private static final RowMapper<TrackSource> SOURCE_MAPPER = (rs, rowNum) ->
            new TrackSource(
                    rs.getLong("song_id"),
                    rs.getString("provider"),
                    PlaybackMode.parse(rs.getString("mode")),
                    rs.getString("format"),
                    rs.getString("object_key"),
                    rs.getString("content_type"),
                    rs.getString("owner_user_id"),
                    rs.getBoolean("processable"),
                    rs.getBoolean("is_test_audio")
            );

    private final JdbcTemplate jdbcTemplate;

    public TrackSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 某首歌的音源映射，按"越能直接交付越靠前"排序后取第一条。
     *
     * <p>不存在时返回 empty——调用方据此给出"明确不可播"而不是 500。
     */
    public Optional<TrackSource> findBestSource(long songId) {
        List<TrackSource> sources = jdbcTemplate.query(
                "SELECT song_id, provider, mode, format, object_key, content_type, "
                        + "       owner_user_id, processable, is_test_audio "
                        + "FROM music_track_source WHERE song_id = ?",
                SOURCE_MAPPER,
                songId
        );

        return sources.stream().min((a, b) -> Integer.compare(rank(a), rank(b)));
    }

    /**
     * 批量取映射（候选列表用），返回 songId → 最佳映射。
     *
     * <p>空集合直接返回空 Map：调用方不必先判空，也避免拼出 `IN ()` 这种非法 SQL。
     */
    public Map<Long, TrackSource> findBestSources(Collection<Long> songIds) {
        if (songIds == null || songIds.isEmpty()) {
            return Map.of();
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(songIds.size(), "?"));
        List<TrackSource> rows = jdbcTemplate.query(
                "SELECT song_id, provider, mode, format, object_key, content_type, "
                        + "       owner_user_id, processable, is_test_audio "
                        + "FROM music_track_source WHERE song_id IN (" + placeholders + ")",
                SOURCE_MAPPER,
                songIds.toArray()
        );

        Map<Long, TrackSource> best = new HashMap<>();
        for (TrackSource source : rows) {
            best.merge(source.songId(), source,
                    (a, b) -> rank(a) <= rank(b) ? a : b);
        }
        return best;
    }

    /**
     * 记录/覆盖"当前曲目"。
     *
     * <p>先 UPDATE 再按影响行数决定是否 INSERT：
     * 相比 upsert，这条路径在任何方言下行为一致，也不会在并发下报唯一键冲突
     * （重复 INSERT 会失败，但我们紧接着把它当成"已存在"处理）。
     */
    public void saveNowPlaying(String userId, long songId, String state) {
        int updated = jdbcTemplate.update(
                "UPDATE music_now_playing SET song_id = ?, state = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE user_id = ?",
                songId, state, userId
        );

        if (updated > 0) {
            return;
        }

        try {
            jdbcTemplate.update(
                    "INSERT INTO music_now_playing (user_id, song_id, state, updated_at) "
                            + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                    userId, songId, state
            );
        } catch (org.springframework.dao.DuplicateKeyException race) {
            // 并发下另一个请求刚插入：再 UPDATE 一次即可，不算失败
            jdbcTemplate.update(
                    "UPDATE music_now_playing SET song_id = ?, state = ?, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE user_id = ?",
                    songId, state, userId
            );
        }
    }

    /**
     * 当前曲目；从未播放过返回 empty。
     */
    public Optional<NowPlaying> findNowPlaying(String userId) {
        List<NowPlaying> rows = jdbcTemplate.query(
                "SELECT song_id, state, updated_at FROM music_now_playing WHERE user_id = ?",
                (rs, rowNum) -> new NowPlaying(
                        rs.getLong("song_id"),
                        rs.getString("state"),
                        rs.getTimestamp("updated_at") == null
                                ? null
                                : rs.getTimestamp("updated_at").toInstant()
                ),
                userId
        );
        return rows.stream().findFirst();
    }

    /**
     * 写一条播放日志（真实播放后才写，用于后台聚合）。
     */
    public void logPlay(String userId, long songId, String source) {
        jdbcTemplate.update(
                "INSERT INTO music_play_log (user_id, song_id, source, created_at) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                userId, songId, source
        );
    }

    /**
     * 交付能力排序：越小越优先。
     *
     * <p>DIRECT_AUDIO 是本地可验证的路径，优先；
     * PROVIDER_SDK / EXTERNAL_LINK 未实现，排在后面；
     * UNAVAILABLE 永远最后，保证"有可用源就不返回不可用"。
     */
    private static int rank(TrackSource source) {
        return switch (source.mode()) {
            case DIRECT_AUDIO -> 0;
            case PROVIDER_SDK -> 1;
            case EXTERNAL_LINK -> 2;
            case UNAVAILABLE -> 3;
        };
    }

    /** 「当前曲目」的一行。 */
    public record NowPlaying(long songId, String state, java.time.Instant updatedAt) {
    }
}
```

```java
package com.example.mkagent.playback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 播放夹具：把"测试音"作为**独立曲目**写进曲库，并建立音源映射。
 *
 * <p>为什么不直接给种子歌曲（1001~1060）挂测试音：
 * 那等于把《夜航星》标成可播，而实际听到的是一段正弦音阶——
 * 用户会以为"播放器坏了/音质很差"，评审也无法区分"音源没接"和"接错了"。
 * 所以测试音有自己的 ID、自己的标题（"测试音 A/B"）、自己的流派
 * （{@code TEST_FIXTURE}），一眼能看出它是技术夹具，不是歌曲。
 *
 * <p>排在 {@code MusicSeedInitializer} 之后（{@code @Order(100)}）：
 * 后者用"曲库是否非空"之外的判据判断是否初始化，这里不依赖顺序，
 * 但显式排序能让"夹具写进了一个还没建用户的库"这种困惑不再出现。
 *
 * <p>幂等：按固定 ID 逐条判断，已存在就跳过。
 * 不复用 {@code MusicSeedInitializer} 的"曲库非空即跳过"判据——
 * 那个判据会让**已经初始化过的** demo 库永远拿不到这批新数据。
 *
 * <p>同时插入一条"仅另一位用户可播"的曲目（1903）：
 * 越权拒绝必须有真实数据可测，否则只能靠 mock，测不出
 * "验签通过但归属不符"这条路径。
 */
@Component
@Profile("!prod")
@Order(100)
public class PlaybackFixtureInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlaybackFixtureInitializer.class);

    /** 测试音 A：A 大调琶音（听感偏高、偏亮）。 */
    public static final long SONG_TEST_A = 1901L;

    /** 测试音 B：低八度小调动机（听感偏低、偏慢）。 */
    public static final long SONG_TEST_B = 1902L;

    /** 测试音 C：只允许 music-user-2 播放，用于验证越权拒绝。 */
    public static final long SONG_RESTRICTED = 1903L;

    /** 受限曲目的 owner（与 MusicSeedInitializer.USER_BOB 一致）。 */
    public static final String RESTRICTED_OWNER = "music-user-2";

    private static final String PROVIDER = "local-test-fixture";

    private static final int DURATION_SECONDS = 10;

    private final JdbcTemplate jdbcTemplate;

    public PlaybackFixtureInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int inserted = 0;

        inserted += ensureSong(SONG_TEST_A, "测试音 A（琶音）", "A 大调琶音，10 秒");
        inserted += ensureSong(SONG_TEST_B, "测试音 B（低音）", "低八度小调动机，10 秒");
        inserted += ensureSong(SONG_RESTRICTED, "测试音 C（受限）", "仅 music-user-2 可播，用于越权校验");

        inserted += ensureSource(SONG_TEST_A, "test-tone-a.wav", null);
        inserted += ensureSource(SONG_TEST_B, "test-tone-b.wav", null);
        inserted += ensureSource(SONG_RESTRICTED, "test-tone-b.wav", RESTRICTED_OWNER);

        if (inserted > 0) {
            log.info("播放夹具已写入：{} 条（测试音 A/B 可播，测试音 C 仅 {} 可播）",
                    inserted, RESTRICTED_OWNER);
        }
    }

    private int ensureSong(long id, String title, String album) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_song WHERE id = ?", Integer.class, id);

        if (existing != null && existing > 0) {
            return 0;
        }

        jdbcTemplate.update(
                "INSERT INTO music_song (id, title, artist, album, duration_seconds, genre, release_year) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id,
                title,
                "MkAgent Test Fixtures",
                album,
                DURATION_SECONDS,
                "TEST_FIXTURE",
                2026
        );
        return 1;
    }

    private int ensureSource(long songId, String objectKey, String ownerUserId) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_track_source WHERE song_id = ? AND provider = ?",
                Integer.class, songId, PROVIDER);

        if (existing != null && existing > 0) {
            return 0;
        }

        jdbcTemplate.update(
                "INSERT INTO music_track_source "
                        + "(song_id, provider, mode, format, object_key, content_type, "
                        + " owner_user_id, processable, is_test_audio, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                songId,
                PROVIDER,
                PlaybackMode.DIRECT_AUDIO.name(),
                "WAV",
                objectKey,
                "audio/wav",
                ownerUserId,
                // 自有受控音频才允许客户端做 EQ；第三方流一律 false
                true,
                true
        );
        return 1;
    }

    /** 供测试引用：三个夹具曲目的 ID（顺序固定）。 */
    public static List<Long> fixtureSongIds() {
        return List.of(SONG_TEST_A, SONG_TEST_B, SONG_RESTRICTED);
    }
}
```

```java
package com.example.mkagent.tools;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.playback.PlaybackService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 播放相关的**只读**工具。
 *
 * <p>两个工具，解决两件模型不该猜的事：
 * <ul>
 *   <li>{@code check_playback}：这首到底能不能播（有没有音源、是不是别人的）。</li>
 *   <li>{@code get_now_playing}：用户"正在播放的那首歌"是哪首——
 *       这是服务端可确认的播放事实，不是模型从上下文里猜的。</li>
 * </ul>
 *
 * <p>三条边界：
 * <ol>
 *   <li><b>不返回任何地址</b>：签名 URL 只给浏览器，模型消息里不出现地址、
 *       凭据或"已开始播放"这类无法验证的结论；</li>
 *   <li><b>不播放</b>：出声是页面的事。工具只能说"可播"，不能说"已播放"；</li>
 *   <li><b>userId 不是参数</b>：身份取自 {@link AgentRunContextHolder}，
 *       与曲库工具同一套失败关闭策略。</li>
 * </ol>
 *
 * <p>为什么"正在播放"必须走服务端：客户端上报时会经过可播性与归属复核
 * （见 {@code PlaybackService#updateNowPlaying}）。如果让模型或前端直接说
 * "正在播放的是 1001"，就等于绕开曲目权限，把任意歌塞进歌单。
 */
@Component
public class PlaybackTool {

    private final PlaybackService playbackService;

    public PlaybackTool(PlaybackService playbackService) {
        this.playbackService = playbackService;
    }

    @Tool(
            name = "check_playback",
            description = """
                    确认一首歌当前能否在播放器里播放。

                    当用户说"播放这首""放一下某某"时，先检索到歌曲，
                    再用本工具确认是否可播，然后把结论告诉用户：
                    可播 → 提示点击页面下方的播放卡；不可播 → 说明原因
                    （没有音源 / 无权播放），不要编造"已经播放"。

                    不返回任何播放地址，也不代表已经开始播放。
                    """
    )
    public String checkPlayback(
            @ToolParam(description = "曲目 ID（来自 search_songs / get_playlist_songs 等工具的真实结果）")
            Long songId
    ) {
        AgentRunContextHolder.requireUserId();

        if (songId == null || songId <= 0) {
            return ToolResponseEnvelope.error("songId 必须是正整数");
        }

        PlaybackService.Playability playability = playbackService.checkPlayable(
                AgentRunContextHolder.requireUserId(), songId);

        if (!playability.playable()) {
            return ToolResponseEnvelope.error(
                    playability.reason() == null ? "这首曲目暂时不能播放" : playability.reason());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("songId", songId);
        data.put("playable", true);
        data.put("format", playability.format());
        data.put("hint", "可播放。播放由页面完成，请提示用户点击播放卡，不要说“已经播放”。");

        return ToolResponseEnvelope.okSingle(data);
    }

    @Tool(
            name = "get_now_playing",
            description = """
                    读取用户当前正在播放（或暂停中）的曲目。

                    当用户说"把正在播放的这首加入歌单""这首加进去"时，
                    必须先调用本工具拿到真实曲目，再用返回的 songId 去调用写工具。
                    不要用历史对话里的歌曲猜测"正在播放"的是哪首。

                    没有当前曲目时返回失败结果，此时应当询问用户先播放一首。
                    """
    )
    public String getNowPlaying() {
        String userId = AgentRunContextHolder.requireUserId();

        Optional<PlaybackService.NowPlayingView> nowPlaying = playbackService.nowPlaying(userId);

        if (nowPlaying.isEmpty()) {
            return ToolResponseEnvelope.error(
                    "当前没有正在播放的曲目。请让用户先在页面播放器里播放一首，再试。");
        }

        PlaybackService.NowPlayingView view = nowPlaying.get();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("songId", view.songId());
        data.put("title", view.title());
        data.put("artist", view.artist());
        data.put("state", view.state());

        return ToolResponseEnvelope.okSingle(data);
    }
}
```

---

## 3. 修改文件的 diff

### 3.1 `src/main/resources/db/schema.sql`

> 注意分界：`agent_conversation` / `agent_conversation_message` / `agent_run.conversation_id` 属于**上一轮**会话层，与本轮无关；本轮只有 `music_track_source` 与 `music_now_playing` 两张表。

```diff
diff --git a/src/main/resources/db/schema.sql b/src/main/resources/db/schema.sql
index 95b0215..71a5c01 100644
--- a/src/main/resources/db/schema.sql
+++ b/src/main/resources/db/schema.sql
@@ -326,6 +326,133 @@ ALTER TABLE music_tool_idempotency ADD COLUMN IF NOT EXISTS tool_call_id TEXT;
 ALTER TABLE music_tool_idempotency
     ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
 
+-- =============================================================
+-- 会话（同一窗口连续聊天）
+--
+-- 三个 ID 的职责严格分开，不能互相顶替：
+--   conversation_id —— 会话：一个窗口里连续的多轮；
+--   run_id          —— 一次用户提交（一轮）；每次提交都新建，**绝不复活**终态 run；
+--   execution_epoch —— 某个 run 的执行代次：审批恢复时 +1，只用于防旧线程迟到写入。
+--
+-- agent_run.conversation_id 可空：历史独立任务保持 NULL，
+-- 不强迫把旧记录迁移成会话。
+-- =============================================================
+CREATE TABLE IF NOT EXISTS agent_conversation
+(
+    id              BIGINT       NOT NULL,
+    conversation_id VARCHAR(64)  NOT NULL,
+    user_id         VARCHAR(64)  NOT NULL,
+    title           VARCHAR(200),
+    refs_json       TEXT,
+    last_run_id     VARCHAR(64),
+    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
+    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
+    CONSTRAINT pk_agent_conversation PRIMARY KEY (id)
+);
+
+CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_conversation_id
+    ON agent_conversation (conversation_id);
+
+CREATE INDEX IF NOT EXISTS idx_agent_conversation_user
+    ON agent_conversation (user_id, updated_at);
+
+-- 会话消息：只存用户输入与模型最终回答的**文本**。
+--
+-- 不存工具调用与工具原始结果：
+-- 1. 回放旧工具调用会让模型把历史指令当新指令重新执行；
+-- 2. 工具结果里的 ID/名称另有 refs_json 承载（见下），
+--    不需要把整段 JSON 塞回上下文。
+--
+-- (run_id, role) 唯一：同一个 run 的助手回答只留一条，
+-- 审批恢复重写时是更新而不是追加。
+CREATE TABLE IF NOT EXISTS agent_conversation_message
+(
+    id              BIGINT       NOT NULL,
+    conversation_id VARCHAR(64)  NOT NULL,
+    run_id          VARCHAR(64)  NOT NULL,
+    role            VARCHAR(16)  NOT NULL,
+    content         TEXT,
+    state           VARCHAR(32),
+    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
+    CONSTRAINT pk_agent_conversation_message PRIMARY KEY (id)
+);
+
+CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_conversation_message_run_role
+    ON agent_conversation_message (run_id, role);
+
+CREATE INDEX IF NOT EXISTS idx_agent_conversation_message_conv
+    ON agent_conversation_message (conversation_id, id);
+
+-- 存量 agent_run 增量升级：会话归属（幂等，可重复执行）
+ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(64);
+
+CREATE INDEX IF NOT EXISTS idx_agent_run_conversation
+    ON agent_run (conversation_id, state);
+
+COMMENT ON TABLE agent_conversation IS '会话：一个窗口里的连续多轮（与 run 一对多）';
+COMMENT ON COLUMN agent_conversation.refs_json IS '会话级业务引用（最近搜索结果 / 歌单），只由真实工具结果写入';
+COMMENT ON COLUMN agent_conversation.last_run_id IS '最近一次提交对应的 run（便于定位，不代表"当前活动 run"）';
+COMMENT ON TABLE agent_conversation_message IS '会话消息：只存用户输入与模型最终回答文本，不存工具调用';
+COMMENT ON COLUMN agent_conversation_message.state IS '该轮 run 的终态快照（FAILED/CANCELLED 等也要如实展示）';
+COMMENT ON COLUMN agent_run.conversation_id IS '所属会话；历史独立任务为 NULL';
+
+-- =============================================================
+-- 播放（MVP）：曲目 → 音源映射
+--
+-- 为什么单独一张表而不是给 music_song 加 url 列：
+--   1. 曲目是"内容元数据"，音源是"交付方式"——两者生命周期不同
+--      （同一首歌将来可能同时有自有音频、官方 SDK、外链三种来源）；
+--   2. 没有映射的曲目必须继续可查询、明确不可播，而不是"有 url 但为空"
+--      这种无法区分"没配"和"配错了"的状态；
+--   3. 播放模式要能表达 DIRECT_AUDIO / PROVIDER_SDK / EXTERNAL_LINK /
+--      UNAVAILABLE 四种，未来加 SDK 不需要改曲库表。
+--
+-- 主键 (song_id, provider)：一首歌在同一 provider 下只有一条映射，
+-- 但可以有多条（例如自有音频 + 未来接入的官方 SDK），解析时按优先级挑选。
+--
+-- object_key 是**服务端内部标识**（classpath 相对路径或媒体目录下的相对路径），
+-- 永远不出现在接口响应里；对外只给带签名的短期地址。
+-- owner_user_id 非空表示"仅该用户可播"（用于验证越权拒绝）；
+-- 为空表示所有已开通音乐服务的用户都可播。
+-- =============================================================
+CREATE TABLE IF NOT EXISTS music_track_source
+(
+    song_id       BIGINT       NOT NULL,
+    provider      VARCHAR(32)  NOT NULL,
+    mode          VARCHAR(24)  NOT NULL,
+    format        VARCHAR(24)  NOT NULL,
+    object_key    VARCHAR(255) NOT NULL,
+    content_type  VARCHAR(64)  NOT NULL,
+    owner_user_id VARCHAR(64),
+    processable   BOOLEAN      NOT NULL DEFAULT FALSE,
+    is_test_audio BOOLEAN      NOT NULL DEFAULT FALSE,
+    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
+    CONSTRAINT pk_music_track_source PRIMARY KEY (song_id, provider)
+);
+
+-- 「正在播放」：客户端在真实 playing 事件后上报，服务端复核可播性再落库。
+--
+-- 为什么不在请求里直接信任前端传的 songId：
+-- "把正在播放的歌加入歌单"这条链路必须以**服务端能确认的播放事实**为前提，
+-- 否则前端可以声称任意歌曲正在播放，等于绕过曲目访问校验。
+-- 一个用户只有一行（切换即覆盖），state 区分 PLAYING / PAUSED / STOPPED。
+CREATE TABLE IF NOT EXISTS music_now_playing
+(
+    user_id    VARCHAR(64) NOT NULL,
+    song_id    BIGINT      NOT NULL,
+    state      VARCHAR(16) NOT NULL,
+    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
+    CONSTRAINT pk_music_now_playing PRIMARY KEY (user_id)
+);
+
+COMMENT ON TABLE music_track_source IS '曲目音源映射（缺失即不可播；object_key 不对前端暴露）';
+COMMENT ON COLUMN music_track_source.mode IS 'DIRECT_AUDIO / PROVIDER_SDK / EXTERNAL_LINK / UNAVAILABLE';
+COMMENT ON COLUMN music_track_source.object_key IS '服务端内部定位（媒体目录相对路径），不对外返回';
+COMMENT ON COLUMN music_track_source.processable IS '是否允许客户端做音频处理（EQ）；仅受控来源为 TRUE';
+COMMENT ON COLUMN music_track_source.is_test_audio IS '是否为技术夹具测试音（非歌曲）';
+COMMENT ON COLUMN music_track_source.owner_user_id IS '非空表示仅该用户可播；空表示所有已开通用户可播';
+COMMENT ON TABLE music_now_playing IS '每个用户当前曲目（由客户端 playing 事件上报，服务端复核）';
+
 COMMENT ON TABLE music_song IS '曲库（虚构种子数据，不含真实版权内容）';
 COMMENT ON COLUMN music_song.duration_seconds IS '时长（秒）';
 COMMENT ON COLUMN music_song.release_year IS '发行年份';
```

### 3.2 `src/main/resources/application.yml`

```diff
diff --git a/src/main/resources/application.yml b/src/main/resources/application.yml
index 5fac961..47b2827 100644
--- a/src/main/resources/application.yml
+++ b/src/main/resources/application.yml
@@ -140,6 +140,32 @@ mkagent:
       # -------------------------------------------------------------
       write-enabled: false
 
+  # ===========================================================
+  # 播放（MVP）：音源解析的服务端配置。
+  #
+  # 服务端只做三件事：曲目 → 音源映射、权限校验、签发短期媒体地址。
+  # 不做解码、不做转码、不代理第三方流——出声发生在浏览器里。
+  # 因此这一节没有"音质""输出设备"之类参数，那些不属于服务端职责。
+  # ===========================================================
+  playback:
+    # 媒体地址的 HMAC-SHA256 签名密钥。
+    #
+    # 留空 = 每次启动生成一个进程内随机密钥：
+    #   · 本地 / demo 不配置也能跑；
+    #   · 重启后旧地址全部失效（前端会自动重新解析，表现为一次短暂重载）；
+    #   · 多实例部署必须显式配置**同一个值**，否则 A 实例签发的地址
+    #     在 B 实例上验签失败（403），表现为"播放随机失败"。
+    # 生产请用环境变量注入，不要写进仓库。
+    signing-secret: ${MKAGENT_PLAYBACK_SIGNING_SECRET:}
+    # 媒体地址有效期（秒）。合法范围 30 ~ 3600，越界回退 300。
+    # 短是刻意的：地址会出现在浏览器 devtools 和 <audio> 的 src 里，
+    # 泄露后也只能被**同一个用户**在很短时间内继续使用（签名绑定 userId）。
+    url-ttl-seconds: 300
+    # 可选：媒体根目录。
+    # 留空 → 从 classpath:media/ 读（内置测试音走这条，开箱即用）；
+    # 配置后 → object_key 按该目录解析，并校验解析结果仍在目录内（防 ../ 穿越）。
+    media-dir: ${MKAGENT_PLAYBACK_MEDIA_DIR:}
+
   # ===========================================================
   # 模型分层（OpenAI 兼容接口）。
   #
```

### 3.3 `src/main/java/com/example/mkagent/config/AgentToolProvider.java`

```diff
diff --git a/src/main/java/com/example/mkagent/config/AgentToolProvider.java b/src/main/java/com/example/mkagent/config/AgentToolProvider.java
index de20421..05bb075 100644
--- a/src/main/java/com/example/mkagent/config/AgentToolProvider.java
+++ b/src/main/java/com/example/mkagent/config/AgentToolProvider.java
@@ -6,6 +6,7 @@ import com.example.mkagent.tools.AdminAnalyticsTool;
 import com.example.mkagent.tools.FileOperationTool;
 import com.example.mkagent.tools.MusicPlaylistQueryTool;
 import com.example.mkagent.tools.MusicQueryTool;
+import com.example.mkagent.tools.PlaybackTool;
 import com.example.mkagent.tools.PDFGenerationTool;
 import com.example.mkagent.tools.ResourceDownloadTool;
 import com.example.mkagent.tools.TerminateTool;
@@ -29,7 +30,7 @@ import java.util.Map;
  * FILE       ：read_file、write_file、download_resource、generate_pdf
  * EVAL       ：评测专用，复用 MANUS 的只读工具集
  * MUSIC_USER ：search_songs、get_song_detail、get_user_playlists、
- *              get_playlist_songs、terminate_task
+ *              get_playlist_songs、check_playback、get_now_playing、terminate_task
  * MUSIC_ADMIN：search_songs、get_song_detail、get_play_stats、
  *              get_top_songs、terminate_task（**没有**歌单类工具）
  *
@@ -70,6 +71,7 @@ public class AgentToolProvider {
             PDFGenerationTool pdfGenerationTool,
             MusicQueryTool musicQueryTool,
             MusicPlaylistQueryTool musicPlaylistQueryTool,
+            PlaybackTool playbackTool,
             AdminAnalyticsTool adminAnalyticsTool,
             ToolRetryWrapper toolRetryWrapper
     ) {
@@ -106,6 +108,12 @@ public class AgentToolProvider {
                 AgentType.MUSIC_USER, List.of(
                         musicQueryTool,
                         musicPlaylistQueryTool,
+                        /*
+                         * 播放只读工具（能否播放 / 当前播放曲目）。
+                         * 它们不签发地址、不触发播放，只回答事实，
+                         * 因此与曲库工具同级，不需要审批。
+                         */
+                        playbackTool,
                         terminateTool
                 ),
                 /*
```

### 3.4 `src/main/java/com/example/mkagent/agent/MusicAgent.java`

```diff
diff --git a/src/main/java/com/example/mkagent/agent/MusicAgent.java b/src/main/java/com/example/mkagent/agent/MusicAgent.java
index c2dfb77..40833f4 100644
--- a/src/main/java/com/example/mkagent/agent/MusicAgent.java
+++ b/src/main/java/com/example/mkagent/agent/MusicAgent.java
@@ -78,6 +78,37 @@ public class MusicAgent extends ToolCallAgent {
                - 用户在回答之外看不到别的说明文字。
             """;
 
+    /**
+     * 播放契约（两个普通用户提示词共用，v4/v6 起追加在收尾契约之后）。
+     *
+     * <p>为什么单独成段而不是插进编号列表：
+     * 编号列表的前 6 条是三个入口（含管理员）共用的，
+     * 插一条就得整体重编号，管理员提示词也会跟着变——
+     * 而管理员根本没有播放工具。追加在末尾既不动共用条款，也不误导管理员。
+     *
+     * <p>这段的核心是"**不要替页面宣布结果**"：
+     * 模型无法播放音频，却很容易写出"正在为你播放"这种听起来完成、
+     * 实际什么都没发生的话。播放卡才是唯一可验证的入口。
+     */
+    public static final String PLAYBACK_CONTRACT = """
+
+            关于播放（页面负责出声，你只负责事实）：
+            - 你**不能**直接播放音频；回答里不要出现任何音频地址、签名链接或播放凭证；
+            - 用户要点播时：先用 search_songs 找到曲目；拿不准能否播放就调用 check_playback；
+              然后把结论告诉用户——可播就提示"点击下方播放卡"，不可播就说明原因
+              （没有音源 / 无权播放）。**不得**说"正在播放""已为你播放"这类无法验证的话；
+            - 用户说"正在播放的那首""现在这首"时，必须调用 get_now_playing 取真实曲目；
+              没有当前曲目就如实说明，不要用历史对话里出现过的歌曲代替。
+            """;
+
+    /**
+     * 播放契约的写操作补充（仅写能力启用时追加）。
+     */
+    public static final String PLAYBACK_WRITE_CONTRACT = """
+            - 把当前播放的曲目加入歌单：先 get_now_playing 拿 songId，再调用
+              add_songs_to_playlist，仍然走审批；不得跳过工具直接声称已加入。
+            """;
+
     /**
      * 普通用户入口的系统提示词（**只读**，生产默认使用这一份）。
      *
@@ -108,7 +139,7 @@ public class MusicAgent extends ToolCallAgent {
                用户提出这类请求时，如实说明当前版本不支持，
                不得声称已经完成，也不得承诺稍后执行。
 
-            """ + GROUNDING_CONTRACT + ENDING_CONTRACT;
+            """ + GROUNDING_CONTRACT + ENDING_CONTRACT + PLAYBACK_CONTRACT;
 
     /**
      * 普通用户入口的系统提示词版本标识（只读）。
@@ -116,8 +147,11 @@ public class MusicAgent extends ToolCallAgent {
      * <p>v3：新增第 5 条"检索不到时的边界"（见 {@link #GROUNDING_CONTRACT}），
      * 收尾条款顺延为第 6 条。提示词内容变了，版本必须跟着变，
      * 否则评测报告无法区分"哪次运行用的是哪套约束"。
+     *
+     * <p>v4：追加播放契约（{@link #PLAYBACK_CONTRACT}）——模型不能播放，
+     * 只能说"可播 / 不可播"并让用户点播放卡。
      */
-    public static final String USER_SYSTEM_PROMPT_VERSION = "music-user-readonly/v3";
+    public static final String USER_SYSTEM_PROMPT_VERSION = "music-user-readonly/v4";
 
     /**
      * 普通用户入口的系统提示词（**写能力已启用，必须走审批**）。
@@ -149,20 +183,38 @@ public class MusicAgent extends ToolCallAgent {
 
             4. 你可以创建歌单、向自己的歌单添加歌曲，但写操作**必须经用户批准**：
                - 调用写工具时会自动暂停并等待用户确认，这是正常流程，不是失败；
+               - **请求已经明确时直接调用写工具**（歌曲与目标歌单都能从上下文确定），
+                 由审批卡承担"再确认一次"的职责；不要在聊天里先问一遍"是否执行"，
+                 那会让用户确认两次；
+               - **不要用"正在为你执行/马上为你添加"这类话代替调用工具**：
+                 这类句子会让用户以为已经在做了，而实际上什么都没发生；
+                 要么直接调用工具，要么如实说明还缺什么信息；
                - 用户确认之前，不得声称已经完成，也不得承诺"稍后为你创建"；
                - 用户拒绝时，不得写入、不得换一种说法重试，
                  应如实说明没有创建/添加，并把原因归为用户的拒绝；
                - 只能操作当前用户自己的歌单，不得修改他人的歌单。
                信息不足时（例如没说歌单名、没说放哪些歌），先问清楚再执行。
 
-            """ + GROUNDING_CONTRACT + ENDING_CONTRACT;
+            """ + GROUNDING_CONTRACT + ENDING_CONTRACT + PLAYBACK_CONTRACT
+            + PLAYBACK_WRITE_CONTRACT;
 
     /**
      * 普通用户入口的系统提示词版本标识（写能力 + 审批）。
      *
      * <p>v3：与只读版本同步新增第 5 条边界契约（检索不到时不得用常识补曲目）。
+     *
+     * <p>v4：第 4 条补一句"请求明确时直接调用写工具，不要在聊天里再问一遍"。
+     * 真实模型联调发现：模型会先在文本里问"是否执行"，用户答"是"之后
+     * 才真正调用工具，等于要用户确认两次。审批卡本身就是确认环节。
+     *
+     * <p>v5：再补一条"不要用'正在为你执行'代替调用工具"。联调里模型
+     * 说了"正在为您执行添加操作…"就结束了这一轮，没有发出 tool_call，
+     * 用户看到的是一句假承诺，歌单没有任何变化。
+     *
+     * <p>v6：追加播放契约与"把当前播放曲目加入歌单"的链路
+     * （先 get_now_playing 再走审批），避免模型凭上下文猜"正在播放的是哪首"。
      */
-    public static final String USER_WRITE_SYSTEM_PROMPT_VERSION = "music-user-write-approval/v3";
+    public static final String USER_WRITE_SYSTEM_PROMPT_VERSION = "music-user-write-approval/v6";
 
     /**
      * 管理员入口的系统提示词版本标识。
```

### 3.5 `src/main/java/com/example/mkagent/tools/DefaultToolEventMessageProvider.java`

```diff
diff --git a/src/main/java/com/example/mkagent/tools/DefaultToolEventMessageProvider.java b/src/main/java/com/example/mkagent/tools/DefaultToolEventMessageProvider.java
index ac7b8d3..de3a236 100644
--- a/src/main/java/com/example/mkagent/tools/DefaultToolEventMessageProvider.java
+++ b/src/main/java/com/example/mkagent/tools/DefaultToolEventMessageProvider.java
@@ -50,6 +50,8 @@ public class DefaultToolEventMessageProvider
             Map.entry("get_playlist_songs", "正在读取歌单曲目"),
             Map.entry("get_play_stats", "正在统计播放数据"),
             Map.entry("get_top_songs", "正在统计歌曲排行"),
+            Map.entry("check_playback", "正在确认音源可用性"),
+            Map.entry("get_now_playing", "正在读取当前播放曲目"),
             /*
              * 写工具：文案里**不出现歌单名与歌曲名**。
              * 这些是用户输入，可能含个人信息，也可能被塞进注入文本；
@@ -109,9 +111,60 @@ public class DefaultToolEventMessageProvider
             return musicResultMessage(toolName, result);
         }
 
+        if ("check_playback".equals(toolName)) {
+            return playbackCheckMessage(result);
+        }
+
+        if ("get_now_playing".equals(toolName)) {
+            return nowPlayingMessage(result);
+        }
+
         return RESULT_MESSAGES.getOrDefault(toolName, "工具调用完成");
     }
 
+    /**
+     * "能不能播"的完成文案。
+     *
+     * <p>失败时**不能**显示成"已确认可播放"：模型可能因此说
+     * "已经为你准备好播放"，而实际没有音源。
+     */
+    private String playbackCheckMessage(String rawResult) {
+        JsonNode root = parseEnvelope(rawResult);
+        if (root == null) {
+            return "音源确认完成";
+        }
+        return root.path("ok").asBoolean(false) ? "音源可用" : "该曲目暂时不可播放";
+    }
+
+    /**
+     * "当前播放曲目"的完成文案：只说明有没有，不推送歌名。
+     */
+    private String nowPlayingMessage(String rawResult) {
+        JsonNode root = parseEnvelope(rawResult);
+        if (root == null) {
+            return "当前播放信息读取完成";
+        }
+        return root.path("ok").asBoolean(false) ? "已获取当前播放曲目" : "当前没有播放中的曲目";
+    }
+
+    /**
+     * 解析信封；解析不了返回 null（调用方给通用文案）。
+     */
+    private static JsonNode parseEnvelope(String rawResult) {
+        if (rawResult == null || rawResult.isBlank()) {
+            return null;
+        }
+        try {
+            JsonNode node = OBJECT_MAPPER.readTree(rawResult);
+            if (node != null && node.isTextual()) {
+                node = OBJECT_MAPPER.readTree(node.asText());
+            }
+            return node != null && node.isObject() ? node : null;
+        } catch (Exception e) {
+            return null;
+        }
+    }
+
     /**
      * 音乐只读工具的完成文案：只暴露"条数"，不暴露内容。
      *
```

### 3.6 `src/main/java/com/example/mkagent/music/MusicSeedInitializer.java`

```diff
diff --git a/src/main/java/com/example/mkagent/music/MusicSeedInitializer.java b/src/main/java/com/example/mkagent/music/MusicSeedInitializer.java
index b61328a..dc97249 100644
--- a/src/main/java/com/example/mkagent/music/MusicSeedInitializer.java
+++ b/src/main/java/com/example/mkagent/music/MusicSeedInitializer.java
@@ -5,6 +5,7 @@ import org.slf4j.LoggerFactory;
 import org.springframework.boot.ApplicationArguments;
 import org.springframework.boot.ApplicationRunner;
 import org.springframework.context.annotation.Profile;
+import org.springframework.core.annotation.Order;
 import org.springframework.jdbc.core.JdbcTemplate;
 import org.springframework.jdbc.support.GeneratedKeyHolder;
 import org.springframework.jdbc.support.KeyHolder;
@@ -23,8 +24,11 @@ import java.util.Map;
  * 音乐业务种子数据初始化器。
  *
  * 设计要点：
- * 1. **幂等**：以 music_song 的行数作为"是否已初始化"的判据，
- *    已有数据直接跳过。应用每次启动都会跑，不能重复插入；
+ * 1. **幂等**：以"种子首曲 id=1001 是否存在"作为"是否已初始化"的判据，
+ *    已有数据直接跳过。应用每次启动都会跑，不能重复插入。
+ *    不用"music_song 总行数 > 0"：曲库里可能还有别的初始化器写入的曲目
+ *    （例如播放夹具的测试音），那样会让本初始化器误判为"已完成"，
+ *    从而永远不写用户与歌单；
  * 2. **单事务**：所有表要么全部写入，要么全部回滚。
  *    否则中途失败会留下"有歌没歌单"的半成品，而幂等判据看的是歌曲数，
  *    下次启动会误判为"已初始化"从而永远缺数据；
@@ -39,6 +43,7 @@ import java.util.Map;
  */
 @Component
 @Profile("!prod")
+@Order(0)
 public class MusicSeedInitializer implements ApplicationRunner {
 
     private static final Logger log =
@@ -228,10 +233,10 @@ public class MusicSeedInitializer implements ApplicationRunner {
     @Transactional
     public int seed() {
         Integer existing = jdbcTemplate.queryForObject(
-                "SELECT COUNT(*) FROM music_song", Integer.class);
+                "SELECT COUNT(*) FROM music_song WHERE id = ?", Integer.class, SONG_ID_BASE);
 
         if (existing != null && existing > 0) {
-            log.info("音乐种子数据已存在（music_song={} 行），跳过初始化", existing);
+            log.info("音乐种子数据已存在（music_song 含 id={}），跳过初始化", SONG_ID_BASE);
             return 0;
         }
```

---

## 4. 测试

### 4.1 新增测试类（全文）

五个类，39 条用例：

| 类 | 用例数 | 覆盖什么 |
| --- | --- | --- |
| `PlaybackSignerTest` | 8 | 往返、篡改 songId/userId、过期、换密钥、畸形令牌、用户绑定、TTL 钳制 |
| `PlaybackResolveIntegrationTest` | 10 | 无音源、夹具可播、两首不同、越权 403、owner 可播、404、401、400、别人的歌单、自己的歌单 |
| `PlaybackMediaRangeIntegrationTest` | 7 | 200 全量、206 分段、偏移正确、后缀区间、416、篡改令牌、伪造令牌 |
| `PlaybackNowPlayingIntegrationTest` | 7 | PLAYING 落库 + 日志、PAUSED 保留 / STOPPED 清除、不可播与越权不可声称、非法 state、候选来自真实引用、别人的会话、401 |
| `PlaybackToolTest` | 7 | 可播不带地址、无音源失败、越权失败且不泄露 owner、无当前曲目、有当前曲目、STOPPED 不算、按用户隔离 |

```java
package com.example.mkagent.playback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体地址签名：只测"能不能被伪造/复用"，不测业务。
 *
 * <p>这些断言对应四条真实风险：
 * 1. 改 songId 换别人的歌 → 验签必须失败；
 * 2. 改 userId 换身份 → 验签必须失败；
 * 3. 过期后继续用 → 必须失败（用固定密钥自己签一个过期令牌，不 sleep）；
 * 4. 换密钥后旧地址还能用 → 必须失败（重启/换实例的场景）。
 */
class PlaybackSignerTest {

    private static final String SECRET = "test-secret-for-signer-unit-test";

    private static PlaybackSigner signer(String secret, long ttl) {
        return new PlaybackSigner(secret, ttl);
    }

    @Test
    @DisplayName("签发后能验签，并解出 songId / userId / 过期时间")
    void roundTrip() {
        PlaybackSigner signer = signer(SECRET, 300);

        PlaybackSigner.IssuedToken issued = signer.issue(1901L, "music-user-1");
        PlaybackSigner.Claims claims = signer.verify(issued.token()).orElseThrow();

        assertEquals(1901L, claims.songId());
        assertEquals("music-user-1", claims.userId());
        assertEquals(issued.expiresAtEpochSecond(), claims.expiresAtEpochSecond());
    }

    @Test
    @DisplayName("篡改 payload 里的 songId 会验签失败")
    void tamperedSongIdRejected() {
        PlaybackSigner signer = signer(SECRET, 300);
        String token = signer.issue(1901L, "music-user-1").token();

        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"s\":1903,\"u\":\"music-user-1\",\"e\":" + (Instant.now().getEpochSecond() + 300) + "}")
                        .getBytes(StandardCharsets.UTF_8));

        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertTrue(signer.verify(forged).isEmpty(), "改了 songId 仍然验签通过，说明签名没覆盖 payload");
    }

    @Test
    @DisplayName("篡改 userId 会验签失败")
    void tamperedUserIdRejected() {
        PlaybackSigner signer = signer(SECRET, 300);
        String token = signer.issue(1903L, "music-user-2").token();

        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"s\":1903,\"u\":\"music-user-1\",\"e\":" + (Instant.now().getEpochSecond() + 300) + "}")
                        .getBytes(StandardCharsets.UTF_8));

        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertTrue(signer.verify(forged).isEmpty());
    }

    @Test
    @DisplayName("签名有效但已过期：仍然拒绝")
    void expiredTokenRejected() throws Exception {
        PlaybackSigner signer = signer(SECRET, 300);

        // 用同一个密钥手工签一个"一小时前就过期"的令牌
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"s\":1901,\"u\":\"music-user-1\",\"e\":" + (Instant.now().getEpochSecond() - 3600) + "}")
                        .getBytes(StandardCharsets.UTF_8));

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));

        String token = "v1." + payload + "." + signature;

        assertTrue(signer.verify(token).isEmpty(), "过期令牌必须被拒绝，否则短期地址等于永久地址");
    }

    @Test
    @DisplayName("换密钥后旧地址失效（重启 / 多实例未共享密钥的场景）")
    void tokenFromOtherSecretRejected() {
        String token = signer("secret-a", 300).issue(1901L, "music-user-1").token();

        assertTrue(signer("secret-b", 300).verify(token).isEmpty());
    }

    @Test
    @DisplayName("格式错误 / 空 / 未知版本一律拒绝，且不抛异常")
    void malformedTokensRejected() {
        PlaybackSigner signer = signer(SECRET, 300);

        assertTrue(signer.verify(null).isEmpty());
        assertTrue(signer.verify("").isEmpty());
        assertTrue(signer.verify("garbage").isEmpty());
        assertTrue(signer.verify("v2.a.b").isEmpty());
        assertTrue(signer.verify("v1.!!!.???").isEmpty());
    }

    @Test
    @DisplayName("同一首歌不同用户签出的令牌不同（绑定身份）")
    void tokensAreBoundToUser() {
        PlaybackSigner signer = signer(SECRET, 300);

        String a = signer.issue(1901L, "music-user-1").token();
        String b = signer.issue(1901L, "music-user-2").token();

        assertNotEquals(a, b);
        assertFalse(signer.verify(a).orElseThrow().userId().equals("music-user-2"));
    }

    @Test
    @DisplayName("TTL 被夹到 [30, 3600] 秒，避免配置成永久地址")
    void ttlIsClamped() {
        assertEquals(30, signer(SECRET, 0).ttlSeconds());
        assertEquals(3600, signer(SECRET, 99_999).ttlSeconds());
    }
}
```

```java
package com.example.mkagent.playback;

import com.example.mkagent.music.MusicSeedInitializer;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 点播解析的端到端回归：真实 HTTP + 真实 H2 + 真实媒体文件。
 *
 * <p>覆盖五种"必须给出不同答案"的分支，因为它们对应页面上五种不同提示：
 * <ol>
 *   <li>种子曲目（无音源映射）→ 200 + {@code playable=false} + {@code NO_SOURCE}；</li>
 *   <li>测试音（有映射、有文件）→ 200 + {@code DIRECT_AUDIO} + 签名地址；</li>
 *   <li>别人的受限曲目 → 403（权限问题，不是"不可播"）；</li>
 *   <li>曲目不存在 → 404；</li>
 *   <li>缺身份 → 401。</li>
 * </ol>
 *
 * <p>没有一条断言依赖模型：播放链路必须在百炼不可用时也能独立验收。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
class PlaybackResolveIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String USER = MusicSeedInitializer.USER_ALICE;

    /** 种子里有元数据、但没有音源映射的曲目（夜航星）。 */
    private static final long SEED_SONG_WITHOUT_SOURCE = 1001L;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /* ==================== 1. 有元数据、无音源 ==================== */

    @Test
    @DisplayName("种子曲目没有音源映射：可查询但明确不可播，且不返回任何地址")
    void seedSongWithoutSourceIsUnavailable() throws Exception {
        JsonNode body = resolveJson(SEED_SONG_WITHOUT_SOURCE, null, USER);

        assertFalse(body.path("playable").asBoolean(), "没有映射的种子曲目不能标成可播");
        assertEquals("UNAVAILABLE", body.path("mode").asText());
        assertEquals("NO_SOURCE", body.path("reasonCode").asText());
        assertTrue(body.path("reason").asText().length() > 0, "必须给出人话原因");
        assertTrue(body.path("streamUrl").isNull(), "不可播时不能返回任何地址");
        assertEquals(SEED_SONG_WITHOUT_SOURCE, body.path("song").path("id").asLong());
    }

    /* ==================== 2. 测试音：真实可播 ==================== */

    @Test
    @DisplayName("测试音 A 解析为 DIRECT_AUDIO：带格式、能力与短期签名地址")
    void fixtureTrackResolvesToDirectAudio() throws Exception {
        JsonNode body = resolveJson(PlaybackFixtureInitializer.SONG_TEST_A, null, USER);

        assertTrue(body.path("playable").asBoolean());
        assertEquals("DIRECT_AUDIO", body.path("mode").asText());
        assertEquals("WAV", body.path("format").asText());
        assertEquals("audio/wav", body.path("contentType").asText());
        assertEquals("local-test-fixture", body.path("provider").asText());

        String url = body.path("streamUrl").asText();
        assertTrue(url.startsWith("/api/ai/music/playback/media/"),
                "地址必须带 context-path，浏览器才能直接 GET：" + url);
        assertTrue(body.path("expiresAt").asLong() > System.currentTimeMillis() / 1000,
                "必须给出过期时间，前端据此在失效前重新解析");

        JsonNode caps = body.path("capabilities");
        assertTrue(caps.path("seek").asBoolean());
        assertTrue(caps.path("range").asBoolean());
        assertTrue(caps.path("eq").asBoolean(), "自有受控音频才允许调音");

        assertFalse(url.contains("test-tone-a.wav"), "内部文件路径不能出现在响应里");
        assertFalse(url.contains("resources"), "内部文件路径不能出现在响应里");
    }

    @Test
    @DisplayName("测试音 B 与 A 是不同曲目、不同地址（切歌可验证）")
    void twoFixturesAreDistinct() throws Exception {
        JsonNode a = resolveJson(PlaybackFixtureInitializer.SONG_TEST_A, null, USER);
        JsonNode b = resolveJson(PlaybackFixtureInitializer.SONG_TEST_B, null, USER);

        assertTrue(a.path("playable").asBoolean());
        assertTrue(b.path("playable").asBoolean());
        assertEquals(PlaybackFixtureInitializer.SONG_TEST_A, a.path("song").path("id").asLong());
        assertEquals(PlaybackFixtureInitializer.SONG_TEST_B, b.path("song").path("id").asLong());
        assertFalse(a.path("streamUrl").asText().equals(b.path("streamUrl").asText()));
    }

    /* ==================== 3. 越权 ==================== */

    @Test
    @DisplayName("别人的受限曲目：403，不能靠猜 songId 播别人的音频")
    void restrictedTrackIsForbiddenForOtherUser() throws Exception {
        HttpResponse<String> response = resolve(PlaybackFixtureInitializer.SONG_RESTRICTED, null, USER);

        assertEquals(403, response.statusCode());
        assertEquals("TRACK_FORBIDDEN", JSON.readTree(response.body()).path("code").asText());
    }

    @Test
    @DisplayName("受限曲目的 owner 本人可以播（拒绝的是越权，不是这首歌）")
    void restrictedTrackIsPlayableForOwner() throws Exception {
        JsonNode body = resolveJson(
                PlaybackFixtureInitializer.SONG_RESTRICTED, null,
                PlaybackFixtureInitializer.RESTRICTED_OWNER);

        assertTrue(body.path("playable").asBoolean());
        assertEquals("DIRECT_AUDIO", body.path("mode").asText());
    }

    /* ==================== 4. 参数与身份 ==================== */

    @Test
    @DisplayName("曲目不存在：404，不返回 500")
    void unknownSongIsNotFound() throws Exception {
        HttpResponse<String> response = resolve(999_999L, null, USER);

        assertEquals(404, response.statusCode());
        assertEquals("SONG_NOT_FOUND", JSON.readTree(response.body()).path("code").asText());
    }

    @Test
    @DisplayName("缺 X-User-Id：401，解析接口不接受匿名点播")
    void missingUserIsUnauthorized() throws Exception {
        HttpResponse<String> response = resolve(PlaybackFixtureInitializer.SONG_TEST_A, null, null);

        assertEquals(401, response.statusCode());
    }

    @Test
    @DisplayName("songId 非法：400")
    void invalidSongIdIsBadRequest() throws Exception {
        HttpResponse<String> response = post("/api/ai/music/playback/resolve",
                "{\"songId\":0}", USER);

        assertEquals(400, response.statusCode());
        assertEquals("INVALID_SONG_ID", JSON.readTree(response.body()).path("code").asText());
    }

    @Test
    @DisplayName("带别人的歌单 ID 点播：404，不能用歌单 ID 探测他人歌单")
    void foreignPlaylistIsRejected() throws Exception {
        Long bobPlaylistId = jdbcTemplate.queryForObject(
                "SELECT id FROM music_playlist WHERE user_id = ? ORDER BY id LIMIT 1",
                Long.class, MusicSeedInitializer.USER_BOB);

        assertNotNull(bobPlaylistId);

        HttpResponse<String> response = resolve(
                PlaybackFixtureInitializer.SONG_TEST_A, bobPlaylistId, USER);

        assertEquals(404, response.statusCode());
        assertEquals("PLAYLIST_NOT_FOUND", JSON.readTree(response.body()).path("code").asText());
    }

    @Test
    @DisplayName("带自己的歌单 ID 点播：正常解析")
    void ownPlaylistIsAccepted() throws Exception {
        Long alicePlaylistId = jdbcTemplate.queryForObject(
                "SELECT id FROM music_playlist WHERE user_id = ? ORDER BY id LIMIT 1",
                Long.class, USER);

        assertNotNull(alicePlaylistId);

        JsonNode body = resolveJson(
                PlaybackFixtureInitializer.SONG_TEST_A, alicePlaylistId, USER);

        assertTrue(body.path("playable").asBoolean());
    }

    /* ==================== 辅助 ==================== */

    /** 解析并断言 200，返回 JSON。 */
    private JsonNode resolveJson(long songId, Long playlistId, String userId) throws Exception {
        HttpResponse<String> response = resolve(songId, playlistId, userId);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> resolve(long songId, Long playlistId, String userId) throws Exception {
        String body = playlistId == null
                ? "{\"songId\":" + songId + "}"
                : "{\"songId\":" + songId + ",\"playlistId\":" + playlistId + "}";
        return post("/api/ai/music/playback/resolve", body, userId);
    }

    private HttpResponse<String> post(String path, String body, String userId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (userId != null) {
            builder.header("X-User-Id", userId);
        }

        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
```

```java
package com.example.mkagent.playback;

import com.example.mkagent.music.MusicSeedInitializer;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体交付的真实 HTTP 验证：Content-Type、Content-Length、Range / 206 / 416。
 *
 * <p>为什么必须打真实端口而不是 MockMvc：
 * 拖动进度条依赖的是**传输层**行为（206 + Content-Range + 精确的字节区间），
 * MockMvc 不会真正按区间写 body，测不出"seek 到了错误偏移"这类问题。
 *
 * <p>本类同时证明"签名地址可用、篡改地址不可用"：
 * 媒体接口不读 X-User-Id（{@code <audio>} 加不了自定义头），身份在令牌里。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
class PlaybackMediaRangeIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 测试音 A 的完整字节（用于逐字节比对区间）。 */
    private byte[] full;

    private String streamUrl;

    @BeforeEach
    void resolveOnce() throws Exception {
        String body = "{\"songId\":" + PlaybackFixtureInitializer.SONG_TEST_A + "}";

        HttpRequest resolveRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/ai/music/playback/resolve"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-User-Id", MusicSeedInitializer.USER_ALICE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resolveResponse =
                client.send(resolveRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, resolveResponse.statusCode(), resolveResponse.body());

        JsonNode json = JSON.readTree(resolveResponse.body());
        streamUrl = json.path("streamUrl").asText();
        assertTrue(streamUrl.startsWith("/api/"));

        HttpResponse<byte[]> media = get(streamUrl, null);
        assertEquals(200, media.statusCode());
        full = media.body();
    }

    @Test
    @DisplayName("无 Range：200 + 完整长度 + audio/wav + Accept-Ranges")
    void fullRequestReturnsWholeFile() {
        assertTrue(full.length > 100_000, "测试音应当是一段真实音频，而不是空文件");
        assertEquals("audio/wav", mediaTypeOfLastResponse);
        assertEquals(String.valueOf(full.length), lastContentLength);
        assertEquals("bytes", lastAcceptRanges);
    }

    @Test
    @DisplayName("Range 0-99：206 + Content-Range + 恰好 100 字节")
    void rangeRequestReturnsPartialContent() throws Exception {
        HttpResponse<byte[]> response = get(streamUrl, "bytes=0-99");

        assertEquals(206, response.statusCode());
        assertEquals("bytes 0-99/" + full.length, response.headers()
                .firstValue("Content-Range").orElse(null));
        assertEquals(100, response.body().length);
        assertArrayEquals(Arrays.copyOfRange(full, 0, 100), response.body());
    }

    @Test
    @DisplayName("Range 1000-1999：区间偏移正确（seek 拖到中段不会串音）")
    void rangeOffsetIsCorrect() throws Exception {
        HttpResponse<byte[]> response = get(streamUrl, "bytes=1000-1999");

        assertEquals(206, response.statusCode());
        assertEquals(1000, response.body().length);
        assertArrayEquals(Arrays.copyOfRange(full, 1000, 2000), response.body(),
                "区间字节不一致：说明服务端没有从正确的偏移开始读");
    }

    @Test
    @DisplayName("后缀 Range（bytes=-100）：返回最后 100 字节")
    void suffixRangeReturnsTail() throws Exception {
        HttpResponse<byte[]> response = get(streamUrl, "bytes=-100");

        assertEquals(206, response.statusCode());
        assertEquals(100, response.body().length);
        assertArrayEquals(
                Arrays.copyOfRange(full, full.length - 100, full.length),
                response.body());
    }

    @Test
    @DisplayName("越界 Range：416 + Content-Range: bytes */总长")
    void unsatisfiableRangeReturns416() throws Exception {
        HttpResponse<byte[]> response = get(streamUrl, "bytes=99999999-100000000");

        assertEquals(416, response.statusCode());
        assertEquals("bytes */" + full.length, response.headers()
                .firstValue("Content-Range").orElse(null));
    }

    @Test
    @DisplayName("篡改令牌：403，且只返回错误说明、不返回任何媒体字节")
    void tamperedTokenIsRejected() throws Exception {
        String tampered = streamUrl.substring(0, streamUrl.length() - 4) + "AAAA";

        HttpResponse<byte[]> response = get(tampered, null);

        assertEquals(403, response.statusCode());

        String body = new String(response.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("MEDIA_TOKEN_INVALID"), body);
        assertTrue(response.body().length < 512,
                "403 时只能是一小段 JSON，不能把音频内容吐出来");
    }

    @Test
    @DisplayName("完全伪造的令牌：403")
    void forgedTokenIsRejected() throws Exception {
        HttpResponse<byte[]> response = get("/api/ai/music/playback/media/v1.Zm9v.YmFy", null);

        assertEquals(403, response.statusCode());
    }

    /* ==================== 辅助 ==================== */

    private String mediaTypeOfLastResponse;
    private String lastContentLength;
    private String lastAcceptRanges;

    private HttpResponse<byte[]> get(String path, String range) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET();

        if (range != null) {
            builder.header("Range", range);
        }

        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

        mediaTypeOfLastResponse = response.headers().firstValue("Content-Type").orElse(null);
        lastContentLength = response.headers().firstValue("Content-Length").orElse(null);
        lastAcceptRanges = response.headers().firstValue("Accept-Ranges").orElse(null);

        assertNotNull(response);
        return response;
    }
}
```

```java
package com.example.mkagent.playback;

import com.example.mkagent.music.MusicSeedInitializer;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「当前曲目」与「会话内候选」的端到端回归。
 *
 * <p>这两条是 B 段（Agent 点歌 + 加歌单）的地基：
 * <ul>
 *   <li>候选只能来自会话已存的**真实工具引用**，不能来自模型文本；</li>
 *   <li>「正在播放」只能由服务端复核后落库——前端声称的 songId 不算数，
 *       否则可以声称任何歌正在播放，绕过曲目权限。</li>
 * </ul>
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
class PlaybackNowPlayingIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String USER = MusicSeedInitializer.USER_ALICE;

    private static final String CONVERSATION_ID = "conv-playback-test-0001";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void seedConversation() {
        jdbcTemplate.update(
                "DELETE FROM agent_conversation WHERE conversation_id = ?", CONVERSATION_ID);

        jdbcTemplate.update(
                "INSERT INTO agent_conversation "
                        + "(id, conversation_id, user_id, title, refs_json, last_run_id, "
                        + " created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                9_000_001L,
                CONVERSATION_ID,
                USER,
                "播放候选测试",
                """
                {"songs":[
                  {"id":1901,"title":"测试音 A（琶音）"},
                  {"id":1902,"title":"测试音 B（低音）"},
                  {"id":1001,"title":"夜航星"}
                ],"playlists":[]}
                """
        );

        // 每个用例从"没有当前曲目"开始，避免互相影响
        jdbcTemplate.update("DELETE FROM music_now_playing WHERE user_id = ?", USER);
    }

    /* ==================== 当前曲目 ==================== */

    @Test
    @DisplayName("上报 PLAYING 后能读回当前曲目，并写入一条播放日志")
    void playingIsRecorded() throws Exception {
        Integer logsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_play_log WHERE user_id = ?", Integer.class, USER);

        HttpResponse<String> update = post("/api/ai/music/playback/now-playing",
                "{\"songId\":1901,\"state\":\"PLAYING\"}", USER);

        assertEquals(200, update.statusCode(), update.body());
        assertEquals("PLAYING", JSON.readTree(update.body()).path("state").asText());

        HttpResponse<String> current = get("/api/ai/music/playback/now-playing", USER);
        assertEquals(200, current.statusCode());
        assertEquals(1901L, JSON.readTree(current.body()).path("songId").asLong());

        Integer logsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_play_log WHERE user_id = ?", Integer.class, USER);

        assertEquals(logsBefore + 1, logsAfter, "真实开始播放应当留下一条播放日志");
    }

    @Test
    @DisplayName("暂停仍算当前曲目；停止后就没有当前曲目（204）")
    void pausedKeepsTrackButStoppedClearsIt() throws Exception {
        post("/api/ai/music/playback/now-playing",
                "{\"songId\":1901,\"state\":\"PLAYING\"}", USER);

        post("/api/ai/music/playback/now-playing",
                "{\"songId\":1901,\"state\":\"PAUSED\"}", USER);

        HttpResponse<String> paused = get("/api/ai/music/playback/now-playing", USER);
        assertEquals(200, paused.statusCode());
        assertEquals("PAUSED", JSON.readTree(paused.body()).path("state").asText());

        post("/api/ai/music/playback/now-playing",
                "{\"songId\":1901,\"state\":\"STOPPED\"}", USER);

        HttpResponse<String> stopped = get("/api/ai/music/playback/now-playing", USER);
        assertEquals(204, stopped.statusCode(), "停止后不该再声称'正在播放'");
    }

    @Test
    @DisplayName("上报无音源 / 越权曲目：拒绝落库（前端声称不算数）")
    void ineligibleTracksCannotBeClaimed() throws Exception {
        HttpResponse<String> noSource = post("/api/ai/music/playback/now-playing",
                "{\"songId\":1001,\"state\":\"PLAYING\"}", USER);
        assertEquals(403, noSource.statusCode());

        HttpResponse<String> restricted = post("/api/ai/music/playback/now-playing",
                "{\"songId\":1903,\"state\":\"PLAYING\"}", USER);
        assertEquals(403, restricted.statusCode());

        assertEquals(204, get("/api/ai/music/playback/now-playing", USER).statusCode());
    }

    @Test
    @DisplayName("非法状态：400")
    void invalidStateIsRejected() throws Exception {
        HttpResponse<String> response = post("/api/ai/music/playback/now-playing",
                "{\"songId\":1901,\"state\":\"DANCING\"}", USER);

        assertEquals(400, response.statusCode());
    }

    /* ==================== 会话内候选 ==================== */

    @Test
    @DisplayName("候选来自会话真实引用：可播的标可播，没音源的说明原因")
    void candidatesReflectRealRefs() throws Exception {
        HttpResponse<String> response =
                get("/api/ai/music/playback/candidates?conversationId=" + CONVERSATION_ID, USER);

        assertEquals(200, response.statusCode(), response.body());

        JsonNode list = JSON.readTree(response.body());
        assertEquals(3, list.size(), "候选应当与会话引用一一对应");

        assertTrue(list.get(0).path("playable").asBoolean());
        assertEquals("WAV", list.get(0).path("format").asText());
        assertTrue(list.get(1).path("playable").asBoolean());

        assertFalse(list.get(2).path("playable").asBoolean(), "夜航星没有音源，不能标成可播");
        assertTrue(list.get(2).path("unavailableReason").asText().length() > 0);
        assertTrue(list.get(2).path("format").isNull(), "不可播时不能给出格式");
    }

    @Test
    @DisplayName("别人的会话：404，不能借 conversationId 读别人的候选")
    void foreignConversationIsRejected() throws Exception {
        HttpResponse<String> response =
                get("/api/ai/music/playback/candidates?conversationId=" + CONVERSATION_ID,
                        MusicSeedInitializer.USER_BOB);

        assertEquals(404, response.statusCode());
    }

    @Test
    @DisplayName("缺身份：401")
    void candidatesRequireIdentity() throws Exception {
        HttpResponse<String> response =
                get("/api/ai/music/playback/candidates?conversationId=" + CONVERSATION_ID, null);

        assertEquals(401, response.statusCode());
    }

    /* ==================== 辅助 ==================== */

    private HttpResponse<String> get(String path, String userId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET();

        if (userId != null) {
            builder.header("X-User-Id", userId);
        }

        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body, String userId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (userId != null) {
            builder.header("X-User-Id", userId);
        }

        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
```

```java
package com.example.mkagent.playback;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.music.MusicSeedInitializer;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.tools.PlaybackTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 播放只读工具的行为约束。
 *
 * <p>四条断言对应四件模型最容易做错的事：
 * <ol>
 *   <li>把"没有音源"说成"可以播放"；</li>
 *   <li>把别人的曲目当成可播；</li>
 *   <li>没有正在播放的曲目时，凭空说出一个歌名；</li>
 *   <li>把签名地址或"已播放"塞进工具结果（进而进入模型消息）。</li>
 * </ol>
 */
@HermeticSpringBootTest
class PlaybackToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private PlaybackTool playbackTool;

    @Autowired
    private TrackSourceRepository sourceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetNowPlaying() {
        /*
         * 测试库是同一个 H2 内存库（DB_CLOSE_DELAY=-1），
         * 其他用例可能留下「正在播放」记录，这里先清一次再断言。
         */
        jdbcTemplate.update("DELETE FROM music_now_playing WHERE user_id IN (?, ?)",
                MusicSeedInitializer.USER_ALICE, MusicSeedInitializer.USER_BOB);
    }

    @AfterEach
    void clearContext() {
        AgentRunContextHolder.clear();
        jdbcTemplate.update("DELETE FROM music_now_playing WHERE user_id IN (?, ?)",
                MusicSeedInitializer.USER_ALICE, MusicSeedInitializer.USER_BOB);
    }

    private void bindUser(String userId) {
        com.example.mkagent.model.AgentRunContext ctx =
                new com.example.mkagent.model.AgentRunContext(java.util.UUID.randomUUID());
        ctx.setUserId(userId);
        AgentRunContextHolder.bind(ctx);
    }

    @Test
    @DisplayName("测试音：check_playback 返回可播，且结果里没有任何地址")
    void playableTrackReportsPlayableWithoutUrl() throws Exception {
        bindUser(MusicSeedInitializer.USER_ALICE);

        JsonNode root = JSON.readTree(
                playbackTool.checkPlayback(PlaybackFixtureInitializer.SONG_TEST_A));

        assertTrue(root.path("ok").asBoolean());
        assertEquals("WAV", root.path("data").path("format").asText());

        String raw = root.toString();
        assertFalse(raw.contains("http"), "工具结果里不能出现地址");
        assertFalse(raw.contains("media/"), "工具结果里不能出现媒体路径");
        assertFalse(raw.contains("token"), "工具结果里不能出现签名令牌");
        assertTrue(root.path("data").path("hint").asText().contains("点击播放卡"),
                "要明确告诉模型：播放由页面完成");
    }

    @Test
    @DisplayName("没有音源的种子曲目：check_playback 返回失败并说明原因")
    void trackWithoutSourceReportsUnavailable() throws Exception {
        bindUser(MusicSeedInitializer.USER_ALICE);

        JsonNode root = JSON.readTree(playbackTool.checkPlayback(1001L));

        assertFalse(root.path("ok").asBoolean());
        assertTrue(root.path("meta").path("hint").asText().contains("暂无可用音源"));
    }

    @Test
    @DisplayName("别人的受限曲目：check_playback 返回无权播放，不泄露它属于谁")
    void restrictedTrackReportsForbidden() throws Exception {
        bindUser(MusicSeedInitializer.USER_ALICE);

        JsonNode root = JSON.readTree(
                playbackTool.checkPlayback(PlaybackFixtureInitializer.SONG_RESTRICTED));

        assertFalse(root.path("ok").asBoolean());
        String hint = root.path("meta").path("hint").asText();
        assertTrue(hint.contains("无权播放"));
        assertFalse(hint.contains(MusicSeedInitializer.USER_BOB), "不能泄露 owner 身份");
    }

    @Test
    @DisplayName("没有正在播放的曲目：get_now_playing 失败，而不是编一首")
    void noNowPlayingReturnsFailure() throws Exception {
        bindUser(MusicSeedInitializer.USER_ALICE);

        JsonNode root = JSON.readTree(playbackTool.getNowPlaying());

        assertFalse(root.path("ok").asBoolean());
        assertTrue(root.path("meta").path("hint").asText().contains("没有正在播放"));
    }

    @Test
    @DisplayName("上报播放后：get_now_playing 返回服务端确认过的曲目")
    void nowPlayingReturnsVerifiedTrack() throws Exception {
        bindUser(MusicSeedInitializer.USER_ALICE);
        sourceRepository.saveNowPlaying(
                MusicSeedInitializer.USER_ALICE, PlaybackFixtureInitializer.SONG_TEST_B, "PLAYING");

        JsonNode root = JSON.readTree(playbackTool.getNowPlaying());

        assertTrue(root.path("ok").asBoolean());
        assertEquals(PlaybackFixtureInitializer.SONG_TEST_B, root.path("data").path("songId").asLong());
        assertEquals("PLAYING", root.path("data").path("state").asText());
        assertTrue(root.path("data").path("title").asText().startsWith("测试音 B"));
    }

    @Test
    @DisplayName("停止后不再是当前曲目")
    void stoppedIsNotNowPlaying() throws Exception {
        bindUser(MusicSeedInitializer.USER_ALICE);
        sourceRepository.saveNowPlaying(
                MusicSeedInitializer.USER_ALICE, PlaybackFixtureInitializer.SONG_TEST_B, "STOPPED");

        JsonNode root = JSON.readTree(playbackTool.getNowPlaying());

        assertFalse(root.path("ok").asBoolean());
    }

    @Test
    @DisplayName("两个用户的「正在播放」互不影响")
    void nowPlayingIsPerUser() throws Exception {
        sourceRepository.saveNowPlaying(
                MusicSeedInitializer.USER_ALICE, PlaybackFixtureInitializer.SONG_TEST_A, "PLAYING");
        sourceRepository.saveNowPlaying(
                MusicSeedInitializer.USER_BOB, PlaybackFixtureInitializer.SONG_RESTRICTED, "PLAYING");

        bindUser(MusicSeedInitializer.USER_ALICE);
        JsonNode alice = JSON.readTree(playbackTool.getNowPlaying());
        assertEquals(PlaybackFixtureInitializer.SONG_TEST_A, alice.path("data").path("songId").asLong());

        AgentRunContextHolder.clear();
        bindUser(MusicSeedInitializer.USER_BOB);
        JsonNode bob = JSON.readTree(playbackTool.getNowPlaying());
        assertEquals(PlaybackFixtureInitializer.SONG_RESTRICTED, bob.path("data").path("songId").asLong());
    }
}
```

### 4.2 修改的测试（diff）

```diff
diff --git a/src/test/java/com/example/mkagent/agent/MusicPromptCapabilityTest.java b/src/test/java/com/example/mkagent/agent/MusicPromptCapabilityTest.java
index aba3507..d8f58ea 100644
--- a/src/test/java/com/example/mkagent/agent/MusicPromptCapabilityTest.java
+++ b/src/test/java/com/example/mkagent/agent/MusicPromptCapabilityTest.java
@@ -96,8 +96,8 @@ class MusicPromptCapabilityTest {
                 MusicAgent.USER_WRITE_SYSTEM_PROMPT_VERSION);
         assertNotEquals(MusicAgent.USER_SYSTEM_PROMPT_VERSION,
                 MusicAgent.ADMIN_SYSTEM_PROMPT_VERSION);
-        assertEquals("music-user-readonly/v3", MusicAgent.USER_SYSTEM_PROMPT_VERSION);
-        assertEquals("music-user-write-approval/v3", MusicAgent.USER_WRITE_SYSTEM_PROMPT_VERSION);
+        assertEquals("music-user-readonly/v4", MusicAgent.USER_SYSTEM_PROMPT_VERSION);
+        assertEquals("music-user-write-approval/v6", MusicAgent.USER_WRITE_SYSTEM_PROMPT_VERSION);
         assertEquals("music-admin-readonly/v3", MusicAgent.ADMIN_SYSTEM_PROMPT_VERSION);
     }
 
@@ -134,6 +134,31 @@ class MusicPromptCapabilityTest {
         }
     }
 
+    @Test
+    @DisplayName("播放契约：两份用户提示词都禁止宣称已播放，管理员提示词不带它")
+    void playbackContractOnlyForUserPrompts() {
+        for (String prompt : new String[]{
+                MusicAgent.USER_SYSTEM_PROMPT,
+                MusicAgent.USER_WRITE_SYSTEM_PROMPT}) {
+            assertTrue(prompt.contains(MusicAgent.PLAYBACK_CONTRACT),
+                    "用户提示词必须包含播放契约");
+            assertTrue(prompt.contains("**不能**直接播放音频"), "必须明确模型不能播放");
+            assertTrue(prompt.contains("**不得**说\"正在播放\""),
+                    "必须禁止宣称已经开始播放");
+            assertTrue(prompt.contains("get_now_playing"),
+                    "必须要求用工具确认\"正在播放\"的是哪首");
+            assertFalse(prompt.contains("http"), "播放契约里不该出现地址样例");
+        }
+
+        assertFalse(MusicAgent.ADMIN_SYSTEM_PROMPT.contains(MusicAgent.PLAYBACK_CONTRACT),
+                "管理员没有播放工具，提示词不应提到播放链路");
+
+        assertTrue(MusicAgent.USER_WRITE_SYSTEM_PROMPT.contains(MusicAgent.PLAYBACK_WRITE_CONTRACT),
+                "写能力版本要说明\"把当前播放曲目加入歌单\"的链路");
+        assertFalse(MusicAgent.USER_SYSTEM_PROMPT.contains(MusicAgent.PLAYBACK_WRITE_CONTRACT),
+                "只读版本不能出现写链路");
+    }
+
     @Test
     @DisplayName("提示词编号连续：边界契约是第 5 条，收尾是第 6 条")
     void contractNumberingStaysContiguous() {
```

```diff
diff --git a/src/test/java/com/example/mkagent/agent/MusicToolWhitelistIntegrationTest.java b/src/test/java/com/example/mkagent/agent/MusicToolWhitelistIntegrationTest.java
index b43445c..85fa705 100644
--- a/src/test/java/com/example/mkagent/agent/MusicToolWhitelistIntegrationTest.java
+++ b/src/test/java/com/example/mkagent/agent/MusicToolWhitelistIntegrationTest.java
@@ -101,7 +101,7 @@ class MusicToolWhitelistIntegrationTest {
     }
 
     @Test
-    @DisplayName("MUSIC_USER：曲库 + 歌单 + 终止，共 5 个")
+    @DisplayName("MUSIC_USER：曲库 + 歌单 + 播放只读 + 终止，共 7 个")
     void musicUserWhitelistIsExact() {
         assertThat(toolNames(musicUserToolCallbacks))
                 .as("MUSIC_USER 白名单必须精确等于设计")
@@ -110,6 +110,12 @@ class MusicToolWhitelistIntegrationTest {
                         "get_song_detail",
                         "get_user_playlists",
                         "get_playlist_songs",
+                        /*
+                         * 播放只读工具：只回答"能不能播""正在播哪首"，
+                         * 不签发地址、不触发播放，因此不需要审批。
+                         */
+                        "check_playback",
+                        "get_now_playing",
                         "terminate_task"
                 );
     }
```

```diff
diff --git a/src/test/java/com/example/mkagent/config/AgentToolWhitelistTest.java b/src/test/java/com/example/mkagent/config/AgentToolWhitelistTest.java
index b71ea9b..538ff25 100644
--- a/src/test/java/com/example/mkagent/config/AgentToolWhitelistTest.java
+++ b/src/test/java/com/example/mkagent/config/AgentToolWhitelistTest.java
@@ -6,6 +6,7 @@ import com.example.mkagent.tools.AdminAnalyticsTool;
 import com.example.mkagent.tools.FileOperationTool;
 import com.example.mkagent.tools.MusicPlaylistQueryTool;
 import com.example.mkagent.tools.MusicQueryTool;
+import com.example.mkagent.tools.PlaybackTool;
 import com.example.mkagent.tools.PDFGenerationTool;
 import com.example.mkagent.tools.ResourceDownloadTool;
 import com.example.mkagent.tools.TerminateTool;
@@ -40,6 +41,8 @@ class AgentToolWhitelistTest {
             // 音乐工具只用到方法签名，构造时依赖传 null 即可。
             new MusicQueryTool(null),
             new MusicPlaylistQueryTool(null),
+            // 播放工具同样只用到方法签名
+            new PlaybackTool(null),
             new AdminAnalyticsTool(null, null),
             // 重试包装器使用与生产一致的默认配置；
             // 包装不改变工具名，白名单断言不受影响。
@@ -120,6 +123,7 @@ class AgentToolWhitelistTest {
         assertEquals(
                 Set.of("search_songs", "get_song_detail",
                         "get_user_playlists", "get_playlist_songs",
+                        "check_playback", "get_now_playing",
                         "terminate_task"),
                 names
         );
```

```diff
diff --git a/src/test/java/com/example/mkagent/music/MusicSeedInitializerTest.java b/src/test/java/com/example/mkagent/music/MusicSeedInitializerTest.java
index 7a5fc08..d890182 100644
--- a/src/test/java/com/example/mkagent/music/MusicSeedInitializerTest.java
+++ b/src/test/java/com/example/mkagent/music/MusicSeedInitializerTest.java
@@ -52,7 +52,13 @@ class MusicSeedInitializerTest {
     @Test
     @DisplayName("种子条数符合设计")
     void seedCountsMatchDesign() {
-        assertEquals(60, count("music_song"), "曲库应为 60 首");
+        /*
+         * 只断言**种子曲目**（1001~1060）：
+         * 曲库里还有播放夹具的测试音（1901~1903），
+         * 它们不是种子数据，用总行数断言会让两个初始化器互相牵制。
+         */
+        assertEquals(60, countWhere("music_song", "id BETWEEN 1001 AND 1060"),
+                "种子曲库应为 60 首");
         assertEquals(3, count("music_user"), "用户应为 3 个");
         assertEquals(3, count("music_playlist"), "歌单应为 3 个");
         assertEquals(19, count("music_playlist_song"), "歌单歌曲合计 8+6+5=19");
@@ -62,12 +68,13 @@ class MusicSeedInitializerTest {
     @Test
     @DisplayName("重复执行不翻倍（幂等）")
     void seedIsIdempotent() {
-        int before = count("music_song");
+        int before = countWhere("music_song", "id BETWEEN 1001 AND 1060");
 
         int inserted = seedInitializer.seed();
 
         assertEquals(0, inserted, "已初始化时应跳过，返回 0");
-        assertEquals(before, count("music_song"), "歌曲数不得变化");
+        assertEquals(before, countWhere("music_song", "id BETWEEN 1001 AND 1060"),
+                "歌曲数不得变化");
         assertEquals(3, count("music_user"), "用户数不得变化");
         assertEquals(3, count("music_playlist"), "歌单数不得变化");
         assertEquals(19, count("music_playlist_song"), "歌单歌曲数不得变化");
@@ -191,4 +198,11 @@ class MusicSeedInitializerTest {
                 "SELECT COUNT(*) FROM " + table, Integer.class);
         return value == null ? 0 : value;
     }
+
+    /** 带条件的计数（表名与条件都来自测试常量，不接受外部输入）。 */
+    private int countWhere(String table, String condition) {
+        Integer value = jdbcTemplate.queryForObject(
+                "SELECT COUNT(*) FROM " + table + " WHERE " + condition, Integer.class);
+        return value == null ? 0 : value;
+    }
 }
```

### 4.3 审批回归（`MusicWriteLoopIntegrationTest` 的改动）

```diff
diff --git a/src/test/java/com/example/mkagent/music/MusicWriteLoopIntegrationTest.java b/src/test/java/com/example/mkagent/music/MusicWriteLoopIntegrationTest.java
index 602efec..5081aa9 100644
--- a/src/test/java/com/example/mkagent/music/MusicWriteLoopIntegrationTest.java
+++ b/src/test/java/com/example/mkagent/music/MusicWriteLoopIntegrationTest.java
@@ -1,6 +1,8 @@
 package com.example.mkagent.music;
 
 import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
+import com.example.mkagent.playback.PlaybackFixtureInitializer;
+import com.example.mkagent.playback.TrackSourceRepository;
 import com.example.mkagent.rag.ChatAppDocumentLoader;
 import com.example.mkagent.support.HermeticSpringBootTest;
 import com.fasterxml.jackson.databind.JsonNode;
@@ -93,6 +95,9 @@ class MusicWriteLoopIntegrationTest {
     @Autowired
     private JdbcTemplate jdbcTemplate;
 
+    @Autowired
+    private TrackSourceRepository trackSourceRepository;
+
     @MockitoBean
     private VectorStore chatAppVectorStore;
 
@@ -118,13 +123,25 @@ class MusicWriteLoopIntegrationTest {
         }
 
         /*
-         * "加入已有歌单"用例会往种子歌单里插 1057，
-         * 这里删掉，保证其它用例看到的种子曲目数不变。
+         * "加入已有歌单"的两条用例都会往种子歌单里插歌：
+         *   1057 —— approveAddsSongsToExistingPlaylist；
+         *   1902 —— addNowPlayingGoesThroughApproval（测试音 B）。
+         * 这里按 song_id 精确删掉。漏删 1902 会让后面的用例看到
+         * "深夜通勤有 9 首"，表现为与本次改动无关的绝对数量断言失败
+         * （共享内存库，测试之间并不隔离）。
          */
         jdbcTemplate.update(
-                "DELETE FROM music_playlist_song WHERE song_id = 1057 AND playlist_id IN "
+                "DELETE FROM music_playlist_song WHERE song_id IN (1057, 1902) "
+                        + "AND playlist_id IN "
                         + "(SELECT id FROM music_playlist WHERE user_id = ? AND name = ?)",
                 USER, MusicSeedInitializer.PLAYLIST_NAME_PRIVATE_ALICE);
+
+        /*
+         * "正在播放"用例会写 music_now_playing。共享内存库下必须清掉：
+         * 残留一行会让其它用例读到"上一个测试留下的当前曲目"，
+         * 表现为时过时不过的假失败。
+         */
+        jdbcTemplate.update("DELETE FROM music_now_playing WHERE user_id = ?", USER);
     }
 
     // ==================== 主链路 ====================
@@ -208,6 +225,78 @@ class MusicWriteLoopIntegrationTest {
         assertTrue(found, "查询接口应能读到刚加入的歌曲，实际：" + songs);
     }
 
+    @Test
+    @DisplayName("把正在播放的歌加入歌单：曲目取自服务端播放事实，批准后才写入")
+    void addNowPlayingGoesThroughApproval() throws Exception {
+        long playlistId = seededPlaylistId(MusicSeedInitializer.PLAYLIST_NAME_PRIVATE_ALICE);
+        int before = playlistSongCount(playlistId);
+
+        /*
+         * 先造一条"服务端确认过的播放事实"。
+         *
+         * 走真实仓储而不是拼 SQL：这样 get_now_playing 读到的就是
+         * 客户端上报那条链路写进去的同一行，测试与生产不出现两套写法。
+         */
+        trackSourceRepository.saveNowPlaying(
+                USER, PlaybackFixtureInitializer.SONG_TEST_B, "PLAYING");
+
+        /*
+         * 两步脚本，把模型必须遵守的顺序钉死：
+         *   1) get_now_playing 拿真实曲目；
+         *   2) 用返回的 songId 调 add_songs_to_playlist。
+         *
+         * 模型不能"猜"正在播放的是哪首，也不能跳过查询直接写——
+         * 一旦跳过，第一步的工具结果就不存在，第二步的 songId 也无从谈起。
+         */
+        scriptedChatModel.script(
+                new String[]{"get_now_playing", "{}"},
+                new String[]{"add_songs_to_playlist",
+                        "{\"playlistId\":" + playlistId + ",\"songIds\":["
+                                + PlaybackFixtureInitializer.SONG_TEST_B + "]}"}
+        );
+
+        String runId = startTaskAndWaitApproval("把正在播放的这首加入「"
+                + MusicSeedInitializer.PLAYLIST_NAME_PRIVATE_ALICE + "」");
+        String approvalId = pendingApprovalId(runId);
+
+        /*
+         * 服务端复核过曲目：摘要里必须是**真实标题**。
+         * 只断言"有个审批"是不够的——那证明不了曲目来自服务端播放事实，
+         * 只能证明写工具被调用了。标题出现，说明 songId 在库里查得到、
+         * 且摘要反查走的是真实数据库。
+         */
+        String summary = summaryText(approvalId);
+        assertTrue(summary.contains("向歌单添加歌曲"),
+                "摘要应写明动作，实际：" + summary);
+        assertTrue(summary.contains(MusicSeedInitializer.PLAYLIST_NAME_PRIVATE_ALICE),
+                "摘要应写明目标歌单名，实际：" + summary);
+        assertTrue(summary.contains("测试音 B"),
+                "摘要应写明曲目标题（证明曲目来自服务端事实），实际：" + summary);
+
+        // 审批前一律不写：加歌与建歌单共用同一套暂停 / 恢复
+        assertEquals(before, playlistSongCount(playlistId), "批准前不得写入");
+
+        HttpResponse<String> approved = post("/ai/approvals/" + approvalId + "/approve");
+        assertEquals(200, approved.statusCode(), "批准失败，body=" + approved.body());
+        awaitFinishedRun(runId);
+
+        assertEquals("SUCCEEDED", runRow(runId).get("state"),
+                "批准后任务应成功，实际：" + runRow(runId));
+        assertEquals("APPROVED", approvalRow(approvalId).get("status"));
+        assertEquals("FINISHED", approvalRow(approvalId).get("resume_state"));
+
+        assertEquals(before + 1, playlistSongCount(playlistId), "批准后曲目数应 +1");
+
+        JsonNode songs = getJson("/ai/music/playlists/" + playlistId + "/songs");
+        boolean found = false;
+        for (JsonNode song : songs) {
+            if (song.get("id").asLong() == PlaybackFixtureInitializer.SONG_TEST_B) {
+                found = true;
+            }
+        }
+        assertTrue(found, "查询接口应能读到正在播放的曲目，实际：" + songs);
+    }
+
     @Test
     @DisplayName("拒绝 → 不执行写工具，数据库里没有歌单")
     void rejectWritesNothing() throws Exception {
@@ -238,7 +327,7 @@ class MusicWriteLoopIntegrationTest {
 
         assertTrue(caps.get("writeApproval").asBoolean(),
                 "开关打开时页面应显示审批写，实际：" + caps);
-        assertEquals("music-user-write-approval/v3", caps.get("promptVersion").asText(),
+        assertEquals("music-user-write-approval/v6", caps.get("promptVersion").asText(),
                 "提示词版本必须与工具开关同步，实际：" + caps);
     }
 
@@ -296,10 +385,20 @@ class MusicWriteLoopIntegrationTest {
         scriptedChatModel.toolArgs = "{\"playlistId\":" + playlistId
                 + ",\"songIds\":" + songIds + "}";
 
+        return startTaskAndWaitApproval(
+                "把这首加到「" + MusicSeedInitializer.PLAYLIST_NAME_PRIVATE_ALICE + "」里");
+    }
+
+    /**
+     * 用当前桩模型脚本发起一次真实对话，等它停在审批上，返回 runId。
+     *
+     * <p>抽出来是为了让多步链路（先查"正在播放"、再用查到的 ID 写歌单）
+     * 复用同一段等待逻辑，而不是把轮询代码再抄一遍——
+     * 抄出来的副本一旦漂移，两条链路的断言就不再等价。
+     */
+    private String startTaskAndWaitApproval(String message) throws Exception {
         String url = "http://localhost:" + port + "/api/ai/music/chat"
-                + "?message=" + URLEncoder.encode(
-                "把这首加到「" + MusicSeedInitializer.PLAYLIST_NAME_PRIVATE_ALICE + "」里",
-                StandardCharsets.UTF_8);
+                + "?message=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
 
         HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                 .header("X-User-Id", USER)
@@ -447,30 +546,64 @@ class MusicWriteLoopIntegrationTest {
 
         volatile String toolArgs = "{}";
 
+        /**
+         * 多步脚本：按顺序发若干次工具调用，发完给最终回答。
+         *
+         * <p>为什么需要它：有的链路本来就是两步——
+         * "先查当前播放曲目，再用查到的 ID 写歌单"。
+         * 只支持单步的桩会把这个约束测丢。
+         */
+        private final java.util.Deque<String[]> steps = new java.util.concurrent.ConcurrentLinkedDeque<>();
+
+        private volatile boolean sequenceMode = false;
+
+        void script(String[]... scriptedSteps) {
+            steps.clear();
+            for (String[] step : scriptedSteps) {
+                steps.add(step);
+            }
+            sequenceMode = true;
+        }
+
         void reset() {
             calls.set(0);
             toolName = "create_playlist";
+            steps.clear();
+            sequenceMode = false;
         }
 
         @Override
         public ChatResponse call(Prompt prompt) {
             int n = calls.incrementAndGet();
 
+            if (sequenceMode) {
+                String[] step = steps.poll();
+                return step == null
+                        ? finalAnswer()
+                        : toolCall("call-" + n, step[0], step[1]);
+            }
+
             if (n == 1) {
-                return new ChatResponse(
-                        List.of(new Generation(new AssistantMessage(
-                                "",
-                                Map.of(),
-                                List.of(new AssistantMessage.ToolCall(
-                                        "call-1", "function", toolName, toolArgs)))))
-                        ,
-                        ChatResponseMetadata.builder()
-                                .model("stub-model")
-                                .usage(new StubUsage(10, 5))
-                                .build()
-                );
+                return toolCall("call-1", toolName, toolArgs);
             }
 
+            return finalAnswer();
+        }
+
+        private ChatResponse toolCall(String id, String name, String args) {
+            return new ChatResponse(
+                    List.of(new Generation(new AssistantMessage(
+                            "",
+                            Map.of(),
+                            List.of(new AssistantMessage.ToolCall(id, "function", name, args))))),
+                    ChatResponseMetadata.builder()
+                            .model("stub-model")
+                            .usage(new StubUsage(10, 5))
+                            .build()
+            );
+        }
+
+        private ChatResponse finalAnswer() {
             return new ChatResponse(
                     List.of(new Generation(new AssistantMessage("已按你的确认处理"))),
                     ChatResponseMetadata.builder()
```

---

## 5. 复现与校验命令

```bash
# 后端全量（必须 unset 凭据，否则 ModelCredentialConfigTest 会因沙箱里的真实 key 失败）
env -u MKAGENT_MODEL_API_KEY -u AI_DASHSCOPE_API_KEY ./mvnw -o test
# → Tests run: 780, Failures: 0, Errors: 0  BUILD SUCCESS

# 只跑播放相关
env -u MKAGENT_MODEL_API_KEY -u AI_DASHSCOPE_API_KEY ./mvnw -o test "-Dtest=Playback*Test"

# 前端
cd web && npx vue-tsc --noEmit && npx vitest run && npm run build
# → 110 tests / 14 files；build ok

# 页面级（需后端 8123 + Vite 5173）
cd web && node tools/e2e-playback.mjs
# → 23/23
```

---

## 6. 本文没有包含的东西

| 没包含 | 原因 |
| --- | --- |
| 真实签名密钥 | 仓库里只有占位符，没有可附的值 |
| 一条完整的可用签名地址 | 按要求脱敏；文中只有令牌格式与构造代码 |
| 本地数据库文件 / 数据导出 | 按要求不附 |
| 上一轮会话层与前端改版的 diff | 不是本轮改动；工作区里仍以未提交状态保留，未被覆盖 |
| 真实声卡输出、iOS Safari 的 `MediaElementAudioSourceNode` 行为 | E2E 跑在 headless Chrome 且 `--mute-audio`，只能证明"解码在推进"，不能证明"扬声器响了"；已写进 README 的人工听音步骤 |
