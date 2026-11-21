package com.example.mkagent.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.music.MusicAnalyticsRepository;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.tools.DefaultToolEventMessageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 音乐工具白名单装配（M2c）。
 *
 * 两个层次：
 * 1. **静态**：MUSIC_USER / MUSIC_ADMIN 的工具名集合必须与设计**精确相等**
 *    （containsExactlyInAnyOrder，不是 contains——多一个工具也算失败）；
 * 2. **动态**：让模型在白名单外用自然语言"强行请求"管理员工具，
 *    断言这次调用没有产生任何数据访问。
 *
 * 为什么要做第 2 层：白名单是"模型看不到这个工具"，
 * 但模型完全可能凭空编一个工具名。编出来的名字必须变成
 * "工具不存在"的错误，而不是被某个兜底逻辑路由到真实工具上。
 */
@HermeticSpringBootTest
@Timeout(60)
class MusicToolWhitelistIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModelConfig {

        @Bean("dashscopeChatModel")
        @Primary
        ChatModel dashscopeChatModel() {
            return Mockito.mock(DashScopeChatModel.class);
        }

        @Bean("chatAppDocumentLoader")
        ChatAppDocumentLoader chatAppDocumentLoader() {
            ChatAppDocumentLoader mock = Mockito.mock(ChatAppDocumentLoader.class);
            Mockito.when(mock.loadMarkdowns()).thenReturn(List.of());
            return mock;
        }

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    @Autowired
    @Qualifier("musicUserToolCallbacks")
    private ToolCallback[] musicUserToolCallbacks;

    @Autowired
    @Qualifier("musicAdminToolCallbacks")
    private ToolCallback[] musicAdminToolCallbacks;

    @MockitoBean
    private VectorStore chatAppVectorStore;

    @MockitoSpyBean
    private MusicAnalyticsRepository analyticsRepository;

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("MUSIC_USER：曲库 + 歌单 + 播放只读 + 终止，共 7 个")
    void musicUserWhitelistIsExact() {
        assertThat(toolNames(musicUserToolCallbacks))
                .as("MUSIC_USER 白名单必须精确等于设计")
                .containsExactlyInAnyOrder(
                        "search_songs",
                        "get_song_detail",
                        "get_user_playlists",
                        "get_playlist_songs",
                        /*
                         * 播放只读工具：只回答"能不能播""正在播哪首"，
                         * 不签发地址、不触发播放，因此不需要审批。
                         */
                        "check_playback",
                        "get_now_playing",
                        "terminate_task"
                );
    }

    @Test
    @DisplayName("MUSIC_ADMIN：曲库 + 统计 + 终止，共 5 个，且没有任何歌单工具")
    void musicAdminWhitelistIsExact() {
        Set<String> names = toolNames(musicAdminToolCallbacks);

        assertThat(names)
                .as("MUSIC_ADMIN 白名单必须精确等于设计")
                .containsExactlyInAnyOrder(
                        "search_songs",
                        "get_song_detail",
                        "get_play_stats",
                        "get_top_songs",
                        "terminate_task"
                );

        assertThat(names)
                .as("管理员不得读取他人歌单")
                .doesNotContain("get_user_playlists", "get_playlist_songs");
    }

    @Test
    @DisplayName("普通用户拿不到统计工具，管理员拿不到歌单工具")
    void permissionsDoNotOverlap() {
        Set<String> user = toolNames(musicUserToolCallbacks);
        Set<String> admin = toolNames(musicAdminToolCallbacks);

        assertThat(user).doesNotContain("get_play_stats", "get_top_songs");
        assertThat(admin).doesNotContain("get_user_playlists", "get_playlist_songs");
    }

    @Test
    @DisplayName("模型强行请求白名单外的统计工具：不产生任何数据访问")
    void forcedAdminToolCallReachesNoData() {
        ToolCallAgent agent = new ToolCallAgent(
                new ForcedToolCallModel("get_play_stats"),
                musicUserToolCallbacks,
                Runnable::run,
                new DefaultToolEventMessageProvider()
        );
        agent.setName("music-user-agent");
        agent.setSystemPrompt("你是音乐助手。");
        agent.setMaxSteps(2);
        agent.setMaxToolCalls(2);
        agent.setTimeout(Duration.ofSeconds(10));

        UserContextHolder.set("music-user-1");
        AgentRunContext ctx = new AgentRunContext();

        /*
         * 工具不在白名单里，框架层会抛"找不到工具"。
         * 这里关心的是"没有数据访问"，而不是异常本身。
         */
        try {
            agent.run("帮我统计一下播放量", ctx);
        } catch (RuntimeException expected) {
            // 预期路径：白名单外的工具名无法解析
        }

        assertFalse(ctx.getToolTrace().contains("get_play_stats"),
                "白名单外的工具不能被真正执行：" + ctx.getToolTrace());
        verify(analyticsRepository, never()).totalPlays(anyInt());
        verify(analyticsRepository, never()).playsByDay(anyInt());
        verify(analyticsRepository, never()).topSongs(anyInt(), anyInt());
    }

    private static Set<String> toolNames(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
    }

    /**
     * 固定请求一个工具名的模型桩。
     */
    private static final class ForcedToolCallModel implements ChatModel {

        private final String toolName;

        private ForcedToolCallModel(String toolName) {
            this.toolName = toolName;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(
                            "",
                            Map.of(),
                            List.of(new AssistantMessage.ToolCall(
                                    "call_1", "function", toolName, "{\"days\":7}"))
                    ))),
                    ChatResponseMetadata.builder().model("stub-model").build()
            );
        }
    }
}
