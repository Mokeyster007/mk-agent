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
 * 商城权限：身份、角色、能力自报。
 *
 * <p>三条要证明的事：
 * <ol>
 *   <li>角色只来自 {@code shop_user} 表，不来自请求；</li>
 *   <li>买家能下单但不能发货；管理员两边都能用（管理员也是人，也会买东西）；</li>
 *   <li>页面在点击之前就能知道"能不能下单"，不用等一个 403 才知道。</li>
 * </ol>
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
class ShopAccessControlIntegrationTest {

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
    @DisplayName("会话视图：买家能下单、不能进后台")
    void buyerSession() {
        ShopApiClient.Reply reply = api.get("/shop/session", BUYER);

        assertEquals(200, reply.status(), reply.raw());
        assertEquals(BUYER, reply.text("userId"));
        assertEquals("BUYER", reply.text("role"));
        assertEquals("DEMO", reply.text("identityMode"));
        assertTrue(reply.bool("canOrder"));
        assertFalse(reply.bool("canOperate"));
    }

    @Test
    @DisplayName("会话视图：管理员既能下单也能进后台")
    void adminSession() {
        ShopApiClient.Reply reply = api.get("/shop/session", ADMIN);

        assertEquals("ADMIN", reply.text("role"));
        assertTrue(reply.bool("canOrder"));
        assertTrue(reply.bool("canOperate"));
    }

    @Test
    @DisplayName("会话视图：没有身份时不报错，但什么都不允许")
    void anonymousSession() {
        ShopApiClient.Reply reply = api.get("/shop/session", null);

        assertEquals(200, reply.status(), reply.raw());
        assertEquals("UNKNOWN", reply.text("role"));
        assertFalse(reply.bool("canOrder"));
        assertFalse(reply.bool("canOperate"));
    }

    @Test
    @DisplayName("管理员也可以作为买家下单")
    void adminCanAlsoBuy() {
        ShopApiClient.Reply placed = api.post("/shop/orders", ADMIN,
                Map.of("productId", ShopSeedInitializer.PRODUCT_KEYBOARD, "quantity", 1));

        assertEquals(201, placed.status(), placed.raw());
        assertEquals(ADMIN, placed.text("buyerId"));
    }

    @Test
    @DisplayName("角色不能靠请求头自报：买家声明自己是 ADMIN 也没用")
    void roleCannotBeSelfDeclared() {
        ShopApiClient.Reply reply = api.get("/shop/admin/orders", BUYER);

        /*
         * 请求里根本没有"角色"这个字段可传；这里再确认一遍：
         * 即使调用方在其它自定义头里写 ADMIN，也不会被读取。
         * 断言点落在"结果仍是 403"上。
         */
        assertEquals(403, reply.status(), reply.raw());
        assertEquals("NOT_ADMIN", reply.text("code"));
    }

    @Test
    @DisplayName("未开通商城的身份访问后台：403，且不暴露该用户是否存在")
    void unknownIdentityOnAdmin() {
        ShopApiClient.Reply reply = api.get("/shop/admin/orders", "nobody-provisioned");

        assertEquals(403, reply.status(), reply.raw());
        assertEquals("NOT_ADMIN", reply.text("code"));
    }

    @Test
    @DisplayName("售后 Agent 入口：缺身份 401、未开通商城 403，且都不启动 Agent")
    void afterSaleChatRequiresBuyerIdentity() {
        ShopApiClient.Reply anonymous = api.get("/ai/aftersale/chat?message=hi", null);
        assertEquals(401, anonymous.status(), anonymous.raw());
        assertTrue(anonymous.text("message").contains("X-User-Id"), anonymous.raw());

        ShopApiClient.Reply unknown = api.get("/ai/aftersale/chat?message=hi", "nobody-provisioned");
        assertEquals(403, unknown.status(), unknown.raw());
        assertEquals("SHOP_USER_NOT_PROVISIONED", unknown.text("code"));

        Integer runs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run WHERE agent_type = 'AFTER_SALES'",
                Integer.class);
        assertEquals(0, runs, "身份不通过时不得启动售后 Agent（也就不会有任何写操作）");
    }

    @Test
    @DisplayName("售后读写入口同样要求身份：缺头 401、未开通 403")
    void afterSaleEndpointsRequireBuyerIdentity() {
        assertEquals(401, api.get("/shop/aftersale/orders", null).status());
        assertEquals(401, api.get("/shop/aftersale/returns", null).status());
        assertEquals(401, api.post("/shop/aftersale/returns", null,
                Map.of("orderNo", "SO-ANY", "quantity", 1, "reason", "测试",
                        "clientRequestId", "req-anon-0001")).status());

        ShopApiClient.Reply unknown = api.get("/shop/aftersale/orders", "nobody-provisioned");
        assertEquals(403, unknown.status(), unknown.raw());
        assertEquals("SHOP_USER_NOT_PROVISIONED", unknown.text("code"));

        assertEquals(0, ShopTestFixture.totalReturns(jdbcTemplate));
    }
}
