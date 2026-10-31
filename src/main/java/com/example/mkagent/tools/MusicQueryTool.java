package com.example.mkagent.tools;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.music.MusicCatalogRepository;
import com.example.mkagent.music.MusicCatalogRepository.PlaylistView;
import com.example.mkagent.music.MusicCatalogRepository.SongView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 音乐业务只读工具（M2b）。
 *
 * 四个工具：检索歌曲、歌曲详情、我的歌单、歌单曲目。
 *
 * 三条设计约束：
 *
 * 1. **userId 不是参数**。用户身份从
 *    {@link AgentRunContextHolder#requireUserId()} 取（服务端固化的值）。
 *    让模型传 userId 等于把越权入口直接开放给它；
 *    参数 schema 里连这个字段都不该出现（有测试断言）。
 *
 * 2. **没有上下文就失败关闭**。requireUserId() 在 try 之外调用，
 *    抛出的异常会变成这次工具调用的错误结果；
 *    绝不回落匿名用户——匿名读"我的歌单"只会返回空或别人的数据，
 *    这种静默降级在生产上极难发现。
 *
 * 3. **返回值统一走信封**（{@link ToolResponseEnvelope}）。
 *    业务失败（找不到歌、参数为空）返回 ok=false，
 *    不抛异常——异常只留给"不该发生"的情况（无上下文、DB 故障），
 *    模型看到的永远是可读文本。
 *
 * 本批不提供 get_play_history：播放历史属于强隐私数据，
 * 等 M3 的权限模型落地再开放。
 */
@Component
public class MusicQueryTool {

    private static final Logger log =
            LoggerFactory.getLogger(MusicQueryTool.class);

    /**
     * 默认返回条数。
     */
    static final int DEFAULT_LIMIT = 10;

    /**
     * 单次最大返回条数。
     *
     * 上限 50 是"上下文预算"约束：50 首歌的 JSON 已经接近
     * ToolResponseEnvelope 的 4000 字符上限，再多只会被截断。
     */
    static final int MAX_LIMIT = 50;

    private final MusicCatalogRepository repository;

    public MusicQueryTool(MusicCatalogRepository repository) {
        this.repository = repository;
    }

    @Tool(
            name = "search_songs",
            description = """
                    在曲库里按关键词检索歌曲（标题或歌手包含关键词，不区分大小写）。

                    当用户想找歌、找某个歌手的作品、找某张专辑里的歌时调用。
                    返回歌曲 ID、标题、歌手、专辑、时长（秒）、流派、发行年份。

                    拿不准时先用较小的 limit 看结果，再决定要不要扩大范围。
                    """
    )
    public String searchSongs(
            @ToolParam(
                    description = """
                            搜索关键词，匹配歌曲标题或歌手名。
                            不要为空；不要用 % 或 _ 之类的通配符，
                            它们会被当作普通字符转义。
                            """
            )
            String keyword,

            @ToolParam(
                    required = false,
                    description = """
                            返回条数，默认 10，最大 50。
                            传 0、负数或超过 50 的值会被夹到合法范围。
                            """
            )
            Integer limit
    ) {
        // 失败关闭：放在 try 之外，不能被下面的业务 catch 吞掉。
        AgentRunContextHolder.requireUserId();

        if (keyword == null || keyword.isBlank()) {
            return ToolResponseEnvelope.error("搜索关键词不能为空");
        }

        int effectiveLimit = clampLimit(limit);

        try {
            List<SongView> songs =
                    repository.searchSongs(keyword.trim(), effectiveLimit);

            if (songs.isEmpty()) {
                return ToolResponseEnvelope.error(
                        "没有找到匹配「" + keyword.trim() + "」的歌曲，"
                                + "可以换个更短的关键词再试");
            }

            return ToolResponseEnvelope.okList(songs);
        } catch (Exception e) {
            log.warn("歌曲检索失败：keyword={}, reason={}",
                    keyword, e.getClass().getSimpleName());
            return ToolResponseEnvelope.error("歌曲检索失败，请稍后重试");
        }
    }

    @Tool(
            name = "get_song_detail",
            description = """
                    按歌曲 ID 取详情（标题、歌手、专辑、时长、流派、发行年份）。

                    只有已经通过 search_songs 或歌单拿到 ID 时才调用；
                    不要自己编造 ID。
                    """
    )
    public String getSongDetail(
            @ToolParam(
                    description = "歌曲 ID（正整数，来自 search_songs 或歌单曲目结果）"
            )
            Long songId
    ) {
        AgentRunContextHolder.requireUserId();

        if (songId == null || songId <= 0) {
            return ToolResponseEnvelope.error("歌曲 ID 必须是正整数");
        }

        try {
            SongView song = repository.findSong(songId);
            if (song == null) {
                return ToolResponseEnvelope.error("未找到该歌曲：" + songId);
            }
            return ToolResponseEnvelope.okSingle(song);
        } catch (Exception e) {
            log.warn("歌曲详情读取失败：songId={}, reason={}",
                    songId, e.getClass().getSimpleName());
            return ToolResponseEnvelope.error("歌曲详情读取失败，请稍后重试");
        }
    }

    @Tool(
            name = "get_user_playlists",
            description = """
                    列出当前用户自己创建的歌单（名称、是否公开、歌曲数量）。

                    不需要任何参数：用户身份由服务端提供。
                    看不到别人的歌单；要查看某个公开歌单的曲目，
                    需要先用本工具拿到歌单 ID。
                    """
    )
    public String getUserPlaylists() {
        String userId = AgentRunContextHolder.requireUserId();

        try {
            List<PlaylistView> playlists =
                    repository.findUserPlaylists(userId);

            if (playlists.isEmpty()) {
                return ToolResponseEnvelope.error(
                        "当前用户还没有创建任何歌单");
            }

            return ToolResponseEnvelope.okList(playlists);
        } catch (Exception e) {
            log.warn("歌单列表读取失败：reason={}",
                    e.getClass().getSimpleName());
            return ToolResponseEnvelope.error("歌单列表读取失败，请稍后重试");
        }
    }

    @Tool(
            name = "get_playlist_songs",
            description = """
                    按歌单 ID 取歌单内的歌曲（按歌单内顺序返回）。

                    只有自己的歌单或公开歌单能读到；
                    歌单不存在与无权查看会返回同样的提示，
                    不要据此推测歌单是否存在。
                    """
    )
    public String getPlaylistSongs(
            @ToolParam(
                    description = "歌单 ID（正整数，来自 get_user_playlists 的结果）"
            )
            Long playlistId
    ) {
        String userId = AgentRunContextHolder.requireUserId();

        if (playlistId == null || playlistId <= 0) {
            return ToolResponseEnvelope.error("歌单 ID 必须是正整数");
        }

        try {
            List<SongView> songs =
                    repository.findPlaylistSongs(userId, playlistId);

            if (songs.isEmpty()) {
                return ToolResponseEnvelope.error(
                        "歌单不存在或无权查看：" + playlistId);
            }

            return ToolResponseEnvelope.okList(songs);
        } catch (Exception e) {
            log.warn("歌单曲目读取失败：playlistId={}, reason={}",
                    playlistId, e.getClass().getSimpleName());
            return ToolResponseEnvelope.error("歌单曲目读取失败，请稍后重试");
        }
    }

    /**
     * limit 归一化：null / ≤0 → 默认 10；>50 → 50。
     *
     * 不报错而是夹取：模型经常传 100 想"多拿点"，
     * 报错会浪费一轮；夹到上限既满足意图又不突破预算。
     */
    static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
