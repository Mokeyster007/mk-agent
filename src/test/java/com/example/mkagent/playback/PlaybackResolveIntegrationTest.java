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
