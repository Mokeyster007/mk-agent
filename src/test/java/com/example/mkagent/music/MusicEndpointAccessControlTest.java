package com.example.mkagent.music;

import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 音乐入口的访问控制（MockMvc + H2，不启动 Agent、不调用模型）。
 *
 * 三条规则对应三种响应：
 * 1. 缺 X-User-Id → 401（与 /ai/manus/chat 一致）；
 * 2. music_user 里没有这个人 → 403；
 * 3. 普通用户访问管理入口 → 403。
 *
 * "agent 从未启动"用 agent_run 行数证明：
 * BaseAgent.runStream 在**同步段**就写入 RUNNING 记录，
 * 只要 runStream 被调用过，行数一定变化。
 * 断言"没有新行"比断言"没有收到 SSE 事件"更严格——
 * 后者在 agent 启动了但立刻失败时同样成立。
 */
@HermeticSpringBootTest
@AutoConfigureMockMvc
class MusicEndpointAccessControlTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class McpStubConfig {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    private static final String USER = "music-user-1";

    private static final String UNKNOWN = "music-user-does-not-exist";

    private static final String USER_ENTRY = "/api/ai/music/chat";

    private static final String ADMIN_ENTRY = "/api/ai/music/admin/chat";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("缺少 X-User-Id：401，两个入口都是")
    void missingHeaderIsUnauthorized() throws Exception {
        assertMissingHeader(USER_ENTRY);
        assertMissingHeader(ADMIN_ENTRY);
    }

    @Test
    @DisplayName("未知用户：403，两个入口都是")
    void unknownUserIsForbidden() throws Exception {
        assertForbidden(USER_ENTRY, UNKNOWN);
        assertForbidden(ADMIN_ENTRY, UNKNOWN);
    }

    @Test
    @DisplayName("普通用户访问管理入口：403，且 Agent 从未启动")
    void normalUserCannotUseAdminEntry() throws Exception {
        int before = agentRunCount();

        mockMvc.perform(get(ADMIN_ENTRY)
                        .contextPath("/api")
                        .header("X-User-Id", USER)
                        .param("message", "统计最近 7 天播放量"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(MusicAccessGuard.NOT_ADMIN_MESSAGE));

        assertEquals(before, agentRunCount(),
                "被拒绝的请求不得在 agent_run 留下任何记录（说明 Agent 没启动）");
    }

    private void assertMissingHeader(String entry) throws Exception {
        mockMvc.perform(get(entry)
                        .contextPath("/api")
                        .param("message", "查询歌曲"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value(MusicAccessGuard.MISSING_USER_MESSAGE));
    }

    private void assertForbidden(String entry, String userId) throws Exception {
        mockMvc.perform(get(entry)
                        .contextPath("/api")
                        .header("X-User-Id", userId)
                        .param("message", "查询歌曲"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    private int agentRunCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run", Integer.class);
        return count == null ? 0 : count;
    }
}
