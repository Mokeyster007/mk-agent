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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 客服批准 / 驳回（H2，真实 HTTP，**不依赖模型**）。
 *
 * <p>覆盖的验收点：
 * <ul>
 *   <li>批准保留原额度；驳回释放本单申请数量（1 → 2）；</li>
 *   <li>两种决策都不动可售库存、不写库存流水、不产生退款字段；</li>
 *   <li>重复同向 → 409 {@code ALREADY_APPROVED/ALREADY_REJECTED}，零写入；</li>
 *   <li>相反决策 → 409 {@code DECISION_CONFLICT}，状态与额度都不变；</li>
 *   <li>驳回原因必填；</li>
 *   <li>普通买家不能访问后台接口；客服列表/详情可见买家与决策记录；</li>
 *   <li>买家在自己的详情里看到最新状态与驳回原因。</li>
 * </ul>
 *
 * <p>并发与真库故障注入在 {@code AfterSaleDecisionPostgresTest}：
 * H2 的行锁语义与 PG 不同，"两个事务真的互相阻塞"只能在真库上证明。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AfterSaleDecisionIntegrationTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    private static final String ADMIN = ShopSeedInitializer.ADMIN;

    private static final String SKU = ShopSeedInitializer.SKU_KEYBOARD;

    private static final long PRODUCT = ShopSeedInitializer.PRODUCT_KEYBOARD;

    private static final String ORDER_NO = "SO-AS-B1-0001";

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
    // 批准
    // ---------------------------------------------------------------

    @Test
    @DisplayName("批准：额度保留 1/2，状态已批准，库存与流水不变")
    void approveKeepsQuota() {
        String returnNo = submitOneReturn(2);

        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);
        int movementsBefore = ShopTestFixture.totalMovements(jdbc);

        ShopApiClient.Reply reply = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/approve", ADMIN, null);

        assertEquals(200, reply.status(), reply.raw());
        assertEquals("APPROVED", reply.text("status"));
        assertEquals("已批准", reply.text("statusLabel"));
        assertEquals(BUYER, reply.text("buyerId"), "客服视角要能看出是谁的单");

        JsonNode decision = reply.body().get("decision");
        assertNotNull(decision, reply.raw());
        assertEquals("APPROVED", decision.get("decision").asText());
        assertTrue(decision.get("reason").isNull(), "批准不写原因");
        assertEquals(ADMIN, decision.get("decidedBy").asText(), "决策人来自服务端身份");
        assertNotNull(decision.get("decidedAt").asText());

        assertEquals("APPROVED", ShopTestFixture.returnStatus(jdbc, returnNo));
        assertEquals(1, ShopTestFixture.decisionCount(jdbc, returnNo));
        assertEquals(List.of("SUBMITTED", "APPROVED"),
                ShopTestFixture.eventTypes(jdbc, returnNo));

        // 批准 = 保留额度：那 1 件仍然不能再被申请
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, ORDER_NO));
        assertNull(ShopTestFixture.quotaConsistencyError(jdbc, ORDER_NO));

        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbc, SKU), "B1 不得修改可售库存");
        assertEquals(movementsBefore, ShopTestFixture.totalMovements(jdbc), "B1 不得写库存流水");
    }

    @Test
    @DisplayName("批准后同一订单行的剩余额度仍受限：再申请 2 件被 409 拦住")
    void approvedReturnStillOccupiesQuota() {
        String returnNo = submitOneReturn(2);
        api.post("/shop/admin/aftersale/returns/" + returnNo + "/approve", ADMIN, null);

        ShopApiClient.Reply again = api.post("/shop/aftersale/returns", BUYER, Map.of(
                "orderNo", ORDER_NO,
                "quantity", 2,
                "reason", "再退两件",
                "clientRequestId", "req-b1-approve-again"));

        assertEquals(409, again.status(), again.raw());
        assertEquals("RETURN_QUOTA_EXCEEDED", again.text("code"));
        assertEquals(1, ShopTestFixture.totalReturns(jdbc));
    }

    // ---------------------------------------------------------------
    // 驳回
    // ---------------------------------------------------------------

    @Test
    @DisplayName("驳回：额度 1 → 2，状态已驳回，决策带原因与操作人")
    void rejectReleasesQuota() {
        String returnNo = submitOneReturn(2);

        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);
        int movementsBefore = ShopTestFixture.totalMovements(jdbc);

        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, ORDER_NO), "前置：申请已占 1 件");

        ShopApiClient.Reply reply = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN,
                Map.of("reason", "超过 7 天无理由期限，且商品已使用"));

        assertEquals(200, reply.status(), reply.raw());
        assertEquals("REJECTED", reply.text("status"));
        assertEquals("已驳回", reply.text("statusLabel"));

        JsonNode decision = reply.body().get("decision");
        assertNotNull(decision, reply.raw());
        assertEquals("REJECTED", decision.get("decision").asText());
        assertEquals("超过 7 天无理由期限，且商品已使用", decision.get("reason").asText());
        assertEquals(ADMIN, decision.get("decidedBy").asText());

        assertEquals("REJECTED", ShopTestFixture.returnStatus(jdbc, returnNo));
        assertEquals("超过 7 天无理由期限，且商品已使用",
                ShopTestFixture.rejectReasonOf(jdbc, returnNo));
        assertEquals(List.of("SUBMITTED", "REJECTED"),
                ShopTestFixture.eventTypes(jdbc, returnNo));

        assertEquals(0, ShopTestFixture.occupiedQty(jdbc, ORDER_NO), "驳回必须释放额度");
        assertNull(ShopTestFixture.quotaConsistencyError(jdbc, ORDER_NO));

        // 释放后可退额度回到 2：列表里的额度三个数也要跟着变
        ShopApiClient.Reply orders = api.get("/shop/aftersale/orders", BUYER);
        JsonNode target = findOrder(orders.body(), ORDER_NO);
        assertNotNull(target, orders.raw());
        assertEquals(2, target.get("quota").get("purchased").asInt());
        assertEquals(0, target.get("quota").get("occupied").asInt());
        assertEquals(2, target.get("quota").get("remaining").asInt());
        assertTrue(target.get("returnable").asBoolean());

        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbc, SKU), "B1 不得修改可售库存");
        assertEquals(movementsBefore, ShopTestFixture.totalMovements(jdbc), "B1 不得写库存流水");
    }

    @Test
    @DisplayName("驳回释放的额度可以再次申请：同订单行退满 2 件成功")
    void releasedQuotaCanBeUsedAgain() {
        String returnNo = submitOneReturn(2);
        api.post("/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN,
                Map.of("reason", "凭证不完整"));

        ShopApiClient.Reply again = api.post("/shop/aftersale/returns", BUYER, Map.of(
                "orderNo", ORDER_NO,
                "quantity", 2,
                "reason", "补齐凭证后重新申请",
                "clientRequestId", "req-b1-after-reject"));

        assertEquals(201, again.status(), again.raw());
        assertEquals(2, ShopTestFixture.occupiedQty(jdbc, ORDER_NO));
        assertEquals(2, ShopTestFixture.totalReturns(jdbc));
        assertNull(ShopTestFixture.quotaConsistencyError(jdbc, ORDER_NO));
    }

    @Test
    @DisplayName("驳回原因必填：空原因 → 400，且零写入")
    void rejectRequiresReason() {
        String returnNo = submitOneReturn(2);

        for (String body : new String[]{"", "   "}) {
            ShopApiClient.Reply reply = api.post(
                    "/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN,
                    Map.of("reason", body));

            assertEquals(400, reply.status(), reply.raw());
            assertEquals("INVALID_REJECT_REASON", reply.text("code"));
        }

        ShopApiClient.Reply noBody = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN, null);
        assertEquals(400, noBody.status(), noBody.raw());
        assertEquals("INVALID_REJECT_REASON", noBody.text("code"));

        assertEquals("SUBMITTED", ShopTestFixture.returnStatus(jdbc, returnNo));
        assertEquals(0, ShopTestFixture.decisionCount(jdbc, returnNo));
        assertEquals(1, ShopTestFixture.eventCount(jdbc, returnNo));
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, ORDER_NO), "失败的驳回不得释放额度");
    }

    // ---------------------------------------------------------------
    // 重复与相反决策
    // ---------------------------------------------------------------

    @Test
    @DisplayName("顺序重复驳回：409 ALREADY_REJECTED，只释放一次、只留一份决策")
    void repeatedRejectIsRejected() {
        String returnNo = submitOneReturn(2);

        assertEquals(200, api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN,
                Map.of("reason", "第一次驳回")).status());

        ShopApiClient.Reply second = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN,
                Map.of("reason", "第二次驳回"));

        assertEquals(409, second.status(), second.raw());
        assertEquals("ALREADY_REJECTED", second.text("code"));

        assertEquals(1, ShopTestFixture.decisionCount(jdbc, returnNo), "只能有一份决策");
        assertEquals("第一次驳回", ShopTestFixture.rejectReasonOf(jdbc, returnNo),
                "重复请求不得覆盖原决策原因");
        assertEquals(2, ShopTestFixture.eventCount(jdbc, returnNo), "不得新增事件");
        assertEquals(0, ShopTestFixture.occupiedQty(jdbc, ORDER_NO), "不得二次释放（也不能变负）");
        assertNull(ShopTestFixture.quotaConsistencyError(jdbc, ORDER_NO));

        // 409 之后仍然可以通过详情核对原决策
        ShopApiClient.Reply detail = api.get(
                "/shop/admin/aftersale/returns/" + returnNo, ADMIN);
        assertEquals(200, detail.status(), detail.raw());
        assertEquals("REJECTED", detail.text("status"));
        assertEquals("第一次驳回", detail.body().get("decision").get("reason").asText());
    }

    @Test
    @DisplayName("顺序重复批准：409 ALREADY_APPROVED，额度不变")
    void repeatedApproveIsRejected() {
        String returnNo = submitOneReturn(2);

        assertEquals(200, api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/approve", ADMIN, null).status());

        ShopApiClient.Reply second = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/approve", ADMIN, null);

        assertEquals(409, second.status(), second.raw());
        assertEquals("ALREADY_APPROVED", second.text("code"));
        assertEquals(1, ShopTestFixture.decisionCount(jdbc, returnNo));
        assertEquals(2, ShopTestFixture.eventCount(jdbc, returnNo));
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, ORDER_NO));
    }

    @Test
    @DisplayName("相反决策：批准后驳回 → 409 DECISION_CONFLICT，状态与额度都不变")
    void oppositeDecisionIsRejected() {
        String returnNo = submitOneReturn(2);

        assertEquals(200, api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/approve", ADMIN, null).status());

        ShopApiClient.Reply reject = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN,
                Map.of("reason", "想改成驳回"));

        assertEquals(409, reject.status(), reject.raw());
        assertEquals("DECISION_CONFLICT", reject.text("code"));
        assertEquals("APPROVED", ShopTestFixture.returnStatus(jdbc, returnNo));
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, ORDER_NO), "不得释放已批准的额度");
        assertEquals(1, ShopTestFixture.decisionCount(jdbc, returnNo));
        assertEquals(2, ShopTestFixture.eventCount(jdbc, returnNo));
    }

    @Test
    @DisplayName("不存在的退货单 → 404，不产生任何记录")
    void unknownReturnIsNotFound() {
        ShopApiClient.Reply approve = api.post(
                "/shop/admin/aftersale/returns/RT00000000000000-FFFF/approve", ADMIN, null);
        assertEquals(404, approve.status(), approve.raw());
        assertEquals("RETURN_NOT_FOUND", approve.text("code"));

        ShopApiClient.Reply reject = api.post(
                "/shop/admin/aftersale/returns/RT00000000000000-FFFF/reject", ADMIN,
                Map.of("reason", "不存在"));
        assertEquals(404, reject.status(), reject.raw());
    }

    // ---------------------------------------------------------------
    // 权限
    // ---------------------------------------------------------------

    @Test
    @DisplayName("普通买家不能调用后台接口：403 NOT_ADMIN，且零写入")
    void buyerCannotDecide() {
        String returnNo = submitOneReturn(2);

        ShopApiClient.Reply list = api.get("/shop/admin/aftersale/returns", BUYER);
        assertEquals(403, list.status(), list.raw());
        assertEquals("NOT_ADMIN", list.text("code"));

        ShopApiClient.Reply approve = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/approve", BUYER, null);
        assertEquals(403, approve.status(), approve.raw());

        ShopApiClient.Reply reject = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/reject", BUYER,
                Map.of("reason", "买家自己想驳回"));
        assertEquals(403, reject.status(), reject.raw());

        assertEquals("SUBMITTED", ShopTestFixture.returnStatus(jdbc, returnNo));
        assertEquals(0, ShopTestFixture.decisionCount(jdbc, returnNo));
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, ORDER_NO));
    }

    @Test
    @DisplayName("缺身份 → 401；未开通商城的身份 → 403")
    void identityIsRequired() {
        ShopApiClient.Reply missing = api.get("/shop/admin/aftersale/returns", null);
        assertEquals(401, missing.status(), missing.raw());

        ShopApiClient.Reply unknown = api.get(
                "/shop/admin/aftersale/returns", "not-a-shop-user");
        assertEquals(403, unknown.status(), unknown.raw());
        assertEquals("NOT_ADMIN", unknown.text("code"));
    }

    // ---------------------------------------------------------------
    // 客服视角的读
    // ---------------------------------------------------------------

    @Test
    @DisplayName("客服列表按状态筛选；详情带买家、事件与决策")
    void adminListAndDetail() {
        String pending = submitOneReturn(2);
        String approved = submitAnotherReturn(3, 1, "req-b1-list-02");

        api.post("/shop/admin/aftersale/returns/" + approved + "/approve", ADMIN, null);

        ShopApiClient.Reply all = api.get("/shop/admin/aftersale/returns", ADMIN);
        assertEquals(200, all.status(), all.raw());
        assertEquals(2, all.body().get("total").asLong());
        assertTrue(all.body().get("items").get(0).get("events").isNull(),
                "列表不加载事件流");

        ShopApiClient.Reply submitted = api.get(
                "/shop/admin/aftersale/returns?status=SUBMITTED", ADMIN);
        assertEquals(1, submitted.body().get("total").asLong(), submitted.raw());
        assertEquals(pending, submitted.body().get("items").get(0).get("returnNo").asText());

        ShopApiClient.Reply approvedOnly = api.get(
                "/shop/admin/aftersale/returns?status=APPROVED", ADMIN);
        assertEquals(1, approvedOnly.body().get("total").asLong(), approvedOnly.raw());

        ShopApiClient.Reply badStatus = api.get(
                "/shop/admin/aftersale/returns?status=WHATEVER", ADMIN);
        assertEquals(400, badStatus.status(), badStatus.raw());
        assertEquals("INVALID_STATUS", badStatus.text("code"));

        ShopApiClient.Reply detail = api.get(
                "/shop/admin/aftersale/returns/" + approved, ADMIN);
        assertEquals(200, detail.status(), detail.raw());
        assertEquals(BUYER, detail.text("buyerId"));
        assertEquals(2, detail.body().get("events").size());
        assertEquals("APPROVED", detail.body().get("decision").get("decision").asText());
    }

    // ---------------------------------------------------------------
    // 买家视角
    // ---------------------------------------------------------------

    @Test
    @DisplayName("买家在自己的详情里看到已驳回与驳回原因；别人的单仍然 404")
    void buyerSeesDecisionOnOwnReturn() {
        String returnNo = submitOneReturn(2);
        api.post("/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN,
                Map.of("reason", "商品已拆封使用"));

        ShopApiClient.Reply detail = api.get("/shop/aftersale/returns/" + returnNo, BUYER);
        assertEquals(200, detail.status(), detail.raw());
        assertEquals("REJECTED", detail.text("status"));
        assertEquals("已驳回", detail.text("statusLabel"));
        assertEquals("商品已拆封使用",
                detail.body().get("decision").get("reason").asText());
        assertEquals(ADMIN, detail.body().get("decision").get("decidedBy").asText());

        ShopApiClient.Reply other = api.get(
                "/shop/aftersale/returns/" + returnNo, ShopSeedInitializer.BUYER_TWO);
        assertEquals(404, other.status(), other.raw());
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    /** 造一张买 2 件的已签收订单，并申请退 1 件。 */
    private String submitOneReturn(int purchased) {
        ShopTestFixture.createSignedOrder(
                jdbc, ORDER_NO, BUYER, SKU, PRODUCT, "机械键盘 K87", 29900, purchased, null);
        return createReturn(1, "req-b1-main-0001");
    }

    /** 第二张订单（列表用例要两单）。 */
    private String submitAnotherReturn(int purchased, int quantity, String requestId) {
        ShopTestFixture.createSignedOrder(
                jdbc, ORDER_NO + "-B", BUYER, SKU, PRODUCT, "机械键盘 K87", 29900, purchased, null);
        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", BUYER, Map.of(
                "orderNo", ORDER_NO + "-B",
                "quantity", quantity,
                "reason", "第二单申请",
                "clientRequestId", requestId));
        assertEquals(201, reply.status(), reply.raw());
        return reply.text("returnNo");
    }

    private String createReturn(int quantity, String requestId) {
        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", BUYER, Map.of(
                "orderNo", ORDER_NO,
                "quantity", quantity,
                "reason", "键盘按键失灵",
                "clientRequestId", requestId));
        assertEquals(201, reply.status(), reply.raw());
        String returnNo = reply.text("returnNo");
        assertNotNull(returnNo, reply.raw());
        return returnNo;
    }

    private static JsonNode findOrder(JsonNode array, String orderNo) {
        for (JsonNode node : array) {
            if (orderNo.equals(node.get("orderNo").asText())) {
                return node;
            }
        }
        return null;
    }
}
