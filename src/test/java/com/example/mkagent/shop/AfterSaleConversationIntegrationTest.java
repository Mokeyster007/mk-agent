package com.example.mkagent.shop;

import com.example.mkagent.conversation.ConversationService;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 售后会话的**归属**与**领域边界**。
 *
 * <h2>要复现的问题</h2>
 *
 * 合法商城买家（{@code shop_user} 里有，{@code music_user} 里没有）在售后页
 * 建不出会话：前端原先走的是 {@code /api/ai/conversations}，
 * 那个入口的守卫要求音乐身份，于是 403 → 退化单轮 → 第二轮追问失忆。
 *
 * <h2>为什么不能"放宽守卫"或"补一条 music_user"</h2>
 *
 * 前者会让音乐入口对非音乐用户开放；后者是伪造身份。
 * 正确做法是**另开领域受限的入口**：守卫换成买家身份，会话领域钉死 AFTER_SALES。
 *
 * <p>因此这里同时钉住三件事：
 * <ol>
 *   <li>纯商城买家在售后入口能建 / 能读 / 能列自己的会话；</li>
 *   <li>同一个人的音乐会话与售后会话**互不可见**（两个方向都是 404）；</li>
 *   <li>音乐入口对纯商城买家**仍然 403**——没有被顺手放宽。</li>
 * </ol>
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AfterSaleConversationIntegrationTest {

    /** 只有商城身份、没有音乐身份的买家（种子数据里就有，且不在 music_user 里）。 */
    private static final String SHOP_ONLY = ShopSeedInitializer.BUYER_SHOP_ONLY;

    /** 同时是音乐用户与商城买家：用来验证领域隔离，而不是身份隔离。 */
    private static final String BOTH = ShopSeedInitializer.BUYER_ONE;

    private static final String OTHER_BUYER = ShopSeedInitializer.BUYER_TWO;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConversationService conversationService;

    private ShopApiClient api;

    /** 本用例创建的会话，结束时按 id 清掉（不做全表删除，避免影响其它测试类）。 */
    private final List<String> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ShopTestFixture.reset(jdbc);
        ShopTestFixture.ensureUser(jdbc, SHOP_ONLY, "BUYER");
        api = new ShopApiClient(port);
        created.clear();
    }

    @AfterEach
    void tearDown() {
        for (String conversationId : created) {
            jdbc.update(
                    "DELETE FROM agent_conversation_message WHERE conversation_id = ?",
                    conversationId);
            jdbc.update(
                    "DELETE FROM agent_conversation WHERE conversation_id = ?", conversationId);
        }
        created.clear();
    }

    // ---------------------------------------------------------------
    // 1. 纯商城买家：能建、能读、能列
    // ---------------------------------------------------------------

    @Test
    @DisplayName("纯商城买家可以在售后入口建会话、读详情、列列表")
    void shopOnlyBuyerOwnsAfterSaleConversation() {
        ShopApiClient.Reply created0 = api.post("/shop/aftersale/conversations", SHOP_ONLY, null);
        assertEquals(200, created0.status(), created0.raw());
        String conversationId = created0.text("conversationId");
        assertNotNull(conversationId, created0.raw());
        created.add(conversationId);

        ShopApiClient.Reply detail = api.get(
                "/shop/aftersale/conversations/" + conversationId, SHOP_ONLY);
        assertEquals(200, detail.status(), detail.raw());
        assertEquals(conversationId, detail.text("conversationId"));

        // 空会话不出现在列表里（与音乐入口同一口径：建了没用不该留空壳）
        ShopApiClient.Reply emptyList = api.get("/shop/aftersale/conversations", SHOP_ONLY);
        assertEquals(200, emptyList.status(), emptyList.raw());
        assertFalse(containsConversation(emptyList, conversationId), emptyList.raw());
    }

    @Test
    @DisplayName("音乐入口对纯商城买家仍然 403（没有放宽守卫）")
    void musicEntryStillRejectsShopOnlyBuyer() {
        ShopApiClient.Reply reply = api.get("/ai/conversations", SHOP_ONLY);
        assertEquals(403, reply.status(), reply.raw());

        /*
         * 同一身份在售后入口是通的（上一个用例），在音乐入口是 403。
         * 两条入口的守卫各自独立，这才是"另开入口"而不是"放宽守卫"的证据。
         */
        assertEquals(200, api.get("/shop/aftersale/conversations", SHOP_ONLY).status());
    }

    // ---------------------------------------------------------------
    // 2. 领域边界：两个方向都不可见
    // ---------------------------------------------------------------

    @Test
    @DisplayName("音乐会话不能被售后入口读到（同一个人，两个方向都是 404）")
    void domainsAreIsolatedInBothDirections() {
        // 音乐会话（BOTH 同时是音乐用户）
        ShopApiClient.Reply music = api.post("/ai/conversations", BOTH, null);
        assertEquals(200, music.status(), music.raw());
        String musicConversationId = music.text("conversationId");
        created.add(musicConversationId);

        // 同一个人的售后会话
        ShopApiClient.Reply aftersale = api.post("/shop/aftersale/conversations", BOTH, null);
        assertEquals(200, aftersale.status(), aftersale.raw());
        String afterSaleConversationId = aftersale.text("conversationId");
        created.add(afterSaleConversationId);

        // 音乐会话 → 售后入口：404（不泄露它存在）
        ShopApiClient.Reply crossRead = api.get(
                "/shop/aftersale/conversations/" + musicConversationId, BOTH);
        assertEquals(404, crossRead.status(), crossRead.raw());

        // 售后会话 → 音乐入口：404
        ShopApiClient.Reply reverseRead = api.get(
                "/ai/conversations/" + afterSaleConversationId, BOTH);
        assertEquals(404, reverseRead.status(), reverseRead.raw());

        // 各自的入口都正常
        assertEquals(200, api.get(
                "/shop/aftersale/conversations/" + afterSaleConversationId, BOTH).status());
        assertEquals(200, api.get(
                "/ai/conversations/" + musicConversationId, BOTH).status());
    }

    @Test
    @DisplayName("售后入口开一轮时也校验领域：拿音乐会话 id 会被 404 挡住")
    void beginTurnRejectsWrongDomain() {
        ShopApiClient.Reply music = api.post("/ai/conversations", BOTH, null);
        String musicConversationId = music.text("conversationId");
        created.add(musicConversationId);

        /*
         * 这是 /ai/aftersale/chat 内部走的同一条路径（beginTurn）。
         * 直接调服务层是为了不依赖模型：控制器那一层只是把异常翻译成 HTTP。
         */
        BusinessException error = assertThrows(BusinessException.class, () ->
                conversationService.beginTurn(
                        BOTH, musicConversationId, "帮我退一把键盘", UUID.randomUUID(),
                        AgentType.AFTER_SALES));
        assertEquals(404, error.getHttpStatus(), error.getMessage());
    }

    @Test
    @DisplayName("别的买家读不到、也开不了我的售后会话")
    void otherBuyerCannotTouchMyConversation() {
        String conversationId = api.post(
                "/shop/aftersale/conversations", SHOP_ONLY, null).text("conversationId");
        created.add(conversationId);

        ShopApiClient.Reply read = api.get(
                "/shop/aftersale/conversations/" + conversationId, OTHER_BUYER);
        assertEquals(404, read.status(), read.raw());

        BusinessException error = assertThrows(BusinessException.class, () ->
                conversationService.beginTurn(
                        OTHER_BUYER, conversationId, "退一把", UUID.randomUUID(),
                        AgentType.AFTER_SALES));
        assertEquals(404, error.getHttpStatus(), error.getMessage());
    }

    // ---------------------------------------------------------------
    // 3. 会话确实被钉上了领域
    // ---------------------------------------------------------------

    @Test
    @DisplayName("新会话在库里带 agent_type=AFTER_SALES")
    void conversationIsTaggedWithDomain() {
        String conversationId = api.post(
                "/shop/aftersale/conversations", SHOP_ONLY, null).text("conversationId");
        created.add(conversationId);

        String agentType = jdbc.queryForObject(
                "SELECT agent_type FROM agent_conversation WHERE conversation_id = ?",
                String.class, conversationId);
        assertEquals(AgentType.AFTER_SALES.name(), agentType);
        assertTrue(conversationId.length() >= 32, "会话 id 应当是 UUID");
    }

    // ---------------------------------------------------------------

    private static boolean containsConversation(ShopApiClient.Reply reply, String conversationId) {
        if (reply.body() == null || !reply.body().isArray()) {
            return false;
        }
        for (var node : reply.body()) {
            if (conversationId.equals(node.path("conversationId").asText())) {
                return true;
            }
        }
        return false;
    }
}
