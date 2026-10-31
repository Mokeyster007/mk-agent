package com.example.mkagent.music;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 歌单写入服务（M2d）。
 *
 * 为什么单独一层而不是直接在工具里写 SQL：
 * 1. **事务边界**。工具方法上标 {@code @Transactional} 依赖代理，
 *    而工具回调是 {@code ToolCallbacks.from(bean)} 出来的，
 *    代理细节（CGLIB / 目标类）不是我们能控制的；
 *    把事务放在独立的 {@code @Service} 上，由容器注入代理，行为确定。
 * 2. **工具层只做"参数是否合法"**，这里只做"数据是否合法 + 落库"，
 *    两边的测试可以分别用桩和真实数据库。
 *
 * 所有方法都以 userId 为**必填入参**，且写入前必须校验归属：
 * 越权写入不能靠上层"记得传对 userId"来保证。
 *
 * 已知限制（写工具目前不在任何白名单里，M3 开放前需解决）：
 * {@code music_playlist.id} 没有自增/序列，只能取 {@code MAX(id)+1}。
 * 并发创建时可能算出同一个 id，主键冲突会让其中一个事务回滚
 * （失败关闭，不会写脏数据），但用户会看到一次失败。
 * 正式开放前应改为 identity 列或独立序列。
 */
@Service
public class MusicPlaylistWriteService {

    /**
     * 单次最多写入的歌曲数（与工具层的上限一致）。
     */
    public static final int MAX_SONGS = 50;

    /**
     * 加歌结果：新增几首、跳过几首（跳过 = 歌单里已经有了）。
     */
    public record AddResult(long playlistId, int added, int skipped) {
    }

    /**
     * 同名歌单已存在（由 uk_music_playlist_user_name 兜底）。
     */
    public static class PlaylistNameTakenException extends RuntimeException {

        public PlaylistNameTakenException() {
            super("同名歌单已存在");
        }
    }

    /**
     * 歌单不存在**或**不属于当前用户。
     *
     * 两种情况刻意合并成一个异常：区分开就等于告诉调用方
     * "这个歌单是存在的，只是不是你的"。
     */
    public static class PlaylistNotFoundException extends RuntimeException {

        public PlaylistNotFoundException() {
            super("歌单不存在或无权修改");
        }
    }

    /**
     * 参数里有曲库中不存在的歌曲 ID。
     */
    public static class UnknownSongException extends RuntimeException {

        private final transient List<Long> missingSongIds;

        public UnknownSongException(List<Long> missingSongIds) {
            super("歌曲不存在");
            this.missingSongIds = List.copyOf(missingSongIds);
        }

        public List<Long> getMissingSongIds() {
            return missingSongIds;
        }
    }

    private final JdbcTemplate jdbcTemplate;

    public MusicPlaylistWriteService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 创建歌单并一次性加入歌曲（要么全成功，要么全回滚）。
     *
     * @param userId  服务端固化的用户标识
     * @param name    已由工具层校验过的名称
     * @param songIds 已去重、已校验数量的歌曲 ID
     * @return 新歌单 ID
     */
    @Transactional
    public long createPlaylist(String userId, String name, List<Long> songIds) {
        if (nameTaken(userId, name)) {
            throw new PlaylistNameTakenException();
        }

        List<Long> songs = distinct(songIds);
        requireAllSongsExist(songs);

        long playlistId = nextPlaylistId();

        jdbcTemplate.update(
                "INSERT INTO music_playlist "
                        + "(id, user_id, name, description, is_public, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                playlistId,
                userId,
                name,
                null,
                false,
                Timestamp.valueOf(LocalDateTime.now())
        );

        insertSongs(playlistId, songs, 1);

        return playlistId;
    }

    /**
     * 向自己的歌单追加歌曲。
     *
     * 语义幂等：已经在歌单里的歌**跳过**而不是报错——
     * 模型重复请求同一批歌是常态，报错会浪费一轮且没有信息量。
     *
     * @throws PlaylistNotFoundException 歌单不存在或不属于该用户
     */
    @Transactional
    public AddResult addSongs(String userId, long playlistId, List<Long> songIds) {
        requireOwned(userId, playlistId);

        List<Long> songs = distinct(songIds);
        requireAllSongsExist(songs);

        Set<Long> already = new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT song_id FROM music_playlist_song WHERE playlist_id = ?",
                Long.class,
                playlistId
        ));

        Integer maxPosition = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(position), 0) FROM music_playlist_song "
                        + "WHERE playlist_id = ?",
                Integer.class,
                playlistId
        );

        int position = maxPosition == null ? 0 : maxPosition;
        int added = 0;
        int skipped = 0;

        for (Long songId : songs) {
            if (already.contains(songId)) {
                skipped++;
                continue;
            }

            jdbcTemplate.update(
                    "INSERT INTO music_playlist_song "
                            + "(playlist_id, song_id, position, added_at) "
                            + "VALUES (?, ?, ?, ?)",
                    playlistId,
                    songId,
                    ++position,
                    Timestamp.valueOf(LocalDateTime.now())
            );
            added++;
        }

        return new AddResult(playlistId, added, skipped);
    }

    /**
     * 歌单归属校验：查不到与不是本人的返回同一个异常。
     */
    private void requireOwned(String userId, long playlistId) {
        List<String> owners = jdbcTemplate.queryForList(
                "SELECT user_id FROM music_playlist WHERE id = ?",
                String.class,
                playlistId
        );

        if (owners.isEmpty() || !userId.equals(owners.get(0))) {
            throw new PlaylistNotFoundException();
        }
    }

    private boolean nameTaken(String userId, String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_playlist WHERE user_id = ? AND name = ?",
                Integer.class,
                userId,
                name
        );
        return count != null && count > 0;
    }

    /**
     * 一次性查回所有存在的歌曲 ID，缺哪个一目了然。
     */
    private void requireAllSongsExist(List<Long> songIds) {
        if (songIds.isEmpty()) {
            return;
        }

        String placeholders = String.join(",",
                Collections.nCopies(songIds.size(), "?"));

        Set<Long> existing = new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT id FROM music_song WHERE id IN (" + placeholders + ")",
                Long.class,
                songIds.toArray()
        ));

        List<Long> missing = new ArrayList<>();
        for (Long songId : songIds) {
            if (!existing.contains(songId)) {
                missing.add(songId);
            }
        }

        if (!missing.isEmpty()) {
            throw new UnknownSongException(missing);
        }
    }

    private void insertSongs(long playlistId, List<Long> songIds, int startPosition) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int position = startPosition;

        for (Long songId : songIds) {
            jdbcTemplate.update(
                    "INSERT INTO music_playlist_song "
                            + "(playlist_id, song_id, position, added_at) "
                            + "VALUES (?, ?, ?, ?)",
                    playlistId,
                    songId,
                    position++,
                    now
            );
        }
    }

    /**
     * 分配歌单 ID。
     *
     * 见类注释的"已知限制"：没有序列可用，只能取 MAX+1。
     */
    private long nextPlaylistId() {
        Long max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM music_playlist",
                Long.class
        );
        return (max == null ? 0L : max) + 1L;
    }

    /**
     * 去重并保持传入顺序（顺序决定 position）。
     */
    private static List<Long> distinct(List<Long> songIds) {
        if (songIds == null || songIds.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(songIds));
    }
}
