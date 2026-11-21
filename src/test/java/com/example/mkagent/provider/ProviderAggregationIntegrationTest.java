package com.example.mkagent.provider;

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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只读聚合的边界。
 *
 * <p>这一组用例对应"多来源"最容易做错的四件事：
 * <ol>
 *   <li>按歌名去重，把两个平台的同名歌合成一条；</li>
 *   <li>一个来源超时 / 报错，整页一起失败；</li>
 *   <li>把权限不足说成"你没有数据"；</li>
 *   <li>把外部异常原文（可能含 token）回显给前端。</li>
 * </ol>
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
class ProviderAggregationIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String USER = MusicSeedInitializer.USER_ALICE;

    private static final String OTHER = MusicSeedInitializer.USER_BOB;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void reset() {
        jdbcTemplate.update(
                "DELETE FROM music_provider_connection WHERE user_id IN (?, ?)", USER, OTHER);
    }

    /* ==================== 多来源 ==================== */

    @Test
    @DisplayName("两个来源的同名歌曲都保留，身份按 provider + providerTrackId")
    void sameTitleFromTwoProvidersIsNotMerged() throws Exception {
        connect("demo-alpha");
        connect("demo-beta");

        JsonNode result = liked();

        List<JsonNode> nightSongs = new ArrayList<>();
        for (JsonNode track : result.path("tracks")) {
            if ("夜航星".equals(track.path("title").asText())) {
                nightSongs.add(track);
            }
        }

        assertEquals(2, nightSongs.size(), "同名歌曲不能被合并成一条");
        assertNotEquals(
                nightSongs.get(0).path("providerTrackId").asText(),
                nightSongs.get(1).path("providerTrackId").asText());
        assertNotEquals(
                nightSongs.get(0).path("provider").asText(),
                nightSongs.get(1).path("provider").asText());

        for (JsonNode source : result.path("sources")) {
            assertEquals("OK", source.path("status").asText());
        }
    }

    @Test
    @DisplayName("外部曲目不可播放、没有本地 songId（不自动导入曲库）")
    void externalTracksAreReadOnly() throws Exception {
        connect("demo-alpha");

        JsonNode result = liked();
        JsonNode track = result.path("tracks").get(0);

        assertFalse(track.path("playable").asBoolean());
        assertTrue(track.path("playableReason").asText().contains("不代理第三方流"));
        assertTrue(track.path("localSongId").isMissingNode(), "外部 ID 不能填进本地 songId");
    }

    /* ==================== 失败隔离 ==================== */

    @Test
    @DisplayName("一个来源超时：只标记该来源，其它来源照常返回")
    void oneSourceTimeoutDoesNotAffectOthers() throws Exception {
        connect("demo-alpha");
        connect("test-slow");

        JsonNode result = liked();

        assertTrue(result.path("tracks").size() > 0, "正常来源的数据仍应返回");

        String slowStatus = sourceStatus(result, "test-slow");
        assertEquals("TIMEOUT", slowStatus);
        assertEquals("OK", sourceStatus(result, "demo-alpha"));
    }

    @Test
    @DisplayName("来源异常：返回通用文案，不泄露异常原文（可能含 token）")
    void errorMessageIsSanitized() throws Exception {
        connect("test-broken");

        HttpResponse<String> response = get("/api/ai/music/providers/tracks?kind=LIKED", USER);
        assertEquals(200, response.statusCode(), response.body());

        assertFalse(response.body().contains("SECRET-SHOULD-NOT-LEAK"),
                "异常原文不能出现在响应里");

        JsonNode result = JSON.readTree(response.body());
        assertEquals("ERROR", sourceStatus(result, "test-broken"));
        assertTrue(result.path("sources").get(0).path("message").asText().contains("其它来源不受影响"));
    }

    /* ==================== 权限 / 状态 ==================== */

    @Test
    @DisplayName("缺少读喜欢权限：PERMISSION_DENIED，而不是空列表")
    void missingScopeIsPermissionDenied() throws Exception {
        connect("demo-alpha");
        connect("demo-beta");

        jdbcTemplate.update(
                "UPDATE music_provider_connection SET scopes = ? "
                        + "WHERE user_id = ? AND provider = ?",
                "read:playlists", USER, "demo-alpha");

        JsonNode result = liked();

        assertEquals("PERMISSION_DENIED", sourceStatus(result, "demo-alpha"));
        assertEquals("SCOPE_MISSING", sourceErrorCode(result, "demo-alpha"));
        assertEquals("OK", sourceStatus(result, "demo-beta"), "其它来源不受影响");
    }

    @Test
    @DisplayName("授权过期：标记 EXPIRED，其它来源照常")
    void expiredConnectionIsReported() throws Exception {
        connect("demo-alpha");
        connect("demo-beta");

        jdbcTemplate.update(
                "UPDATE music_provider_connection SET status = 'EXPIRED' "
                        + "WHERE user_id = ? AND provider = ?",
                USER, "demo-alpha");

        JsonNode result = liked();

        assertEquals("EXPIRED", sourceStatus(result, "demo-alpha"));
        assertEquals("CONNECTION_EXPIRED", sourceErrorCode(result, "demo-alpha"));
        assertEquals("OK", sourceStatus(result, "demo-beta"));
    }

    /* ==================== 分页 / 空态 ==================== */

    @Test
    @DisplayName("分页按来源生效：同一来源的不同页返回不同条目")
    void paginationIsPerSource() throws Exception {
        connect("demo-alpha");

        JsonNode first = JSON.readTree(
                get("/api/ai/music/providers/tracks?kind=LIKED&size=1&page=1", USER).body());
        JsonNode second = JSON.readTree(
                get("/api/ai/music/providers/tracks?kind=LIKED&size=1&page=2", USER).body());

        assertEquals(1, first.path("tracks").size());
        assertEquals(1, second.path("tracks").size());
        assertNotEquals(
                first.path("tracks").get(0).path("providerTrackId").asText(),
                second.path("tracks").get(0).path("providerTrackId").asText());
    }

    @Test
    @DisplayName("没有连接：返回空条目与空来源（零连接仍可使用本地功能）")
    void noConnectionsReturnsEmpty() throws Exception {
        JsonNode result = JSON.readTree(
                get("/api/ai/music/providers/tracks?kind=LIKED", OTHER).body());

        assertEquals(0, result.path("tracks").size());
        assertEquals(0, result.path("sources").size());
    }

    @Test
    @DisplayName("搜索没有关键词：不发请求，返回空")
    void searchWithoutQueryReturnsEmpty() throws Exception {
        connect("demo-alpha");

        JsonNode result = JSON.readTree(
                get("/api/ai/music/providers/tracks?kind=SEARCH", USER).body());

        assertEquals(0, result.path("tracks").size());
        assertEquals(0, result.path("sources").size());
    }

    /* ==================== 辅助 ==================== */

    private void connect(String provider) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port
                                + "/api/ai/music/providers/" + provider + "/connect"))
                        .timeout(Duration.ofSeconds(10))
                        .header("X-User-Id", USER)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), "连接失败：" + response.body());
    }

    private JsonNode liked() throws Exception {
        HttpResponse<String> response =
                get("/api/ai/music/providers/tracks?kind=LIKED&size=20", USER);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> get(String path, String userId) throws Exception {
        return client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("X-User-Id", userId)
                        .header("Accept", "application/json")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String sourceStatus(JsonNode result, String provider) {
        for (JsonNode source : result.path("sources")) {
            if (provider.equals(source.path("provider").asText())) {
                return source.path("status").asText();
            }
        }
        return "<missing>";
    }

    private static String sourceErrorCode(JsonNode result, String provider) {
        for (JsonNode source : result.path("sources")) {
            if (provider.equals(source.path("provider").asText())) {
                return source.path("errorCode").asText();
            }
        }
        return "<missing>";
    }

    @TestConfiguration
    static class Adapters {

        @Bean
        MusicProviderAdapter testSlowProvider() {
            return new TestProviderAdapters.SlowAdapter();
        }

        @Bean
        MusicProviderAdapter testBrokenProvider() {
            return new TestProviderAdapters.BrokenAdapter();
        }
    }
}
