package com.example.mkagent.playback;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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
     * 开启一个新的活跃播放实例（覆盖该用户之前的实例）。
     *
     * <p>时间由调用方传入（{@link PlaybackClock}），不写 {@code CURRENT_TIMESTAMP}：
     * "180 秒失效"必须和写入时间用**同一个时钟**，否则测试里没法在不 sleep 的情况下
     * 验证过期，线上也可能出现应用时钟与数据库时钟偏差导致的误判。
     */
    public void startInstance(String userId, long songId, String instanceId, Instant now) {
        java.sql.Timestamp at = java.sql.Timestamp.from(now);

        int updated = jdbcTemplate.update(
                "UPDATE music_now_playing "
                        + "SET song_id = ?, state = ?, instance_id = ?, "
                        + "    started_at = ?, updated_at = ? "
                        + "WHERE user_id = ?",
                songId, PlaybackService.STATE_PLAYING, instanceId, at, at, userId
        );

        if (updated > 0) {
            return;
        }

        try {
            jdbcTemplate.update(
                    "INSERT INTO music_now_playing "
                            + "(user_id, song_id, state, instance_id, started_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    userId, songId, PlaybackService.STATE_PLAYING, instanceId, at, at
            );
        } catch (org.springframework.dao.DuplicateKeyException race) {
            // 并发下另一个请求刚插入：再 UPDATE 一次即可，不算失败
            jdbcTemplate.update(
                    "UPDATE music_now_playing "
                            + "SET song_id = ?, state = ?, instance_id = ?, "
                            + "    started_at = ?, updated_at = ? "
                            + "WHERE user_id = ?",
                    songId, PlaybackService.STATE_PLAYING, instanceId, at, at, userId
            );
        }
    }

    /**
     * 心跳：只在**同一实例且仍在播放**时刷新最后活跃时间。
     *
     * @return false 表示实例不匹配或已不在播放（调用方据此返回 409，不写任何数据）
     */
    public boolean touchIfPlaying(String userId, String instanceId, Instant now) {
        int updated = jdbcTemplate.update(
                "UPDATE music_now_playing SET updated_at = ? "
                        + "WHERE user_id = ? AND instance_id = ? AND state = ?",
                java.sql.Timestamp.from(now), userId, instanceId, PlaybackService.STATE_PLAYING
        );
        return updated > 0;
    }

    /**
     * 在**同一实例**上切换状态（暂停 / 停止）。
     *
     * @return false 表示实例不匹配（旧标签页不能停掉新实例）
     */
    public boolean setStateIfInstance(
            String userId, String instanceId, String state, Instant now) {
        int updated = jdbcTemplate.update(
                "UPDATE music_now_playing SET state = ?, updated_at = ? "
                        + "WHERE user_id = ? AND instance_id = ?",
                state, java.sql.Timestamp.from(now), userId, instanceId
        );
        return updated > 0;
    }

    /**
     * 当前曲目；从未播放过返回 empty。
     */
    public Optional<NowPlaying> findNowPlaying(String userId) {
        List<NowPlaying> rows = jdbcTemplate.query(
                "SELECT song_id, state, instance_id, started_at, updated_at "
                        + "FROM music_now_playing WHERE user_id = ?",
                (rs, rowNum) -> new NowPlaying(
                        rs.getLong("song_id"),
                        rs.getString("state"),
                        rs.getString("instance_id"),
                        toInstant(rs.getTimestamp("started_at")),
                        toInstant(rs.getTimestamp("updated_at"))
                ),
                userId
        );
        return rows.stream().findFirst();
    }

    private static Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
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
    public record NowPlaying(
            long songId,
            String state,
            String instanceId,
            java.time.Instant startedAt,
            java.time.Instant updatedAt
    ) {
    }
}
