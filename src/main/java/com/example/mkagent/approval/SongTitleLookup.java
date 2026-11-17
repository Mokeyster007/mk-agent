package com.example.mkagent.approval;

import java.util.List;

/**
 * 按歌曲 ID 查标题（只读）。
 *
 * 抽成接口是为了让摘要构建可以在没有数据库的单测里跑：
 * 测试传一个 lambda 即可，不需要起 Spring 上下文。
 */
public interface SongTitleLookup {

    /**
     * 按传入顺序返回标题；查不到的 ID 直接跳过（不占位）。
     *
     * @param songIds 歌曲 ID（可为空）
     */
    List<String> titles(List<Long> songIds);

    /**
     * 按歌单 ID 查歌单名（只查**本人**的歌单）。
     *
     * <p>为什么需要：{@code add_songs_to_playlist} 的参数里只有 {@code playlistId}，
     * 没有歌单名。审批卡如果只显示"未指定歌单名"，用户根本无法确认
     * "到底要往哪个歌单里加歌"——这等于把最重要的一条信息留空。
     *
     * <p>写成 {@code default} 是为了让本接口保持函数式（抽象方法仍只有
     * {@link #titles(List)}）：单测可以直接用 lambda 实现，不必起 Spring 上下文；
     * 而"查不到歌单名"本身就是合法状态，不是每个实现都必须会查。
     *
     * @param playlistId 歌单 ID
     * @param userId     当前用户；为 null 时不查（避免跨用户泄露歌单名）
     * @return 歌单名；查不到或不属于该用户时返回 null
     */
    default String playlistName(long playlistId, String userId) {
        return null;
    }
}
