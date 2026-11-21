package com.example.mkagent.config;

import com.example.mkagent.model.AgentType;
import com.example.mkagent.resilience.ToolRetryWrapper;
import com.example.mkagent.tools.AdminAnalyticsTool;
import com.example.mkagent.tools.FileOperationTool;
import com.example.mkagent.tools.MusicPlaylistQueryTool;
import com.example.mkagent.tools.MusicQueryTool;
import com.example.mkagent.tools.PlaybackTool;
import com.example.mkagent.tools.PDFGenerationTool;
import com.example.mkagent.tools.ResourceDownloadTool;
import com.example.mkagent.tools.TerminateTool;
import com.example.mkagent.tools.WebScrapingTool;
import com.example.mkagent.tools.WebSearchTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 按 Agent 类型提供工具白名单（最小权限原则）。
 *
 * 每个 Agent 只能拿到自己类型对应的工具集合，
 * 而不是默认获得全部工具：
 *
 * CHAT       ：web_search
 * MANUS      ：web_search、web_scrape、terminate_task
 * FILE       ：read_file、write_file、download_resource、generate_pdf
 * EVAL       ：评测专用，复用 MANUS 的只读工具集
 * MUSIC_USER ：search_songs、get_song_detail、get_user_playlists、
 *              get_playlist_songs、check_playback、get_now_playing、terminate_task
 * MUSIC_ADMIN：search_songs、get_song_detail、get_play_stats、
 *              get_top_songs、terminate_task（**没有**歌单类工具）
 *
 * 音乐类型的白名单粒度是 **Bean**，所以曲库工具与歌单工具被拆成
 * MusicQueryTool / MusicPlaylistQueryTool 两个类：
 * 管理员不该读任何人的歌单，而"减法式白名单"（拿全集再排除）
 * 在新增工具时会被悄悄突破，拆 Bean 才能让白名单一眼可读。
 *
 * 高风险工具（文件读写、资源下载）不会进入
 * 与文件操作无关的 Agent，
 * 从源头避免模型误用或滥用高权限工具。
 *
 * 工具重试：
 * 返回前经过 ToolRetryWrapper 包装，
 * 白名单内的只读幂等工具（web_search / web_scrape）
 * 自动获得有限次数的指数退避重试；
 * 高风险工具永远不会被包装。
 */
@Component
public class AgentToolProvider {

    /**
     * AgentType → 该类型允许使用的工具 Bean 列表。
     */
    private final Map<AgentType, List<Object>> toolBeansByType;

    /**
     * 只读幂等工具的重试包装器（仅包装白名单内工具）。
     */
    private final ToolRetryWrapper toolRetryWrapper;

    public AgentToolProvider(
            WebSearchTool webSearchTool,
            WebScrapingTool webScrapingTool,
            TerminateTool terminateTool,
            FileOperationTool fileOperationTool,
            ResourceDownloadTool resourceDownloadTool,
            PDFGenerationTool pdfGenerationTool,
            MusicQueryTool musicQueryTool,
            MusicPlaylistQueryTool musicPlaylistQueryTool,
            PlaybackTool playbackTool,
            AdminAnalyticsTool adminAnalyticsTool,
            ToolRetryWrapper toolRetryWrapper
    ) {
        this.toolRetryWrapper = toolRetryWrapper;
        this.toolBeansByType = Map.of(
                AgentType.CHAT, List.of(
                        webSearchTool
                ),
                AgentType.MANUS, List.of(
                        webSearchTool,
                        webScrapingTool,
                        terminateTool
                ),
                AgentType.FILE, List.of(
                        fileOperationTool,
                        resourceDownloadTool,
                        pdfGenerationTool
                ),
                /*
                 * 评测类型复用 MANUS 的只读工具集，
                 * 但评测 Agent 由 AgentEvaluator 直接注入工具，
                 * 不经过线上请求链路。
                 */
                AgentType.EVAL, List.of(
                        webSearchTool,
                        webScrapingTool,
                        terminateTool
                ),
                /*
                 * 音乐普通用户：曲库只读 + 歌单只读。
                 * terminate_task 必须带上，否则 Agent Loop 只能靠
                 * 步数预算耗尽来结束，每次都要多烧一轮模型调用。
                 */
                AgentType.MUSIC_USER, List.of(
                        musicQueryTool,
                        musicPlaylistQueryTool,
                        /*
                         * 播放只读工具（能否播放 / 当前播放曲目）。
                         * 它们不签发地址、不触发播放，只回答事实，
                         * 因此与曲库工具同级，不需要审批。
                         */
                        playbackTool,
                        terminateTool
                ),
                /*
                 * 音乐管理员：曲库只读 + 聚合统计，**不含歌单类工具**。
                 * 管理员不得读取他人歌单或播放历史，这条规则在这里
                 * 就体现为"数组里没有 MusicPlaylistQueryTool"。
                 */
                AgentType.MUSIC_ADMIN, List.of(
                        musicQueryTool,
                        adminAnalyticsTool,
                        terminateTool
                )
        );
    }

    /**
     * 返回指定 Agent 类型的工具白名单（ToolCallback 数组）。
     *
     * 每次调用都重新生成数组，避免外部修改共享引用；
     * 工具定义本身来自单例工具 Bean，无状态，可安全复用。
     *
     * 返回前统一经过重试包装：
     * 白名单内的只读幂等工具带上指数退避重试，
     * 白名单外的高风险工具原样返回，绝不自动重试。
     */
    public ToolCallback[] getTools(AgentType agentType) {
        List<Object> tools = toolBeansByType.get(agentType);

        if (tools == null || tools.isEmpty()) {
            throw new IllegalArgumentException(
                    "AgentType 未配置任何工具：" + agentType
            );
        }

        return toolRetryWrapper.wrap(ToolCallbacks.from(tools.toArray()));
    }
}
