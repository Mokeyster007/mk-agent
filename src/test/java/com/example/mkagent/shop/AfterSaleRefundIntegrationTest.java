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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 模拟退款（H2，真实 HTTP，**不依赖模型**）。
 *
 * <p>覆盖的验收点：
 * <ul>
 *   <li>只有「已批准 + 全部实收」能退款；未收满 / 待审批 / 已驳回一律 409；</li>
 *   <li>金额 = <b>原订单成交单价 × 累计实收</b>（不是合格件数，也不是请求里传的）；</li>
 *   <li>一张退货单最多一笔：重复调用 409，库里仍然只有一笔；</li>
 *   <li>退款**不动可售库存、不写第二条入库流水、不释放可退额度**；</li>
 *   <li>普通买家不能退款（403），但能看到自己那笔模拟退款。</li>
 * </ul>
 *
 * <p>并发重复退款在 {@code AfterSaleRefundPostgresTest}：
 * 唯一键冲突在 H2 与 PG 上的事务语义不同，只有真库能证明"输的那一方可解释地失败"。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AfterSaleRefundIntegrationTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    private static final String OTHER_BUYER = ShopSeedInitializer.BUYER_TWO;

    private static final String ADMIN = ShopSeedInitializer.ADMIN;

    private static final String SKU = ShopSeedInitializer.SKU_KEYBOARD;

    private static final long PRODUCT = ShopSeedInitializer.PRODUCT_KEYBOARD;

    private static final String ORDER_NO = "SO-AS-B3-0001";

    /** 成交单价 29900 分，与种子商品一致。 */
    private static final long UNIT_PRICE = 29900L;

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
    // 正常路径
    // ---------------------------------------------------------------

    @Test
    @DisplayName("全部收货后模拟退款：金额 = 成交单价 × 累计实收（含损坏件）")
    void refundAfterFullyReceived() {
        String returnNo = approvedReturn(2);

        // 第一批 1 件合格、第二批 1 件损坏：累计实收 2，合格只有 1
        assertEquals(201, receive(returnNo, 1, 1, 0, "rc-b3-part-1").status());
        assertEquals(201, receive(returnNo, 1, 0, 1, "rc-b3-part-2").status());
        assertEquals(2, ShopTestFixture.receivedQtyOf(jdbc, returnNo));
        assertEquals(1, ShopTestFixture.qualifiedQtyOf(jdbc, returnNo));

        ShopApiClient.Reply reply = refund(returnNo);

        assertEquals(201, reply.status(), reply.raw());
        JsonNode refund = reply.body().get("refund");
        assertNotNull(refund, "详情里必须带退款记录：" + reply.raw());
        assertEquals("模拟退款", refund.get("label").asText());
        assertEquals(UNIT_PRICE * 2, refund.get("amountCents").asLong(),
                "退款按累计实收 2 件算，损坏那件也要退钱");
        assertEquals("¥598.00", refund.get("amountText").asText());
        assertEquals(ADMIN, refund.get("operatorId").asText(), "操作人来自服务端身份");
        assertTrue(refund.get("refundNo").asText().startsWith("RF"), refund.toString());

        // 落库事实
        assertEquals(1, ShopTestFixture.refundCount(jdbc, returnNo));
        assertEquals(UNIT_PRICE * 2, ShopTestFixture.refundAmountOf(jdbc, returnNo));

        // 退款不改变状态：主状态仍然只表达客服结论
        assertEquals("APPROVED", reply.text("status"));
    }

    @Test
    @DisplayName("退款不动库存、不写第二条入库流水、不释放可退额度")
    void refundTouchesNeitherStockNorQuota() {
        String returnNo = approvedReturn(2);
        assertEquals(201, receive(returnNo, 1, 1, 0, "rc-b3-stock-1").status());
        assertEquals(201, receive(returnNo, 1, 1, 0, "rc-b3-stock-2").status());

        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);
        int inboundBefore = ShopTestFixture.movementCountOfType(jdbc, SKU, "RETURN_INBOUND");
        int occupiedBefore = ShopTestFixture.occupiedQty(jdbc, ORDER_NO);
        int movementsBefore = ShopTestFixture.totalMovements(jdbc);

        assertEquals(201, refund(returnNo).status());

        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbc, SKU), "退款不动可售库存");
        assertEquals(inboundBefore, ShopTestFixture.movementCountOfType(jdbc, SKU, "RETURN_INBOUND"),
                "退款不写第二条入库流水");
        assertEquals(movementsBefore, ShopTestFixture.totalMovements(jdbc), "退款不产生任何流水");
        assertEquals(occupiedBefore, ShopTestFixture.occupiedQty(jdbc, ORDER_NO),
                "退款不释放可退额度");
        assertEquals(2, ShopTestFixture.occupiedQty(jdbc, ORDER_NO), "2 件仍然全部占着");
        assertNull(ShopTestFixture.quotaConsistencyError(jdbc, ORDER_NO));
    }

    // ---------------------------------------------------------------
    // 拒绝路径
    // ---------------------------------------------------------------

    @Test
    @DisplayName("没收到批准数量不能退款（409 REFUND_NOT_RECEIVED），零写入")
    void cannotRefundBeforeFullyReceived() {
        String returnNo = approvedReturn(2);
        assertEquals(201, receive(returnNo, 1, 1, 0, "rc-b3-partial").status());

        ShopApiClient.Reply reply = refund(returnNo);

        assertEquals(409, reply.status(), reply.raw());
        assertEquals("REFUND_NOT_RECEIVED", reply.text("code"));
        assertEquals(0, ShopTestFixture.refundCount(jdbc, returnNo), "被拒绝不得留下退款记录");
    }

    @Test
    @DisplayName("待审批的退货单不能退款（409 REFUND_NOT_ALLOWED）")
    void submittedCannotRefund() {
        String returnNo = submittedReturn(2);

        ShopApiClient.Reply reply = refund(returnNo);

        assertEquals(409, reply.status(), reply.raw());
        assertEquals("REFUND_NOT_ALLOWED", reply.text("code"));
        assertEquals(0, ShopTestFixture.refundCount(jdbc, returnNo));
    }

    @Test
    @DisplayName("已驳回的退货单不能退款（409 REFUND_NOT_ALLOWED）")
    void rejectedCannotRefund() {
        String returnNo = submittedReturn(2);
        ShopApiClient.Reply reject = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN,
                Map.of("reason", "超过 7 天无理由期限"));
        assertEquals(200, reject.status(), reject.raw());

        ShopApiClient.Reply reply = refund(returnNo);

        assertEquals(409, reply.status(), reply.raw());
        assertEquals("REFUND_NOT_ALLOWED", reply.text("code"));
        assertEquals(0, ShopTestFixture.refundCount(jdbc, returnNo));
    }

    @Test
    @DisplayName("重复退款 409 REFUND_ALREADY_EXISTS，库里仍然只有一笔")
    void duplicateRefundRejected() {
        String returnNo = approvedReturn(2);
        assertEquals(201, receive(returnNo, 2, 2, 0, "rc-b3-dup").status());

        ShopApiClient.Reply first = refund(returnNo);
        assertEquals(201, first.status(), first.raw());
        String refundNo = first.body().get("refund").get("refundNo").asText();

        ShopApiClient.Reply second = refund(returnNo);

        assertEquals(409, second.status(), second.raw());
        assertEquals("REFUND_ALREADY_EXISTS", second.text("code"));
        assertEquals(1, ShopTestFixture.refundCount(jdbc, returnNo), "一张退货单最多一笔");
        assertEquals(refundNo, ShopTestFixture.refundNoOf(jdbc, returnNo), "原记录不能被覆盖");
    }

    @Test
    @DisplayName("退货单上的单价快照与订单行不一致：拒绝退款（409 REFUND_AMOUNT_MISMATCH）")
    void priceMismatchRejected() {
        String returnNo = approvedReturn(2);
        assertEquals(201, receive(returnNo, 2, 2, 0, "rc-b3-mismatch").status());

        // 直接改库模拟"数据被绕过业务路径改过"
        jdbc.update("UPDATE shop_aftersale_return SET unit_price_cents = 1 WHERE return_no = ?",
                returnNo);

        ShopApiClient.Reply reply = refund(returnNo);

        assertEquals(409, reply.status(), reply.raw());
        assertEquals("REFUND_AMOUNT_MISMATCH", reply.text("code"));
        assertEquals(0, ShopTestFixture.refundCount(jdbc, returnNo),
                "宁可拒绝，也不能按一个说不清来源的单价退钱");
    }

    @Test
    @DisplayName("累计实收超过批准数量（数据不一致）：拒绝退款（409 REFUND_RECEIPT_QUANTITY_MISMATCH），零写入")
    void overReceivedRejected() {
        String returnNo = approvedReturn(2);
        assertEquals(201, receive(returnNo, 2, 2, 0, "rc-b3-over").status());

        /*
         * 正常路径写不出"收 3 件、批准 2 件"：收货写入在锁内校验"累计 + 本批 ≤ 批准数量"。
         * 这里直接插一条收货记录，模拟"数据被绕过业务路径改过"——
         * 此时按实收金额退款会多退钱，必须拒绝而不是猜一个金额。
         */
        jdbc.update("""
                INSERT INTO shop_aftersale_receipt
                    (receipt_no, return_id, order_item_id, received_qty, qualified_qty, damaged_qty,
                     note, request_key, request_hash, operator_id)
                SELECT 'RC-B3-OVER-0001', r.id, r.order_item_id, 1, 1, 0,
                       NULL, 'rc-b3-over-injected', 'injected', 'music-admin-1'
                FROM shop_aftersale_return r WHERE r.return_no = ?
                """, returnNo);
        assertEquals(3, ShopTestFixture.receivedQtyOf(jdbc, returnNo), "注入后累计实收 3 件");

        ShopApiClient.Reply reply = refund(returnNo);

        assertEquals(409, reply.status(), reply.raw());
        assertEquals("REFUND_RECEIPT_QUANTITY_MISMATCH", reply.text("code"));
        assertEquals(0, ShopTestFixture.refundCount(jdbc, returnNo),
                "数据不一致时不得产生退款记录（既不按 3 件退，也不按 2 件猜）");
    }

    @Test
    @DisplayName("普通买家不能退款（403 NOT_ADMIN），且不留记录")
    void buyerCannotRefund() {
        String returnNo = approvedReturn(2);
        assertEquals(201, receive(returnNo, 2, 2, 0, "rc-b3-buyer").status());

        ShopApiClient.Reply reply = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/refund", BUYER, null);

        assertEquals(403, reply.status(), reply.raw());
        assertEquals("NOT_ADMIN", reply.text("code"));
        assertEquals(0, ShopTestFixture.refundCount(jdbc, returnNo));
    }

    // ---------------------------------------------------------------
    // 可见性
    // ---------------------------------------------------------------

    @Test
    @DisplayName("买家能在列表与详情看到自己的模拟退款（只读，含金额文案）")
    void buyerSeesRefund() {
        String returnNo = approvedReturn(2);
        assertEquals(201, receive(returnNo, 2, 2, 0, "rc-b3-buyer-see").status());
        assertEquals(201, refund(returnNo).status());

        ShopApiClient.Reply list = api.get("/shop/aftersale/returns", BUYER);
        assertEquals(200, list.status(), list.raw());
        JsonNode row = list.body().get(0);
        assertEquals(returnNo, row.get("returnNo").asText());
        assertNotNull(row.get("refund"), "列表也要带退款，否则买家看不出已经退过");
        assertEquals("模拟退款", row.get("refund").get("label").asText());
        assertEquals("¥598.00", row.get("refund").get("amountText").asText());

        ShopApiClient.Reply detail = api.get("/shop/aftersale/returns/" + returnNo, BUYER);
        assertEquals(200, detail.status(), detail.raw());
        assertEquals("模拟退款", detail.body().get("refund").get("label").asText());

        // 别的买家看不到这张单（404，不是 403）
        assertEquals(404, api.get("/shop/aftersale/returns/" + returnNo, OTHER_BUYER).status());
    }

    @Test
    @DisplayName("客服列表与详情都能看到退款（列表一次批量查，不逐行查）")
    void adminListShowsRefund() {
        String returnNo = approvedReturn(2);
        assertEquals(201, receive(returnNo, 2, 2, 0, "rc-b3-admin").status());
        assertEquals(201, refund(returnNo).status());

        ShopApiClient.Reply list = api.get("/shop/admin/aftersale/returns?status=APPROVED", ADMIN);
        assertEquals(200, list.status(), list.raw());
        JsonNode row = list.body().get("items").get(0);
        assertEquals(returnNo, row.get("returnNo").asText());
        assertEquals("模拟退款", row.get("refund").get("label").asText());
        assertEquals(UNIT_PRICE * 2, row.get("refund").get("amountCents").asLong());

        ShopApiClient.Reply detail = api.get("/shop/admin/aftersale/returns/" + returnNo, ADMIN);
        assertEquals(200, detail.status(), detail.raw());
        assertEquals("模拟退款", detail.body().get("refund").get("label").asText());
        assertEquals(1, detail.body().get("receipts").size(), "退款不该新增收货记录");
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    private ShopApiClient.Reply refund(String returnNo) {
        return api.post("/shop/admin/aftersale/returns/" + returnNo + "/refund", ADMIN, null);
    }

    /** 走真实接口建一张"已批准"的退货单。 */
    private String approvedReturn(int quantity) {
        String returnNo = submittedReturn(quantity);
        ShopApiClient.Reply approve = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/approve", ADMIN, null);
        assertEquals(200, approve.status(), approve.raw());
        assertEquals("APPROVED", ShopTestFixture.returnStatus(jdbc, returnNo));
        return returnNo;
    }

    /** 走真实买家接口提交一张待审批的退货单。 */
    private String submittedReturn(int quantity) {
        ShopTestFixture.createSignedOrder(
                jdbc, ORDER_NO, BUYER, SKU, PRODUCT, "机械键盘 K87", UNIT_PRICE, quantity, null);

        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", BUYER, Map.of(
                "orderNo", ORDER_NO,
                "quantity", quantity,
                "reason", "键盘按键失灵",
                "clientRequestId", "req-b3-" + quantity));
        assertEquals(201, reply.status(), reply.raw());

        String returnNo = reply.text("returnNo");
        assertNotNull(returnNo, reply.raw());
        return returnNo;
    }

    private ShopApiClient.Reply receive(
            String returnNo,
            int received,
            int qualified,
            int damaged,
            String requestId
    ) {
        // HashMap 而不是 Map.of：note 允许为 null
        Map<String, Object> body = new HashMap<>();
        body.put("receivedQty", received);
        body.put("qualifiedQty", qualified);
        body.put("damagedQty", damaged);
        body.put("note", null);
        body.put("clientRequestId", requestId);
        return api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/receipts", ADMIN, body);
    }
}
