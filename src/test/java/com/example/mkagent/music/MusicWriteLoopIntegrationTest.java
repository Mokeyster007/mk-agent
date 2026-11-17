package com.example.mkagent.music;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批写闭环的端到端回归：**真实写工具** + 真实 HTTP + 真实 H2 + 桩模型。
 *
 * <p>与 {@code ApprovalResumeSseIntegrationTest} 的区别：那边用策略替身把
 * 只读工具临时标成"需确认"，验证的是审批状态机；这里打开
 * {@code mkagent.agent.music.write-enabled}，让模型真的调用
 * {@code create_playlist}，验证的是**业务结果**：
 *
 * <ol>
 *   <li>对话 → 模型提出写请求 → 任务暂停在 WAITING_APPROVAL；</li>
 *   <li>暂停期间数据库里**没有**歌单（不能"先写后问"）；</li>
 *   <li>页面批准 → 恢复执行 → 歌单真的落库，曲目与顺序正确；</li>
 *   <li>查询接口（页面用的那条）能读到它 —— 刷新后仍可核对；</li>
 *   <li>拒绝 → 不产生任何歌单。</li>
 * </ol>
 *
 * <p>桩模型只负责"提出请求"和"收尾"，不验证模型质量；
 * 真实模型的回答质量属于评测范畴，不在本测试的断言范围内。
 */
@HermeticSpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mkagent.approval.ttl=10m",
                // 本测试的唯一目的：让写工具真的进入 MUSIC_USER 的白名单
                "mkagent.agent.music.write-enabled=true"
        }
)
@Timeout(120)
class MusicWriteLoopIntegrationTest {

    private static final String USER = MusicSeedInitializer.USER_ALICE;

    /** 测试创建的歌单名（带前缀，便于清理且不会撞上种子数据）。 */
    private static final String PLAYLIST_APPROVED = "闭环验证-批准";
    private static final String PLAYLIST_REJECTED = "闭环验证-拒绝";

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private ScriptedChatModel scriptedChatModel;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private VectorStore chatAppVectorStore;

    @BeforeEach
    void reset() {
        scriptedChatModel.reset();
        cleanup();
    }

    @AfterEach
    void cleanup() {
        /*
         * 共享内存库：删掉本测试造的歌单（先关联表后主表），
         * 避免影响其它用例对歌单数量的断言。
         */
        for (String name : List.of(PLAYLIST_APPROVED, PLAYLIST_REJECTED)) {
            jdbcTemplate.update(
                    "DELETE FROM music_playlist_song WHERE playlist_id IN "
                            + "(SELECT id FROM music_playlist WHERE user_id = ? AND name = ?)",
                    USER, name);
            jdbcTemplate.update(
                    "DELETE FROM music_playlist WHERE user_id = ? AND name = ?", USER, name);
        }

        /*
         * "加入已有歌单"用例会往种子歌单里插 1057，
         * 这里删掉，保证其它用例看到的种子曲目数不变。
         */
        jdbcTemplate.update(
                "DELETE FROM music_playlist_song WHERE song_id = 1057 AND playlist_id IN "
                        + "(SELECT id FROM music_playlist WHERE user_id = ? AND name = ?)",
                USER, MusicSeedInitializer.PLAYLIST_NAME_PRIVATE_ALICE);
    }

    // ==================== 主链路 ====================

    @Test
    @DisplayName("批准 → 歌单真的写入数据库，且查询接口能读到（含曲目与顺序）")
    void approveCreatesPlaylistForReal() throws Exception {
        String runId = startTaskAndPause(PLAYLIST_APPROVED, List.of(1001L, 1002L));

        // 暂停期间不能有任何写入：审批前写库等于"先斩后奏"
        assertEquals(0, countPlaylists(PLAYLIST_APPROVED),
                "暂停期间不得创建歌单");

        String approvalId = pendingApprovalId(runId);

        HttpResponse<String> approved = post("/ai/approvals/" + approvalId + "/approve");
        assertEquals(200, approved.statusCode(), "批准失败，body=" + approved.body());
        assertTrue(approved.body().contains("event:done"),
                "续流应正常结束。原始 SSE：\n" + approved.body());

        awaitFinishedRun(runId);
        assertEquals("SUCCEEDED", runRow(runId).get("state"),
                "批准后任务应成功，实际：" + runRow(runId));
        assertEquals("APPROVED", approvalRow(approvalId).get("status"));
        assertEquals("FINISHED", approvalRow(approvalId).get("resume_state"));

        // 业务结果：数据库里真的有一行，且曲目是 1001、1002（顺序一致）
        assertEquals(1, countPlaylists(PLAYLIST_APPROVED), "批准后应有且仅有一个歌单");

        JsonNode playlists = getJson("/ai/music/playlists");
        JsonNode created = findByName(playlists, PLAYLIST_APPROVED);
        assertNotNull(created, "查询接口应能读到新建的歌单，实际：" + playlists);
        assertEquals(2, created.get("songCount").asInt(), "歌曲数应为 2");

        long playlistId = created.get("id").asLong();
        JsonNode songs = getJson("/ai/music/playlists/" + playlistId + "/songs");
        assertEquals(2, songs.size(), "曲目数应为 2，实际：" + songs);
        assertEquals(1001, songs.get(0).get("id").asLong(), "顺序应与请求一致");
        assertEquals(1002, songs.get(1).get("id").asLong(), "顺序应与请求一致");
        assertFalse(songs.get(0).get("title").asText().isBlank(), "应返回曲目标题");
    }

    @Test
    @DisplayName("加入已有歌单：批准后曲目真的多出来，查询接口立即可见")
    void approveAddsSongsToExistingPlaylist() throws Exception {
        long playlistId = seededPlaylistId(MusicSeedInitializer.PLAYLIST_NAME_PRIVATE_ALICE);
        int before = playlistSongCount(playlistId);

        // 1057 不在种子歌单里，避免"本来就有"造成假通过
        String runId = startAddSongsTask(playlistId, List.of(1057L));
        String approvalId = pendingApprovalId(runId);

        /*
         * 审批卡必须写明"往哪个歌单加"：加歌工具的参数里只有 playlistId，
         * 歌单名靠 JdbcSongTitleLookup 按 (id, 当前用户) 反查。
         * 这条断言锁住真实数据库路径——只有单测覆盖不够，SQL 写错照样会
         * 在页面上显示成"未指定歌单名"。
         */
        String summary = summaryText(approvalId);
        assertTrue(summary.contains(MusicSeedInitializer.PLAYLIST_NAME_PRIVATE_ALICE),
                "审批摘要应写明目标歌单名，实际：" + summary);
        assertTrue(summary.contains("向歌单添加歌曲"),
                "审批摘要应写明动作，实际：" + summary);

        assertEquals(before, playlistSongCount(playlistId), "批准前不得写入");

        HttpResponse<String> approved = post("/ai/approvals/" + approvalId + "/approve");
        assertEquals(200, approved.statusCode(), "批准失败，body=" + approved.body());
        awaitFinishedRun(runId);

        assertEquals(before + 1, playlistSongCount(playlistId),
                "批准后曲目数应 +1");

        JsonNode songs = getJson("/ai/music/playlists/" + playlistId + "/songs");
        boolean found = false;
        for (JsonNode song : songs) {
            if (song.get("id").asLong() == 1057L) {
                found = true;
            }
        }
        assertTrue(found, "查询接口应能读到刚加入的歌曲，实际：" + songs);
    }

    @Test
    @DisplayName("拒绝 → 不执行写工具，数据库里没有歌单")
    void rejectWritesNothing() throws Exception {
        String runId = startTaskAndPause(PLAYLIST_REJECTED, List.of(1001L));
        String approvalId = pendingApprovalId(runId);

        HttpResponse<String> rejected = post("/ai/approvals/" + approvalId + "/reject");
        assertEquals(200, rejected.statusCode(), "拒绝失败，body=" + rejected.body());

        awaitFinishedRun(runId);
        assertEquals("REJECTED", approvalRow(approvalId).get("status"));
        assertEquals(0, countPlaylists(PLAYLIST_REJECTED),
                "拒绝后不得产生任何歌单");
        assertEquals(0, ((Number) runRow(runId).get("tool_call_count")).intValue(),
                "拒绝不得执行工具，实际：" + runRow(runId));

        JsonNode playlists = getJson("/ai/music/playlists");
        assertNull(findByName(playlists, PLAYLIST_REJECTED),
                "查询接口也不应出现被拒绝的歌单");
    }

    // ==================== 能力探测 ====================

    @Test
    @DisplayName("能力探测与装配同源：write-enabled=true 时返回审批写与写版提示词版本")
    void capabilitiesReflectAssembly() throws Exception {
        JsonNode caps = getJson("/ai/music/capabilities");

        assertTrue(caps.get("writeApproval").asBoolean(),
                "开关打开时页面应显示审批写，实际：" + caps);
        assertEquals("music-user-write-approval/v3", caps.get("promptVersion").asText(),
                "提示词版本必须与工具开关同步，实际：" + caps);
    }

    // ==================== 辅助 ====================

    /**
     * 通过真实入口发起一次任务，等它停在审批上，返回 runId。
     */
    private String startTaskAndPause(String playlistName, List<Long> songIds) throws Exception {
        // 桩模型第一次调用就发这个工具请求（JSON 由测试构造，模型不参与）
        scriptedChatModel.toolArgs = "{\"name\":\"" + playlistName
                + "\",\"songIds\":" + songIds + "}";

        String url = "http://localhost:" + port + "/api/ai/music/chat"
                + "?message=" + URLEncoder.encode(
                "帮我建一个歌单叫「" + playlistName + "」", StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("X-User-Id", USER)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode(), "请求失败，body=" + response.body());
        assertTrue(response.body().contains("event:approval_required"),
                "写工具必须触发审批。原始 SSE：\n" + response.body());

        Map<String, Object> run = awaitLatestRun("WAITING_APPROVAL");
        assertNotNull(run, "应有一行暂停中的任务");

        String runId = run.get("run_id").toString();

        // 桩模型固定请求这两个参数，这里顺便证明参数原样落到了待执行调用里
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload_json FROM agent_checkpoint WHERE run_id = ?", String.class, runId);
        assertNotNull(payload);
        assertTrue(payload.contains(playlistName), "检查点应保留歌单名");
        for (Long songId : songIds) {
            assertTrue(payload.contains(String.valueOf(songId)),
                    "检查点应保留歌曲 ID " + songId);
        }

        return runId;
    }

    /**
     * 发起"往已有歌单加歌"的任务，等到暂停。
     */
    private String startAddSongsTask(long playlistId, List<Long> songIds) throws Exception {
        scriptedChatModel.toolName = "add_songs_to_playlist";
        scriptedChatModel.toolArgs = "{\"playlistId\":" + playlistId
                + ",\"songIds\":" + songIds + "}";

        String url = "http://localhost:" + port + "/api/ai/music/chat"
                + "?message=" + URLEncoder.encode(
                "把这首加到「" + MusicSeedInitializer.PLAYLIST_NAME_PRIVATE_ALICE + "」里",
                StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("X-User-Id", USER)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode(), "请求失败，body=" + response.body());
        assertTrue(response.body().contains("event:approval_required"),
                "写工具必须触发审批。原始 SSE：\n" + response.body());

        Map<String, Object> run = awaitLatestRun("WAITING_APPROVAL");
        assertNotNull(run, "应有一行暂停中的任务");
        return run.get("run_id").toString();
    }

    private long seededPlaylistId(String name) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM music_playlist WHERE user_id = ? AND name = ?",
                Long.class, USER, name);
        assertNotNull(id, "种子歌单应存在：" + name);
        return id;
    }

    private int playlistSongCount(long playlistId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_playlist_song WHERE playlist_id = ?",
                Integer.class, playlistId);
        return count == null ? 0 : count;
    }

    private int countPlaylists(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_playlist WHERE user_id = ? AND name = ?",
                Integer.class, USER, name);
        return count == null ? 0 : count;
    }

    private JsonNode findByName(JsonNode playlists, String name) {
        for (JsonNode node : playlists) {
            if (name.equals(node.path("name").asText())) {
                return node;
            }
        }
        return null;
    }

    private String pendingApprovalId(String runId) {
        return jdbcTemplate.queryForObject(
                "SELECT approval_id FROM agent_approval WHERE run_id = ? AND status = 'PENDING'",
                String.class, runId);
    }

    private String summaryText(String approvalId) {
        String text = jdbcTemplate.queryForObject(
                "SELECT summary_text FROM agent_approval WHERE approval_id = ?",
                String.class, approvalId);
        return text == null ? "" : text;
    }

    private HttpResponse<String> post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api" + path))
                .header("X-User-Id", USER)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode getJson(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api" + path))
                .header("X-User-Id", USER)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode(),
                "GET " + path + " 失败，body=" + response.body());
        return JSON.readTree(response.body());
    }

    private Map<String, Object> runRow(String runId) {
        return jdbcTemplate.queryForMap(
                "SELECT state, tool_call_count, approval_count, finished_at, error_code "
                        + "FROM agent_run WHERE run_id = ?", runId);
    }

    private Map<String, Object> approvalRow(String approvalId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, resume_state FROM agent_approval WHERE approval_id = ?",
                approvalId);
    }

    private Map<String, Object> awaitLatestRun(String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        Map<String, Object> row = null;

        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT run_id, state FROM agent_run WHERE user_id = ? "
                            + "ORDER BY id DESC LIMIT 1", USER);
            row = rows.isEmpty() ? null : rows.get(0);
            if (row != null && expected.equals(row.get("state"))) {
                return row;
            }
            Thread.sleep(25);
        }
        return row;
    }

    private void awaitFinishedRun(String runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (runRow(runId).get("finished_at") != null) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("等待任务终态超时，实际：" + runRow(runId));
    }

    /**
     * 桩模型：第一次请求 {@code create_playlist}，之后直接给最终回答。
     *
     * <p>参数用 {@link #currentArgs} 注入，测试可以在每次运行前换歌单名与歌曲，
     * 不必为每个用例写一个模型桩。
     */
    static class ScriptedChatModel implements ChatModel {

        final AtomicInteger calls = new AtomicInteger();

        volatile String toolName = "create_playlist";

        volatile String toolArgs = "{}";

        void reset() {
            calls.set(0);
            toolName = "create_playlist";
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            int n = calls.incrementAndGet();

            if (n == 1) {
                return new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "",
                                Map.of(),
                                List.of(new AssistantMessage.ToolCall(
                                        "call-1", "function", toolName, toolArgs)))))
                        ,
                        ChatResponseMetadata.builder()
                                .model("stub-model")
                                .usage(new StubUsage(10, 5))
                                .build()
                );
            }

            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage("已按你的确认处理"))),
                    ChatResponseMetadata.builder()
                            .model("stub-model")
                            .usage(new StubUsage(10, 5))
                            .build()
            );
        }
    }

    private record StubUsage(int promptTokens, int completionTokens) implements Usage {

        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubModelConfig {

        private final ScriptedChatModel behavior = new ScriptedChatModel();

        @Bean("dashscopeChatModel")
        @Primary
        ChatModel dashscopeChatModel() {
            DashScopeChatModel mock = Mockito.mock(DashScopeChatModel.class);
            Mockito.when(mock.call(Mockito.any(Prompt.class)))
                    .thenAnswer(invocation -> {
                        /*
                         * 每次调用前按当前测试的意图设置参数：
                         * 用 ThreadLocal 式的简化做法——参数由测试线程写入，
                         * 模型调用发生在 Agent 线程，读的是同一个 volatile 字段。
                         */
                        return behavior.call(invocation.getArgument(0, Prompt.class));
                    });
            return mock;
        }

        @Bean("chatAppDocumentLoader")
        ChatAppDocumentLoader chatAppDocumentLoader() {
            ChatAppDocumentLoader mock = Mockito.mock(ChatAppDocumentLoader.class);
            Mockito.when(mock.loadMarkdowns()).thenReturn(List.of());
            return mock;
        }

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new org.springframework.ai.tool.ToolCallback[0];
        }

        @Bean
        ScriptedChatModel scriptedChatModel() {
            return behavior;
        }
    }
}
