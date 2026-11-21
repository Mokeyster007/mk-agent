package com.example.mkagent.conversation;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.music.MusicSeedInitializer;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一会话连续聊天的端到端回归：真实 HTTP + 真实 H2 + 真实查询工具 + 桩模型。
 *
 * <p>验证的是**协议与上下文**，不是模型质量：
 *
 * <ol>
 *   <li>同一 conversationId 连续两轮 → 两个不同的 runId（终态 run 绝不复用）；</li>
 *   <li>第二轮送给模型的输入里真的有第一轮的问答文本（不是前端拼的假历史）；</li>
 *   <li>第二轮输入里带"会话已知对象"，且 ID 来自第一轮的真实工具结果；</li>
 *   <li>另一个会话看不到这些上下文（不串号）；</li>
 *   <li>同会话还有待审批任务时，第二条消息被 409 拦下；</li>
 *   <li>不带 conversationId 的老入口行为不变，且不产生会话记录。</li>
 * </ol>
 */
@HermeticSpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mkagent.approval.ttl=10m",
                "mkagent.agent.music.write-enabled=true"
        }
)
@Timeout(180)
class ConversationLoopIntegrationTest {

    private static final String USER = MusicSeedInitializer.USER_ALICE;

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingChatModel model;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private VectorStore chatAppVectorStore;

    private final List<String> createdConversations = new ArrayList<>();

    @BeforeEach
    void reset() {
        model.reset();
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ==================== 主链路 ====================

    @Test
    @DisplayName("同一会话连续两轮：新 runId + 历史文本 + 业务引用都进了模型输入")
    void secondTurnCarriesHistoryAndRefs() throws Exception {
        String conversationId = createConversation();
        createdConversations.add(conversationId);

        String firstRun = send("帮我查一下夜航星的歌", conversationId);
        assertEquals("SUCCEEDED", runState(firstRun), "第一轮应成功");

        // 第一轮真实工具结果：夜航星有 5 首（种子数据）
        assertTrue(toolNames(firstRun).contains("search_songs"),
                "第一轮应调用 search_songs，实际：" + toolNames(firstRun));

        List<Message> firstPrompt = model.promptOf(0);

        String secondRun = send("第一首是哪一年发行的？", conversationId);
        assertEquals("SUCCEEDED", runState(secondRun), "第二轮应成功");

        // 1. 每轮都是新 run：绝不复活终态 run
        assertNotEquals(firstRun, secondRun, "同一会话的每一轮都必须是新 runId");
        assertEquals(2, countRuns(conversationId), "该会话应有两轮");

        // 2. 第二轮输入比第一轮多：system + 历史(2) + 本轮
        List<Message> turn2Prompt = model.promptOf(model.size() - 1);
        assertTrue(turn2Prompt.size() > firstPrompt.size(),
                "第二轮应带上历史，实际第一轮=" + firstPrompt.size()
                        + " 第二轮=" + turn2Prompt.size());

        assertTrue(turn2Prompt.stream().anyMatch(m -> m instanceof UserMessage
                        && m.getText().contains("帮我查一下夜航星的歌")),
                "第二轮输入应包含第一轮的用户原话");
        assertTrue(turn2Prompt.stream().anyMatch(m -> m instanceof AssistantMessage
                        && m.getText().contains(RecordingChatModel.ANSWER)),
                "第二轮输入应包含第一轮的助手回答");

        // 3. 业务引用来自真实工具结果（歌曲 ID + 顺序），不是模型自述
        String lastUserText = lastUserText(turn2Prompt);
        assertTrue(lastUserText.contains("[会话已知对象"),
                "第二轮输入应带会话已知对象，实际：" + lastUserText);
        assertTrue(lastUserText.contains("城市霓虹"),
                "引用里应有第一轮真实搜到的歌名，实际：" + lastUserText);
        assertTrue(lastUserText.contains("（id=1001）"),
                "引用里应带真实歌曲 ID，实际：" + lastUserText);
        assertTrue(lastUserText.contains("第一首是哪一年发行的？"),
                "用户原话必须原样保留在本轮输入里，实际：" + lastUserText);

        // 第一轮不该有引用（会话还没有任何工具结果）
        assertFalse(lastUserText(firstPrompt).contains("[会话已知对象"),
                "第一轮不应凭空出现引用");

        // 引用也落了库：刷新后仍可复用
        String refsJson = jdbcTemplate.queryForObject(
                "SELECT refs_json FROM agent_conversation WHERE conversation_id = ?",
                String.class, conversationId);
        assertNotNull(refsJson, "业务引用应落库");
        assertTrue(refsJson.contains("1001"), "落库的引用应含真实歌曲 ID，实际：" + refsJson);
    }

    @Test
    @DisplayName("会话详情：消息按时间正序，刷新后能还原同一会话")
    void detailReturnsMessagesInOrder() throws Exception {
        String conversationId = createConversation();
        createdConversations.add(conversationId);

        send("帮我查一下夜航星的歌", conversationId);
        send("第一首是哪一年发行的？", conversationId);

        JsonNode detail = getJson("/ai/conversations/" + conversationId);
        JsonNode messages = detail.get("messages");

        assertEquals(4, messages.size(), "两轮 = 4 条消息（用户+助手各两条）");
        assertEquals("USER", messages.get(0).get("role").asText());
        assertEquals("帮我查一下夜航星的歌", messages.get(0).get("content").asText());
        assertEquals("ASSISTANT", messages.get(1).get("role").asText());
        assertEquals("USER", messages.get(2).get("role").asText());
        assertEquals("第一首是哪一年发行的？", messages.get(2).get("content").asText());

        // 标题取首条输入，便于列表里辨认
        assertEquals("帮我查一下夜航星的歌", detail.get("title").asText());

        // 列表里能看到这个会话
        JsonNode list = getJson("/ai/conversations");
        boolean found = false;
        for (JsonNode item : list) {
            if (conversationId.equals(item.get("conversationId").asText())) {
                found = true;
                assertEquals(4, item.get("messageCount").asInt());
            }
        }
        assertTrue(found, "会话列表应包含新建的会话，实际：" + list);
    }

    @Test
    @DisplayName("不同会话上下文互不可见")
    void conversationsAreIsolated() throws Exception {
        String first = createConversation();
        String second = createConversation();
        createdConversations.add(first);
        createdConversations.add(second);

        send("帮我查一下夜航星的歌", first);
        send("把前两首建一个歌单", second);

        List<Message> secondPrompt = model.promptOf(model.size() - 1);
        String lastUser = lastUserText(secondPrompt);

        assertFalse(secondPrompt.stream().anyMatch(m -> m instanceof UserMessage
                        && m.getText().contains("帮我查一下夜航星的歌")),
                "第二个会话不应看到第一个会话的历史");
        assertFalse(lastUser.contains("[会话已知对象"),
                "第二个会话不应继承第一个会话的引用，实际：" + lastUser);
    }

    @Test
    @DisplayName("同会话还有待审批任务时，第二条消息被拦下且不新建 run")
    void blocksSecondTaskWhileWaitingApproval() throws Exception {
        String conversationId = createConversation();
        createdConversations.add(conversationId);

        // 让桩模型提出一次写请求 → 任务停在审批上
        model.toolName = "create_playlist";
        model.toolArgs = "{\"name\":\"会话并发验证\",\"songIds\":[1001]}";

        String pausedRun = send("帮我建一个歌单叫「会话并发验证」", conversationId);
        assertEquals("WAITING_APPROVAL", runState(pausedRun),
                "写工具应停在审批上，实际：" + runState(pausedRun));

        /*
         * 暂停中的轮次只有用户消息（助手回答要等恢复执行完才有），
         * 所以这里记下当前条数，只断言"被拦下时没有多出任何一条"。
         */
        int messagesBefore = countMessages(conversationId);

        HttpResponse<String> blocked = getRaw(
                "/ai/music/chat?message=" + encode("再帮我加一首")
                        + "&conversationId=" + conversationId);
        assertEquals(409, blocked.statusCode(),
                "同会话并发应被拦下，实际：" + blocked.statusCode() + " " + blocked.body());

        assertEquals(1, countRuns(conversationId), "被拦下时不得新建 run");
        assertEquals(messagesBefore, countMessages(conversationId),
                "被拦下时不得落下第二条用户消息");

        // 收尾：拒绝掉，避免残留 PENDING 影响其它用例
        String approvalId = jdbcTemplate.queryForObject(
                "SELECT approval_id FROM agent_approval WHERE run_id = ? AND status = 'PENDING'",
                String.class, pausedRun);
        post("/ai/approvals/" + approvalId + "/reject");
    }

    @Test
    @DisplayName("不带 conversationId 的老入口行为不变，且不产生会话记录")
    void standaloneEntryCreatesNoConversation() throws Exception {
        int before = countAllConversations();

        HttpResponse<String> response = getRaw(
                "/ai/music/chat?message=" + encode("帮我查一下夜航星的歌"));
        assertEquals(200, response.statusCode(), "独立任务入口应正常，body=" + response.body());

        assertEquals(before, countAllConversations(),
                "不带 conversationId 时不应创建会话");
    }

    // ==================== 辅助 ====================

    private String createConversation() throws Exception {
        HttpResponse<String> response = post("/ai/conversations");
        assertEquals(200, response.statusCode(), "建会话失败，body=" + response.body());
        JsonNode node = JSON.readTree(response.body());
        String conversationId = node.get("conversationId").asText();
        assertFalse(conversationId.isBlank(), "应返回 conversationId");
        return conversationId;
    }

    /**
     * 发一条消息并等它跑完（SSE 是全量缓冲读取，返回时任务已终态）。
     */
    private String send(String message, String conversationId) throws Exception {
        HttpResponse<String> response = getRaw(
                "/ai/music/chat?message=" + encode(message)
                        + "&conversationId=" + conversationId);

        assertEquals(200, response.statusCode(),
                "发送失败，body=" + response.body());

        // 用"该会话最新一轮"定位 runId：SSE 里也有 run_id，但库里是权威来源
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT run_id FROM agent_run WHERE conversation_id = ? ORDER BY id DESC LIMIT 1",
                conversationId);
        assertFalse(rows.isEmpty(), "应有一轮 run");
        return rows.get(0).get("run_id").toString();
    }

    private String runState(String runId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM agent_run WHERE run_id = ?", String.class, runId);
    }

    private String toolNames(String runId) {
        String trace = jdbcTemplate.queryForObject(
                "SELECT tool_trace FROM agent_run WHERE run_id = ?", String.class, runId);
        return trace == null ? "" : trace;
    }

    private int countRuns(String conversationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run WHERE conversation_id = ?",
                Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    private int countMessages(String conversationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_conversation_message WHERE conversation_id = ?",
                Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    private int countAllConversations() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_conversation", Integer.class);
        return count == null ? 0 : count;
    }

    /** 取本轮用户输入（最后一条 UserMessage 的文本）。 */
    private String lastUserText(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                return messages.get(i).getText();
            }
        }
        return "";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> getRaw(String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api" + pathAndQuery))
                .header("X-User-Id", USER)
                .header("Accept", "text/event-stream, application/json")
                .timeout(Duration.ofSeconds(60))
                .GET()
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

    /**
     * 清理本测试造的会话与其 run。
     *
     * <p>按"子表 → 主表"顺序删：消息 → 审批/检查点 → run → 会话。
     * 只删本测试记录的 conversationId，不碰其它用例的数据。
     */
    private void cleanup() {
        for (String conversationId : createdConversations) {
            List<String> runIds = jdbcTemplate.queryForList(
                    "SELECT run_id FROM agent_run WHERE conversation_id = ?",
                    String.class, conversationId);

            for (String runId : runIds) {
                jdbcTemplate.update("DELETE FROM agent_approval WHERE run_id = ?", runId);
                jdbcTemplate.update("DELETE FROM agent_checkpoint WHERE run_id = ?", runId);
            }
            jdbcTemplate.update(
                    "DELETE FROM agent_conversation_message WHERE conversation_id = ?",
                    conversationId);
            jdbcTemplate.update(
                    "DELETE FROM agent_run WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update(
                    "DELETE FROM agent_conversation WHERE conversation_id = ?", conversationId);
        }
        createdConversations.clear();
    }

    // ==================== 桩模型 ====================

    /**
     * 记录每一次真实模型输入的桩模型。
     *
     * <p>行为只有两条：输入末尾是用户消息 → 提出工具调用；否则 → 给最终回答。
     * 这样同一套桩可以跑多轮、多个会话，而 {@link #prompts()} 里留下了
     * "模型到底看到了什么"的直接证据——这正是本轮要验证的东西。
     */
    static class RecordingChatModel implements ChatModel {

        static final String ANSWER = "夜航星一共有 5 首作品。";

        private final List<List<Message>> prompts =
                Collections.synchronizedList(new ArrayList<>());

        volatile String toolName = "search_songs";

        volatile String toolArgs = "{\"keyword\":\"夜航星\",\"limit\":5}";

        void reset() {
            prompts.clear();
            toolName = "search_songs";
            toolArgs = "{\"keyword\":\"夜航星\",\"limit\":5}";
        }

        int size() {
            return prompts.size();
        }

        List<Message> promptOf(int index) {
            synchronized (prompts) {
                return List.copyOf(prompts.get(index));
            }
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = List.copyOf(prompt.getInstructions());
            prompts.add(messages);

            Message last = messages.get(messages.size() - 1);
            if (last instanceof UserMessage) {
                return new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "",
                                Map.of(),
                                List.of(new AssistantMessage.ToolCall(
                                        "call-" + prompts.size(), "function",
                                        toolName, toolArgs))))),
                        ChatResponseMetadata.builder()
                                .model("stub-model")
                                .usage(new StubUsage(10, 5))
                                .build());
            }

            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(ANSWER))),
                    ChatResponseMetadata.builder()
                            .model("stub-model")
                            .usage(new StubUsage(10, 5))
                            .build());
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

        private final RecordingChatModel behavior = new RecordingChatModel();

        @Bean
        RecordingChatModel recordingChatModel() {
            return behavior;
        }

        @Bean("dashscopeChatModel")
        @Primary
        ChatModel dashscopeChatModel() {
            DashScopeChatModel mock = Mockito.mock(DashScopeChatModel.class);
            Mockito.when(mock.call(Mockito.any(Prompt.class)))
                    .thenAnswer(invocation ->
                            behavior.call(invocation.getArgument(0, Prompt.class)));
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
    }
}
