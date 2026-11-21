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
 * 模型不可用时，商城闭环仍然可用。
 *
 * <p>做法：把一个"一调用就抛异常"的 {@link ChatModel} 设为 {@code @Primary}，
 * 覆盖容器里的真实模型。于是这个上下文里的模型**确实**不可用，
 * 而不是"假装不可用"。
 *
 * <p>如果商城链路上任何一处偷偷调用了模型（比如为了生成订单摘要、
 * 为了给商品写文案），这里会直接失败——这正是本用例要保证的事。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
class ShopModelIndependenceTest {

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
    @DisplayName("模型一调用就抛异常，商城仍能下单、发货、签收")
    void shopLoopWorksWithoutModel() {
        // 先证明这个上下文里的模型确实是坏的
        assertThrows(IllegalStateException.class,
                () -> chatModel.call(new Prompt("ping")),
                "测试桩应当拒绝任何模型调用");

        // 商品列表
        ShopApiClient.Reply products = api.get("/shop/products", BUYER);
        assertEquals(200, products.status(), products.raw());

        // 下单
        ShopApiClient.Reply placed = api.post("/shop/orders", BUYER,
                Map.of("productId", ShopSeedInitializer.PRODUCT_KEYBOARD, "quantity", 2));
        assertEquals(201, placed.status(), placed.raw());
        String orderNo = placed.text("orderNo");

        // 发货
        ShopApiClient.Reply shipped = api.post(
                "/shop/admin/orders/" + orderNo + "/ship", ADMIN, null);
        assertEquals(200, shipped.status(), shipped.raw());
        assertTrue(shipped.bool("applied"));

        // 签收
        ShopApiClient.Reply signed = api.post(
                "/shop/admin/orders/" + orderNo + "/sign", ADMIN, null);
        assertEquals(200, signed.status(), signed.raw());
        assertTrue(signed.bool("applied"));

        assertEquals(8, ShopTestFixture.stockOf(jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD));
        assertEquals(1, ShopTestFixture.movementCount(jdbcTemplate, orderNo));
    }
}
