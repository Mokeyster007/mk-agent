package com.example.mkagent.music;

import com.example.mkagent.entity.MusicSong;
import com.example.mkagent.mapper.MusicSongMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 音乐只读查询仓库。
 *
 * 分工（见 docs/music-agent.md 2.3）：
 * 1. 按主键取单行 → MyBatis-Plus（与 agent_run 一致，零 SQL）；
 * 2. 联表 / 聚合 / LIKE 查询 → JdbcTemplate。
 *    本项目没有 Mapper XML，动态 SQL 用 JdbcTemplate 更直观，
 *    也更容易在 H2（PostgreSQL 兼容模式）上验证。
 *
 * 安全约束：
 * 1. 所有查询都返回**脱敏 DTO**（{@link SongView} / {@link PlaylistView}），
 *    不把实体直接抛给工具层——实体将来加了敏感列也不会意外外泄；
 * 2. 歌单可见性在 SQL 里判定（owner 或 public），
 *    不在 Java 里"查出来再过滤"，避免出现"先读到再丢弃"的窗口；
 * 3. 不提供任何写方法：M2 只有只读工具。
 */
@Repository
public class MusicCatalogRepository {

    /**
     * 歌曲视图（工具返回给模型的字段）。
     */
    public record SongView(
            Long id,
            String title,
            String artist,
            String album,
            Integer durationSeconds,
            String genre,
            Integer releaseYear
    ) {
    }

    /**
     * 歌单视图：songCount 由联表聚合得出，不是冗余计数列。
     */
    public record PlaylistView(
            Long id,
            String name,
            String description,
            boolean isPublic,
            int songCount
    ) {
    }

    private static final RowMapper<SongView> SONG_MAPPER = (rs, rowNum) ->
            new SongView(
                    rs.getLong("id"),
                    rs.getString("title"),
                    rs.getString("artist"),
                    rs.getString("album"),
                    (Integer) rs.getObject("duration_seconds"),
                    rs.getString("genre"),
                    (Integer) rs.getObject("release_year")
            );

    private final JdbcTemplate jdbcTemplate;

    private final MusicSongMapper songMapper;

    public MusicCatalogRepository(
            JdbcTemplate jdbcTemplate,
            MusicSongMapper songMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.songMapper = songMapper;
    }

    /**
     * 按关键词检索歌曲：标题或歌手包含关键词（大小写不敏感）。
     *
     * 曲库是公共数据，不按用户过滤——需要用户归属的是歌单，不是曲库。
     *
     * @param keyword 已由调用方校验非空
     * @param limit   已由调用方限制在 [1, 50]
     */
    public List<SongView> searchSongs(String keyword, int limit) {
        String pattern = "%" + escapeLike(keyword) + "%";

        return jdbcTemplate.query(
                "SELECT id, title, artist, album, duration_seconds, genre, release_year "
                        + "FROM music_song "
                        + "WHERE LOWER(title) LIKE LOWER(?) ESCAPE '\\' "
                        + "   OR LOWER(artist) LIKE LOWER(?) ESCAPE '\\' "
                        + "ORDER BY title, artist "
                        + "LIMIT ?",
                SONG_MAPPER,
                pattern,
                pattern,
                limit
        );
    }

    /**
     * 按 ID 取歌曲详情；不存在返回 null。
     */
    public SongView findSong(long songId) {
        MusicSong song = songMapper.selectById(songId);
        if (song == null) {
            return null;
        }
        return new SongView(
                song.getId(),
                song.getTitle(),
                song.getArtist(),
                song.getAlbum(),
                song.getDurationSeconds(),
                song.getGenre(),
                song.getReleaseYear()
        );
    }

    /**
     * 我的歌单（只返回 user_id 本人的，按创建时间升序）。
     */
    public List<PlaylistView> findUserPlaylists(String userId) {
        return jdbcTemplate.query(
                "SELECT p.id, p.name, p.description, p.is_public, "
                        + "       COUNT(ps.song_id) AS song_count "
                        + "FROM music_playlist p "
                        + "LEFT JOIN music_playlist_song ps ON ps.playlist_id = p.id "
                        + "WHERE p.user_id = ? "
                        + "GROUP BY p.id, p.name, p.description, p.is_public, p.created_at "
                        + "ORDER BY p.created_at, p.id",
                (rs, rowNum) -> new PlaylistView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getBoolean("is_public"),
                        rs.getInt("song_count")
                ),
                userId
        );
    }

    /**
     * 歌单内的歌曲（按 position 顺序）。
     *
     * 可见性判定放在 SQL 里：
     * 只有"自己的歌单"或"公开歌单"能查到行；
     * 查不到与不存在返回同一个空列表——不向模型泄露"这个歌单是否存在"。
     */
    public List<SongView> findPlaylistSongs(String userId, long playlistId) {
        return jdbcTemplate.query(
                "SELECT s.id, s.title, s.artist, s.album, "
                        + "       s.duration_seconds, s.genre, s.release_year "
                        + "FROM music_playlist_song ps "
                        + "JOIN music_song s ON s.id = ps.song_id "
                        + "JOIN music_playlist p ON p.id = ps.playlist_id "
                        + "WHERE ps.playlist_id = ? "
                        + "  AND (p.user_id = ? OR p.is_public = TRUE) "
                        + "ORDER BY ps.position",
                SONG_MAPPER,
                playlistId,
                userId
        );
    }

    /**
     * 转义 LIKE 通配符。
     *
     * 不转义的话，关键词里的 % 会变成"匹配任意内容"：
     * 模型传 "100%" 会命中整个曲库，既浪费上下文又容易被当成"注入"利用。
     */
    static String escapeLike(String keyword) {
        return keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
