package com.example.mkagent.shop;

import com.example.mkagent.support.HermeticSpringBootTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 售后业务闭环（普通表单路径，**不依赖模型**）。
 *
 * <p>覆盖的验收点：
 * <ul>
 *   <li>已签收订单可以按部分数量申请退货，退货单号来自真实服务结果；</li>
 *   <li>额度三个数（已购 / 已占用 / 剩余）正确，且申请不动库存、不写库存流水；</li>
 *   <li>未签收 / 超窗口 / 超额度 / 原因为空 / 数量非法都被拒，且不留下脏数据；</li>
 *   <li>买家只能读自己的申请（别人的返回 404）；</li>
 *   <li>同一提交标识重放返回原单号，不建第二单；同键不同参数明确拒绝。</li>
 * </ul>
 *
 * <p>订单用夹具直接造成"已签收"（{@link ShopTestFixture#createSignedOrder}），
 * 因为要精确控制签收时间与数量。**主验收路径仍走 A0 页面**（浏览器 E2E 覆盖）。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AfterSaleFlowIntegrationTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    private static final String OTHER_BUYER = ShopSeedInitializer.BUYER_TWO;

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

    // ---------------------------------------------------------------
    // 主流程
    // ---------------------------------------------------------------

    @Test
    @DisplayName("主流程：买 2 件退 1 件 → 单号真实、额度 1/2、库存与流水不变")
    void partialReturnOnSignedOrder() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-MAIN", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        int stockBefore = ShopTestFixture.stockOf(jdbc, ShopSeedInitializer.SKU_KEYBOARD);
        int movementsBefore = ShopTestFixture.totalMovements(jdbc);

        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 1, "键帽有划痕", "req-main-0001"));

        assertEquals(201, reply.status(), reply.raw());
        String returnNo = reply.text("returnNo");
        assertNotNull(returnNo, reply.raw());
        assertTrue(returnNo.matches("RT\\d{14}-[0-9A-F]{4}"), "退货单号格式：" + returnNo);
        assertEquals(orderNo, reply.text("orderNo"));
        assertEquals("SUBMITTED", reply.text("status"));
        assertEquals("已提交", reply.text("statusLabel"));
        assertEquals("FORM", reply.text("source"));
        assertEquals(1, reply.number("quantity"));
        assertEquals(29900L, reply.longNumber("refundCents"));
        assertEquals("¥299.00", reply.text("refundText"));

        // 事件流：详情里恰好一条 SUBMITTED
        JsonNode events = reply.body().get("events");
        assertEquals(1, events.size(), reply.raw());
        assertEquals("SUBMITTED", events.get(0).get("type").asText());
        assertEquals(1, ShopTestFixture.eventCount(jdbc, returnNo));

        // 额度：已购 2、已占用 1、剩余 1
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, orderNo));

        // 不动库存、不写库存流水
        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbc, ShopSeedInitializer.SKU_KEYBOARD),
                "A1 不得修改可售库存");
        assertEquals(movementsBefore, ShopTestFixture.totalMovements(jdbc),
                "A1 不得写退货入库流水");
    }

    @Test
    @DisplayName("额度三个数可分别读到：已购 2 / 已占用 1 / 剩余 1")
    void quotaIsReported() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-QUOTA", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 1, "尺寸不合适", "req-quota-0001"));

        ShopApiClient.Reply orders = api.get("/shop/aftersale/orders", BUYER);
        assertEquals(200, orders.status(), orders.raw());

        JsonNode target = null;
        for (JsonNode node : orders.body()) {
            if (orderNo.equals(node.get("orderNo").asText())) {
                target = node;
            }
        }
        assertNotNull(target, "已签收订单列表里应包含 " + orderNo);

        assertEquals(2, target.get("quota").get("purchased").asInt());
        assertEquals(1, target.get("quota").get("occupied").asInt());
        assertEquals(1, target.get("quota").get("remaining").asInt());
        assertTrue(target.get("returnable").asBoolean());
        assertFalse(target.get("expired").asBoolean());
    }

    @Test
    @DisplayName("预览不占额度、不建单")
    void previewWritesNothing() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-PREVIEW", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        ShopApiClient.Reply preview = api.post("/shop/aftersale/preview", BUYER,
                new PreviewBody(orderNo, 2, "不想要了"));

        assertEquals(200, preview.status(), preview.raw());
        assertTrue(preview.bool("eligible"), preview.raw());
        assertEquals(59800L, preview.longNumber("refundCents"));
        assertEquals(0, preview.body().get("quota").get("occupied").asInt());

        assertEquals(0, ShopTestFixture.totalReturns(jdbc), "预览不得建单");
        assertEquals(0, ShopTestFixture.occupiedQty(jdbc, orderNo), "预览不得占额度");
    }

    // ---------------------------------------------------------------
    // 资格与参数
    // ---------------------------------------------------------------

    @Test
    @DisplayName("超过剩余额度 → 409，且不建单、不占额度")
    void quantityExceedsQuota() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-OVER", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 3, "多退一件", "req-over-0001"));

        assertEquals(409, reply.status(), reply.raw());
        assertEquals("RETURN_QUOTA_EXCEEDED", reply.text("code"));
        assertEquals(0, ShopTestFixture.totalReturns(jdbc));
        assertEquals(0, ShopTestFixture.occupiedQty(jdbc, orderNo));
    }

    @Test
    @DisplayName("未签收订单 → 409 ORDER_NOT_SIGNED，且不建单")
    void unsignedOrderIsRejected() {
        String orderNo = placeAndShip("SO-AS-SHIPPED");

        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 1, "还没收到", "req-unsigned-01"));

        assertEquals(409, reply.status(), reply.raw());
        assertEquals("ORDER_NOT_SIGNED", reply.text("code"));
        assertEquals(0, ShopTestFixture.totalReturns(jdbc));
    }

    @Test
    @DisplayName("超过 demo 退货窗口 → 409 RETURN_WINDOW_CLOSED")
    void windowExpiredIsRejected() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-EXPIRED", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2,
                LocalDateTime.now().minusDays(30));

        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 1, "过了很久才想起来", "req-expired-001"));

        assertEquals(409, reply.status(), reply.raw());
        assertEquals("RETURN_WINDOW_CLOSED", reply.text("code"));

        // 列表里仍然能看到这张订单，但标记为过期、不可退
        ShopApiClient.Reply orders = api.get("/shop/aftersale/orders", BUYER);
        JsonNode target = null;
        for (JsonNode node : orders.body()) {
            if (orderNo.equals(node.get("orderNo").asText())) {
                target = node;
            }
        }
        assertNotNull(target);
        assertTrue(target.get("expired").asBoolean());
        assertFalse(target.get("returnable").asBoolean());
    }

    @Test
    @DisplayName("原因为空 → 400 INVALID_REASON")
    void blankReasonIsRejected() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-REASON", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 1, "   \n  ", "req-reason-0001"));

        assertEquals(400, reply.status(), reply.raw());
        assertEquals("INVALID_REASON", reply.text("code"));
        assertEquals(0, ShopTestFixture.totalReturns(jdbc));
    }

    @Test
    @DisplayName("数量为 0 → 400 INVALID_QUANTITY")
    void zeroQuantityIsRejected() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-ZERO", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 0, "数量为零", "req-zero-00001"));

        assertEquals(400, reply.status(), reply.raw());
        assertEquals("INVALID_QUANTITY", reply.text("code"));
    }

    @Test
    @DisplayName("缺 clientRequestId → 400 INVALID_REQUEST_ID（提交身份不可缺失）")
    void missingClientRequestIdIsRejected() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-NOKEY", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 1, "没有提交标识", null));

        assertEquals(400, reply.status(), reply.raw());
        assertEquals("INVALID_REQUEST_ID", reply.text("code"));
        assertEquals(0, ShopTestFixture.totalReturns(jdbc));
    }

    @Test
    @DisplayName("别人的订单 → 404，不泄露订单是否存在")
    void otherBuyersOrderIsInvisible() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-OTHER", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", OTHER_BUYER,
                submit(orderNo, 1, "不是我的订单", "req-other-0001"));

        assertEquals(404, reply.status(), reply.raw());
        assertEquals("ORDER_NOT_FOUND", reply.text("code"));
        assertEquals(0, ShopTestFixture.totalReturns(jdbc));
    }

    @Test
    @DisplayName("买家 A 读不到买家 B 的退货单（404），列表也为空")
    void otherBuyersReturnIsInvisible() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-PRIVATE", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        ShopApiClient.Reply created = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 1, "键盘有异响", "req-private-01"));
        assertEquals(201, created.status(), created.raw());
        String returnNo = created.text("returnNo");

        ShopApiClient.Reply read = api.get("/shop/aftersale/returns/" + returnNo, OTHER_BUYER);
        assertEquals(404, read.status(), read.raw());
        assertEquals("RETURN_NOT_FOUND", read.text("code"));

        ShopApiClient.Reply list = api.get("/shop/aftersale/returns", OTHER_BUYER);
        assertEquals(200, list.status(), list.raw());
        assertEquals(0, list.body().size(), "别人的售后列表必须为空");
    }

    // ---------------------------------------------------------------
    // 幂等
    // ---------------------------------------------------------------

    @Test
    @DisplayName("同一提交标识重放 → 返回原单号，只建一单、只占一次额度")
    void replayReturnsSameReturnNo() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-REPLAY", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        ShopApiClient.Reply first = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 2, "重复提交测试", "req-replay-0001"));
        assertEquals(201, first.status(), first.raw());

        ShopApiClient.Reply second = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 2, "重复提交测试", "req-replay-0001"));

        assertEquals(201, second.status(), second.raw());
        assertEquals(first.text("returnNo"), second.text("returnNo"),
                "重放必须返回同一张退货单");

        assertEquals(1, ShopTestFixture.totalReturns(jdbc), "重放不得建第二单");
        assertEquals(2, ShopTestFixture.occupiedQty(jdbc, orderNo), "重放不得重复占额度");
    }

    @Test
    @DisplayName("同一提交标识 + 不同参数 → 409 IDEMPOTENCY_KEY_REUSED")
    void sameKeyDifferentParamsIsRejected() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-CONFLICT", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        ShopApiClient.Reply first = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 1, "第一次提交", "req-conflict-01"));
        assertEquals(201, first.status(), first.raw());

        ShopApiClient.Reply second = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 2, "改了数量但没换标识", "req-conflict-01"));

        assertEquals(409, second.status(), second.raw());
        assertEquals("IDEMPOTENCY_KEY_REUSED", second.text("code"));
        assertEquals(1, ShopTestFixture.totalReturns(jdbc));
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, orderNo));
    }

    @Test
    @DisplayName("额度用光后的重放仍然返回原单号（不被当前额度不足误判为失败）")
    void replaySucceedsEvenWhenQuotaIsNowExhausted() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-EXHAUST", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        ShopApiClient.Reply first = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 2, "整单退掉", "req-exhaust-001"));
        assertEquals(201, first.status(), first.raw());

        // 额度已为 0：新的提交必须失败
        ShopApiClient.Reply fresh = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 1, "再退一件", "req-exhaust-002"));
        assertEquals(409, fresh.status(), fresh.raw());
        assertEquals("RETURN_QUOTA_EXCEEDED", fresh.text("code"));

        // 但同一次提交的重放必须仍然成功，并返回原单号
        ShopApiClient.Reply replay = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 2, "整单退掉", "req-exhaust-001"));
        assertEquals(201, replay.status(), replay.raw());
        assertEquals(first.text("returnNo"), replay.text("returnNo"));
        assertEquals(1, ShopTestFixture.totalReturns(jdbc));
    }

    // ---------------------------------------------------------------
    // 未启用身份
    // ---------------------------------------------------------------

    @Test
    @DisplayName("缺身份 → 401，且不建单")
    void missingIdentityIsRejected() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-NOIDENT", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", null,
                submit(orderNo, 1, "没有身份", "req-noident-01"));

        assertEquals(401, reply.status(), reply.raw());
        assertEquals(0, ShopTestFixture.totalReturns(jdbc));
    }

    @Test
    @DisplayName("详情返回事件流；列表的 events 为 null（未加载 ≠ 没有事件）")
    void detailHasEventsListHasNull() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-SHAPE", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        ShopApiClient.Reply created = api.post("/shop/aftersale/returns", BUYER,
                submit(orderNo, 1, "形状检查", "req-shape-0001"));
        String returnNo = created.text("returnNo");

        ShopApiClient.Reply list = api.get("/shop/aftersale/returns", BUYER);
        assertEquals(1, list.body().size());
        assertTrue(list.body().get(0).get("events").isNull(), "列表不应加载事件流");

        ShopApiClient.Reply detail = api.get("/shop/aftersale/returns/" + returnNo, BUYER);
        assertEquals(200, detail.status(), detail.raw());
        assertEquals(1, detail.body().get("events").size());
        assertEquals("买家提交退货申请", detail.body().get("events").get(0).get("label").asText());
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    /** 走 A0 的真实接口下单并发货（不签收），用来验证"未签收不能退"。 */
    private String placeAndShip(String orderNo) {
        ShopTestFixture.createSignedOrder(
                jdbc, orderNo, BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 1, null);
        jdbc.update("UPDATE shop_order SET status = 'SHIPPED', signed_at = NULL, signed_by = NULL "
                + "WHERE order_no = ?", orderNo);
        return orderNo;
    }

    private static SubmitBody submit(String orderNo, Integer quantity, String reason, String key) {
        return new SubmitBody(orderNo, quantity, reason, key);
    }

    /** 表单提交请求体（字段与后端 record 一一对应）。 */
    record SubmitBody(String orderNo, Integer quantity, String reason, String clientRequestId) {
    }

    /** 预览请求体。 */
    record PreviewBody(String orderNo, Integer quantity, String reason) {
    }
}
