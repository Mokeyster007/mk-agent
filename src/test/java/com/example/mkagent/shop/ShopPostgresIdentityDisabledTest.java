package com.example.mkagent.shop;

import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * E 场景的后半段：**关闭演示身份**时，受保护入口不得因为带着 {@code X-User-Id} 就放行。
 *
 * <h2>为什么单独一个类</h2>
 * {@code mkagent.shop.demo-identity-enabled=false} 是上下文级属性，
 * 改它必须换一个 Spring 上下文；同一个类里改不了。
 *
 * <h2>断言的重点</h2>
 * 不是"没有身份时返回 403"这种废话，而是：
 * <ul>
 *   <li>带着一个**真实存在于 shop_user 的 BUYER/ADMIN** 的 {@code X-User-Id}，
 *       读私有订单 / 读后台列表 / 发货仍然被拒——即身份占位符本身不是授权；</li>
 *   <li>拒绝码是 {@code SHOP_IDENTITY_NOT_CONFIGURED}（配置层拒绝），
 *       而不是 {@code NOT_ADMIN}（会误导人去查角色表）；</li>
 *   <li>被拒的写操作没有任何副作用：订单没建、库存没动、流水没增。</li>
 * </ul>
 */
@Tag("pg")
@HermeticSpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mkagent.shop.demo-identity-enabled=false")
@Timeout(120)
class ShopPostgresIdentityDisabledTest extends AbstractPostgresShopTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    private static final String ADMIN = ShopSeedInitializer.ADMIN;

    private static final String SKU = ShopSeedInitializer.SKU_KEYBOARD;

    /** 夹具订单号：直接插库，用来验证"私有订单读不到"。 */
    private static final String EXISTING_ORDER = "SO-PG-FIXTURE-0001";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private ShopApiClient api;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName(),
                    "本轮是真实 PostgreSQL 回归，拒绝在别的数据库上出结论");
        }

        api = new ShopApiClient(port);
        ShopTestFixture.reset(jdbc);
        insertExistingOrder();
    }

    @Test
    @DisplayName("关闭演示身份：session 报 DISABLED，能力位全 false（角色仍如实返回）")
    void sessionReportsDisabled() {
        ShopApiClient.Reply session = api.get("/shop/session", BUYER);

        assertEquals(200, session.status(), session.raw());
        assertEquals("DISABLED", session.text("identityMode"));

        /*
         * 角色仍然如实返回 BUYER：它回答的是"你是谁"，
         * 不是"你能做什么"。后者由下面两个能力位回答，都为 false。
         * 前端只按能力位渲染入口，所以这里不会导致任何操作被放行；
         * 把它改成 UNKNOWN 反而会让排查"为什么不能下单"时少一条线索。
         */
        assertEquals("BUYER", session.text("role"),
                "session 只报告身份事实，不代表该身份此刻可用");

        assertFalse(session.bool("canOrder"), "关闭演示身份后不得允许下单");
        assertFalse(session.bool("canOperate"), "关闭演示身份后不得允许后台操作");
    }

    @Test
    @DisplayName("关闭演示身份：带 BUYER 的 X-User-Id 也读不到自己的私有订单")
    void privateOrderRejectedEvenWithHeader() {
        ShopApiClient.Reply detail = api.get("/shop/orders/" + EXISTING_ORDER, BUYER);
        assertEquals(403, detail.status(), detail.raw());
        assertEquals("SHOP_IDENTITY_NOT_CONFIGURED", detail.text("code"));

        ShopApiClient.Reply list = api.get("/shop/orders", BUYER);
        assertEquals(403, list.status(), list.raw());
        assertEquals("SHOP_IDENTITY_NOT_CONFIGURED", list.text("code"));
    }

    @Test
    @DisplayName("关闭演示身份：带 ADMIN 的 X-User-Id 也读不到后台列表")
    void adminListRejectedEvenWithAdminHeader() {
        ShopApiClient.Reply orders = api.get("/shop/admin/orders", ADMIN);
        assertEquals(403, orders.status(), orders.raw());
        assertEquals("SHOP_IDENTITY_NOT_CONFIGURED", orders.text("code"));

        ShopApiClient.Reply inventory = api.get("/shop/admin/inventory", ADMIN);
        assertEquals(403, inventory.status(), inventory.raw());
        assertEquals("SHOP_IDENTITY_NOT_CONFIGURED", inventory.text("code"));
    }

    @Test
    @DisplayName("关闭演示身份：受保护写操作被拒且没有任何副作用")
    void protectedWritesRejectedWithoutSideEffects() {
        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);
        int movementsBefore = ShopTestFixture.totalMovements(jdbc);

        ShopApiClient.Reply place = api.post("/shop/orders", BUYER,
                Map.of("productId", ShopSeedInitializer.PRODUCT_KEYBOARD, "quantity", 2));
        assertEquals(403, place.status(), place.raw());
        assertEquals("SHOP_IDENTITY_NOT_CONFIGURED", place.text("code"));

        ShopApiClient.Reply ship = api.post(
                "/shop/admin/orders/" + EXISTING_ORDER + "/ship", ADMIN, null);
        assertEquals(403, ship.status(), ship.raw());
        assertEquals("SHOP_IDENTITY_NOT_CONFIGURED", ship.text("code"));

        ShopApiClient.Reply sign = api.post(
                "/shop/admin/orders/" + EXISTING_ORDER + "/sign", ADMIN, null);
        assertEquals(403, sign.status(), sign.raw());

        assertEquals(1, totalOrderCount(), "被拒的下单不得创建订单（库里只应有夹具那一单）");
        assertEquals("PENDING_SHIPMENT", ShopTestFixture.statusOf(jdbc, EXISTING_ORDER));
        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbc, SKU), "被拒的发货不得动库存");
        assertEquals(movementsBefore, ShopTestFixture.totalMovements(jdbc), "不得写流水");
    }

    @Test
    @DisplayName("关闭演示身份不影响公开浏览：商品列表仍然 200")
    void publicCatalogStillWorks() {
        ShopApiClient.Reply products = api.get("/shop/products", null);

        assertEquals(200, products.status(), products.raw());
        assertEquals(3, products.body().size());
        assertNotNull(products.body().get(0).get("sku"));
    }

    // ==================== 辅助 ====================

    /** 直接插一条待发货订单（含明细），作为"别人的私有订单"的靶子。 */
    private void insertExistingOrder() {
        jdbc.update("""
                INSERT INTO shop_order
                    (order_no, buyer_id, status, total_cents, payment_mode)
                VALUES (?, ?, 'PENDING_SHIPMENT', ?, 'DEMO')
                """, EXISTING_ORDER, BUYER, 29900L);

        Long orderId = jdbc.queryForObject(
                "SELECT id FROM shop_order WHERE order_no = ?", Long.class, EXISTING_ORDER);
        assertNotNull(orderId);

        jdbc.update("""
                INSERT INTO shop_order_item
                    (order_id, product_id, sku, product_name, unit_price_cents, quantity, line_cents)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, orderId, ShopSeedInitializer.PRODUCT_KEYBOARD, SKU,
                "机械键盘 K87", 29900L, 1, 29900L);
    }

    private int totalOrderCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM shop_order", Integer.class);
        return count == null ? 0 : count;
    }
}
