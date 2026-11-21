package com.example.mkagent.shop;

import com.example.mkagent.support.HermeticSpringBootTest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 演示身份关闭时的行为（共享部署的默认形态）。
 *
 * <p>验收要求："禁用 demo 配置且无可信身份时，不开放受保护写操作。"
 * 这条必须是真的——只写在文档里不算。
 *
 * <p>注意 {@code properties} 覆盖了 application-test.yml 里的
 * {@code demo-identity-enabled: true}：Spring 的 inlined properties 优先级更高。
 */
@HermeticSpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mkagent.shop.demo-identity-enabled=false")
@Timeout(120)
class ShopIdentityDisabledIntegrationTest {

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
    @DisplayName("商品浏览仍然可用（公开能力不受身份开关影响）")
    void productsRemainPublic() {
        ShopApiClient.Reply reply = api.get("/shop/products", null);

        assertEquals(200, reply.status(), reply.raw());
        assertEquals(3, reply.body().size(), "三个种子商品都应返回");
    }

    @Test
    @DisplayName("会话视图如实报告 DISABLED，页面据此禁用下单")
    void sessionReportsDisabled() {
        ShopApiClient.Reply reply = api.get("/shop/session", ADMIN);

        assertEquals(200, reply.status(), reply.raw());
        assertEquals("DISABLED", reply.text("identityMode"));
        assertFalse(reply.bool("canOrder"), "身份不可信时不允许下单");
        assertFalse(reply.bool("canOperate"));
    }

    @Test
    @DisplayName("下单被拒绝：403 SHOP_IDENTITY_NOT_CONFIGURED，且不写订单")
    void orderingRejected() {
        ShopApiClient.Reply reply = api.post("/shop/orders", ADMIN,
                Map.of("productId", ShopSeedInitializer.PRODUCT_KEYBOARD, "quantity", 1));

        assertEquals(403, reply.status(), reply.raw());
        assertEquals("SHOP_IDENTITY_NOT_CONFIGURED", reply.text("code"));
        assertTrue(reply.text("message").contains("demo profile"), reply.raw());

        Integer orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_order", Integer.class);
        assertEquals(0, orders);
    }

    @Test
    @DisplayName("读自己的订单也被拒绝：身份不可信时\"自己的\"没有定义")
    void readingOwnOrdersRejected() {
        assertEquals(403, api.get("/shop/orders", ADMIN).status());
        assertEquals(403, api.get("/shop/orders/SO-ANY", ADMIN).status());
    }

    @Test
    @DisplayName("后台发货被拒绝，且原因是身份未启用而不是权限不足")
    void shippingRejected() {
        ShopApiClient.Reply reply = api.post(
                "/shop/admin/orders/SO-ANY/ship", ADMIN, null);

        assertEquals(403, reply.status(), reply.raw());
        assertEquals("SHOP_IDENTITY_NOT_CONFIGURED", reply.text("code"),
                "应先把\"身份不可用\"说清楚，而不是含糊地说没权限");
    }

    @Test
    @DisplayName("后台库存核对也被拒绝")
    void inventoryRejected() {
        assertEquals(403, api.get("/shop/admin/inventory", ADMIN).status());
    }
}
