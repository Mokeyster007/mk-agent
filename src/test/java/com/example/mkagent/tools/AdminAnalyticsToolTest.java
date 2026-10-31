package com.example.mkagent.tools;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.music.MusicAnalyticsRepository;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 管理员统计工具（H2 + 真实聚合仓库 + spy）。
 *
 * 用 spy 而不是纯 mock：既让真实 SQL 在 H2 上跑一遍（证明方言兼容、
 * 聚合结果正确），又能在"无权限"用例里断言**仓库一次都没被调用**——
 * 权限校验必须在查库之前发生，否则"拒绝了但数据已经读出来"仍然算泄露。
 *
 * 种子播放日志是相对当前时间的最近 30 天，所以窗口内一定有数据，
 * 不需要为测试单独插数据。
 */
@HermeticSpringBootTest
class AdminAnalyticsToolTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class McpStubConfig {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AdminAnalyticsTool tool;

    @MockitoSpyBean
    private MusicAnalyticsRepository repository;

    @AfterEach
    void tearDown() {
        AgentRunContextHolder.clear();
    }

    @Test
    @DisplayName("管理员：返回聚合总览，含总次数与按日明细")
    void adminGetsPlayStats() throws Exception {
        bindUser("music-admin-1");

        JsonNode root = objectMapper.readTree(tool.getPlayStats(7));

        assertTrue(root.path("ok").asBoolean(), root.toString());
        long total = root.path("data").path("totalPlays").asLong();
        assertTrue(total > 0, "最近 7 天应有播放记录：" + root);
        assertTrue(root.path("data").path("days").isArray());
        assertTrue(root.path("data").path("days").size() > 0);
    }

    @Test
    @DisplayName("普通用户：无权限，且一次都没查库")
    void normalUserGetsNoPermission() throws Exception {
        bindUser("music-user-1");

        JsonNode root = objectMapper.readTree(tool.getPlayStats(7));

        assertFalse(root.path("ok").asBoolean());
        assertEquals(AdminAnalyticsTool.NO_PERMISSION_MESSAGE,
                root.path("meta").path("hint").asText());
        verify(repository, never()).totalPlays(anyInt());
        verify(repository, never()).playsByDay(anyInt());
    }

    @Test
    @DisplayName("未知用户：同样无权限（不回落成普通用户）")
    void unknownUserGetsNoPermission() throws Exception {
        bindUser("nobody-at-all");

        JsonNode stats = objectMapper.readTree(tool.getPlayStats(7));
        JsonNode top = objectMapper.readTree(tool.getTopSongs(7, 10));

        assertFalse(stats.path("ok").asBoolean());
        assertFalse(top.path("ok").asBoolean());
        verify(repository, never()).topSongs(
                anyInt(),
                anyInt());
    }

    @Test
    @DisplayName("没有任务上下文：无权限，不查库")
    void missingContextGetsNoPermission() throws Exception {
        AgentRunContextHolder.clear();

        JsonNode root = objectMapper.readTree(tool.getPlayStats(7));

        assertFalse(root.path("ok").asBoolean());
        verify(repository, never()).totalPlays(anyInt());
    }

    @Test
    @DisplayName("days 越界被夹取：null→7、0→7、1000→90、1 原样")
    void daysIsClamped() {
        bindUser("music-admin-1");

        tool.getPlayStats(null);
        tool.getPlayStats(0);
        tool.getPlayStats(1000);
        tool.getPlayStats(1);

        /*
         * 一次断言整个序列：verify(spy).totalPlays(7) 是"累计恰好一次"，
         * null 与 0 都夹到 7 之后，第二次调用就会让它失败。
         */
        ArgumentCaptor<Integer> days = ArgumentCaptor.forClass(Integer.class);
        verify(repository, times(4)).totalPlays(days.capture());
        assertEquals(List.of(7, 7, 90, 1), days.getAllValues());
    }

    @Test
    @DisplayName("limit 越界被夹取：null→10、0→10、1000→50")
    void limitIsClamped() {
        bindUser("music-admin-1");

        tool.getTopSongs(7, null);
        tool.getTopSongs(7, 0);
        tool.getTopSongs(7, 1000);

        ArgumentCaptor<Integer> days = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(repository, times(3)).topSongs(days.capture(), limits.capture());

        assertEquals(List.of(7, 7, 7), days.getAllValues());
        assertEquals(List.of(10, 10, 50), limits.getAllValues());
    }

    @Test
    @DisplayName("排行结果不含任何用户标识")
    void topSongsLeakNoUserIdentity() throws Exception {
        bindUser("music-admin-1");

        JsonNode root = objectMapper.readTree(tool.getTopSongs(30, 5));
        String json = root.toString().toLowerCase();

        assertTrue(root.path("ok").asBoolean(), json);
        assertTrue(root.path("meta").path("count").asInt() > 0, json);
        assertFalse(json.contains("music-user"), "不得出现 userId：" + json);
        assertFalse(json.contains("user_id"), "不得出现 user_id：" + json);
        assertFalse(json.contains("userid"), "不得出现 userId：" + json);
    }

    @Test
    @DisplayName("统计结果不含任何用户标识")
    void playStatsLeakNoUserIdentity() throws Exception {
        bindUser("music-admin-1");

        JsonNode root = objectMapper.readTree(tool.getPlayStats(30));
        String json = root.toString().toLowerCase();

        assertFalse(json.contains("music-user"), "不得出现 userId：" + json);
        assertFalse(json.contains("user_id"), "不得出现 user_id：" + json);
    }

    private void bindUser(String userId) {
        AgentRunContext ctx = new AgentRunContext();
        ctx.setUserId(userId);
        AgentRunContextHolder.bind(ctx);
    }
}
