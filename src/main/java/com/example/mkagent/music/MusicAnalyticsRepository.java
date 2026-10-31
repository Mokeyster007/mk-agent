package com.example.mkagent.music;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 播放统计聚合仓库（管理员专用，只读）。
 *
 * 与 {@link MusicCatalogRepository} 的区别：这里的返回值**不含任何用户标识**。
 * 不是"查出来再删掉 user_id"，而是 SQL 里根本不 SELECT 它——
 * 少一个字段就少一条泄露路径，也少一次"忘了脱敏"的机会。
 *
 * 为什么用 JdbcTemplate：需要 GROUP BY + 日期截断 + 排序取前 N，
 * 这是典型的聚合查询，MyBatis-Plus 的单表 CRUD 表达不了；
 * 项目没有 Mapper XML，动态 SQL 用 JdbcTemplate 更直观，
 * 也更容易在 H2（PostgreSQL 兼容模式）上验证。
 *
 * 时间窗口：`created_at >= now - days 天`。
 * 用 `CAST(created_at AS DATE)` 而不是 DATE_TRUNC：
 * 前者在 PostgreSQL 与 H2 上行为一致，后者 H2 需要额外兼容层。
 */
@Repository
public class MusicAnalyticsRepository {

    /**
     * 单日播放量。
     */
    public record DayStat(String day, long plays) {
    }

    /**
     * 歌曲播放排行条目（无 userId）。
     */
    public record SongStat(long songId, String title, String artist, long plays) {
    }

    /**
     * 播放总览：总次数 + 有播放的天数 + 按日明细。
     */
    public record PlayStats(long totalPlays, int activeDays, List<DayStat> days) {
    }

    private final JdbcTemplate jdbcTemplate;

    public MusicAnalyticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 时间窗口下界（含）。
     *
     * 每次查询现算，避免持有过期的"现在"。
     */
    private Timestamp windowStart(int days) {
        return Timestamp.valueOf(LocalDateTime.now().minusDays(days));
    }

    /**
     * 窗口内的总播放次数。
     */
    public long totalPlays(int days) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_play_log WHERE created_at >= ?",
                Long.class,
                windowStart(days)
        );
        return total == null ? 0L : total;
    }

    /**
     * 窗口内的按日播放量（按日期升序，只返回有播放的日期）。
     */
    public List<DayStat> playsByDay(int days) {
        return jdbcTemplate.query(
                "SELECT CAST(created_at AS DATE) AS play_date, COUNT(*) AS plays "
                        + "FROM music_play_log "
                        + "WHERE created_at >= ? "
                        + "GROUP BY CAST(created_at AS DATE) "
                        + "ORDER BY play_date",
                (rs, rowNum) -> new DayStat(
                        rs.getDate("play_date").toLocalDate().toString(),
                        rs.getLong("plays")
                ),
                windowStart(days)
        );
    }

    /**
     * 窗口内播放次数最多的歌曲。
     *
     * 排序：播放次数降序，次数相同时按标题升序——
     * 没有第二排序键时，同分歌曲的顺序由数据库决定，
     * 同样的请求两次可能给出不同结果（对模型是噪声）。
     */
    public List<SongStat> topSongs(int days, int limit) {
        return jdbcTemplate.query(
                "SELECT s.id AS song_id, s.title AS title, s.artist AS artist, "
                        + "       COUNT(*) AS plays "
                        + "FROM music_play_log l "
                        + "JOIN music_song s ON s.id = l.song_id "
                        + "WHERE l.created_at >= ? "
                        + "GROUP BY s.id, s.title, s.artist "
                        + "ORDER BY plays DESC, s.title "
                        + "LIMIT ?",
                (rs, rowNum) -> new SongStat(
                        rs.getLong("song_id"),
                        rs.getString("title"),
                        rs.getString("artist"),
                        rs.getLong("plays")
                ),
                windowStart(days),
                limit
        );
    }
}
