package com.example.mkagent.music;

import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 歌单 id 生成方式的回归测试（H2 + 种子数据）。
 *
 * 修复前的做法是 {@code SELECT COALESCE(MAX(id), 0) + 1}：
 * 两个并发创建会算出同一个 id，其中一个撞主键回滚。
 * 现在 id 由 identity 列生成，本测试守住三件事：
 * <ol>
 *   <li>种子插入完成后，写入服务建歌单不撞主键；</li>
 *   <li>连续创建拿到的 id 严格递增；</li>
 *   <li>写入之后再跑一次种子，仍然幂等（哨兵判据没被写入破坏）。</li>
 * </ol>
 *
 * 为什么"种子不写 id"能避免序列不前进：
 * identity 序列只在**数据库自己生成**主键时前进。
 * 显式插入 id 不会推进序列，序列仍停在 1，之后生成的值就会撞上历史行；
 * 让数据库生成则每插一行序列都前进，新值必然大于所有已存在的值。
 */
@HermeticSpringBootTest
class MusicPlaylistIdGenerationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class McpStubConfig {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    /**
     * 测试创建的歌单统一前缀，便于清理，不污染其他用例的行数断言。
     */
    private static final String NAME_PREFIX = "IT-ID-";

    private static final String ALICE = MusicSeedInitializer.USER_ALICE;

    @Autowired
    private MusicPlaylistWriteService writeService;

    @Autowired
    private MusicSeedInitializer seedInitializer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update(
                "DELETE FROM music_playlist_song WHERE playlist_id IN "
                        + "(SELECT id FROM music_playlist WHERE name LIKE ?)",
                NAME_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM music_playlist WHERE name LIKE ?",
                NAME_PREFIX + "%");
    }

    @Test
    @DisplayName("种子插入后创建歌单：不撞主键，且新 id 大于所有种子 id")
    void createAfterSeedDoesNotConflict() {
        Long maxSeedId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM music_playlist", Long.class);

        long playlistId = writeService.createPlaylist(
                ALICE, NAME_PREFIX + "种子之后", List.of(1001L, 1002L));

        assertTrue(playlistId > (maxSeedId == null ? 0L : maxSeedId),
                "生成值应大于种子的最大 id：generated=" + playlistId
                        + ", maxSeed=" + maxSeedId);

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_playlist WHERE id = ?",
                Integer.class, playlistId), "歌单必须真的落库");
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_playlist_song WHERE playlist_id = ?",
                Integer.class, playlistId), "关联歌曲必须真的落库");
    }

    @Test
    @DisplayName("连续创建：id 严格递增（不是 MAX+1 也能自增）")
    void idsIncreaseAcrossCreations() {
        long first = writeService.createPlaylist(
                ALICE, NAME_PREFIX + "递增-1", List.of(1001L));
        long second = writeService.createPlaylist(
                ALICE, NAME_PREFIX + "递增-2", List.of(1002L));

        assertTrue(second > first,
                "第二个歌单 id 必须大于第一个：first=" + first + ", second=" + second);
    }

    @Test
    @DisplayName("写入之后再执行种子：仍然幂等，不新增任何行")
    void seedStaysIdempotentAfterWrite() {
        writeService.createPlaylist(ALICE, NAME_PREFIX + "幂等", List.of(1001L));

        int playlistsBefore = count("music_playlist");
        int songsBefore = count("music_song");

        assertEquals(0, seedInitializer.seed(), "已初始化时种子应跳过");

        assertEquals(songsBefore, count("music_song"));
        assertEquals(playlistsBefore, count("music_playlist"),
                "种子不得因为已有写入而重复插入");
    }

    private int count(String table) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }
}
