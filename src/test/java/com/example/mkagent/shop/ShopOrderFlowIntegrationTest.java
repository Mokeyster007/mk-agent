package com.example.mkagent.shop;

import com.example.mkagent.support.HermeticSpringBootTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A0 主流程与状态机（真实 HTTP + 真实事务）。
 *
 * <p>覆盖验收里的这一段：
 * <blockquote>
 * 种子键盘库存 10 件，买家在浏览器购买 2 件。下单后库存仍为 10；
 * 后台发货后为 8，只产生一笔数量为 -2 的出库流水；签收后仍为 8。
 * </blockquote>
 *
 * <p>这里用 HTTP 而不是直接调 Service：接口层的权限、错误码、响应结构
 * 也是交付物的一部分，绕过去测等于少测一半。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
class ShopOrderFlowIntegrationTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    private static final String ADMIN = ShopSeedInitializer.ADMIN;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ShopApiClient api;

    @BeforeEach
    void setUp() {
        api = new ShopApiClient(port);
        ShopTestFixture.reset(jdbcTemplate);
    }

    @Test
    @DisplayName("主流程：下单不扣库存 → 发货扣 2 并写一笔流水 → 签收不改库存")
    void mainFlowFromPendingToSigned() {
        // 1) 商品与初始库存
        ShopApiClient.Reply products = api.get("/shop/products", BUYER);
        assertEquals(200, products.status(), products.raw());
        JsonNode keyboard = product(products.body(), ShopSeedInitializer.PRODUCT_KEYBOARD);
        assertEquals(10, keyboard.get("available").asInt());
        assertEquals(29900L, keyboard.get("priceCents").asLong());
        assertTrue(keyboard.get("purchasable").asBoolean());

        // 2) 下单 2 件
        ShopApiClient.Reply placed = api.post("/shop/orders", BUYER,
                Map.of("productId", ShopSeedInitializer.PRODUCT_KEYBOARD, "quantity", 2));
        assertEquals(201, placed.status(), placed.raw());

        String orderNo = placed.text("orderNo");
        assertNotNull(orderNo);
        assertEquals("PENDING_SHIPMENT", placed.text("status"));
        assertEquals("待发货", placed.text("statusLabel"));
        assertEquals("DEMO", placed.text("paymentMode"));
        assertEquals(59800L, placed.longNumber("totalCents"));
        assertEquals("¥598.00", placed.text("totalText"));
        assertEquals(2, placed.body().get("item").get("quantity").asInt());
        assertEquals(29900L, placed.body().get("item").get("unitPriceCents").asLong());
        assertEquals(ShopSeedInitializer.SKU_KEYBOARD,
                placed.body().get("item").get("sku").asText());

        // 3) 下单不预占、不扣库存：库存仍是 10，也没有任何流水
        assertEquals(10, ShopTestFixture.stockOf(jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD));
        assertEquals(0, ShopTestFixture.movementCount(jdbcTemplate, orderNo));

        // 4) 发货：扣到 8，写一笔 -2
        ShopApiClient.Reply shipped = api.post(
                "/shop/admin/orders/" + orderNo + "/ship", ADMIN, null);
        assertEquals(200, shipped.status(), shipped.raw());
        assertTrue(shipped.bool("applied"));
        assertEquals(8, ShopTestFixture.stockOf(jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD));
        assertEquals(1, ShopTestFixture.movementCount(jdbcTemplate, orderNo));

        // 5) 流水明细：类型、数量、来源订单行
        ShopApiClient.Reply adminOrder = api.get("/shop/admin/orders/" + orderNo, ADMIN);
        assertEquals(200, adminOrder.status(), adminOrder.raw());
        JsonNode movements = adminOrder.body().get("movements");
        assertEquals(1, movements.size(), adminOrder.raw());
        assertEquals(-2, movements.get(0).get("quantityDelta").asInt());
        assertEquals("OUTBOUND_SHIPMENT", movements.get(0).get("type").asText());
        assertEquals(ShopSeedInitializer.SKU_KEYBOARD, movements.get(0).get("sku").asText());
        assertFalse(movements.get(0).get("orderItemId").isNull());
        assertEquals(ADMIN, movements.get(0).get("operatorId").asText());

        // 6) 签收：只记时间，库存仍是 8
        ShopApiClient.Reply signed = api.post(
                "/shop/admin/orders/" + orderNo + "/sign", ADMIN, null);
        assertEquals(200, signed.status(), signed.raw());
        assertTrue(signed.bool("applied"));
        assertEquals(8, ShopTestFixture.stockOf(jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD));
        assertEquals(1, ShopTestFixture.movementCount(jdbcTemplate, orderNo));

        // 7) 买家视角：状态、签收时间、出库记录都能读到（刷新后仍一致）
        ShopApiClient.Reply buyerView = api.get("/shop/orders/" + orderNo, BUYER);
        assertEquals(200, buyerView.status(), buyerView.raw());
        assertEquals("SIGNED", buyerView.text("status"));
        assertEquals("已签收", buyerView.text("statusLabel"));
        assertFalse(buyerView.body().get("signedAt").isNull());
        assertEquals(ADMIN, buyerView.text("signedBy"));
        assertEquals(1, buyerView.body().get("movements").size());

        // 8) 我的订单列表里也能看到这一单
        ShopApiClient.Reply list = api.get("/shop/orders", BUYER);
        assertEquals(200, list.status(), list.raw());
        assertEquals(1, list.body().size());
        assertEquals(orderNo, list.body().get(0).get("orderNo").asText());
    }

    @Test
    @DisplayName("订单归属与价格由服务端决定：请求体里的 buyerId / price 不生效")
    void buyerAndPriceComeFromServer() {
        ShopApiClient.Reply placed = api.post("/shop/orders", BUYER, Map.of(
                "productId", ShopSeedInitializer.PRODUCT_KEYBOARD,
                "quantity", 1,
                // 下面两个字段在请求 DTO 里根本不存在，传了也不会被绑定
                "buyerId", ShopSeedInitializer.BUYER_TWO,
                "unitPriceCents", 1,
                "totalCents", 1));

        assertEquals(201, placed.status(), placed.raw());
        assertEquals(BUYER, placed.text("buyerId"));
        assertEquals(29900L, placed.longNumber("totalCents"));
        assertEquals(BUYER, ShopTestFixture.buyerOf(jdbcTemplate, placed.text("orderNo")));
    }

    @Test
    @DisplayName("重复发货（顺序重试）：只扣一次、只写一笔流水")
    void repeatedShipmentDeductsOnce() {
        String orderNo = placeOrder(2);

        ShopApiClient.Reply first = api.post("/shop/admin/orders/" + orderNo + "/ship", ADMIN, null);
        assertTrue(first.bool("applied"));

        ShopApiClient.Reply second = api.post("/shop/admin/orders/" + orderNo + "/ship", ADMIN, null);
        assertEquals(200, second.status(), second.raw());
        assertFalse(second.bool("applied"), "第二次发货不应再产生副作用");
        assertTrue(second.text("message").contains("已发货"));

        assertEquals(8, ShopTestFixture.stockOf(jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD));
        assertEquals(1, ShopTestFixture.movementCount(jdbcTemplate, orderNo));
    }

    @Test
    @DisplayName("库存不足：发货失败，订单仍待发货，库存与流水均不变")
    void insufficientStockKeepsOrderPending() {
        // 数据线种子库存为 0
        ShopApiClient.Reply placed = api.post("/shop/orders", BUYER,
                Map.of("productId", ShopSeedInitializer.PRODUCT_CABLE, "quantity", 1));
        assertEquals(201, placed.status(), placed.raw());
        String orderNo = placed.text("orderNo");

        ShopApiClient.Reply shipped = api.post(
                "/shop/admin/orders/" + orderNo + "/ship", ADMIN, null);
        assertEquals(409, shipped.status(), shipped.raw());
        assertEquals("INSUFFICIENT_STOCK", shipped.text("code"));
        assertTrue(shipped.text("message").contains("库存不足"), shipped.raw());

        assertEquals("PENDING_SHIPMENT", ShopTestFixture.statusOf(jdbcTemplate, orderNo));
        assertEquals(0, ShopTestFixture.stockOf(jdbcTemplate, ShopSeedInitializer.SKU_CABLE));
        assertEquals(0, ShopTestFixture.movementCount(jdbcTemplate, orderNo));
        assertEquals(0, ShopTestFixture.totalMovements(jdbcTemplate));
    }

    @Test
    @DisplayName("未发货不能签收")
    void cannotSignBeforeShipment() {
        String orderNo = placeOrder(1);

        ShopApiClient.Reply signed = api.post(
                "/shop/admin/orders/" + orderNo + "/sign", ADMIN, null);
        assertEquals(409, signed.status(), signed.raw());
        assertEquals("ORDER_NOT_SHIPPED", signed.text("code"));
        assertEquals("PENDING_SHIPMENT", ShopTestFixture.statusOf(jdbcTemplate, orderNo));
    }

    @Test
    @DisplayName("重复签收：不重复记录时间")
    void repeatedSignIsIdempotent() {
        String orderNo = placeOrder(1);
        api.post("/shop/admin/orders/" + orderNo + "/ship", ADMIN, null);

        ShopApiClient.Reply first = api.post("/shop/admin/orders/" + orderNo + "/sign", ADMIN, null);
        assertTrue(first.bool("applied"));
        String signedAt = first.body().get("order").get("signedAt").asText();

        ShopApiClient.Reply second = api.post("/shop/admin/orders/" + orderNo + "/sign", ADMIN, null);
        assertFalse(second.bool("applied"));
        assertEquals(signedAt, second.body().get("order").get("signedAt").asText());
    }

    @Test
    @DisplayName("买家只能读自己的订单：别人的订单号返回 404")
    void buyerCannotReadOthersOrder() {
        String orderNo = placeOrder(1);

        ShopApiClient.Reply other = api.get("/shop/orders/" + orderNo, ShopSeedInitializer.BUYER_TWO);
        assertEquals(404, other.status(), other.raw());
        assertEquals("ORDER_NOT_FOUND", other.text("code"));

        ShopApiClient.Reply otherList = api.get("/shop/orders", ShopSeedInitializer.BUYER_TWO);
        assertEquals(200, otherList.status());
        assertEquals(0, otherList.body().size(), "别人的订单不应出现在我的列表里");
    }

    @Test
    @DisplayName("普通买家不能走后台入口")
    void buyerCannotUseAdminEndpoints() {
        String orderNo = placeOrder(1);

        assertEquals(403, api.get("/shop/admin/orders", BUYER).status());
        assertEquals(403, api.get("/shop/admin/inventory", BUYER).status());
        assertEquals(403, api.post("/shop/admin/orders/" + orderNo + "/ship", BUYER, null).status());
        assertEquals(403, api.post("/shop/admin/orders/" + orderNo + "/sign", BUYER, null).status());

        assertEquals("PENDING_SHIPMENT", ShopTestFixture.statusOf(jdbcTemplate, orderNo));
    }

    @Test
    @DisplayName("缺身份：401，且不写任何订单")
    void missingIdentityRejected() {
        ShopApiClient.Reply reply = api.post("/shop/orders", null,
                Map.of("productId", ShopSeedInitializer.PRODUCT_KEYBOARD, "quantity", 1));

        assertEquals(401, reply.status(), reply.raw());

        Integer orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_order", Integer.class);
        assertEquals(0, orders);
    }

    @Test
    @DisplayName("未开通商城的身份：403，不落到买家")
    void unprovisionedIdentityRejected() {
        ShopApiClient.Reply reply = api.post("/shop/orders", "someone-else",
                Map.of("productId", ShopSeedInitializer.PRODUCT_KEYBOARD, "quantity", 1));

        assertEquals(403, reply.status(), reply.raw());
        assertEquals("SHOP_USER_NOT_PROVISIONED", reply.text("code"));
    }

    @Test
    @DisplayName("非法数量与未知商品：400 / 404，且不写订单")
    void invalidOrderRequestsRejected() {
        assertEquals(400, api.post("/shop/orders", BUYER,
                Map.of("productId", ShopSeedInitializer.PRODUCT_KEYBOARD, "quantity", 0)).status());
        assertEquals(400, api.post("/shop/orders", BUYER,
                Map.of("productId", ShopSeedInitializer.PRODUCT_KEYBOARD, "quantity", 1000)).status());
        assertEquals(400, api.post("/shop/orders", BUYER, Map.of("quantity", 1)).status());
        assertEquals(404, api.post("/shop/orders", BUYER,
                Map.of("productId", 999999, "quantity", 1)).status());

        Integer orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_order", Integer.class);
        assertEquals(0, orders);
    }

    @Test
    @DisplayName("订单详情里没有流水时返回空数组，列表接口不返回流水字段内容")
    void detailAndListShape() {
        String orderNo = placeOrder(1);

        ShopApiClient.Reply detail = api.get("/shop/orders/" + orderNo, BUYER);
        assertEquals(200, detail.status());
        assertEquals(0, detail.body().get("movements").size(),
                "刚下单的订单不应有任何出库流水");

        ShopApiClient.Reply list = api.get("/shop/orders", BUYER);
        assertTrue(list.body().get(0).get("movements").isNull(),
                "列表接口不查流水，必须显式为 null 而不是空数组");
        assertTrue(list.body().get(0).get("canShip").asBoolean());
        assertFalse(list.body().get(0).get("canSign").asBoolean());
    }

    private String placeOrder(int quantity) {
        ShopApiClient.Reply placed = api.post("/shop/orders", BUYER,
                Map.of("productId", ShopSeedInitializer.PRODUCT_KEYBOARD, "quantity", quantity));
        assertEquals(201, placed.status(), placed.raw());
        String orderNo = placed.text("orderNo");
        assertNotNull(orderNo);
        return orderNo;
    }

    private static JsonNode product(JsonNode products, long id) {
        for (JsonNode node : products) {
            if (node.get("id").asLong() == id) {
                return node;
            }
        }
        throw new AssertionError("商品不存在：" + id);
    }
}
