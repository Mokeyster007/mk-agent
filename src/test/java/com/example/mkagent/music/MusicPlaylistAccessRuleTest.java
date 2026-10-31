package com.example.mkagent.music;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.tools.MusicPlaylistQueryTool;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 歌单访问规则的**真实 SQL** 验证（H2 + 种子数据）。
 *
 * 为什么必须连库：规则写在 `findPlaylistSongs` 的 WHERE 里
 * （`p.user_id = ? OR p.is_public = TRUE`），
 * 用桩仓库只能证明"工具把 userId 传下去了"，
 * 证明不了"数据库确实按 owner/public 过滤"——
 * 而越权恰恰发生在 SQL 写错的时候。
 *
 * 种子数据（见 MusicSeedInitializer）：
 * <ul>
 *   <li>alice 的私有歌单「深夜通勤」（8 首）</li>
 *   <li>alice 的公开歌单「周末清晨」（6 首）</li>
 *   <li>bob 的公开歌单「跑步歌单」（5 首）</li>
 * </ul>
 *
 * 歌单 id 由数据库 identity 生成，所以这里**按 (user_id, name) 回查**，
 * 不再硬编码 2001/2002/2003——硬编码会随种子插入顺序变化而失效。
 *
 * 四象限全部覆盖：他人私有（拒）、他人公开（可读）、
 * 本人私有（可读）、管理员读他人（拒）。
 */
@HermeticSpringBootTest
class MusicPlaylistAccessRuleTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class McpStubConfig {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    private static final String ALICE = "music-user-1";

    private static final String BOB = "music-user-2";

    private static final String ADMIN = "music-admin-1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MusicCatalogRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long alicePrivate;

    private long alicePublic;

    private long bobPublic;

    @BeforeEach
    void resolveSeedPlaylistIds() {
        alicePrivate = playlistId(
                ALICE, MusicSeedInitializer.PLAYLIST_NAME_PRIVATE_ALICE);
        alicePublic = playlistId(
                ALICE, MusicSeedInitializer.PLAYLIST_NAME_PUBLIC_ALICE);
        bobPublic = playlistId(
                BOB, MusicSeedInitializer.PLAYLIST_NAME_PUBLIC_BOB);
    }

    @Autowired
    private MusicPlaylistQueryTool playlistTool;

    @AfterEach
    void tearDown() {
        AgentRunContextHolder.clear();
    }

    @Test
    @DisplayName("他人私有歌单：读不到（空列表，不区分不存在）")
    void otherUsersPrivatePlaylistIsInvisible() {
        assertTrue(repository.findPlaylistSongs(BOB, alicePrivate).isEmpty(),
                "bob 不应读到 alice 的私有歌单");
    }

    @Test
    @DisplayName("他人公开歌单：可读")
    void otherUsersPublicPlaylistIsReadable() {
        assertFalse(repository.findPlaylistSongs(BOB, alicePublic).isEmpty(),
                "bob 应能读到 alice 的公开歌单");
    }

    @Test
    @DisplayName("本人私有歌单：可读")
    void ownPrivatePlaylistIsReadable() {
        assertFalse(repository.findPlaylistSongs(ALICE, alicePrivate).isEmpty(),
                "alice 应能读到自己的私有歌单");
    }

    @Test
    @DisplayName("管理员读他人歌单：同样读不到（管理员只有聚合统计）")
    void adminCannotReadOtherUsersPlaylist() {
        assertTrue(repository.findPlaylistSongs(ADMIN, alicePrivate).isEmpty(),
                "管理员不得读取他人歌单");
        assertTrue(repository.findUserPlaylists(ADMIN).isEmpty(),
                "管理员没有自己的歌单时就是空列表");
    }

    @Test
    @DisplayName("我的歌单：只返回本人的，不含他人的公开歌单")
    void userPlaylistsContainOnlyOwn() {
        List<MusicCatalogRepository.PlaylistView> bobPlaylists =
                repository.findUserPlaylists(BOB);

        assertEquals(1, bobPlaylists.size(), "bob 只有一个歌单：" + bobPlaylists);
        assertEquals(bobPublic, bobPlaylists.get(0).id());
    }

    @Test
    @DisplayName("工具层：无权与不存在返回同一文案，不泄露存在性")
    void toolReportsMissingAndForbiddenIdentically() throws Exception {
        bindUser(BOB);

        JsonNode forbidden = objectMapper.readTree(
                playlistTool.getPlaylistSongs(alicePrivate));
        JsonNode missing = objectMapper.readTree(
                playlistTool.getPlaylistSongs(999_999L));

        assertFalse(forbidden.path("ok").asBoolean());
        assertFalse(missing.path("ok").asBoolean());
        assertEquals(
                missing.path("meta").path("hint").asText()
                        .replace("999999", "{id}"),
                forbidden.path("meta").path("hint").asText()
                        .replace(String.valueOf(alicePrivate), "{id}"),
                "两种情况的文案必须完全一致，否则可以据此探测歌单是否存在"
        );
    }

    @Test
    @DisplayName("工具层：他人公开歌单能读到曲目")
    void toolReadsOtherUsersPublicPlaylist() throws Exception {
        bindUser(BOB);

        JsonNode root = objectMapper.readTree(
                playlistTool.getPlaylistSongs(alicePublic));

        assertTrue(root.path("ok").asBoolean(), root.toString());
        assertTrue(root.path("meta").path("count").asInt() > 0);
    }

    private long playlistId(String userId, String name) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM music_playlist WHERE user_id = ? AND name = ?",
                Long.class,
                userId,
                name);
        assertNotNull(id, "种子歌单必须存在：" + userId + "/" + name);
        return id;
    }

    private void bindUser(String userId) {
        AgentRunContext ctx = new AgentRunContext();
        ctx.setUserId(userId);
        AgentRunContextHolder.bind(ctx);
    }
}
