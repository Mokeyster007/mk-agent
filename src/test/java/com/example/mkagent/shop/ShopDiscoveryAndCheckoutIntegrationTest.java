package com.example.mkagent.shop;

import com.example.mkagent.approval.ApprovalToolPolicy;
import com.example.mkagent.config.AgentToolProvider;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一期核心链路验收测试（场景 A / B / D）：
 * <ul>
 *   <li>A：硬条件筛选 + 偏好排序 → 商品比较 → 共享购物车（不锁库存）→ 结算预览 → 确认下单 → 幂等防重 → 订单详情 → A0 发货才扣库存；</li>
 *   <li>B：无匹配不伪造候选并给出放宽建议；价格变化/商品下架拦截旧报价并零建单；缺失规格如实返回“未知”；</li>
 *   <li>D：纯电商身份与默认启动不依赖音乐工具或音乐配置。</li>
 * </ul>
 */
@HermeticSpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mkagent.shop.demo-identity-enabled=true"
        }
)
class ShopDiscoveryAndCheckoutIntegrationTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_SHOP_ONLY;
    private static final String ADMIN = ShopSeedInitializer.ADMIN_SHOP_ONLY;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentToolProvider toolProvider;

    @Autowired
    private ApprovalToolPolicy approvalToolPolicy;

    private ShopApiClient api;

    @BeforeEach
    void setUp() {
        api = new ShopApiClient(port);
        ShopTestFixture.reset(jdbcTemplate);
    }

    @Test
    @DisplayName("场景 A：硬条件选品 → 比较 → 共享购物车 → 结算预览 → 确认下单 → 重复确认不建第二单")
    void endToEndDiscoveryCompareCartPreviewAndIdempotentCheckout() {
        // 1. 硬条件（400 元内、无线、兼容 Mac、键盘）+ 软偏好（最好安静）
        ShopApiClient.Reply search = api.get(
                "/shop/products/search?category=KEYBOARD&maxPriceYuan=400&wirelessOnly=true&macCompatible=true&preferSilent=true",
                BUYER);
        assertEquals(200, search.status(), search.raw());
        JsonNode candidates = search.body().get("items");
        assertEquals(2, candidates.size(), "只应返回 Air75 和 B68：" + search.raw());
        assertEquals(ShopSeedInitializer.SKU_KEYBOARD_AIR75, candidates.get(0).get("sku").asText(),
                "静音偏好应把静音红轴 Air75 排在第一");
        assertEquals(ShopSeedInitializer.SKU_KEYBOARD_B68, candidates.get(1).get("sku").asText());

        // 2. 比较两款：返回结构化规格
        ShopApiClient.Reply compared = api.post("/shop/products/compare", BUYER,
                Map.of("keys", List.of(
                        ShopSeedInitializer.SKU_KEYBOARD_AIR75,
                        ShopSeedInitializer.SKU_KEYBOARD_B68)));
        assertEquals(200, compared.status(), compared.raw());
        assertEquals(2, compared.body().get("products").size());

        // 3. 加入共享购物车（不锁库存）
        int stockBefore = ShopTestFixture.stockOf(jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD_AIR75);
        ShopApiClient.Reply cartAdded = api.post("/shop/cart/items", BUYER,
                Map.of("sku", ShopSeedInitializer.SKU_KEYBOARD_AIR75, "quantity", 1));
        assertEquals(201, cartAdded.status(), cartAdded.raw());
        assertEquals(1, cartAdded.body().get("items").size());
        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD_AIR75),
                "加购不得扣减或预占库存");

        // 4. 改数量为 2，再预览结算
        ShopApiClient.Reply updated = api.patch(
                "/shop/cart/items/" + ShopSeedInitializer.PRODUCT_KEYBOARD_AIR75,
                BUYER, Map.of("quantity", 2));
        assertEquals(200, updated.status(), updated.raw());
        assertEquals(2, updated.body().get("totalQuantity").asInt());

        ShopApiClient.Reply preview = api.post("/shop/checkout/preview", BUYER,
                Map.of("productId", ShopSeedInitializer.PRODUCT_KEYBOARD_AIR75, "quantity", 2));
        assertEquals(200, preview.status(), preview.raw());
        assertTrue(preview.bool("eligible"));
        assertEquals(36900L, preview.body().get("unitPriceCents").asLong());
        assertEquals(73800L, preview.body().get("totalCents").asLong());
        assertTrue(preview.text("singleSkuNotice").contains("单 SKU"));
        assertTrue(preview.text("stockNotice").contains("发货"));

        // 5. 确认下单 + 重复确认幂等（同一 clientRequestId 只建一张订单）
        String reqId = "req-" + UUID.randomUUID();
        Map<String, Object> confirmReq = Map.of(
                "productId", ShopSeedInitializer.PRODUCT_KEYBOARD_AIR75,
                "quantity", 2,
                "expectedUnitPriceCents", 36900L,
                "removeFromCart", true,
                "clientRequestId", reqId
        );
        ShopApiClient.Reply first = api.post("/shop/checkout/confirm", BUYER, confirmReq);
        assertEquals(201, first.status(), first.raw());
        assertFalse(first.bool("replayed"));
        String orderNo = first.body().get("order").get("orderNo").asText();
        assertNotNull(orderNo);

        ShopApiClient.Reply second = api.post("/shop/checkout/confirm", BUYER, confirmReq);
        assertEquals(201, second.status(), second.raw());
        assertTrue(second.bool("replayed"), "重复确认应命中幂等回放");
        assertEquals(orderNo, second.body().get("order").get("orderNo").asText(), "重复确认必须返回同一单号");

        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_order WHERE buyer_id = ?", Integer.class, BUYER);
        assertEquals(1, orderCount, "重复确认不得创建第二张订单");

        // 6. 打开订单详情核对 SKU、数量、单价；且下单不扣库存（A0 口径），发货才扣库存
        ShopApiClient.Reply detail = api.get("/shop/orders/" + orderNo, BUYER);
        assertEquals(200, detail.status(), detail.raw());
        assertEquals(ShopSeedInitializer.SKU_KEYBOARD_AIR75, detail.body().get("item").get("sku").asText());
        assertEquals(2, detail.body().get("item").get("quantity").asInt());
        assertEquals(36900L, detail.body().get("item").get("unitPriceCents").asLong());
        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD_AIR75),
                "确认下单后库存仍不变（发货才扣库存）");

        // 购物车已清空该已下单商品
        ShopApiClient.Reply cartAfter = api.get("/shop/cart", BUYER);
        assertEquals(0, cartAfter.body().get("items").size());

        // 管理员发货后才扣减库存
        ShopApiClient.Reply shipped = api.post("/shop/admin/orders/" + orderNo + "/ship", ADMIN, null);
        assertEquals(200, shipped.status(), shipped.raw());
        assertEquals(stockBefore - 2, ShopTestFixture.stockOf(jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD_AIR75));
    }

    @Test
    @DisplayName("场景 B：无匹配不伪造候选；缺失规格标“未知”；改价/下架拦截旧报价零建单")
    void zeroMatchUnknownSpecsAndPriceOrStatusChangeGuard() {
        // 1. 严格无匹配：100 元以内的无线 Mac 键盘不存在
        ShopApiClient.Reply zero = api.get(
                "/shop/products/search?category=KEYBOARD&maxPriceYuan=100&wirelessOnly=true&macCompatible=true",
                BUYER);
        assertEquals(200, zero.status(), zero.raw());
        assertEquals(0, zero.body().get("items").size(), "无匹配不得塞入不合规商品");
        assertTrue(zero.body().get("relaxSuggestions").size() > 0, "应给出可放宽条件建议");

        // 2. 缺失规格（M5 鼠标静音参数为空）必须显式返回“未知”
        ShopApiClient.Reply mouseSearch = api.get("/shop/products/search?keyword=M5", BUYER);
        assertEquals(200, mouseSearch.status(), mouseSearch.raw());
        assertEquals("未知", mouseSearch.body().get("items").get(0).get("silentLabel").asText());

        // 3. 后台改价：旧报价确认下单必须 409 PRICE_CHANGED，零建单，并给出最新报价
        ShopApiClient.Reply priceUp = api.post(
                "/shop/admin/products/" + ShopSeedInitializer.PRODUCT_KEYBOARD_AIR75 + "/price",
                ADMIN, Map.of("priceCents", 39900L));
        assertEquals(200, priceUp.status(), priceUp.raw());

        ShopApiClient.Reply staleConfirm = api.post("/shop/checkout/confirm", BUYER, Map.of(
                "productId", ShopSeedInitializer.PRODUCT_KEYBOARD_AIR75,
                "quantity", 1,
                "expectedUnitPriceCents", 36900L,
                "clientRequestId", "stale-" + UUID.randomUUID()
        ));
        assertEquals(409, staleConfirm.status(), staleConfirm.raw());
        assertEquals("PRICE_CHANGED", staleConfirm.text("code"));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shop_order", Integer.class),
                "旧报价不得建单");

        // 按最新报价重新确认可成功建单
        ShopApiClient.Reply freshConfirm = api.post("/shop/checkout/confirm", BUYER, Map.of(
                "productId", ShopSeedInitializer.PRODUCT_KEYBOARD_AIR75,
                "quantity", 1,
                "expectedUnitPriceCents", 39900L,
                "clientRequestId", "fresh-" + UUID.randomUUID()
        ));
        assertEquals(201, freshConfirm.status(), freshConfirm.raw());
        assertEquals(39900L, freshConfirm.body().get("order").get("item").get("unitPriceCents").asLong());

        // 4. 后台下架：确认下单必须 409 PRODUCT_UNAVAILABLE，不再新增订单
        ShopApiClient.Reply off = api.post(
                "/shop/admin/products/" + ShopSeedInitializer.PRODUCT_KEYBOARD_B68 + "/status",
                ADMIN, Map.of("status", "INACTIVE"));
        assertEquals(200, off.status(), off.raw());

        ShopApiClient.Reply inactiveConfirm = api.post("/shop/checkout/confirm", BUYER, Map.of(
                "productId", ShopSeedInitializer.PRODUCT_KEYBOARD_B68,
                "quantity", 1,
                "expectedUnitPriceCents", 25900L,
                "clientRequestId", "off-" + UUID.randomUUID()
        ));
        assertEquals(409, inactiveConfirm.status(), inactiveConfirm.raw());
        assertEquals("PRODUCT_UNAVAILABLE", inactiveConfirm.text("code"));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shop_order", Integer.class),
                "下架商品不得建单");
    }

    @Test
    @DisplayName("场景 D：无音乐能力注册，默认电商导航与纯商城身份可用")
    @SuppressWarnings("deprecation")
    void noMusicToolsRegisteredAndPureShopIdentityWorks() {
        assertThrows(IllegalArgumentException.class, () -> toolProvider.getTools(AgentType.MUSIC_USER));
        assertThrows(IllegalArgumentException.class, () -> toolProvider.getTools(AgentType.MUSIC_ADMIN));

        Set<String> approvalTools = approvalToolPolicy.requiredToolNames();
        assertTrue(approvalTools.contains("submit_return"));
        assertTrue(approvalTools.contains("submit_checkout_order"));
        assertFalse(approvalTools.contains("create_playlist"));
        assertFalse(approvalTools.contains("add_songs_to_playlist"));

        Set<String> shopTools = Arrays.stream(toolProvider.getTools(AgentType.SHOP_ASSISTANT))
                .map(t -> t.getToolDefinition().name())
                .collect(Collectors.toSet());
        assertFalse(shopTools.contains("search_songs"));
        assertTrue(shopTools.contains("search_products"));
        assertTrue(shopTools.contains("submit_checkout_order"));

        ShopApiClient.Reply session = api.get("/shop/session", BUYER);
        assertEquals(200, session.status(), session.raw());
        assertEquals(BUYER, session.text("userId"));
        assertTrue(session.bool("canOrder"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubMcp {
        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }
}
