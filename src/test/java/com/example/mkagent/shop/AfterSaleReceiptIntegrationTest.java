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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2 仓库收货 / 验货与库存回补（H2，真实 HTTP，**不依赖模型**）。
 *
 * <p>覆盖的验收点：
 * <ul>
 *   <li>只有「已批准」能收货；待审批 / 已驳回一律 409；</li>
 *   <li>一张退货单可分多批收货，累计实收不得超过批准数量；</li>
 *   <li>只有合格数量增加可售库存并写 {@code RETURN_INBOUND}；整批损坏只留记录；</li>
 *   <li>同键同参重放不重复加库存；同键不同参 409；不同批次各自入库；</li>
 *   <li>收货**不释放**可退额度（收进来的件数已消耗该资格）；</li>
 *   <li>买家能看到自己的收货进度与每批记录，别人看不到；普通买家不能收货。</li>
 * </ul>
 *
 * <p>并发与真库故障注入在 {@code AfterSaleReceiptPostgresTest}：
 * H2 的行锁语义与 PG 不同，"两个事务真的互相阻塞"只能在真库上证明。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AfterSaleReceiptIntegrationTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    private static final String OTHER_BUYER = ShopSeedInitializer.BUYER_TWO;

    private static final String ADMIN = ShopSeedInitializer.ADMIN;

    private static final String SKU = ShopSeedInitializer.SKU_KEYBOARD;

    private static final long PRODUCT = ShopSeedInitializer.PRODUCT_KEYBOARD;

    private static final String ORDER_NO = "SO-AS-B2-0001";

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
    // 正常路径：分批收货 + 只有合格入库存
    // ---------------------------------------------------------------

    @Test
    @DisplayName("部分收货：合格 1 件回补库存并写一条 RETURN_INBOUND，进度为部分收货")
    void partialReceiptIncreasesStockOnce() {
        String returnNo = approvedReturn(2, 2);
        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);

        ShopApiClient.Reply reply = receive(returnNo, 1, 1, 0, "外包装完好", "rc-partial-0001");

        assertEquals(201, reply.status(), reply.raw());
        assertEquals("APPROVED", reply.text("status"), "收货不改变客服结论");

        JsonNode progress = reply.body().get("progress");
        assertNotNull(progress, reply.raw());
        assertEquals("PARTIAL", progress.get("code").asText());
        assertEquals("部分收货", progress.get("label").asText());
        assertEquals(2, progress.get("approvedQty").asInt());
        assertEquals(1, progress.get("receivedQty").asInt());
        assertEquals(1, progress.get("remainingQty").asInt());

        JsonNode receipts = reply.body().get("receipts");
        assertEquals(1, receipts.size(), reply.raw());
        JsonNode receipt = receipts.get(0);
        assertEquals(1, receipt.get("receivedQty").asInt());
        assertEquals(1, receipt.get("qualifiedQty").asInt());
        assertEquals(0, receipt.get("damagedQty").asInt());
        assertEquals(ADMIN, receipt.get("operatorId").asText(), "操作人来自服务端身份");

        // 这一批真的产生了流水，并且流水能回到收货记录
        JsonNode movement = receipt.get("movement");
        assertNotNull(movement, "合格 1 件必须有入库流水");
        assertEquals(1, movement.get("quantityDelta").asInt());
        assertEquals("RETURN_INBOUND", movement.get("type").asText());
        assertEquals("退货入库", movement.get("typeLabel").asText());
        assertFalse(movement.get("receiptId").isNull(), "流水必须指向收货记录");

        assertEquals(stockBefore + 1, ShopTestFixture.stockOf(jdbc, SKU));
        assertEquals(1, ShopTestFixture.movementCountOfType(jdbc, SKU, "RETURN_INBOUND"));
        assertEquals(1, ShopTestFixture.receiptCount(jdbc, returnNo));
        assertEquals(1, ShopTestFixture.receivedQtyOf(jdbc, returnNo));

        // 额度不释放：2 件仍然全部占着，买家不能再申请
        assertEquals(2, ShopTestFixture.occupiedQty(jdbc, ORDER_NO));
        assertNull(ShopTestFixture.quotaConsistencyError(jdbc, ORDER_NO));
    }

    @Test
    @DisplayName("整批损坏：只留收货记录，不加库存、不写流水")
    void damagedBatchDoesNotIncreaseStock() {
        String returnNo = approvedReturn(2, 2);
        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);

        ShopApiClient.Reply reply = receive(returnNo, 1, 0, 1, "外壳破裂，不可售", "rc-damaged-0001");

        assertEquals(201, reply.status(), reply.raw());
        JsonNode receipt = reply.body().get("receipts").get(0);
        assertEquals(1, receipt.get("receivedQty").asInt());
        assertEquals(0, receipt.get("qualifiedQty").asInt());
        assertEquals(1, receipt.get("damagedQty").asInt());
        assertTrue(receipt.get("movement").isNull(), "整批损坏不应产生流水");
        assertEquals("外壳破裂，不可售", receipt.get("note").asText());

        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbc, SKU), "损坏件不能回补可售库存");
        assertEquals(0, ShopTestFixture.movementCountOfType(jdbc, SKU, "RETURN_INBOUND"));
        assertEquals(1, ShopTestFixture.receiptCount(jdbc, returnNo));

        // 实收累计仍然算 1 件：损坏也算"收到了"
        assertEquals("PARTIAL", reply.body().get("progress").get("code").asText());
        assertEquals(1, reply.body().get("progress").get("receivedQty").asInt());
    }

    @Test
    @DisplayName("两批收完：进度为全部收货，库存只加合格的那 1 件")
    void completeProgressAfterTwoBatches() {
        String returnNo = approvedReturn(2, 2);
        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);

        assertEquals(201, receive(returnNo, 1, 1, 0, null, "rc-batch1-0001").status());
        ShopApiClient.Reply second = receive(returnNo, 1, 0, 1, "第二批外壳破裂", "rc-batch2-0001");

        assertEquals(201, second.status(), second.raw());
        JsonNode progress = second.body().get("progress");
        assertEquals("COMPLETE", progress.get("code").asText());
        assertEquals("全部收货", progress.get("label").asText());
        assertEquals(2, progress.get("receivedQty").asInt());
        assertEquals(0, progress.get("remainingQty").asInt());
        assertEquals(2, second.body().get("receipts").size());

        assertEquals(stockBefore + 1, ShopTestFixture.stockOf(jdbc, SKU));
        assertEquals(1, ShopTestFixture.movementCountOfType(jdbc, SKU, "RETURN_INBOUND"));
        assertEquals(2, ShopTestFixture.receivedQtyOf(jdbc, returnNo));
        assertEquals(1, ShopTestFixture.qualifiedQtyOf(jdbc, returnNo));
    }

    @Test
    @DisplayName("不同批次各自入库：同一订单行可以有多笔退货入库流水")
    void differentBatchesProduceSeparateMovements() {
        String returnNo = approvedReturn(3, 3);
        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);

        assertEquals(201, receive(returnNo, 1, 1, 0, null, "rc-multi-0001").status());
        assertEquals(201, receive(returnNo, 1, 1, 0, null, "rc-multi-0002").status());

        // 旧的 (order_item_id, movement_type) 唯一索引已经被 dedupe_key 取代，
        // 否则第二批会被数据库挡下来——这条用例就是那个改动的证据。
        assertEquals(2, ShopTestFixture.movementCountOfType(jdbc, SKU, "RETURN_INBOUND"));
        assertEquals(stockBefore + 2, ShopTestFixture.stockOf(jdbc, SKU));
        assertEquals("PARTIAL", progressOf(returnNo).get("code").asText());
    }

    // ---------------------------------------------------------------
    // 边界：状态与超收
    // ---------------------------------------------------------------

    @Test
    @DisplayName("待审批的退货单不能收货（409 RETURN_NOT_APPROVED）")
    void submittedReturnCannotBeReceived() {
        String returnNo = submittedReturn(2);

        ShopApiClient.Reply reply = receive(returnNo, 1, 1, 0, null, "rc-not-approved-1");

        assertEquals(409, reply.status(), reply.raw());
        assertEquals("RETURN_NOT_APPROVED", reply.text("code"));
        assertTrue(reply.text("message").contains("批准之后"), reply.text("message"));
        assertEquals(0, ShopTestFixture.receiptCount(jdbc, returnNo));
        assertEquals(0, ShopTestFixture.movementCountOfType(jdbc, SKU, "RETURN_INBOUND"));
    }

    @Test
    @DisplayName("已驳回的退货单不能收货（409 RETURN_NOT_APPROVED）")
    void rejectedReturnCannotBeReceived() {
        String returnNo = submittedReturn(2);
        api.post("/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN,
                Map.of("reason", "超过 7 天无理由期限"));

        ShopApiClient.Reply reply = receive(returnNo, 1, 1, 0, null, "rc-rejected-0001");

        assertEquals(409, reply.status(), reply.raw());
        assertEquals("RETURN_NOT_APPROVED", reply.text("code"));
        assertTrue(reply.text("message").contains("驳回"), reply.text("message"));
        assertEquals(0, ShopTestFixture.receiptCount(jdbc, returnNo));
        assertEquals(0, ShopTestFixture.movementCountOfType(jdbc, SKU, "RETURN_INBOUND"));
    }

    @Test
    @DisplayName("累计实收不能超过批准数量（409 RECEIPT_OVER_APPROVED）")
    void cannotReceiveMoreThanApproved() {
        String returnNo = approvedReturn(2, 1);

        ShopApiClient.Reply tooMany = receive(returnNo, 2, 2, 0, null, "rc-over-0001");
        assertEquals(409, tooMany.status(), tooMany.raw());
        assertEquals("RECEIPT_OVER_APPROVED", tooMany.text("code"));
        assertTrue(tooMany.text("message").contains("最多还能收 1 件"), tooMany.text("message"));
        assertEquals(0, ShopTestFixture.receiptCount(jdbc, returnNo));

        assertEquals(201, receive(returnNo, 1, 1, 0, null, "rc-over-0002").status());

        // 已经收满，再来一件同样是超收
        ShopApiClient.Reply afterFull = receive(returnNo, 1, 1, 0, null, "rc-over-0003");
        assertEquals(409, afterFull.status(), afterFull.raw());
        assertEquals("RECEIPT_OVER_APPROVED", afterFull.text("code"));
        assertEquals(1, ShopTestFixture.receiptCount(jdbc, returnNo));
    }

    @Test
    @DisplayName("数量不自洽直接 400：实收 0 / 合格 + 损坏 ≠ 实收")
    void invalidQuantitiesRejected() {
        String returnNo = approvedReturn(2, 2);

        ShopApiClient.Reply zero = receive(returnNo, 0, 0, 0, null, "rc-invalid-0001");
        assertEquals(400, zero.status(), zero.raw());
        assertEquals("RECEIPT_QUANTITY_INVALID", zero.text("code"));

        ShopApiClient.Reply mismatch = receive(returnNo, 2, 1, 0, null, "rc-invalid-0002");
        assertEquals(400, mismatch.status(), mismatch.raw());
        assertEquals("RECEIPT_QUANTITY_INVALID", mismatch.text("code"));

        ShopApiClient.Reply negative = receive(returnNo, 1, 2, -1, null, "rc-invalid-0003");
        assertEquals(400, negative.status(), negative.raw());

        assertEquals(0, ShopTestFixture.receiptCount(jdbc, returnNo));
    }

    // ---------------------------------------------------------------
    // 幂等：同键同参 / 同键不同参
    // ---------------------------------------------------------------

    @Test
    @DisplayName("同键同参重放：返回同一条收货记录，不重复加库存")
    void sameRequestKeyReplaysWithoutDoubleStock() {
        String returnNo = approvedReturn(2, 2);
        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);

        ShopApiClient.Reply first = receive(returnNo, 1, 1, 0, "第一次提交", "rc-replay-0001");
        ShopApiClient.Reply replay = receive(returnNo, 1, 1, 0, "第一次提交", "rc-replay-0001");

        assertEquals(201, first.status(), first.raw());
        assertEquals(201, replay.status(), replay.raw());
        assertEquals(
                first.body().get("receipts").get(0).get("receiptNo").asText(),
                replay.body().get("receipts").get(0).get("receiptNo").asText(),
                "重放必须返回首次的收货单号");

        assertEquals(1, ShopTestFixture.receiptCount(jdbc, returnNo), "只能有一条收货记录");
        assertEquals(stockBefore + 1, ShopTestFixture.stockOf(jdbc, SKU), "库存只能加一次");
        assertEquals(1, ShopTestFixture.movementCountOfType(jdbc, SKU, "RETURN_INBOUND"));
        assertEquals(1, ShopTestFixture.receivedQtyOf(jdbc, returnNo));
    }

    @Test
    @DisplayName("同键不同参：409 RECEIPT_REQUEST_CONFLICT，零写入")
    void sameRequestKeyDifferentParamsConflicts() {
        String returnNo = approvedReturn(2, 2);
        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);

        assertEquals(201, receive(returnNo, 1, 1, 0, "第一次提交", "rc-conflict-0001").status());

        ShopApiClient.Reply conflict = receive(returnNo, 1, 0, 1, "改了拆分", "rc-conflict-0001");

        assertEquals(409, conflict.status(), conflict.raw());
        assertEquals("RECEIPT_REQUEST_CONFLICT", conflict.text("code"));
        assertEquals(1, ShopTestFixture.receiptCount(jdbc, returnNo));
        assertEquals(stockBefore + 1, ShopTestFixture.stockOf(jdbc, SKU));
    }

    // ---------------------------------------------------------------
    // 权限与买家可见性
    // ---------------------------------------------------------------

    @Test
    @DisplayName("普通买家不能收货（403 NOT_ADMIN），且不会留下任何记录")
    void buyerCannotReceive() {
        String returnNo = approvedReturn(2, 2);

        ShopApiClient.Reply reply = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/receipts",
                BUYER,
                receiveBody(1, 1, 0, null, "rc-buyer-0001"));

        assertEquals(403, reply.status(), reply.raw());
        assertEquals("NOT_ADMIN", reply.text("code"));
        assertEquals(0, ShopTestFixture.receiptCount(jdbc, returnNo));
    }

    @Test
    @DisplayName("买家能看到自己的收货进度与每批记录，别人看不到")
    void buyerSeesOwnReceipts() {
        String returnNo = approvedReturn(2, 2);
        assertEquals(201, receive(returnNo, 1, 1, 0, "外包装完好", "rc-buyer-visible-1").status());

        ShopApiClient.Reply detail = api.get("/shop/aftersale/returns/" + returnNo, BUYER);
        assertEquals(200, detail.status(), detail.raw());
        assertEquals("PARTIAL", detail.body().get("progress").get("code").asText());
        assertEquals(1, detail.body().get("receipts").size());
        assertEquals(1, detail.body().get("receipts").get(0).get("qualifiedQty").asInt());
        assertEquals("退货入库",
                detail.body().get("receipts").get(0).get("movement").get("typeLabel").asText());

        // 列表也带进度，但不带收货明细（避免一次拉太多）
        ShopApiClient.Reply list = api.get("/shop/aftersale/returns", BUYER);
        assertEquals(200, list.status(), list.raw());
        JsonNode row = list.body().get(0);
        assertEquals("PARTIAL", row.get("progress").get("code").asText());
        assertTrue(row.get("receipts").isNull(), "列表不加载收货明细");

        // 别人看不到这一单（404，不是 403）
        assertEquals(404, api.get("/shop/aftersale/returns/" + returnNo, OTHER_BUYER).status());
    }

    @Test
    @DisplayName("客服列表与详情都能看到收货进度（列表一次批量算，不逐行查）")
    void adminListShowsProgress() {
        String returnNo = approvedReturn(2, 2);
        assertEquals(201, receive(returnNo, 2, 2, 0, null, "rc-admin-list-01").status());

        ShopApiClient.Reply list = api.get("/shop/admin/aftersale/returns?status=APPROVED", ADMIN);
        assertEquals(200, list.status(), list.raw());
        JsonNode row = list.body().get("items").get(0);
        assertEquals(returnNo, row.get("returnNo").asText());
        assertEquals("COMPLETE", row.get("progress").get("code").asText());
        assertEquals(0, row.get("progress").get("remainingQty").asInt());

        ShopApiClient.Reply detail = api.get("/shop/admin/aftersale/returns/" + returnNo, ADMIN);
        assertEquals(200, detail.status(), detail.raw());
        assertEquals(1, detail.body().get("receipts").size());
        assertEquals("全部收货", detail.body().get("progress").get("label").asText());
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    /** 走真实接口建一张"已批准"的退货单。 */
    private String approvedReturn(int purchased, int returnQty) {
        String returnNo = submittedReturn(purchased, returnQty);
        ShopApiClient.Reply approve = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/approve", ADMIN, null);
        assertEquals(200, approve.status(), approve.raw());
        assertEquals("APPROVED", ShopTestFixture.returnStatus(jdbc, returnNo));
        return returnNo;
    }

    private String submittedReturn(int purchased) {
        return submittedReturn(purchased, purchased);
    }

    /** 走真实买家接口提交一张待审批的退货单。 */
    private String submittedReturn(int purchased, int returnQty) {
        ShopTestFixture.createSignedOrder(
                jdbc, ORDER_NO, BUYER, SKU, PRODUCT, "机械键盘 K87", 29900, purchased, null);

        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", BUYER, Map.of(
                "orderNo", ORDER_NO,
                "quantity", returnQty,
                "reason", "键盘按键失灵",
                "clientRequestId", "req-b2-" + returnQty + "-" + purchased));
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
            String note,
            String requestId
    ) {
        return api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/receipts",
                ADMIN,
                receiveBody(received, qualified, damaged, note, requestId));
    }

    private static Map<String, Object> receiveBody(
            int received,
            int qualified,
            int damaged,
            String note,
            String requestId
    ) {
        // HashMap 而不是 Map.of：note 允许为 null
        Map<String, Object> body = new HashMap<>();
        body.put("receivedQty", received);
        body.put("qualifiedQty", qualified);
        body.put("damagedQty", damaged);
        body.put("note", note);
        body.put("clientRequestId", requestId);
        return body;
    }

    private JsonNode progressOf(String returnNo) {
        ShopApiClient.Reply detail = api.get("/shop/admin/aftersale/returns/" + returnNo, ADMIN);
        assertEquals(200, detail.status(), detail.raw());
        return detail.body().get("progress");
    }
}
