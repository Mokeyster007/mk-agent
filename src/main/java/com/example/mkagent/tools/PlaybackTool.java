package com.example.mkagent.tools;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.playback.PlaybackService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 播放相关的**只读**工具。
 *
 * <p>两个工具，解决两件模型不该猜的事：
 * <ul>
 *   <li>{@code check_playback}：这首到底能不能播（有没有音源、是不是别人的）。</li>
 *   <li>{@code get_now_playing}：用户"正在播放的那首歌"是哪首——
 *       这是服务端可确认的播放事实，不是模型从上下文里猜的。</li>
 * </ul>
 *
 * <p>三条边界：
 * <ol>
 *   <li><b>不返回任何地址</b>：签名 URL 只给浏览器，模型消息里不出现地址、
 *       凭据或"已开始播放"这类无法验证的结论；</li>
 *   <li><b>不播放</b>：出声是页面的事。工具只能说"可播"，不能说"已播放"；</li>
 *   <li><b>userId 不是参数</b>：身份取自 {@link AgentRunContextHolder}，
 *       与曲库工具同一套失败关闭策略。</li>
 * </ol>
 *
 * <p>为什么"正在播放"必须走服务端：客户端上报时会经过可播性与归属复核
 * （见 {@code PlaybackService#updateNowPlaying}）。如果让模型或前端直接说
 * "正在播放的是 1001"，就等于绕开曲目权限，把任意歌塞进歌单。
 */
@Component
public class PlaybackTool {

    private final PlaybackService playbackService;

    public PlaybackTool(PlaybackService playbackService) {
        this.playbackService = playbackService;
    }

    @Tool(
            name = "check_playback",
            description = """
                    确认一首歌当前能否在播放器里播放。

                    当用户说"播放这首""放一下某某"时，先检索到歌曲，
                    再用本工具确认是否可播，然后把结论告诉用户：
                    可播 → 提示点击页面下方的播放卡；不可播 → 说明原因
                    （没有音源 / 无权播放），不要编造"已经播放"。

                    不返回任何播放地址，也不代表已经开始播放。
                    """
    )
    public String checkPlayback(
            @ToolParam(description = "曲目 ID（来自 search_songs / get_playlist_songs 等工具的真实结果）")
            Long songId
    ) {
        AgentRunContextHolder.requireUserId();

        if (songId == null || songId <= 0) {
            return ToolResponseEnvelope.error("songId 必须是正整数");
        }

        PlaybackService.Playability playability = playbackService.checkPlayable(
                AgentRunContextHolder.requireUserId(), songId);

        if (!playability.playable()) {
            return ToolResponseEnvelope.error(
                    playability.reason() == null ? "这首曲目暂时不能播放" : playability.reason());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("songId", songId);
        data.put("playable", true);
        data.put("format", playability.format());
        data.put("hint", "可播放。播放由页面完成，请提示用户点击播放卡，不要说“已经播放”。");

        return ToolResponseEnvelope.okSingle(data);
    }

    @Tool(
            name = "get_now_playing",
            description = """
                    读取用户当前**有效**的正在播放曲目。

                    只认"真的在播"：暂停、已停止、或超过 3 分钟没有心跳，

                    都会返回"没有当前曲目"。浏览器本地记录的"上次听到这里"
                    不算正在播放，不能拿来顶替。

                    当用户说"把正在播放的这首加入歌单""这首加进去"时，
                    必须先调用本工具拿到真实曲目，再用返回的 songId 去调用写工具。
                    不要用历史对话里的歌曲猜测"正在播放"的是哪首。

                    返回失败时应当询问用户**具体要加哪一首**（或先播放一首），
                    不要自己挑一首代替。
                    """
    )
    public String getNowPlaying() {
        String userId = AgentRunContextHolder.requireUserId();

        Optional<PlaybackService.NowPlayingView> nowPlaying = playbackService.nowPlaying(userId);

        if (nowPlaying.isEmpty()) {
            return ToolResponseEnvelope.error(
                    "当前没有有效的正在播放曲目（暂停、已停止或心跳超时都不算）。"
                            + "请让用户明确指定要加入歌单的曲目，或先在页面播放器里播放一首。");
        }

        PlaybackService.NowPlayingView view = nowPlaying.get();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("songId", view.songId());
        data.put("title", view.title());
        data.put("artist", view.artist());
        data.put("state", view.state());

        return ToolResponseEnvelope.okSingle(data);
    }
}
