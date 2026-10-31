package com.example.mkagent.tools;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.music.MusicSeedInitializer;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 歌单写工具（H2 + 真实事务 + 种子数据）。
 *
 * 直接调用工具方法并绑定 {@link AgentRunContextHolder}——
 * 这条链路就是 M3 审批恢复后会走的链路，
 * 提前把它测通，M3 只需要加"挂起 → 确认 → 恢复"。
 *
 * 覆盖：
 * 1. 成功路径（创建 / 追加），并检查真的落库；
 * 2. 归属校验：他人歌单被拒，且文案不泄露"歌单是否存在"；
 * 3. 语义幂等：重复加同一首歌 → 跳过而不是报错；
 * 4. 参数越界（名称长度、控制字符、数量、非法 ID）；
 * 5. 缺失上下文 → 失败关闭（抛异常，不回落匿名用户）。
 */
@HermeticSpringBootTest
class PlaylistWriteToolTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class McpStubConfig {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    /**
     * 测试创建的歌单统一用这个前缀，便于清理，也不会碰到种子数据。
     */
    private static final String NAME_PREFIX = "IT-歌单-";

    private static final String ALICE = "music-user-1";

    private static final String BOB = "music-user-2";

    /**
     * 种子曲库里确定存在的三首歌。
     */
    private static final List<Long> SEED_SONGS = List.of(1001L, 1002L, 1003L);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private PlaylistWriteTool tool;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * alice 的私有种子歌单 id（由数据库生成，按 (user_id, name) 回查）。
     */
    private long alicePrivate;

    @BeforeEach
    void resolveSeedPlaylist() {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM music_playlist WHERE user_id = ? AND name = ?",
                Long.class,
                ALICE,
                MusicSeedInitializer.PLAYLIST_NAME_PRIVATE_ALICE);
        assertNotNull(id, "种子歌单必须存在");
        alicePrivate = id;
    }

    @AfterEach
    void tearDown() {
        AgentRunContextHolder.clear();
        jdbcTemplate.update(
                "DELETE FROM music_playlist_song WHERE playlist_id IN "
                        + "(SELECT id FROM music_playlist WHERE name LIKE ?)",
                NAME_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM music_playlist WHERE name LIKE ?",
                NAME_PREFIX + "%");
    }

    @Test
    @DisplayName("创建成功：返回歌单 ID 与歌曲数，数据真的落库")
    void createPlaylistSucceeds() throws Exception {
        bindUser(ALICE);

        JsonNode root = objectMapper.readTree(
                tool.createPlaylist(NAME_PREFIX + "通勤", SEED_SONGS));

        assertTrue(root.path("ok").asBoolean(), root.toString());
        long playlistId = root.path("data").path("playlistId").asLong();
        assertTrue(playlistId > 0);
        assertEquals(3, root.path("data").path("songCount").asInt());

        assertEquals(3, countSongs(playlistId));
        assertEquals("music-user-1", ownerOf(playlistId));
    }

    @Test
    @DisplayName("同名歌单：拒绝且不覆盖")
    void duplicateNameIsRejected() throws Exception {
        bindUser(ALICE);
        tool.createPlaylist(NAME_PREFIX + "重名", List.of(1001L));

        JsonNode root = objectMapper.readTree(
                tool.createPlaylist(NAME_PREFIX + "重名", List.of(1002L)));

        assertFalse(root.path("ok").asBoolean());
        assertTrue(root.path("meta").path("hint").asText().contains("同名歌单"),
                root.toString());
        assertEquals(1, countByName(NAME_PREFIX + "重名"),
                "同名歌单只能有一条");
    }

    @Test
    @DisplayName("歌曲不存在：拒绝整批，不产生半成品歌单")
    void unknownSongRejectsWholeBatch() throws Exception {
        bindUser(ALICE);

        JsonNode root = objectMapper.readTree(
                tool.createPlaylist(NAME_PREFIX + "坏歌",
                        List.of(1001L, 999_999L)));

        assertFalse(root.path("ok").asBoolean());
        assertTrue(root.path("meta").path("hint").asText().contains("不存在"),
                root.toString());
        assertEquals(0, countByName(NAME_PREFIX + "坏歌"),
                "校验失败不能留下歌单");
    }

    @Test
    @DisplayName("追加歌曲：新增与跳过的数量分别报告（语义幂等）")
    void addSongsReportsAddedAndSkipped() throws Exception {
        bindUser(ALICE);
        long playlistId = create(NAME_PREFIX + "追加", List.of(1001L));

        JsonNode first = objectMapper.readTree(
                tool.addSongsToPlaylist(playlistId, List.of(1001L, 1002L)));

        assertTrue(first.path("ok").asBoolean(), first.toString());
        assertEquals(1, first.path("data").path("added").asInt());
        assertEquals(1, first.path("data").path("skipped").asInt());

        // 再传一次同样的歌：全部跳过，不报错。
        JsonNode second = objectMapper.readTree(
                tool.addSongsToPlaylist(playlistId, List.of(1001L, 1002L)));

        assertTrue(second.path("ok").asBoolean(), second.toString());
        assertEquals(0, second.path("data").path("added").asInt());
        assertEquals(2, second.path("data").path("skipped").asInt());
        assertEquals(2, countSongs(playlistId));
    }

    @Test
    @DisplayName("他人歌单：拒绝，且与“不存在”同一文案")
    void otherUsersPlaylistIsRejected() throws Exception {
        bindUser(BOB);

        // alice 的私有歌单（种子数据）。
        JsonNode forbidden = objectMapper.readTree(
                tool.addSongsToPlaylist(alicePrivate, List.of(1001L)));
        JsonNode missing = objectMapper.readTree(
                tool.addSongsToPlaylist(999_999L, List.of(1001L)));

        assertFalse(forbidden.path("ok").asBoolean());
        assertFalse(missing.path("ok").asBoolean());

        String forbiddenHint = forbidden.path("meta").path("hint").asText();
        String missingHint = missing.path("meta").path("hint").asText();
        assertEquals(
                missingHint.replace("999999", "{id}"),
                forbiddenHint.replace(String.valueOf(alicePrivate), "{id}"),
                "两种情况文案必须一致，否则可以探测歌单是否存在"
        );
        assertEquals(8, countSongs(alicePrivate), "拒绝后不能改动歌单");
    }

    @Test
    @DisplayName("名称越界：空、超长、控制字符都被拒，且不落库")
    void invalidNamesAreRejected() throws Exception {
        bindUser(ALICE);

        assertFalse(createRaw("   ", SEED_SONGS)
                .path("ok").asBoolean());
        assertFalse(createRaw("x".repeat(51), SEED_SONGS)
                .path("ok").asBoolean());
        assertFalse(createRaw("标题\n换行", SEED_SONGS)
                .path("ok").asBoolean());
        assertFalse(createRaw("标题\t制表", SEED_SONGS)
                .path("ok").asBoolean());

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_playlist WHERE name LIKE ?",
                Integer.class, NAME_PREFIX + "%"));
    }

    @Test
    @DisplayName("歌曲数量越界：空列表、超过 50 首、非法 ID 都被拒")
    void invalidSongIdsAreRejected() throws Exception {
        bindUser(ALICE);

        assertTrue(createRaw(NAME_PREFIX + "空", List.of())
                .path("meta").path("hint").asText().contains("至少"));

        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= 51; i++) {
            tooMany.add(1000L + i);
        }
        assertTrue(createRaw(NAME_PREFIX + "太多", tooMany)
                .path("meta").path("hint").asText().contains("最多"));

        assertTrue(createRaw(NAME_PREFIX + "零", List.of(0L))
                .path("meta").path("hint").asText().contains("正整数"));

        assertEquals(0, countByName(NAME_PREFIX + "空"));
        assertEquals(0, countByName(NAME_PREFIX + "太多"));
    }

    @Test
    @DisplayName("重复 ID 自动去重，不产生重复行")
    void duplicateSongIdsAreDeduplicated() throws Exception {
        bindUser(ALICE);

        JsonNode root = objectMapper.readTree(tool.createPlaylist(
                NAME_PREFIX + "去重", List.of(1001L, 1001L, 1002L)));

        assertTrue(root.path("ok").asBoolean(), root.toString());
        assertEquals(2, root.path("data").path("songCount").asInt());
        assertEquals(2, countSongs(root.path("data").path("playlistId").asLong()));
    }

    @Test
    @DisplayName("缺失上下文：失败关闭（抛异常，不回落匿名用户）")
    void missingContextFailsClosed() {
        AgentRunContextHolder.clear();

        assertThrows(IllegalStateException.class,
                () -> tool.createPlaylist(NAME_PREFIX + "无上下文", SEED_SONGS));
        assertThrows(IllegalStateException.class,
                () -> tool.addSongsToPlaylist(alicePrivate, SEED_SONGS));

        assertEquals(0, countByName(NAME_PREFIX + "无上下文"));
    }

    // ===== 辅助 =====

    private JsonNode createRaw(String name, List<Long> songIds) throws Exception {
        return objectMapper.readTree(tool.createPlaylist(name, songIds));
    }

    private long create(String name, List<Long> songIds) throws Exception {
        JsonNode root = createRaw(name, songIds);
        assertTrue(root.path("ok").asBoolean(), root.toString());
        return root.path("data").path("playlistId").asLong();
    }

    private void bindUser(String userId) {
        AgentRunContext ctx = new AgentRunContext();
        ctx.setUserId(userId);
        AgentRunContextHolder.bind(ctx);
    }

    private int countSongs(long playlistId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_playlist_song WHERE playlist_id = ?",
                Integer.class, playlistId);
        return value == null ? 0 : value;
    }

    private int countByName(String name) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_playlist WHERE name = ?",
                Integer.class, name);
        return value == null ? 0 : value;
    }

    private String ownerOf(long playlistId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id FROM music_playlist WHERE id = ?", playlistId);
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("user_id"));
    }
}
