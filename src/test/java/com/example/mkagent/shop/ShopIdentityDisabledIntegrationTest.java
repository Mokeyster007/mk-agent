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

    @Test
    @DisplayName("售后入口一律拒绝：表单、列表、详情、Agent 对话都不放行")
    void afterSaleEntriesRejected() {
        // 普通表单与查询
        assertEquals(403, api.get("/shop/aftersale/orders", ADMIN).status());
        assertEquals(403, api.get("/shop/aftersale/returns", ADMIN).status());
        assertEquals(403, api.get("/shop/aftersale/returns/RT-ANY", ADMIN).status());
        assertEquals(403, api.post("/shop/aftersale/preview", ADMIN,
                Map.of("orderNo", "SO-ANY", "quantity", 1, "reason", "测试")).status());
        assertEquals(403, api.post("/shop/aftersale/returns", ADMIN,
                Map.of("orderNo", "SO-ANY", "quantity", 1, "reason", "测试",
                        "clientRequestId", "req-disabled-0001")).status());

        // Agent 对话入口：新增的私有读写入口不得因为"只是聊天"就放行
        ShopApiClient.Reply chat = api.get("/ai/aftersale/chat?message=hi", ADMIN);
        assertEquals(403, chat.status(), chat.raw());
        assertEquals("SHOP_IDENTITY_NOT_CONFIGURED", chat.text("code"));

        /*
         * 售后会话入口（A1 收尾新增）：它是"纯商城买家也能建会话"的落点，
         * 因此更必须在身份不可信时先拒绝——否则就成了绕过守卫的旁路。
         */
        ShopApiClient.Reply conversations = api.get("/shop/aftersale/conversations", ADMIN);
        assertEquals(403, conversations.status(), conversations.raw());
        assertEquals("SHOP_IDENTITY_NOT_CONFIGURED", conversations.text("code"));

        assertEquals(403, api.post("/shop/aftersale/conversations", ADMIN, null).status());
        assertEquals(403, api.get("/shop/aftersale/conversations/any-id", ADMIN).status());

        Integer conversationsWritten = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_conversation WHERE agent_type = 'AFTER_SALES'",
                Integer.class);
        assertEquals(0, conversationsWritten, "被拒绝的请求不得留下售后会话");

        assertEquals(0, ShopTestFixture.totalReturns(jdbcTemplate), "拒绝的请求不得建单");
        Integer runs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run WHERE agent_type = 'AFTER_SALES'",
                Integer.class);
        assertEquals(0, runs, "身份被拒绝时不得启动售后 Agent");
    }

    /**
     * 客服售后接口（B1）：关闭 demo 且没有可信身份时，后台私有读写一律不放行。
     *
     * <p>这几个入口能改状态、能释放额度，比"读"更危险：
     * 身份不可信时放行它们，等于任何人都能把别人的退货申请批掉。
     * 断言的是 {@code SHOP_IDENTITY_NOT_CONFIGURED}（身份不可用）
     * 而不是 {@code NOT_ADMIN}（权限不足）——先解释"为什么不行"。
     */
    @Test
    @DisplayName("客服售后接口一律拒绝：列表、详情、批准、驳回、收货、模拟退款都不放行")
    void afterSaleAdminEntriesRejected() {
        ShopApiClient.Reply list = api.get("/shop/admin/aftersale/returns", ADMIN);
        assertEquals(403, list.status(), list.raw());
        assertEquals("SHOP_IDENTITY_NOT_CONFIGURED", list.text("code"));

        assertEquals(403, api.get("/shop/admin/aftersale/returns/RT-ANY", ADMIN).status());

        ShopApiClient.Reply approve = api.post(
                "/shop/admin/aftersale/returns/RT-ANY/approve", ADMIN, null);
        assertEquals(403, approve.status(), approve.raw());
        assertEquals("SHOP_IDENTITY_NOT_CONFIGURED", approve.text("code"));

        ShopApiClient.Reply reject = api.post(
                "/shop/admin/aftersale/returns/RT-ANY/reject", ADMIN,
                Map.of("reason", "尝试驳回"));
        assertEquals(403, reject.status(), reject.raw());
        assertEquals("SHOP_IDENTITY_NOT_CONFIGURED", reject.text("code"));

        // B2：收货会改可售库存，身份不可信时同样一律拒绝
        ShopApiClient.Reply receive = api.post(
                "/shop/admin/aftersale/returns/RT-ANY/receipts", ADMIN,
                Map.of("receivedQty", 1, "qualifiedQty", 1, "damagedQty", 0,
                        "clientRequestId", "rc-disabled-0001"));
        assertEquals(403, receive.status(), receive.raw());
        assertEquals("SHOP_IDENTITY_NOT_CONFIGURED", receive.text("code"));

        /*
         * B3：模拟退款虽然是"模拟"，但它会落一条不可重复的退款记录（一张退货单最多一笔）。
         * 身份不可信时放行，等于谁都能把别人的退货单标成"已退款"，
         * 而且这个标记一旦落下就没有第二笔来纠正——所以同样必须在这里先拒绝。
         */
        ShopApiClient.Reply refund = api.post(
                "/shop/admin/aftersale/returns/RT-ANY/refund", ADMIN, null);
        assertEquals(403, refund.status(), refund.raw());
        assertEquals("SHOP_IDENTITY_NOT_CONFIGURED", refund.text("code"));

        Integer decisions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_aftersale_decision", Integer.class);
        assertEquals(0, decisions, "被拒绝的请求不得留下决策记录");

        Integer receipts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_aftersale_receipt", Integer.class);
        assertEquals(0, receipts, "被拒绝的请求不得留下收货记录");

        Integer refunds = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_aftersale_refund", Integer.class);
        assertEquals(0, refunds, "被拒绝的请求不得留下退款记录");
    }
}
