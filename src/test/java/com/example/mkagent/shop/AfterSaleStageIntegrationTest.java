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
 * C1 买家进度：六个阶段与它们的措辞（H2，真实 HTTP，**不依赖模型**）。
 *
 * <p>这里验的不是"有没有一个新字段"，而是三件事：
 * <ol>
 *   <li>每个业务分支都落到**唯一**一个阶段，不重不漏；</li>
 *   <li>驳回是**终止**分支：不能出现"待收货 / 待退款"这种还会继续的暗示；</li>
 *   <li>"收满但没退款"与"已登记退款"是**两个**阶段——
 *       把前者显示成后者，等于告诉买家钱已经处理了。</li>
 * </ol>
 *
 * <p>{@code stage} 是派生值：由 status + 收货进度 + 退款记录算出，
 * 不落库、不改退货单状态（详见 {@code AfterSaleViews.StageVO}）。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AfterSaleStageIntegrationTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    private static final String ADMIN = ShopSeedInitializer.ADMIN;

    private static final String SKU = ShopSeedInitializer.SKU_KEYBOARD;

    private static final long PRODUCT = ShopSeedInitializer.PRODUCT_KEYBOARD;

    private static final String ORDER_NO = "SO-AS-C1-0001";

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
    // 六个阶段
    // ---------------------------------------------------------------

    @Test
    @DisplayName("待客服处理：阶段是「待客服处理」，不暗示已经进入收货")
    void waitingDecision() {
        String returnNo = submittedReturn(2);

        JsonNode stage = detail(returnNo).get("stage");

        assertEquals("WAITING_DECISION", stage.get("code").asText());
        assertEquals("待客服处理", stage.get("label").asText());
        assertTrue(stage.get("hint").asText().contains("客服"), stage.toString());
        assertFalse(stage.get("hint").asText().contains("收货"), stage.toString());
        assertTrue(detail(returnNo).get("progress").isNull(), "未批准没有收货进度");
    }

    @Test
    @DisplayName("已驳回：阶段是「已驳回」，明确终止（没有待收货 / 退款），原因与时间可见")
    void rejectedIsTerminal() {
        String returnNo = submittedReturn(2);
        ShopApiClient.Reply reject = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN,
                Map.of("reason", "超过 7 天无理由期限"));
        assertEquals(200, reject.status(), reject.raw());

        JsonNode body = detail(returnNo);
        JsonNode stage = body.get("stage");

        assertEquals("REJECTED", stage.get("code").asText());
        assertEquals("已驳回", stage.get("label").asText());

        String hint = stage.get("hint").asText();
        assertTrue(hint.contains("终止"), hint);
        assertTrue(hint.contains("不会有模拟退款"), hint);
        assertFalse(hint.contains("待收货"), "驳回是终态，不能提示还会收货：" + hint);

        assertEquals("超过 7 天无理由期限", body.get("decision").get("reason").asText());
        assertNotNull(body.get("decision").get("decidedAt"), "决策时间要能看到");
        assertTrue(body.get("progress").isNull(), "驳回没有收货进度");
        assertTrue(body.get("refund").isNull(), "驳回没有退款记录");
    }

    @Test
    @DisplayName("已批准未收货：阶段是「待收货」，hint 带批准数量")
    void waitingReceipt() {
        String returnNo = approvedReturn(2);

        JsonNode body = detail(returnNo);
        JsonNode stage = body.get("stage");

        assertEquals("WAITING_RECEIPT", stage.get("code").asText());
        assertEquals("待收货", stage.get("label").asText());
        assertTrue(stage.get("hint").asText().contains("批准 2 件"), stage.toString());

        assertEquals(0, body.get("progress").get("receivedQty").asInt());
        assertEquals(2, body.get("progress").get("remainingQty").asInt());
    }

    @Test
    @DisplayName("部分收货：阶段是「部分收货」，hint 说明还差几件、损坏件也算收到")
    void partialReceipt() {
        String returnNo = approvedReturn(2);
        assertEquals(201, receive(returnNo, 1, 1, 0, "rc-c1-partial").status());

        JsonNode stage = detail(returnNo).get("stage");

        assertEquals("PARTIAL_RECEIPT", stage.get("code").asText());
        assertEquals("部分收货", stage.get("label").asText());

        String hint = stage.get("hint").asText();
        assertTrue(hint.contains("已收 1 件"), hint);
        assertTrue(hint.contains("还差 1 件"), hint);
        assertTrue(hint.contains("损坏件也计入实收"), hint);
    }

    @Test
    @DisplayName("全部收货但未退款：阶段是「已全部收货，尚未登记模拟退款」，不是「已退款」")
    void receivedButNotRefunded() {
        String returnNo = approvedReturn(2);
        // 1 件合格 + 1 件损坏：累计实收 2，损坏件也算收到
        assertEquals(201, receive(returnNo, 1, 1, 0, "rc-c1-part-1").status());
        assertEquals(201, receive(returnNo, 1, 0, 1, "rc-c1-part-2").status());

        JsonNode body = detail(returnNo);
        JsonNode stage = body.get("stage");

        assertEquals("RECEIVED_NOT_REFUNDED", stage.get("code").asText());
        assertEquals("已全部收货，尚未登记模拟退款", stage.get("label").asText());
        assertFalse(stage.get("label").asText().contains("已退款"), "收满不等于已退款");

        assertEquals("COMPLETE", body.get("progress").get("code").asText());
        assertEquals(2, body.get("progress").get("receivedQty").asInt());
        assertEquals(0, body.get("progress").get("remainingQty").asInt());
        assertTrue(body.get("refund").isNull(), "还没有退款记录");
    }

    @Test
    @DisplayName("已登记模拟退款：阶段是「已登记模拟退款」，带单号与金额，且说明没有资金流动")
    void refunded() {
        String returnNo = approvedReturn(2);
        assertEquals(201, receive(returnNo, 1, 1, 0, "rc-c1-ref-1").status());
        assertEquals(201, receive(returnNo, 1, 0, 1, "rc-c1-ref-2").status());

        ShopApiClient.Reply refund = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/refund", ADMIN, null);
        assertEquals(201, refund.status(), refund.raw());

        JsonNode body = detail(returnNo);
        JsonNode stage = body.get("stage");
        String refundNo = body.get("refund").get("refundNo").asText();

        assertEquals("REFUNDED", stage.get("code").asText());
        assertEquals("已登记模拟退款", stage.get("label").asText());
        assertTrue(stage.get("hint").asText().contains(refundNo), "hint 要能追到退款单号");
        assertTrue(stage.get("hint").asText().contains("没有真实资金流动"), stage.toString());

        assertEquals(UNIT_PRICE * 2, body.get("refund").get("amountCents").asLong());
        assertEquals("¥598.00", body.get("refund").get("amountText").asText());
        assertNotNull(body.get("refund").get("createdAt"), "登记时间要能看到");

        // 退款不改客服结论，也不新增退货单状态
        assertEquals("APPROVED", body.get("status").asText());
    }

    // ---------------------------------------------------------------
    // 列表复用同一个派生阶段
    // ---------------------------------------------------------------

    @Test
    @DisplayName("买家列表与详情用同一套阶段措辞（列表不额外查库）")
    void listSharesSameStage() {
        String returnNo = approvedReturn(2);
        assertEquals(201, receive(returnNo, 2, 1, 1, "rc-c1-list").status());

        ShopApiClient.Reply list = api.get("/shop/aftersale/returns", BUYER);
        assertEquals(200, list.status(), list.raw());

        JsonNode row = list.body().get(0);
        assertEquals(returnNo, row.get("returnNo").asText());
        assertEquals("RECEIVED_NOT_REFUNDED", row.get("stage").get("code").asText());
        assertEquals("已全部收货，尚未登记模拟退款", row.get("stage").get("label").asText());

        JsonNode detailStage = detail(returnNo).get("stage");
        assertEquals(row.get("stage"), detailStage, "列表与详情必须一字不差");
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    private JsonNode detail(String returnNo) {
        ShopApiClient.Reply reply = api.get("/shop/aftersale/returns/" + returnNo, BUYER);
        assertEquals(200, reply.status(), reply.raw());
        return reply.body();
    }

    private String approvedReturn(int quantity) {
        String returnNo = submittedReturn(quantity);
        ShopApiClient.Reply approve = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/approve", ADMIN, null);
        assertEquals(200, approve.status(), approve.raw());
        return returnNo;
    }

    private String submittedReturn(int quantity) {
        ShopTestFixture.createSignedOrder(
                jdbc, ORDER_NO, BUYER, SKU, PRODUCT, "机械键盘 K87", UNIT_PRICE, quantity, null);

        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", BUYER, Map.of(
                "orderNo", ORDER_NO,
                "quantity", quantity,
                "reason", "键盘按键失灵",
                "clientRequestId", "req-c1-" + quantity));
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
