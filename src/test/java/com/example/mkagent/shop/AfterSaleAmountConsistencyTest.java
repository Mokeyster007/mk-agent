package com.example.mkagent.shop;

import com.example.mkagent.shop.aftersale.AfterSaleEligibility;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预览与提交的**金额一致性**。
 *
 * <h2>要防的错误</h2>
 *
 * 预览原先用裸乘法 {@code unitPrice × qty}，溢出会静默回绕成负数：
 * 页面显示「预计退款 -¥21474836.48」，而提交那边用 {@code multiplyExact} 直接拒绝。
 * 同一笔申请，两个入口给出互相矛盾的结果。
 *
 * <h2>修法</h2>
 *
 * 两处共用 {@code AfterSaleMoney}（整数分 + {@code multiplyExact}）：
 * <ul>
 *   <li>正常金额：预览与提交算出**同一个数**；</li>
 *   <li>溢出：预览不下发金额（文案「—」）并标记不可提交，提交返回 400 且不建单。</li>
 * </ul>
 *
 * <p>本轮**没有真实退款**：这里校验的只是"金额怎么算"，钱不会动。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AfterSaleAmountConsistencyTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    private static final String ORDER_NO = "SO-AS-AMOUNT";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private ShopApiClient api;

    @BeforeEach
    void setUp() {
        ShopTestFixture.reset(jdbc);
        api = new ShopApiClient(port);
    }

    @Test
    @DisplayName("正常金额：预览与提交算出同一个数（29900 分 × 2）")
    void previewAndSubmitAgreeOnNormalAmount() {
        createOrder(29900L, 2);

        ShopApiClient.Reply preview = api.post("/shop/aftersale/preview", BUYER,
                new Body(ORDER_NO, 2, "两件都有问题"));
        assertEquals(200, preview.status(), preview.raw());
        assertTrue(preview.bool("eligible"), preview.raw());
        assertEquals(59800L, preview.longNumber("refundCents"), preview.raw());
        assertEquals("¥598.00", preview.text("refundText"));

        ShopApiClient.Reply submit = api.post("/shop/aftersale/returns", BUYER,
                new SubmitBody(ORDER_NO, 2, "两件都有问题", "req-amount-0001"));
        assertEquals(201, submit.status(), submit.raw());

        assertEquals(preview.longNumber("refundCents"), submit.longNumber("refundCents"),
                "预览与提交的金额必须一致");
        assertEquals(preview.text("refundText"), submit.text("refundText"));
    }

    @Test
    @DisplayName("金额溢出：预览不显示错误金额、提交不建单，两处同一个原因码")
    void overflowIsRejectedOnBothSides() {
        /*
         * 用 Long.MAX_VALUE 做单价：2 件必然溢出。
         * 直接改订单行，是为了绕开夹具里同样会溢出的 total_cents 计算——
         * 这里要测的是售后的金额逻辑，不是夹具。
         */
        createOrder(Long.MAX_VALUE, 2);

        ShopApiClient.Reply preview = api.post("/shop/aftersale/preview", BUYER,
                new Body(ORDER_NO, 2, "溢出测试"));
        assertEquals(200, preview.status(), preview.raw());
        assertFalse(preview.bool("eligible"), "溢出时必须标记为不可提交：" + preview.raw());
        assertEquals(AfterSaleEligibility.CODE_REFUND_AMOUNT_OUT_OF_RANGE,
                preview.text("code"), preview.raw());
        assertEquals(0L, preview.longNumber("refundCents"), "不得下发一个回绕后的数字");
        assertEquals("—", preview.text("refundText"), "不能显示 ¥0.00 或负数");

        ShopApiClient.Reply submit = api.post("/shop/aftersale/returns", BUYER,
                new SubmitBody(ORDER_NO, 2, "溢出测试", "req-amount-overflow"));
        assertEquals(400, submit.status(), submit.raw());
        assertEquals(AfterSaleEligibility.CODE_REFUND_AMOUNT_OUT_OF_RANGE,
                submit.text("code"), submit.raw());

        // 提交被拒 = 零写入：没有单、没占额度、没有幂等占位行
        assertEquals(0, ShopTestFixture.totalReturns(jdbc));
        assertEquals(0, ShopTestFixture.occupiedQty(jdbc, ORDER_NO));
        Integer idempotency = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shop_aftersale_idempotency", Integer.class);
        assertEquals(0, idempotency == null ? 0 : idempotency);

        /*
         * 边界对照：同样的单价，1 件刚好等于 Long.MAX_VALUE，**没有**溢出，
         * 所以它仍然是可提交的。这说明拒绝的是"乘完装不下"，不是"单价太大"。
         */
        ShopApiClient.Reply single = api.post("/shop/aftersale/preview", BUYER,
                new Body(ORDER_NO, 1, "溢出测试"));
        assertEquals(200, single.status(), single.raw());
        assertTrue(single.bool("eligible"), single.raw());
        assertEquals(Long.MAX_VALUE, single.longNumber("refundCents"));
    }

    @Test
    @DisplayName("单价为 0 的订单：金额 0 是可表示的合法值，不触发溢出码")
    void zeroPriceIsNotOverflow() {
        createOrder(0L, 2);

        ShopApiClient.Reply preview = api.post("/shop/aftersale/preview", BUYER,
                new Body(ORDER_NO, 1, "零元测试"));
        assertEquals(200, preview.status(), preview.raw());
        assertTrue(preview.bool("eligible"), preview.raw());
        assertEquals(0L, preview.longNumber("refundCents"));
        assertEquals("¥0.00", preview.text("refundText"));
    }

    // ---------------------------------------------------------------

    private void createOrder(long unitPriceCents, int quantity) {
        ShopTestFixture.createSignedOrder(
                jdbc, ORDER_NO, BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, quantity, null);

        // 夹具按 29900 算 total_cents，这里单独把订单行单价改成目标值
        jdbc.update("""
                UPDATE shop_order_item SET unit_price_cents = ?
                WHERE order_id = (SELECT id FROM shop_order WHERE order_no = ?)
                """, unitPriceCents, ORDER_NO);
    }

    /** 预览请求体。 */
    record Body(String orderNo, Integer quantity, String reason) {
    }

    /** 提交请求体。 */
    record SubmitBody(String orderNo, Integer quantity, String reason, String clientRequestId) {
    }
}
