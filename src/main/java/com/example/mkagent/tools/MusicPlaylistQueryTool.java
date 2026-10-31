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
 * 歌单只读工具（M2b 起，M2c 从 MusicQueryTool 拆出）。
 *
 * 为什么单独一个 Bean：白名单是按 **Bean** 组织的
 * （见 {@code AgentToolProvider}），而管理员不允许读任何歌单。
 * 如果歌单工具和曲库工具挤在同一个 Bean 里，
 * "管理员只拿曲库工具"这条规则就没法用现有机制表达，
 * 只能再叠一层按名字过滤——那种"减法式白名单"最容易在
 * 新增工具时被悄悄突破。
 *
 * 访问规则：
 * 1. 只返回**本人**创建的歌单（`WHERE p.user_id = ?`）；
 * 2. 歌单曲目允许"本人歌单 或 is_public = true"，
 *    其余情况一律返回空 → 工具层统一报"歌单不存在或无权查看"，
 *    不泄露歌单是否存在。
 *
 * userId 不是参数：身份来自 {@link AgentRunContextHolder#requireUserId()}，
 * 无上下文时失败关闭（抛异常），不回落匿名用户。
 */
@Component
public class MusicPlaylistQueryTool {

    private static final Logger log =
            LoggerFactory.getLogger(MusicPlaylistQueryTool.class);

    private final MusicCatalogRepository repository;

    public MusicPlaylistQueryTool(MusicCatalogRepository repository) {
        this.repository = repository;
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
}
