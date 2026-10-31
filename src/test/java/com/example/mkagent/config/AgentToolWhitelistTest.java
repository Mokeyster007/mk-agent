package com.example.mkagent.config;

import com.example.mkagent.model.AgentType;
import com.example.mkagent.resilience.ToolRetryWrapper;
import com.example.mkagent.tools.AdminAnalyticsTool;
import com.example.mkagent.tools.FileOperationTool;
import com.example.mkagent.tools.MusicPlaylistQueryTool;
import com.example.mkagent.tools.MusicQueryTool;
import com.example.mkagent.tools.PDFGenerationTool;
import com.example.mkagent.tools.ResourceDownloadTool;
import com.example.mkagent.tools.TerminateTool;
import com.example.mkagent.tools.WebScrapingTool;
import com.example.mkagent.tools.WebSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * AgentToolProvider 工具白名单单元测试。
 *
 * 不启动 Spring 上下文、不调用任何真实模型或外部 API：
 * 直接 new 工具实例，验证每种 Agent 类型拿到的
 * 工具集合严格等于白名单（最小权限原则）。
 */
class AgentToolWhitelistTest {

    private final AgentToolProvider provider = new AgentToolProvider(
            new WebSearchTool("test-api-key"),
            new WebScrapingTool(),
            new TerminateTool(),
            new FileOperationTool(),
            new ResourceDownloadTool(),
            new PDFGenerationTool(),
            // 音乐工具只用到方法签名，构造时依赖传 null 即可。
            new MusicQueryTool(null),
            new MusicPlaylistQueryTool(null),
            new AdminAnalyticsTool(null, null),
            // 重试包装器使用与生产一致的默认配置；
            // 包装不改变工具名，白名单断言不受影响。
            new ToolRetryWrapper(
                    true,
                    3,
                    200,
                    2.0,
                    "web_search,web_scrape",
                    "web_search:搜索工具执行失败,web_scrape:网页抓取失败"
            )
    );

    @Test
    void chatAgentOnlyHasWebSearchAndNoFileTools() {
        Set<String> names = toolNames(provider.getTools(AgentType.CHAT));

        assertEquals(Set.of("web_search"), names);

        // CHAT Agent 获取不到任何文件类高风险工具。
        assertFalse(names.contains("read_file"));
        assertFalse(names.contains("write_file"));
        assertFalse(names.contains("download_resource"));
        assertFalse(names.contains("generate_pdf"));
    }

    @Test
    void manusAgentHasNoHighRiskFileTools() {
        Set<String> names = toolNames(provider.getTools(AgentType.MANUS));

        assertEquals(
                Set.of("web_search", "web_scrape", "terminate_task"),
                names
        );

        // MkManus 获取不到不需要的高风险工具：
        // 文件读写、资源下载、PDF 生成都在 FILE 白名单内。
        assertFalse(names.contains("read_file"));
        assertFalse(names.contains("write_file"));
        assertFalse(names.contains("download_resource"));
        assertFalse(names.contains("generate_pdf"));
    }

    @Test
    void fileAgentHasFileToolsButNoSearchTools() {
        Set<String> names = toolNames(provider.getTools(AgentType.FILE));

        assertEquals(
                Set.of("read_file", "write_file", "download_resource", "generate_pdf"),
                names
        );

        // FILE 类型不包含联网类工具。
        assertFalse(names.contains("web_search"));
        assertFalse(names.contains("web_scrape"));
    }

    @Test
    void evalAgentReusesManusReadOnlyTools() {
        Set<String> names = toolNames(provider.getTools(AgentType.EVAL));

        assertEquals(
                Set.of("web_search", "web_scrape", "terminate_task"),
                names
        );

        // 评测类型同样不能获得文件类高风险工具。
        assertFalse(names.contains("read_file"));
        assertFalse(names.contains("write_file"));
        assertFalse(names.contains("download_resource"));
        assertFalse(names.contains("generate_pdf"));
    }

    @Test
    void musicUserHasReadOnlyCatalogAndPlaylistTools() {
        Set<String> names = toolNames(provider.getTools(AgentType.MUSIC_USER));

        assertEquals(
                Set.of("search_songs", "get_song_detail",
                        "get_user_playlists", "get_playlist_songs",
                        "terminate_task"),
                names
        );

        // 普通用户拿不到管理员统计，也拿不到任何写工具。
        assertFalse(names.contains("get_play_stats"));
        assertFalse(names.contains("get_top_songs"));
        assertFalse(names.contains("create_playlist"));
        assertFalse(names.contains("add_songs_to_playlist"));
    }

    @Test
    void musicAdminHasStatsButNoPlaylistTools() {
        Set<String> names = toolNames(provider.getTools(AgentType.MUSIC_ADMIN));

        assertEquals(
                Set.of("search_songs", "get_song_detail",
                        "get_play_stats", "get_top_songs",
                        "terminate_task"),
                names
        );

        // 管理员不得读取他人歌单。
        assertFalse(names.contains("get_user_playlists"));
        assertFalse(names.contains("get_playlist_songs"));
        assertFalse(names.contains("create_playlist"));
        assertFalse(names.contains("add_songs_to_playlist"));
    }

    private static Set<String> toolNames(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
    }
}
