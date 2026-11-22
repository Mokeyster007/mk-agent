package com.example.mkagent.shop;

import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型不可用时，售后闭环（表单 + 我的售后）仍然可用。
 *
 * <p>做法与 {@code ShopModelIndependenceTest} 一致：把一个"一调用就抛异常"的
 * {@link ChatModel} 设为 {@code @Primary}，覆盖容器里的真实模型。
 * 于是这个上下文里的模型**确实**不可用，而不是"假装不可用"。
 *
 * <p>这条要求来自本轮范围：普通退货表单与 Agent 是两条并行入口，
 * 表单不依赖模型。如果哪天有人为了"生成退货摘要"在服务里加一次模型调用，
 * 这个用例会立刻失败。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
class AfterSaleModelIndependenceTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    private static final String ADMIN = ShopSeedInitializer.ADMIN;

    @TestConfiguration
    static class UnavailableModelConfig {

        @Bean
        @Primary
        ChatModel unavailableChatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    throw new IllegalStateException("模型不可用（测试桩）");
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    throw new IllegalStateException("模型不可用（测试桩）");
                }
            };
        }
    }

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
    @DisplayName("模型一调用就抛异常，退货表单仍能提交、查询、看详情")
    void afterSaleFormWorksWithoutModel() {
        // 先证明这个上下文里的模型确实是坏的
        assertThrows(IllegalStateException.class,
                () -> chatModel.call(new Prompt("ping")),
                "测试桩应当拒绝任何模型调用");

        // 主路径：下单 → 发货 → 签收（A0 链路，同样不依赖模型）
        String orderNo = placeShipAndSign(2);

        // 可退订单与额度三个数
        ShopApiClient.Reply orders = api.get("/shop/aftersale/orders", BUYER);
        assertEquals(200, orders.status(), orders.raw());
        assertEquals(1, orders.body().size());
        assertEquals(2, orders.body().get(0).get("quota").get("purchased").asInt());
        assertEquals(0, orders.body().get(0).get("quota").get("occupied").asInt());
        assertEquals(2, orders.body().get(0).get("quota").get("remaining").asInt());

        // 提交
        ShopApiClient.Reply created = api.post("/shop/aftersale/returns", BUYER,
                Map.of("orderNo", orderNo,
                        "quantity", 1,
                        "reason", "键盘按键失灵",
                        "clientRequestId", "req-model-off-0001"));
        assertEquals(201, created.status(), created.raw());

        String returnNo = created.text("returnNo");
        assertTrue(returnNo.matches("RT\\d{14}-[0-9A-F]{4}"),
                "退货单号必须来自数据库：" + returnNo);
        assertEquals("SUBMITTED", created.text("status"));
        assertEquals("已提交", created.text("statusLabel"));
        assertEquals("FORM", created.text("source"));
        assertEquals(1, created.body().get("events").size(), "详情应带一条处理记录");

        // 查询：列表与详情
        ShopApiClient.Reply list = api.get("/shop/aftersale/returns", BUYER);
        assertEquals(200, list.status(), list.raw());
        assertEquals(1, list.body().size());
        assertEquals(returnNo, list.body().get(0).get("returnNo").asText());

        ShopApiClient.Reply detail = api.get("/shop/aftersale/returns/" + returnNo, BUYER);
        assertEquals(200, detail.status(), detail.raw());
        assertEquals(returnNo, detail.text("returnNo"));

        // 额度：已占用 1、剩余 1
        ShopApiClient.Reply after = api.get("/shop/aftersale/orders", BUYER);
        assertEquals(1, after.body().get(0).get("quota").get("occupied").asInt());
        assertEquals(1, after.body().get(0).get("quota").get("remaining").asInt());

        // A1 不动库存、不写流水
        assertEquals(8, ShopTestFixture.stockOf(jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD));
        assertEquals(1, ShopTestFixture.movementCount(jdbcTemplate, orderNo),
                "只有签收时那一条出库流水，退货不得新增流水");
    }

    private String placeShipAndSign(int quantity) {
        ShopApiClient.Reply placed = api.post("/shop/orders", BUYER,
                Map.of("productId", ShopSeedInitializer.PRODUCT_KEYBOARD, "quantity", quantity));
        assertEquals(201, placed.status(), placed.raw());
        String orderNo = placed.text("orderNo");

        assertEquals(200, api.post("/shop/admin/orders/" + orderNo + "/ship", ADMIN, null).status());
        assertEquals(200, api.post("/shop/admin/orders/" + orderNo + "/sign", ADMIN, null).status());

        return orderNo;
    }
}
