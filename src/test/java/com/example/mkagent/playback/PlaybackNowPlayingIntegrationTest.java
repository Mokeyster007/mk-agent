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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活跃播放（服务端事实）与会话内候选的端到端回归。
 *
 * <p>这一组用例覆盖三件"前端说了不算"的事：
 * <ol>
 *   <li>「正在播放」必须由服务端复核后落库，且**只在真的在播时**成立
 *       （暂停、停止、心跳超时都不算）；</li>
 *   <li>心跳只能刷新当前播放实例：不能换曲目、不能复活已停止的实例、
 *       旧标签页不能覆盖新实例；</li>
 *   <li>候选只能来自会话已存的真实工具引用，不能来自模型文本。</li>
 * </ol>
 *
 * <p>时间用 {@link MutableClock} 控制：180 秒失效、12 小时续播这类规则
 * 不应该靠 {@code Thread.sleep} 验证。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
class PlaybackNowPlayingIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String USER = MusicSeedInitializer.USER_ALICE;

    private static final String OTHER_USER = MusicSeedInitializer.USER_BOB;

    private static final String CONVERSATION_ID = "conv-playback-test-0001";

    private static final String INSTANCE_1 = "inst-0001";

    private static final String INSTANCE_2 = "inst-0002";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void reset() {
        clock.reset();

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
        jdbcTemplate.update(
                "DELETE FROM music_now_playing WHERE user_id IN (?, ?)", USER, OTHER_USER);
    }

    /* ==================== 开始 / 心跳 / 暂停 / 停止 ==================== */

    @Test
    @DisplayName("START 后能读回当前曲目，并写入一条播放日志")
    void startIsRecordedAndLogged() throws Exception {
        int logsBefore = playLogCount(USER);

        HttpResponse<String> started = start(USER, 1901, INSTANCE_1);

        assertEquals(200, started.statusCode(), started.body());
        assertEquals("PLAYING", JSON.readTree(started.body()).path("state").asText());

        HttpResponse<String> current = nowPlaying(USER);
        assertEquals(200, current.statusCode());
        assertEquals(1901L, JSON.readTree(current.body()).path("songId").asLong());

        assertEquals(logsBefore + 1, playLogCount(USER), "真实开始播放应当留下一条播放日志");
    }

    @Test
    @DisplayName("心跳只刷新时间：不新增播放日志，也不改变当前曲目")
    void heartbeatRefreshesWithoutNewLog() throws Exception {
        start(USER, 1901, INSTANCE_1);
        int logsAfterStart = playLogCount(USER);

        clock.advance(Duration.ofSeconds(30));
        HttpResponse<String> beat = heartbeat(USER, 1901, INSTANCE_1);

        assertEquals(200, beat.statusCode(), beat.body());
        assertEquals(logsAfterStart, playLogCount(USER), "重复心跳不能刷播放日志");

        assertEquals(200, nowPlaying(USER).statusCode(), "心跳后仍在有效窗口内");
    }

    @Test
    @DisplayName("心跳不能切歌：携带别的 songId 也只会返回当前曲目")
    void heartbeatCannotSwitchTrack() throws Exception {
        start(USER, 1901, INSTANCE_1);

        HttpResponse<String> beat = heartbeat(USER, 1902, INSTANCE_1);

        assertEquals(200, beat.statusCode(), beat.body());
        assertEquals(1901L, JSON.readTree(beat.body()).path("songId").asLong(),
                "心跳不是换曲入口，songId 被忽略");

        assertEquals(1901L, JSON.readTree(nowPlaying(USER).body()).path("songId").asLong());
    }

    @Test
    @DisplayName("暂停不算正在播放（查询 204），但仍保留当前实例")
    void pausedIsNotPlaying() throws Exception {
        start(USER, 1901, INSTANCE_1);

        HttpResponse<String> paused = report(USER, 1901, "PAUSE", INSTANCE_1);
        assertEquals(200, paused.statusCode(), paused.body());
        assertEquals("PAUSED", JSON.readTree(paused.body()).path("state").asText());

        assertEquals(204, nowPlaying(USER).statusCode(), "暂停不是'正在播放'");
    }

    @Test
    @DisplayName("停止后没有当前曲目；心跳不能复活它")
    void stoppedClearsAndCannotBeRevived() throws Exception {
        start(USER, 1901, INSTANCE_1);
        assertEquals(200, report(USER, 1901, "STOP", INSTANCE_1).statusCode());

        assertEquals(204, nowPlaying(USER).statusCode());

        HttpResponse<String> beat = heartbeat(USER, 1901, INSTANCE_1);
        assertEquals(409, beat.statusCode(), "已停止的实例不能被心跳复活");
        assertEquals("PLAYBACK_INSTANCE_STALE",
                JSON.readTree(beat.body()).path("code").asText());

        assertEquals(204, nowPlaying(USER).statusCode());
    }

    @Test
    @DisplayName("超过 180 秒没有心跳：查询不可用，但实例仍在，下一次心跳可恢复")
    void noHeartbeatWithinTtlExpires() throws Exception {
        start(USER, 1901, INSTANCE_1);

        clock.advance(Duration.ofSeconds(181));
        assertEquals(204, nowPlaying(USER).statusCode(),
                "3 个心跳周期没有消息，就不能再声称'正在播放'");

        // 过期是**读侧窗口**，不是硬删除：后台标签页恢复后的一次心跳应当重新生效
        clock.advance(Duration.ofSeconds(1));
        assertEquals(200, heartbeat(USER, 1901, INSTANCE_1).statusCode());
        assertEquals(200, nowPlaying(USER).statusCode());
    }

    /* ==================== 实例隔离 ==================== */

    @Test
    @DisplayName("旧标签页的迟到心跳不能覆盖新实例")
    void staleInstanceCannotOverrideNewInstance() throws Exception {
        start(USER, 1901, INSTANCE_1);
        start(USER, 1902, INSTANCE_2);

        HttpResponse<String> beat = heartbeat(USER, 1901, INSTANCE_1);

        assertEquals(409, beat.statusCode(), beat.body());
        assertEquals(1902L, JSON.readTree(nowPlaying(USER).body()).path("songId").asLong(),
                "当前曲目必须还是新实例那一首");
    }

    @Test
    @DisplayName("旧标签页的 PAUSE / STOP 不能停掉新实例")
    void staleInstanceCannotStopNewInstance() throws Exception {
        start(USER, 1901, INSTANCE_1);
        start(USER, 1902, INSTANCE_2);

        assertEquals(409, report(USER, 1901, "PAUSE", INSTANCE_1).statusCode());
        assertEquals(409, report(USER, 1901, "STOP", INSTANCE_1).statusCode());

        HttpResponse<String> current = nowPlaying(USER);
        assertEquals(200, current.statusCode());
        assertEquals(1902L, JSON.readTree(current.body()).path("songId").asLong());
    }

    @Test
    @DisplayName("播放实例按用户隔离：别人的 instanceId 不能操作我的播放")
    void instanceIsScopedToUser() throws Exception {
        start(USER, 1901, INSTANCE_1);

        assertEquals(409, heartbeat(OTHER_USER, 1901, INSTANCE_1).statusCode());
        assertEquals(204, nowPlaying(OTHER_USER).statusCode());
        assertEquals(200, nowPlaying(USER).statusCode());
    }

    /* ==================== 输入校验 ==================== */

    @Test
    @DisplayName("上报无音源 / 越权曲目：拒绝落库（前端声称不算数）")
    void ineligibleTracksCannotBeClaimed() throws Exception {
        assertEquals(403, start(USER, 1001, INSTANCE_1).statusCode());
        assertEquals(403, start(USER, 1903, INSTANCE_1).statusCode());
        assertEquals(204, nowPlaying(USER).statusCode());
    }

    @Test
    @DisplayName("未知事件：400")
    void invalidEventIsRejected() throws Exception {
        HttpResponse<String> response = report(USER, 1901, "DANCING", INSTANCE_1);
        assertEquals(400, response.statusCode());
    }

    @Test
    @DisplayName("缺 instanceId：400（否则无法做实例隔离）")
    void missingInstanceIsRejected() throws Exception {
        HttpResponse<String> response = report(USER, 1901, "START", null);
        assertEquals(400, response.statusCode());
        assertEquals("INVALID_INSTANCE_ID", JSON.readTree(response.body()).path("code").asText());
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
                        OTHER_USER);

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

    private int playLogCount(String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_play_log WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    private HttpResponse<String> start(String userId, long songId, String instanceId)
            throws Exception {
        return report(userId, songId, "START", instanceId);
    }

    private HttpResponse<String> heartbeat(String userId, long songId, String instanceId)
            throws Exception {
        return report(userId, songId, "HEARTBEAT", instanceId);
    }

    private HttpResponse<String> report(
            String userId, long songId, String event, String instanceId) throws Exception {
        String body = "{\"songId\":" + songId
                + ",\"event\":\"" + event + "\""
                + (instanceId == null ? "" : ",\"instanceId\":\"" + instanceId + "\"")
                + "}";
        return post("/api/ai/music/playback/now-playing", body, userId);
    }

    private HttpResponse<String> nowPlaying(String userId) throws Exception {
        return get("/api/ai/music/playback/now-playing", userId);
    }

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

    /* ==================== 可控时钟 ==================== */

    /**
     * 可拨动的播放时钟。
     *
     * <p>用 {@code @Primary} 覆盖生产里的 {@link PlaybackClock}：
     * 这样"180 秒失效"是确定性断言，而不是等待 3 分钟。
     */
    static final class MutableClock extends PlaybackClock {

        private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

        private volatile Instant current = BASE;

        @Override
        public Instant now() {
            return current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        void reset() {
            current = BASE;
        }
    }

    @TestConfiguration
    static class ClockConfig {

        @Bean
        @Primary
        MutableClock mutablePlaybackClock() {
            return new MutableClock();
        }
    }
}
