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
        assertTrue(root.path("meta").path("hint").asText().contains("没有有效的正在播放"));
    }

    @Test
    @DisplayName("上报播放后：get_now_playing 返回服务端确认过的曲目")
    void nowPlayingReturnsVerifiedTrack() throws Exception {
        bindUser(MusicSeedInitializer.USER_ALICE);
        sourceRepository.startInstance(
                MusicSeedInitializer.USER_ALICE,
                PlaybackFixtureInitializer.SONG_TEST_B,
                "inst-tool-1",
                java.time.Instant.now());

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
        sourceRepository.startInstance(
                MusicSeedInitializer.USER_ALICE,
                PlaybackFixtureInitializer.SONG_TEST_B,
                "inst-tool-2",
                java.time.Instant.now());
        sourceRepository.setStateIfInstance(
                MusicSeedInitializer.USER_ALICE, "inst-tool-2", "STOPPED", java.time.Instant.now());

        JsonNode root = JSON.readTree(playbackTool.getNowPlaying());

        assertFalse(root.path("ok").asBoolean());
    }

    @Test
    @DisplayName("两个用户的「正在播放」互不影响")
    void nowPlayingIsPerUser() throws Exception {
        sourceRepository.startInstance(
                MusicSeedInitializer.USER_ALICE,
                PlaybackFixtureInitializer.SONG_TEST_A,
                "inst-alice",
                java.time.Instant.now());
        sourceRepository.startInstance(
                MusicSeedInitializer.USER_BOB,
                PlaybackFixtureInitializer.SONG_RESTRICTED,
                "inst-bob",
                java.time.Instant.now());

        bindUser(MusicSeedInitializer.USER_ALICE);
        JsonNode alice = JSON.readTree(playbackTool.getNowPlaying());
        assertEquals(PlaybackFixtureInitializer.SONG_TEST_A, alice.path("data").path("songId").asLong());

        AgentRunContextHolder.clear();
        bindUser(MusicSeedInitializer.USER_BOB);
        JsonNode bob = JSON.readTree(playbackTool.getNowPlaying());
        assertEquals(PlaybackFixtureInitializer.SONG_RESTRICTED, bob.path("data").path("songId").asLong());
    }
}
