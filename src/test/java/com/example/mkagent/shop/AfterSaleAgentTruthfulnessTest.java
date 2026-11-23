package com.example.mkagent.shop;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 C（F-01 / F-02 核心回归）：
 * <ol>
 *   <li>复现原缺陷：模型调完 preview_return 后不调 submit_return，直接输出“正在为您提交退货申请...” →
 *       系统必须拦截并桥接到真实 submit_return 审批暂停卡，确认前零建单/零占额；批准确认后才建单返回真实退货单号；</li>
 *   <li>模型未做任何有效预览却编造“已提交/正在提交” → 后端纠偏为“尚未提交”，发出 NOT_SUBMITTED 状态事件；</li>
 *   <li>显式“准备确认卡”接口 {@code POST /api/shop/aftersale/conversations/prepare-approval} →
 *       不依赖模型即可生成真实待确认卡，确认前零建单，批准后建 1 单；</li>
 *   <li>从订单详情带入 {@code orderNo} 上下文 → 模型收到的输入里包含权威订单上下文。</li>
 * </ol>
 */
@HermeticSpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mkagent.shop.demo-identity-enabled=true",
                "mkagent.approval.ttl=10m"
        }
)
@Timeout(120)
class AfterSaleAgentTruthfulnessTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_SHOP_ONLY;
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private ScriptedAfterSaleModel scriptedModel;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private VectorStore chatAppVectorStore;

    private ShopApiClient api;
    private String signedOrderNo;

    @BeforeEach
    void setUp() {
        api = new ShopApiClient(port);
        scriptedModel.reset();
        ShopTestFixture.reset(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM agent_approval WHERE user_id = ?", BUYER);
        jdbcTemplate.update("DELETE FROM agent_checkpoint WHERE run_id IN (SELECT run_id FROM agent_run WHERE user_id = ?)", BUYER);
        jdbcTemplate.update("DELETE FROM agent_conversation_message WHERE conversation_id IN (SELECT conversation_id FROM agent_conversation WHERE user_id = ?)", BUYER);
        jdbcTemplate.update("DELETE FROM agent_run WHERE user_id = ?", BUYER);
        jdbcTemplate.update("DELETE FROM agent_conversation WHERE user_id = ?", BUYER);

        signedOrderNo = ShopTestFixture.createSignedOrder(
                jdbcTemplate, "SO-AFTERSALE-F01-01", BUYER,
                ShopSeedInitializer.SKU_KEYBOARD_AIR75,
                ShopSeedInitializer.PRODUCT_KEYBOARD_AIR75,
                "无线轻音机械键盘 Air75", 36900L, 2,
                LocalDateTime.now().minusDays(1));
    }

    @Test
    @DisplayName("F-01/F-02 回归：模型停在 preview_return 并宣称正在提交 → 桥接真实确认卡，确认前零建单，批准后建 1 单")
    void previewThenFalseSubmitClaimBridgesToRealApprovalCard() throws Exception {
        scriptedModel.mode = Mode.PREVIEW_THEN_FALSE_SUBMIT_TEXT;
        scriptedModel.orderNo = signedOrderNo;

        // 发起售后对话（带入订单明细上下文 orderNo）
        String sse = getSse("/ai/aftersale/chat?orderNo=" + signedOrderNo
                + "&message=" + encode("这单退 1 把，原因是按键回弹偏硬，直接提交退货申请"));

        // 1. 模型提示里收到了明确的订单上下文
        assertTrue(scriptedModel.lastUserPromptText().contains(signedOrderNo),
                "从订单进入必须把明确订单号交给模型");

        // 2. 桥接产生了真实 approval_required，且确认前零建单、零占额
        assertTrue(sse.contains("event:approval_required"), "必须产出真实待确认卡：\n" + sse);
        assertTrue(sse.contains("READY_FOR_CONFIRMATION"), "必须推送待确认结构化状态：\n" + sse);
        assertFalse(sse.contains("正在为您提交退货申请"), "绝不能把模型假提交文案推送成最终回答：\n" + sse);
        assertEquals(0, ShopTestFixture.totalReturns(jdbcTemplate), "确认前必须零建单");
        assertEquals(0, ShopTestFixture.occupiedQty(jdbcTemplate, signedOrderNo), "确认前必须零占额");

        String approvalId = jdbcTemplate.queryForObject(
                "SELECT approval_id FROM agent_approval WHERE user_id = ? AND status = 'PENDING' ORDER BY created_at DESC LIMIT 1",
                String.class, BUYER);
        assertNotNull(approvalId);

        // 3. 买家批准确认卡 → 真正执行 submit_return → 创建 1 张退货单并返回真实 returnNo
        String resumeSse = postSse("/ai/approvals/" + approvalId + "/approve");
        assertTrue(resumeSse.contains("SUBMITTED"), "批准执行后应发出 SUBMITTED 结构化状态：\n" + resumeSse);
        assertEquals(1, ShopTestFixture.totalReturns(jdbcTemplate), "批准后应恰好创建 1 张退货单");
        assertEquals(1, ShopTestFixture.occupiedQty(jdbcTemplate, signedOrderNo), "批准后应准确占用 1 件额度");

        String returnNo = jdbcTemplate.queryForObject(
                "SELECT return_no FROM shop_aftersale_return ORDER BY id DESC LIMIT 1", String.class);
        assertNotNull(returnNo);
        assertTrue(resumeSse.contains(returnNo), "最终结果必须带真实退货单号 " + returnNo);
    }

    @Test
    @DisplayName("F-02 回归：模型未预览却空口说“正在提交退货申请” → 纠偏为尚未提交，不建单")
    void ungroundedSubmitClaimIsSanitizedAsNotSubmitted() throws Exception {
        scriptedModel.mode = Mode.HALLUCINATE_SUBMIT_WITHOUT_TOOL;

        String sse = getSse("/ai/aftersale/chat?message=" + encode("帮我退货，不用再问了"));

        assertTrue(sse.contains("NOT_SUBMITTED"), "未产出确认卡且无单号时必须标记 NOT_SUBMITTED：\n" + sse);
        assertTrue(sse.contains("尚未提交"), "最终文案必须诚实说明尚未提交：\n" + sse);
        assertEquals(0, ShopTestFixture.totalReturns(jdbcTemplate), "“不用再问”绝不等于已批准落单");
    }

    @Test
    @DisplayName("显式准备确认卡接口：校验资格后生成真实 PENDING 审批卡，拒绝零建单，批准建 1 单")
    void explicitPrepareApprovalEndpointProducesRealApprovalCard() throws Exception {
        ShopApiClient.Reply prepared = api.post("/shop/aftersale/conversations/prepare-approval", BUYER, Map.of(
                "orderNo", signedOrderNo,
                "quantity", 1,
                "reason", "尺寸不合适"
        ));
        assertEquals(200, prepared.status(), prepared.raw());
        String approvalId = prepared.text("approvalId");
        assertNotNull(approvalId);
        assertEquals(0, ShopTestFixture.totalReturns(jdbcTemplate), "准备确认卡阶段不得建单");

        // 拒绝该确认卡 → 零建单
        String rejectSse = postSse("/ai/approvals/" + approvalId + "/reject");
        assertTrue(rejectSse.contains("event:done"), rejectSse);
        assertEquals(0, ShopTestFixture.totalReturns(jdbcTemplate), "拒绝确认卡不得建单");

        // 再次准备并批准 → 建 1 单
        ShopApiClient.Reply prepared2 = api.post("/shop/aftersale/conversations/prepare-approval", BUYER, Map.of(
                "orderNo", signedOrderNo,
                "quantity", 1,
                "reason", "尺寸不合适"
        ));
        assertEquals(200, prepared2.status(), prepared2.raw());
        String approvalId2 = prepared2.text("approvalId");

        scriptedModel.mode = Mode.PLAIN_CONFIRM_SUMMARY;
        String resumeSse = postSse("/ai/approvals/" + approvalId2 + "/approve");
        assertTrue(resumeSse.contains("SUBMITTED"), resumeSse);
        assertEquals(1, ShopTestFixture.totalReturns(jdbcTemplate));
    }

    // ==================== 辅助 ====================

    private String getSse(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                .header("X-User-Id", BUYER)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, resp.statusCode(), resp.body());
        return resp.body();
    }

    private String postSse(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                .header("X-User-Id", BUYER)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, resp.statusCode(), resp.body());
        return resp.body();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    enum Mode {
        PREVIEW_THEN_FALSE_SUBMIT_TEXT,
        HALLUCINATE_SUBMIT_WITHOUT_TOOL,
        PLAIN_CONFIRM_SUMMARY
    }

    static class ScriptedAfterSaleModel implements ChatModel {
        volatile Mode mode = Mode.PREVIEW_THEN_FALSE_SUBMIT_TEXT;
        volatile String orderNo = "";
        private final List<List<Message>> prompts = Collections.synchronizedList(new ArrayList<>());

        void reset() {
            prompts.clear();
            mode = Mode.PREVIEW_THEN_FALSE_SUBMIT_TEXT;
            orderNo = "";
        }

        String lastUserPromptText() {
            synchronized (prompts) {
                for (int i = prompts.size() - 1; i >= 0; i--) {
                    for (Message m : prompts.get(i)) {
                        if ("user".equalsIgnoreCase(m.getMessageType().getValue())) {
                            return m.getText();
                        }
                    }
                }
                return "";
            }
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> msgs = List.copyOf(prompt.getInstructions());
            prompts.add(msgs);
            Message last = msgs.get(msgs.size() - 1);

            if (mode == Mode.HALLUCINATE_SUBMIT_WITHOUT_TOOL) {
                return textResponse("好的，正在为您提交退货申请，请稍候...");
            }
            if (mode == Mode.PLAIN_CONFIRM_SUMMARY) {
                return textResponse("退货申请已为您提交完成。");
            }

            // PREVIEW_THEN_FALSE_SUBMIT_TEXT:
            // 第 1 步调用 preview_return；收到工具结果后不调 submit_return，故意复现“正在为您提交...”
            boolean hasPreviewResult = msgs.stream().anyMatch(m -> m instanceof ToolResponseMessage tr
                    && tr.getResponses().stream().anyMatch(r -> "preview_return".equals(r.name())));
            boolean hasSubmitResult = msgs.stream().anyMatch(m -> m instanceof ToolResponseMessage tr
                    && tr.getResponses().stream().anyMatch(r -> "submit_return".equals(r.name())));

            if (hasSubmitResult) {
                String returnNo = extractReturnNo(msgs);
                return textResponse("退货申请已提交，退货单号：" + returnNo);
            }
            if (!hasPreviewResult) {
                String args = String.format(
                        "{\"orderNo\":\"%s\",\"quantity\":1,\"reason\":\"按键回弹偏硬\"}", orderNo);
                return new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "", Map.of(),
                                List.of(new AssistantMessage.ToolCall("call-prev-1", "function", "preview_return", args))))),
                        meta());
            }
            return textResponse("正在为您提交退货申请，请稍候...");
        }

        private String extractReturnNo(List<Message> msgs) {
            for (Message m : msgs) {
                if (m instanceof ToolResponseMessage tr) {
                    for (ToolResponseMessage.ToolResponse r : tr.getResponses()) {
                        if ("submit_return".equals(r.name())) {
                            try {
                                String raw = r.responseData();
                                JsonNode node = JSON.readTree(raw);
                                if (node.isTextual()) {
                                    node = JSON.readTree(node.asText());
                                }
                                if (node.has("data") && node.get("data").has("returnNo")) {
                                    return node.get("data").get("returnNo").asText();
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
            return "";
        }

        private ChatResponse textResponse(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), meta());
        }

        private ChatResponseMetadata meta() {
            return ChatResponseMetadata.builder()
                    .model("scripted-aftersale")
                    .usage(new StubUsage(10, 10))
                    .build();
        }
    }

    record StubUsage(int promptTokens, int completionTokens) implements Usage {
        @Override public Integer getPromptTokens() { return promptTokens; }
        @Override public Integer getCompletionTokens() { return completionTokens; }
        @Override public Object getNativeUsage() { return null; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfig {
        private final ScriptedAfterSaleModel model = new ScriptedAfterSaleModel();

        @Bean("dashscopeChatModel")
        @Primary
        ChatModel dashscopeChatModel() {
            DashScopeChatModel mock = Mockito.mock(DashScopeChatModel.class);
            Mockito.when(mock.call(Mockito.any(Prompt.class)))
                    .thenAnswer(inv -> model.call(inv.getArgument(0, Prompt.class)));
            return mock;
        }

        @Bean
        ScriptedAfterSaleModel scriptedAfterSaleModel() {
            return model;
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
}
