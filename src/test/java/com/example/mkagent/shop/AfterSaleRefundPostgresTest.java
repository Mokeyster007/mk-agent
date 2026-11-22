package com.example.mkagent.shop;

import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 模拟退款的真 PostgreSQL 回归：并发重复退款只落一笔。
 *
 * <h2>为什么 H2 过了还要再跑一遍</h2>
 * "一张退货单最多一笔"是靠 {@code uk_shop_aftersale_refund_return} 兜的，
 * 而<b>唯一键冲突在 PG 上会 abort 整个事务</b>——输的那一方拿到的
 * {@code DuplicateKeyException} 必须能被翻译成一句可解释的 409（带上赢家的单号），
 * 而不是把 500 抛给客服。H2 的锁粒度更粗，两个请求往往直接串行，
 * 根本走不到"冲突后回查赢家"这条路径。
 *
 * <p>默认构建不执行（{@code @Tag("pg")}）。
 */
@Tag("pg")
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(180)
class AfterSaleRefundPostgresTest extends AbstractPostgresShopTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    private static final String ADMIN = ShopSeedInitializer.ADMIN;

    private static final String SKU = ShopSeedInitializer.SKU_KEYBOARD;

    private static final long PRODUCT = ShopSeedInitializer.PRODUCT_KEYBOARD;

    /** 成交单价 29900 分，与种子商品一致。 */
    private static final long UNIT_PRICE = 29900L;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private ShopApiClient api;

    @BeforeEach
    void setUp() {
        api = new ShopApiClient(port);
        ShopTestFixture.reset(jdbc);
    }

    // ==================== 1. 并发重复退款 ====================

    /**
     * 客服手抖点两下，或者两个客服同时点：只能有一笔退款。
     *
     * <p>赢家 201；输家 409 {@code REFUND_ALREADY_EXISTS}，且消息里带着赢家的退款单号——
     * 这样客服能立刻知道"不是我点失败，是已经退过了，单号是这个"。
     */
    @Test
    @DisplayName("两个并发退款请求：只落一笔，另一个 409 且指向同一张退款单")
    void concurrentRefundsWriteOnce() throws Exception {
        String returnNo = givenFullyReceivedReturn("SO-B3-PG-REFUND", 2);

        List<ShopApiClient.Reply> replies = runConcurrently(2, index -> refund(returnNo));

        long ok = replies.stream().filter(reply -> reply.status() == 201).count();
        long conflict = replies.stream().filter(reply -> reply.status() == 409).count();
        assertEquals(1, ok, "只能有一笔退款成功：" + statuses(replies));
        assertEquals(1, conflict, "另一个必须被 409 拦住（不能 500）：" + statuses(replies));

        ShopApiClient.Reply winner = replies.stream()
                .filter(reply -> reply.status() == 201)
                .findFirst()
                .orElseThrow();
        ShopApiClient.Reply loser = replies.stream()
                .filter(reply -> reply.status() == 409)
                .findFirst()
                .orElseThrow();

        assertEquals("REFUND_ALREADY_EXISTS", loser.text("code"));
        String refundNo = winner.body().get("refund").get("refundNo").asText();
        assertTrue(loser.text("message").contains(refundNo),
                "409 必须带上已经存在的那张退款单号：" + loser.raw());

        assertEquals(1, ShopTestFixture.refundCount(jdbc, returnNo), "一张退货单最多一笔");
        assertEquals(refundNo, ShopTestFixture.refundNoOf(jdbc, returnNo), "落库的就是赢家那一笔");
        assertEquals(UNIT_PRICE * 2, ShopTestFixture.refundAmountOf(jdbc, returnNo));
    }

    // ==================== 2. 真库上退款不动库存与额度 ====================

    @Test
    @DisplayName("真库上退款不动可售库存、不写流水、不释放额度")
    void refundTouchesNeitherStockNorQuota() {
        String returnNo = givenFullyReceivedReturn("SO-B3-PG-TOUCH", 2);

        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);
        int movementsBefore = ShopTestFixture.totalMovements(jdbc);
        int occupiedBefore = ShopTestFixture.occupiedQty(jdbc, "SO-B3-PG-TOUCH");

        ShopApiClient.Reply reply = refund(returnNo);
        assertEquals(201, reply.status(), reply.raw());

        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbc, SKU), "退款不动可售库存");
        assertEquals(movementsBefore, ShopTestFixture.totalMovements(jdbc), "退款不产生任何流水");
        assertEquals(occupiedBefore, ShopTestFixture.occupiedQty(jdbc, "SO-B3-PG-TOUCH"),
                "退款不释放可退额度");
        assertEquals(2, ShopTestFixture.occupiedQty(jdbc, "SO-B3-PG-TOUCH"), "2 件仍然全部占着");
        assertNull(ShopTestFixture.quotaConsistencyError(jdbc, "SO-B3-PG-TOUCH"));
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    /** 造一张"已批准且全部实收"的退货单（走真实接口，不直接改库）。 */
    private String givenFullyReceivedReturn(String orderNo, int quantity) {
        ShopTestFixture.createSignedOrder(
                jdbc, orderNo, BUYER, SKU, PRODUCT, "机械键盘 K87", UNIT_PRICE, quantity,
                LocalDateTime.now());

        ShopApiClient.Reply submitted = api.post("/shop/aftersale/returns", BUYER, Map.of(
                "orderNo", orderNo,
                "quantity", quantity,
                "reason", "键盘按键失灵",
                "clientRequestId", "req-b3-pg-" + orderNo));
        assertEquals(201, submitted.status(), submitted.raw());
        String returnNo = submitted.text("returnNo");
        assertNotNull(returnNo, submitted.raw());

        ShopApiClient.Reply approved = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/approve", ADMIN, null);
        assertEquals(200, approved.status(), approved.raw());

        Map<String, Object> body = new HashMap<>();
        body.put("receivedQty", quantity);
        body.put("qualifiedQty", quantity);
        body.put("damagedQty", 0);
        body.put("note", null);
        body.put("clientRequestId", "rc-b3-pg-" + orderNo);
        ShopApiClient.Reply received = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/receipts", ADMIN, body);
        assertEquals(201, received.status(), received.raw());

        return returnNo;
    }

    private ShopApiClient.Reply refund(String returnNo) {
        return api.post("/shop/admin/aftersale/returns/" + returnNo + "/refund", ADMIN, null);
    }

    /**
     * 同时发 N 个请求（每个请求各自一个事务、各自一条连接）。
     *
     * <p>先全部就位再一起放行，避免"第一个已经跑完第二个才开始"的假并发。
     */
    private List<ShopApiClient.Reply> runConcurrently(
            int threads,
            ThrowingIntFunction<ShopApiClient.Reply> action
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            List<Future<ShopApiClient.Reply>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return action.apply(index);
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未就绪");
            start.countDown();

            List<ShopApiClient.Reply> replies = new ArrayList<>();
            for (Future<ShopApiClient.Reply> future : futures) {
                replies.add(future.get(120, TimeUnit.SECONDS));
            }
            return replies;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Integer> statuses(List<ShopApiClient.Reply> replies) {
        return replies.stream().map(ShopApiClient.Reply::status).toList();
    }

    @FunctionalInterface
    private interface ThrowingIntFunction<T> {
        T apply(int index) throws Exception;
    }
}
