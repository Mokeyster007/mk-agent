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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导购 Agent 端到端链路测试（场景 A + B）：
 * 选品 → 比较（不写购物车）→ “第二款加一把” 绑定 SKU 加购 → 共享购物车可见 →
 * 结算预览 → 确认卡暂停（确认前零建单）→ 批准确认建 1 张订单 → 重复批准被拦下且不建第二单。
 */
@HermeticSpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mkagent.shop.demo-identity-enabled=true",
                "mkagent.approval.ttl=10m"
        }
)
@Timeout(120)
class ShopAssistantAgentFlowTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_SHOP_ONLY;
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private ScriptedShopModel scriptedModel;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private VectorStore chatAppVectorStore;

    private ShopApiClient api;

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
    }

    @Test
    @DisplayName("完整导购链路：自然语言选品 → 比较 → 第二款加一把 → 共享购物车 → 确认下单 → 订单详情")
    void fullShoppingAgentConversationToOrderDetail() throws Exception {
        ShopApiClient.Reply conv = api.post("/shop/assistant/conversations", BUYER, null);
        assertEquals(200, conv.status(), conv.raw());
        String conversationId = conv.text("conversationId");

        // Turn 1: 选品
        scriptedModel.nextTool("search_products",
                "{\"category\":\"KEYBOARD\",\"maxPriceYuan\":400,\"wirelessOnly\":true,\"macCompatible\":true,\"preferSilent\":true}",
                "为您找到 2 款 400 元以内兼容 Mac 的无线键盘：1. 无线轻音机械键盘 Air75（静音红轴 ¥369）；2. 紧凑蓝牙键盘 B68（青轴 ¥259）。");
        String turn1 = getSse("/ai/shop/chat?conversationId=" + conversationId
                + "&message=" + encode("400 元内、无线、兼容 Mac，最好安静"));
        assertTrue(turn1.contains("product_cards"), "应推送结构化商品卡事件：\n" + turn1);
        assertEquals(0, cartCount(), "只问推荐不得写入购物车");

        // Turn 2: 比较两款
        scriptedModel.nextTool("compare_products",
                "{\"skusOrIds\":\"SKU-KEYBOARD-A75,SKU-KEYBOARD-B68\"}",
                "两款已并排比较完毕：Air75 更安静且支持三模无线，B68 更轻便且价格更低。");
        String turn2 = getSse("/ai/shop/chat?conversationId=" + conversationId
                + "&message=" + encode("帮我比较这两款"));
        assertTrue(turn2.contains("product_comparison"), "应推送结构化对比事件：\n" + turn2);
        assertEquals(0, cartCount(), "只做比较不得写入购物车");

        // Turn 3: 第二款加一把（模型输入必须能看到 ConversationRefs 中的第 2 款 = SKU-KEYBOARD-B68）
        scriptedModel.nextTool("add_to_cart",
                "{\"skuOrId\":\"SKU-KEYBOARD-B68\",\"quantity\":1}",
                "已将紧凑蓝牙键盘 B68（SKU-KEYBOARD-B68）× 1 加入购物车。");
        String turn3 = getSse("/ai/shop/chat?conversationId=" + conversationId
                + "&message=" + encode("第二款加一把"));
        assertTrue(turn3.contains("cart_snapshot"), turn3);
        assertTrue(scriptedModel.lastPromptContains("2. 紧凑蓝牙键盘 B68"),
                "会话引用必须保留候选顺序以绑定“第二款”");

        // 页面共享购物车立即可见该 SKU
        ShopApiClient.Reply cart = api.get("/shop/cart", BUYER);
        assertEquals(200, cart.status(), cart.raw());
        assertEquals(1, cart.body().get("items").size());
        assertEquals(ShopSeedInitializer.SKU_KEYBOARD_B68,
                cart.body().get("items").get(0).get("sku").asText());

        // Turn 4: 结算预览并触发下单确认卡
        scriptedModel.nextTwoTools(
                "preview_checkout",
                "{\"skuOrId\":\"SKU-KEYBOARD-B68\",\"quantity\":1}",
                "submit_checkout_order",
                "{\"sku\":\"SKU-KEYBOARD-B68\",\"quantity\":1,\"expectedUnitPriceCents\":25900}",
                "下单已完成。");
        String turn4 = getSse("/ai/shop/chat?conversationId=" + conversationId
                + "&message=" + encode("结算购物车里的这把键盘并下单"));
        assertTrue(turn4.contains("event:approval_required"), "必须产出真实下单确认卡：\n" + turn4);
        assertEquals(0, orderCount(), "用户确认前必须零建单");

        String approvalId = jdbcTemplate.queryForObject(
                "SELECT approval_id FROM agent_approval WHERE user_id = ? AND status = 'PENDING' ORDER BY created_at DESC LIMIT 1",
                String.class, BUYER);
        assertNotNull(approvalId);

        // 批准确认 → 创建 1 张真实订单
        String approveSse = postSse("/ai/approvals/" + approvalId + "/approve");
        assertTrue(approveSse.contains("order_created"), "确认后应推送 order_created 事件：\n" + approveSse);
        assertEquals(1, orderCount(), "批准后应恰好创建 1 张订单");

        // 重复批准同一确认卡 → 409 且绝不建第二单
        ShopApiClient.Reply dup = api.post("/ai/approvals/" + approvalId + "/approve", BUYER, null);
        assertEquals(409, dup.status(), dup.raw());
        assertEquals(1, orderCount(), "重复确认绝不得建第二张订单");

        // 打开订单详情核对 SKU、数量、单价
        String orderNo = jdbcTemplate.queryForObject(
                "SELECT order_no FROM shop_order WHERE buyer_id = ? ORDER BY id DESC LIMIT 1",
                String.class, BUYER);
        ShopApiClient.Reply detail = api.get("/shop/orders/" + orderNo, BUYER);
        assertEquals(200, detail.status(), detail.raw());
        assertEquals(ShopSeedInitializer.SKU_KEYBOARD_B68, detail.body().get("item").get("sku").asText());
        assertEquals(1, detail.body().get("item").get("quantity").asInt());
        assertEquals(25900L, detail.body().get("item").get("unitPriceCents").asLong());
    }

    private int cartCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_cart_item WHERE buyer_id = ?", Integer.class, BUYER);
        return n == null ? 0 : n;
    }

    private int orderCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_order WHERE buyer_id = ?", Integer.class, BUYER);
        return n == null ? 0 : n;
    }

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

    static class ScriptedShopModel implements ChatModel {
        private final List<List<Message>> prompts = Collections.synchronizedList(new ArrayList<>());
        private volatile String tool1;
        private volatile String args1;
        private volatile String tool2;
        private volatile String args2;
        private volatile String answer = "完成";
        private int turnStep = 0;

        void reset() {
            prompts.clear();
            tool1 = null;
            args1 = null;
            tool2 = null;
            args2 = null;
            turnStep = 0;
        }

        void nextTool(String tool, String args, String finalAnswer) {
            this.tool1 = tool;
            this.args1 = args;
            this.tool2 = null;
            this.args2 = null;
            this.answer = finalAnswer;
            this.turnStep = 0;
        }

        void nextTwoTools(String t1, String a1, String t2, String a2, String finalAnswer) {
            this.tool1 = t1;
            this.args1 = a1;
            this.tool2 = t2;
            this.args2 = a2;
            this.answer = finalAnswer;
            this.turnStep = 0;
        }

        boolean lastPromptContains(String snippet) {
            synchronized (prompts) {
                for (int i = prompts.size() - 1; i >= 0; i--) {
                    for (Message m : prompts.get(i)) {
                        if (m.getText() != null && m.getText().contains(snippet)) {
                            return true;
                        }
                    }
                }
                return false;
            }
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> msgs = List.copyOf(prompt.getInstructions());
            prompts.add(msgs);
            turnStep++;

            if (turnStep == 1 && tool1 != null) {
                return toolResp("call-s1-" + prompts.size(), tool1, args1);
            }
            if (turnStep == 2 && tool2 != null) {
                return toolResp("call-s2-" + prompts.size(), tool2, args2);
            }
            // 如果是订单确认后的总结轮，把真实订单号带回文本
            String reply = answer;
            for (Message m : msgs) {
                if (m instanceof ToolResponseMessage tr) {
                    for (ToolResponseMessage.ToolResponse r : tr.getResponses()) {
                        if ("submit_checkout_order".equals(r.name())) {
                            try {
                                JsonNode n = JSON.readTree(r.responseData());
                                if (n.isTextual()) n = JSON.readTree(n.asText());
                                if (n.has("data") && n.get("data").has("orderNo")) {
                                    reply = "已为您创建订单：" + n.get("data").get("orderNo").asText();
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))), meta());
        }

        private ChatResponse toolResp(String id, String name, String args) {
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(
                            "", Map.of(),
                            List.of(new AssistantMessage.ToolCall(id, "function", name, args))))),
                    meta());
        }

        private ChatResponseMetadata meta() {
            return ChatResponseMetadata.builder()
                    .model("scripted-shop")
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
        private final ScriptedShopModel model = new ScriptedShopModel();

        @Bean("dashscopeChatModel")
        @Primary
        ChatModel dashscopeChatModel() {
            DashScopeChatModel mock = Mockito.mock(DashScopeChatModel.class);
            Mockito.when(mock.call(Mockito.any(Prompt.class)))
                    .thenAnswer(inv -> model.call(inv.getArgument(0, Prompt.class)));
            return mock;
        }

        @Bean
        ScriptedShopModel scriptedShopModel() {
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
