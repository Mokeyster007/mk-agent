package com.example.mkagent.tools;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.music.MusicPlaylistWriteService;
import com.example.mkagent.music.MusicPlaylistWriteService.AddResult;
import com.example.mkagent.music.MusicPlaylistWriteService.PlaylistNameTakenException;
import com.example.mkagent.music.MusicPlaylistWriteService.PlaylistNotFoundException;
import com.example.mkagent.music.MusicPlaylistWriteService.UnknownSongException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 歌单写工具（M2d）。
 *
 * <b>本批不进入任何 AgentType 的白名单</b>：
 * 两个方法都标了 {@link RequiresApproval}，
 * 由 {@code WriteToolGuardTest} 反射扫描并断言"不在任何白名单、不在重试名单里"。
 * 也就是说：代码存在、测试覆盖、但模型拿不到——这是"能力不存在"级别的防护，
 * 比"提示词里叮嘱模型不要写"可靠得多。
 *
 * 为什么这一批就写它：M3 的审批链路需要一个真实的写工具作为落点，
 * 先把"参数校验 + 归属校验 + 事务 + 语义幂等"做对，
 * M3 只加"挂起 → 用户确认 → 恢复执行"，不用再动工具语义。
 *
 * 不做的三件事（刻意）：
 * 1. **不做 rename / delete / remove_song**：删除类操作的幂等语义
 *    与"重复执行结果相同"不同（第二次删已经没了，是失败还是成功？），
 *    需要先定清楚再写；
 * 2. **不做 log_play**：播放日志属于高频写入，应该走独立通道，
 *    不该由模型决定"用户听了什么"；
 * 3. **不接收 userId**：身份只来自 {@link AgentRunContextHolder}。
 */
@Component
public class PlaylistWriteTool {

    private static final Logger log =
            LoggerFactory.getLogger(PlaylistWriteTool.class);

    /**
     * 歌单名称长度上限（按 Unicode 码点计，不是 char）。
     */
    static final int MAX_NAME_LENGTH = 50;

    /**
     * 单次最多涉及的歌曲数（去重后）。
     */
    static final int MAX_SONGS = 50;

    /**
     * 创建结果。
     */
    public record CreateResult(long playlistId, String name, int songCount) {
    }

    private final MusicPlaylistWriteService writeService;

    public PlaylistWriteTool(MusicPlaylistWriteService writeService) {
        this.writeService = writeService;
    }

    @Tool(
            name = "create_playlist",
            description = """
                    创建一个新歌单，并一次性把指定歌曲加入其中。

                    需要人工确认后才会真正写入数据库。
                    歌单默认私有；同名歌单已存在时不会覆盖，会返回失败。
                    """
    )
    @RequiresApproval(reason = "创建歌单会写入数据库")
    public String createPlaylist(
            @ToolParam(
                    description = """
                            歌单名称，1~50 个字符（按 Unicode 码点计），
                            不能包含换行、制表符等控制字符。
                            """
            )
            String name,

            @ToolParam(
                    description = """
                            歌曲 ID 列表，去重后 1~50 个。
                            ID 必须来自 search_songs 或歌单曲目结果，不要自己编造；
                            重复的 ID 会自动去重，顺序决定歌单内顺序。
                            """
            )
            List<Long> songIds
    ) {
        // 失败关闭：放在 try 之外，不能被下面的业务 catch 吞掉。
        String userId = AgentRunContextHolder.requireUserId();

        String normalizedName = name == null ? "" : name.trim();
        String invalid = validateName(normalizedName);
        if (invalid != null) {
            return ToolResponseEnvelope.error(invalid);
        }

        List<Long> songs = normalizeSongIds(songIds);
        String invalidSongs = validateSongIds(songs);
        if (invalidSongs != null) {
            return ToolResponseEnvelope.error(invalidSongs);
        }

        try {
            long playlistId = writeService.createPlaylist(
                    userId, normalizedName, songs);

            return ToolResponseEnvelope.okSingle(
                    new CreateResult(playlistId, normalizedName, songs.size()));
        } catch (PlaylistNameTakenException e) {
            return ToolResponseEnvelope.error(
                    "已存在同名歌单，请换一个名称");
        } catch (UnknownSongException e) {
            return ToolResponseEnvelope.error(
                    "以下歌曲不存在：" + joinIds(e.getMissingSongIds()));
        } catch (Exception e) {
            log.warn("创建歌单失败：reason={}", e.getClass().getSimpleName());
            return ToolResponseEnvelope.error("创建歌单失败，请稍后重试");
        }
    }

    @Tool(
            name = "add_songs_to_playlist",
            description = """
                    把歌曲加入一个已存在的歌单（只能操作自己的歌单）。

                    需要人工确认后才会真正写入数据库。
                    歌单里已有的歌曲会被跳过，不会报错；
                    返回值里会给出新增与跳过的数量。
                    """
    )
    @RequiresApproval(reason = "向歌单添加歌曲会写入数据库")
    public String addSongsToPlaylist(
            @ToolParam(
                    description = """
                            歌单 ID，正整数，必须来自 get_user_playlists 的结果。
                            只能操作自己的歌单。
                            """
            )
            Long playlistId,

            @ToolParam(
                    description = """
                            要加入的歌曲 ID 列表，去重后 1~50 个。
                            ID 必须来自 search_songs 或歌单曲目结果。
                            """
            )
            List<Long> songIds
    ) {
        String userId = AgentRunContextHolder.requireUserId();

        if (playlistId == null || playlistId <= 0) {
            return ToolResponseEnvelope.error("歌单 ID 必须是正整数");
        }

        List<Long> songs = normalizeSongIds(songIds);
        String invalidSongs = validateSongIds(songs);
        if (invalidSongs != null) {
            return ToolResponseEnvelope.error(invalidSongs);
        }

        try {
            AddResult result = writeService.addSongs(userId, playlistId, songs);
            return ToolResponseEnvelope.okSingle(result);
        } catch (PlaylistNotFoundException e) {
            return ToolResponseEnvelope.error(
                    "歌单不存在或无权修改：" + playlistId);
        } catch (UnknownSongException e) {
            return ToolResponseEnvelope.error(
                    "以下歌曲不存在：" + joinIds(e.getMissingSongIds()));
        } catch (Exception e) {
            log.warn("向歌单添加歌曲失败：reason={}",
                    e.getClass().getSimpleName());
            return ToolResponseEnvelope.error("向歌单添加歌曲失败，请稍后重试");
        }
    }

    /**
     * 名称校验：空、超长、控制字符。
     *
     * @return 错误文案；合法时返回 null
     */
    static String validateName(String name) {
        if (name.isEmpty()) {
            return "歌单名称不能为空";
        }

        if (name.codePointCount(0, name.length()) > MAX_NAME_LENGTH) {
            return "歌单名称不能超过 " + MAX_NAME_LENGTH + " 个字符";
        }

        boolean hasControl = name.codePoints().anyMatch(Character::isISOControl);
        if (hasControl) {
            return "歌单名称不能包含控制字符";
        }

        return null;
    }

    /**
     * 歌曲 ID 校验：数量、取值范围。
     *
     * @return 错误文案；合法时返回 null
     */
    static String validateSongIds(List<Long> songIds) {
        if (songIds.isEmpty()) {
            return "至少需要选择一首歌曲";
        }

        if (songIds.size() > MAX_SONGS) {
            return "一次最多添加 " + MAX_SONGS + " 首歌曲";
        }

        for (Long songId : songIds) {
            if (songId == null || songId <= 0) {
                return "歌曲 ID 必须是正整数";
            }
        }

        return null;
    }

    /**
     * 去掉 null 并去重，保持传入顺序（顺序决定歌单内 position）。
     *
     * 不在这里判非法值：{@link #validateSongIds} 统一报错，
     * 避免"同一个问题两处文案"。
     */
    static List<Long> normalizeSongIds(List<Long> songIds) {
        if (songIds == null) {
            return List.of();
        }

        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long songId : songIds) {
            if (songId != null) {
                unique.add(songId);
            }
        }
        return List.copyOf(unique);
    }

    private static String joinIds(List<Long> ids) {
        List<String> parts = new ArrayList<>();
        for (Long id : ids) {
            parts.add(String.valueOf(id));
            if (parts.size() >= 5) {
                parts.add("…");
                break;
            }
        }
        return String.join("、", parts);
    }
}
