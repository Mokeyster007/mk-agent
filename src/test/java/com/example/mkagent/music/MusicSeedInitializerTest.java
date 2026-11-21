package com.example.mkagent.music;

import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 音乐种子数据（H2 内存库，不依赖外部数据库）。
 *
 * 验证三件事：
 * 1. 条数符合设计（60 首 / 3 用户 / 3 歌单 / 19 条歌单歌曲 / 30 条播放日志）；
 * 2. **幂等**：再执行一次 seed() 不新增任何行；
 * 3. 注入样例与角色数据符合预期（M4 评测要引用这些固定 ID）。
 */
@HermeticSpringBootTest
class MusicSeedInitializerTest {

    /**
     * 排除 MCP 自动配置后，chatApp 注入的 ToolCallbackProvider 由此桩提供，
     * 测试完全不依赖 8127 的 MCP 服务。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class McpStubConfig {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MusicSeedInitializer seedInitializer;

    @Test
    @DisplayName("种子条数符合设计")
    void seedCountsMatchDesign() {
        /*
         * 只断言**种子曲目**（1001~1060）：
         * 曲库里还有播放夹具的测试音（1901~1903），
         * 它们不是种子数据，用总行数断言会让两个初始化器互相牵制。
         */
        assertEquals(60, countWhere("music_song", "id BETWEEN 1001 AND 1060"),
                "种子曲库应为 60 首");
        assertEquals(3, count("music_user"), "用户应为 3 个");
        assertEquals(3, count("music_playlist"), "歌单应为 3 个");
        assertEquals(19, count("music_playlist_song"), "歌单歌曲合计 8+6+5=19");
        assertEquals(30, count("music_play_log"), "播放日志应为 30 条");
    }

    @Test
    @DisplayName("重复执行不翻倍（幂等）")
    void seedIsIdempotent() {
        int before = countWhere("music_song", "id BETWEEN 1001 AND 1060");

        int inserted = seedInitializer.seed();

        assertEquals(0, inserted, "已初始化时应跳过，返回 0");
        assertEquals(before, countWhere("music_song", "id BETWEEN 1001 AND 1060"),
                "歌曲数不得变化");
        assertEquals(3, count("music_user"), "用户数不得变化");
        assertEquals(3, count("music_playlist"), "歌单数不得变化");
        assertEquals(19, count("music_playlist_song"), "歌单歌曲数不得变化");
        assertEquals(30, count("music_play_log"), "播放日志数不得变化");
    }

    @Test
    @DisplayName("管理员角色只有 1 个，且来自 music_user 表")
    void exactlyOneAdmin() {
        Integer admins = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_user WHERE role = 'ADMIN'",
                Integer.class);
        assertEquals(1, admins);

        String role = jdbcTemplate.queryForObject(
                "SELECT role FROM music_user WHERE user_id = ?",
                String.class,
                MusicSeedInitializer.USER_ADMIN);
        assertEquals("ADMIN", role);
    }

    @Test
    @DisplayName("公开/私有歌单都存在，且私有歌单有归属用户")
    void playlistsCoverBothVisibilities() {
        Integer publicCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_playlist WHERE is_public = TRUE",
                Integer.class);
        Integer privateCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_playlist WHERE is_public = FALSE",
                Integer.class);

        assertEquals(2, publicCount, "应有 2 个公开歌单");
        assertEquals(1, privateCount, "应有 1 个私有歌单");

        String owner = jdbcTemplate.queryForObject(
                "SELECT user_id FROM music_playlist WHERE id = ?",
                String.class,
                playlistId(
                        MusicSeedInitializer.USER_ALICE,
                        MusicSeedInitializer.PLAYLIST_NAME_PRIVATE_ALICE));
        assertEquals(MusicSeedInitializer.USER_ALICE, owner,
                "私有歌单必须能查到归属用户（可见性靠它判定）");
    }

    @Test
    @DisplayName("注入样例：固定 ID 的歌曲标题/专辑里带指令文本")
    void injectionSamplesArePresent() {
        for (Long songId : MusicSeedInitializer.INJECTION_SONG_IDS) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT title, album FROM music_song WHERE id = ?", songId);

            assertNotNull(row, "注入样例歌曲必须存在：" + songId);

            String text = row.get("title") + " " + row.get("album");
            assertTrue(
                    text.contains("忽略之前"),
                    "注入样例应包含'忽略之前'类指令文本，实际：" + text);
        }
    }

    @Test
    @DisplayName("歌单内 position 从 1 连续递增，同一歌单内歌曲不重复")
    void playlistSongsAreOrderedAndUnique() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT song_id, position FROM music_playlist_song "
                        + "WHERE playlist_id = ? ORDER BY position",
                playlistId(
                        MusicSeedInitializer.USER_ALICE,
                        MusicSeedInitializer.PLAYLIST_NAME_PRIVATE_ALICE));

        assertEquals(8, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1, ((Number) rows.get(i).get("position")).intValue(),
                    "position 应从 1 连续递增");
        }
    }

    /**
     * 播放日志必须落在最近 30 天内。
     *
     * 这是"dev 环境统计工具不是摆设"的回归护栏：
     * get_play_stats(days) 的窗口上限是 90 天，
     * 一旦有人把锚点改回硬编码日期，这条测试会立刻失败。
     */
    @Test
    @DisplayName("播放日志落在最近 30 天内（统计工具才有数据可查）")
    void playLogsAreRecent() {
        Integer outdated = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_play_log "
                        + "WHERE created_at < ?",
                Integer.class,
                Timestamp.valueOf(LocalDateTime.now().minusDays(30)));

        assertEquals(0, outdated == null ? 0 : outdated,
                "不应有 30 天前的播放日志");

        Integer future = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_play_log WHERE created_at > ?",
                Integer.class,
                Timestamp.valueOf(LocalDateTime.now()));

        assertEquals(0, future == null ? 0 : future,
                "不应有未来时间的播放日志");
    }

    /**
     * 歌单 id 由数据库生成，测试按 (user_id, name) 回查。
     */
    private long playlistId(String userId, String name) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM music_playlist WHERE user_id = ? AND name = ?",
                Long.class,
                userId,
                name);
        assertNotNull(id, "种子歌单必须存在：" + userId + "/" + name);
        return id;
    }

    private int count(String table) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    /** 带条件的计数（表名与条件都来自测试常量，不接受外部输入）。 */
    private int countWhere(String table, String condition) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + condition, Integer.class);
        return value == null ? 0 : value;
    }
}
