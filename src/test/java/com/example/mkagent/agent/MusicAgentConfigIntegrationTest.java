package com.example.mkagent.agent;

import com.example.mkagent.model.AgentType;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 音乐 Agent 装配与白名单的集成测试（H2，不调用模型）。
 *
 * 断言的是**Agent 实例实际持有的工具**，而不是 AgentToolProvider 的返回值：
 * 两者之间还隔着一层 @Bean，装配时传错数组（例如把管理员 Bean 传给了用户 Agent）
 * 只有看实例才看得出来。
 */
@HermeticSpringBootTest
class MusicAgentConfigIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class McpStubConfig {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    @Autowired
    @Qualifier("musicUserAgent")
    private MusicAgent musicUserAgent;

    @Autowired
    @Qualifier("musicAdminAgent")
    private MusicAgent musicAdminAgent;

    @Test
    @DisplayName("两个 Agent 的 AgentType 与名称")
    void agentTypesAndNames() {
        assertEquals(AgentType.MUSIC_USER, musicUserAgent.getAgentType());
        assertEquals(AgentType.MUSIC_ADMIN, musicAdminAgent.getAgentType());

        assertEquals("MusicUserAgent", musicUserAgent.getName());
        assertEquals("MusicAdminAgent", musicAdminAgent.getName());
    }

    @Test
    @DisplayName("MUSIC_USER 的工具集合与权限矩阵精确一致")
    void userAgentToolsMatchMatrix() {
        Set<String> names = toolNames(musicUserAgent);

        assertEquals(
                Set.of("search_songs", "get_song_detail",
                        "get_user_playlists", "get_playlist_songs",
                        "terminate_task"),
                names
        );

        // 没有统计工具，也没有任何写工具。
        assertFalse(names.contains("get_play_stats"));
        assertFalse(names.contains("get_top_songs"));
        assertFalse(names.contains("create_playlist"));
        assertFalse(names.contains("add_songs_to_playlist"));
    }

    @Test
    @DisplayName("MUSIC_ADMIN 的工具集合与权限矩阵精确一致（无歌单工具）")
    void adminAgentToolsMatchMatrix() {
        Set<String> names = toolNames(musicAdminAgent);

        assertEquals(
                Set.of("search_songs", "get_song_detail",
                        "get_play_stats", "get_top_songs",
                        "terminate_task"),
                names
        );

        // 管理员不得读取他人歌单：不是"提示词里说不要"，而是工具根本不存在。
        assertFalse(names.contains("get_user_playlists"));
        assertFalse(names.contains("get_playlist_songs"));
        assertFalse(names.contains("create_playlist"));
        assertFalse(names.contains("add_songs_to_playlist"));
    }

    @Test
    @DisplayName("预算来自 mkagent.agent.music.*，默认 8 / 12 / 2m / 30s")
    void budgetComesFromConfig() {
        for (MusicAgent agent : new MusicAgent[]{musicUserAgent, musicAdminAgent}) {
            assertEquals(8, agent.getMaxSteps(), agent.getName());
            assertEquals(12, agent.getMaxToolCalls(), agent.getName());
            assertEquals(Duration.ofMinutes(2), agent.getTimeout(), agent.getName());
            assertEquals(Duration.ofSeconds(30), agent.getSseBuffer(), agent.getName());
        }
    }

    @Test
    @DisplayName("全局收尾开关默认值：budget-warning-steps=2、force-final-answer=true")
    void globalSwitchDefaults() {
        for (MusicAgent agent : new MusicAgent[]{musicUserAgent, musicAdminAgent}) {
            assertEquals(2, agent.getBudgetWarningSteps(), agent.getName());
            assertTrue(agent.isForceFinalAnswer(), agent.getName());
        }
    }

    @Test
    @DisplayName("系统提示词按类型装配，没有互相串")
    void systemPromptsAreBoundToTheRightAgent() {
        assertTrue(musicUserAgent.getSystemPrompt()
                .contains("不提供"), "用户 Agent 应声明没有写能力");
        assertFalse(musicUserAgent.getSystemPrompt().contains("get_play_stats"),
                "用户 Agent 的提示词不得提及统计工具");

        assertTrue(musicAdminAgent.getSystemPrompt().contains("聚合"),
                "管理员 Agent 应声明只做聚合统计");
        assertFalse(musicAdminAgent.getSystemPrompt().contains("get_user_playlists"),
                "管理员提示词不得提及歌单工具");
    }

    private static Set<String> toolNames(MusicAgent agent) {
        return Arrays.stream(agent.getAvailableTools())
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
    }
}
