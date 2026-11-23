package com.example.mkagent.shop;

import com.example.mkagent.config.UnavailableChatModel;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话模型完全没有凭据时，应用仍能启动，商城闭环仍能跑通。
 *
 * <p>这是本轮"用最小改动隔离启动耦合"的直接验证：
 * <ul>
 *   <li>凭据为空 + {@code mkagent.model.allow-missing-credentials=true}
 *       → 注册 {@link UnavailableChatModel} 占位，而不是启动失败；</li>
 *   <li>占位模型一旦被调用就抛 503，绝不返回假回答；</li>
 *   <li>商城的下单 / 发货 / 签收完全不碰模型。</li>
 * </ul>
 *
 * <p><b>本用例的边界（不要读成"任何情况下零凭据都能启动"）：</b>
 * 这里清空的是 {@code spring.ai.dashscope.api-key} / {@code chat.api-key}，
 * 也就是**对话模型**的凭据。DashScope 的其它模型族（语音、Image、Rerank、
 * Embedding）在 api-key 为空时会由库自己抛
 * {@code IllegalArgumentException("DashScope API key must be set")}，
 * 与本次改动无关。因此：
 * <ul>
 *   <li>语音（生产已永久摘除，零使用）与 Image / Rerank / Embedding
 *       （测试配置排除）不在本上下文中装配；</li>
 *   <li>生产仍保留 Embedding 自动配置（RAG 依赖），所以"完全零凭据"
 *       的完整部署还需自行关掉 RAG / Embedding，这是本轮明确留下的限制。</li>
 * </ul>
 *
 * <p>默认（不打开开关）时行为不变：缺凭据仍然启动失败并给出明确提示，
 * 那条路径由 {@code ModelCredentialConfigTest} / {@code StartupSmokeTest} 守着。
 */
@HermeticSpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mkagent.model.allow-missing-credentials=true",
                "spring.ai.dashscope.api-key=",
                "spring.ai.dashscope.chat.api-key="
        })
@Timeout(120)
class ShopStartsWithoutModelCredentialTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    private static final String ADMIN = ShopSeedInitializer.ADMIN;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatModel chatModel;

    private ShopApiClient api;

    @BeforeEach
    void setUp() {
        api = new ShopApiClient(port);
        ShopTestFixture.reset(jdbcTemplate);
    }

    @Test
    @DisplayName("无凭据时容器里是占位模型，调用即 503，不返回假回答")
    void placeholderModelFailsLoudly() {
        assertInstanceOf(UnavailableChatModel.class, chatModel,
                "缺凭据且开启开关时应注册占位模型");

        var error = assertThrows(
                com.example.mkagent.exception.BusinessException.class,
                () -> chatModel.call(new Prompt("ping")));

        assertEquals(503, error.getHttpStatus());
        assertEquals(UnavailableChatModel.CODE, error.getCode());
    }

    @Test
    @DisplayName("无凭据时商城仍然可以下单、发货、签收")
    void shopWorksWithoutAnyCredential() {
        ShopApiClient.Reply products = api.get("/shop/products", BUYER);
        assertEquals(200, products.status(), products.raw());
        assertEquals(ShopSeedInitializer.SEED_PRODUCT_COUNT, products.body().size());

        ShopApiClient.Reply placed = api.post("/shop/orders", BUYER,
                Map.of("productId", ShopSeedInitializer.PRODUCT_KEYBOARD, "quantity", 2));
        assertEquals(201, placed.status(), placed.raw());

        String orderNo = placed.text("orderNo");

        ShopApiClient.Reply shipped = api.post(
                "/shop/admin/orders/" + orderNo + "/ship", ADMIN, null);
        assertEquals(200, shipped.status(), shipped.raw());
        assertTrue(shipped.bool("applied"));

        ShopApiClient.Reply signed = api.post(
                "/shop/admin/orders/" + orderNo + "/sign", ADMIN, null);
        assertEquals(200, signed.status(), signed.raw());
        assertTrue(signed.bool("applied"));

        assertEquals(8, ShopTestFixture.stockOf(jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD));
        assertEquals(1, ShopTestFixture.movementCount(jdbcTemplate, orderNo));
    }
}
