package com.example.mkagent.shop;

import com.example.mkagent.shop.inventory.InventoryRepository;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * B2 的真实 PostgreSQL 回归：并发收货不超收、同键并发只入一次、写入失败整笔回滚。
 *
 * <h2>为什么 H2 过了还要再跑一遍</h2>
 * <ol>
 *   <li>{@code SELECT ... FOR UPDATE} 的**行锁等待**：H2 上经常退化成串行，
 *       看起来"永远不会超收"，其实是因为两个请求根本没同时跑；</li>
 *   <li>READ COMMITTED 下"锁之后重读累计实收"才能看到另一个事务已提交的行；</li>
 *   <li>唯一键冲突在 PG 上会 **abort 整个事务**，
 *       所以"同键并发输了就回滚、再换新事务回放"这条路径只有真库能验证。</li>
 * </ol>
 *
 * <p>默认构建不执行（{@code @Tag("pg")}）。
 */
@Tag("pg")
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(180)
class AfterSaleReceiptPostgresTest extends AbstractPostgresShopTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    private static final String ADMIN = ShopSeedInitializer.ADMIN;

    private static final String SKU = ShopSeedInitializer.SKU_KEYBOARD;

    private static final long PRODUCT = ShopSeedInitializer.PRODUCT_KEYBOARD;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 故障注入点：写库存流水。
     *
     * <p>它位于"可售库存已经加上"之后——只有在这个位置失败，
     * 才能证明回滚把库存和收货记录一起退回去了，
     * 而不是"库存根本没加成功"。
     */
    @MockitoSpyBean
    private InventoryRepository inventoryRepository;

    private ShopApiClient api;

    @BeforeEach
    void setUp() {
        api = new ShopApiClient(port);
        ShopTestFixture.reset(jdbc);
        Mockito.reset(inventoryRepository);
    }

    // ==================== 1. 争最后一件 ====================

    @Test
    @DisplayName("两个并发收货争最后一件：只有一个成功，累计不超批准数量")
    void concurrentReceiptsCannotOverReceive() throws Exception {
        String returnNo = givenApprovedReturn("SO-B2-PG-ONE", 1);
        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);

        List<ShopApiClient.Reply> replies = runConcurrently(2, index -> receive(
                returnNo, 1, 1, 0, "rc-pg-race-" + index));

        long ok = replies.stream().filter(reply -> reply.status() == 201).count();
        long conflict = replies.stream().filter(reply -> reply.status() == 409).count();
        assertEquals(1, ok, "只能有一批收货成功：" + statuses(replies));
        assertEquals(1, conflict, "另一批必须被 409 拦住：" + statuses(replies));

        String code = replies.stream()
                .filter(reply -> reply.status() == 409)
                .findFirst()
                .map(reply -> reply.text("code"))
                .orElse(null);
        assertEquals("RECEIPT_OVER_APPROVED", code);

        assertEquals(1, ShopTestFixture.receiptCount(jdbc, returnNo));
        assertEquals(1, ShopTestFixture.receivedQtyOf(jdbc, returnNo), "累计实收不得超过批准数量");
        assertEquals(stockBefore + 1, ShopTestFixture.stockOf(jdbc, SKU), "只能加一件");
        assertEquals(1, ShopTestFixture.movementCountOfType(jdbc, SKU, "RETURN_INBOUND"));
    }

    // ==================== 2. 同键并发 ====================

    @Test
    @DisplayName("同键并发收货：只落一条记录、只加一次库存")
    void concurrentSameKeyWritesOnce() throws Exception {
        String returnNo = givenApprovedReturn("SO-B2-PG-KEY", 2);
        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);

        List<ShopApiClient.Reply> replies = runConcurrently(2,
                index -> receive(returnNo, 1, 1, 0, "rc-pg-same-key"));

        // 一个真写、一个命中幂等键回放：都应当看到"收货成功"
        assertEquals(List.of(201, 201), statuses(replies),
                "同键重试对调用方必须表现为成功：" + statuses(replies));

        String first = replies.get(0).body().get("receipts").get(0).get("receiptNo").asText();
        String second = replies.get(1).body().get("receipts").get(0).get("receiptNo").asText();
        assertEquals(first, second, "两次响应必须指向同一条收货记录");

        assertEquals(1, ShopTestFixture.receiptCount(jdbc, returnNo));
        assertEquals(stockBefore + 1, ShopTestFixture.stockOf(jdbc, SKU), "库存只能加一次");
        assertEquals(1, ShopTestFixture.movementCountOfType(jdbc, SKU, "RETURN_INBOUND"));
    }

    // ==================== 3. 两批都在上限内 ====================

    @Test
    @DisplayName("两个并发收货都在上限内：各自入库，互不覆盖")
    void concurrentBatchesWithinLimitBothSucceed() throws Exception {
        String returnNo = givenApprovedReturn("SO-B2-PG-TWO", 2);
        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);

        List<ShopApiClient.Reply> replies = runConcurrently(2, index -> receive(
                returnNo, 1, 1, 0, "rc-pg-two-" + index));

        assertEquals(List.of(201, 201), statuses(replies), statuses(replies).toString());
        assertEquals(2, ShopTestFixture.receiptCount(jdbc, returnNo));
        assertEquals(2, ShopTestFixture.receivedQtyOf(jdbc, returnNo));
        assertEquals(stockBefore + 2, ShopTestFixture.stockOf(jdbc, SKU));
        assertEquals(2, ShopTestFixture.movementCountOfType(jdbc, SKU, "RETURN_INBOUND"),
                "同一订单行的两批退货入库必须是两条流水");
        assertNull(ShopTestFixture.quotaConsistencyError(jdbc, "SO-B2-PG-TWO"));
    }

    // ==================== 4. 库存已加后写入失败 ====================

    @Test
    @DisplayName("库存已加后写流水失败：收货记录与库存一起回滚")
    void failureAfterInboundRollsBackEverything() {
        String returnNo = givenApprovedReturn("SO-B2-PG-ROLLBACK", 2);
        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);

        AtomicInteger stockInsideTx = new AtomicInteger(-1);
        doAnswer(invocation -> {
            String type = invocation.getArgument(2);
            if ("RETURN_INBOUND".equals(type)) {
                // 回调里先读一次：证明"库存确实已经加上了"，然后才失败
                stockInsideTx.set(ShopTestFixture.stockOf(jdbc, SKU));
                throw new IllegalStateException("injected: 写退货入库流水失败（仅测试注入）");
            }
            return invocation.callRealMethod();
        }).when(inventoryRepository).insertMovement(
                anyString(), anyInt(), anyString(), any(), any(), any(),
                anyString(), anyString(), anyString());

        ShopApiClient.Reply failed = receive(returnNo, 1, 1, 0, "rc-pg-rollback");

        assertEquals(500, failed.status(), "写流水失败应表现为 500（未预期异常）：" + failed.raw());
        assertEquals(stockBefore + 1, stockInsideTx.get(),
                "注入点之前库存必须真的加过，否则测的是'没加成功'而不是回滚");

        assertEquals(0, ShopTestFixture.receiptCount(jdbc, returnNo), "收货记录必须回滚");
        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbc, SKU), "库存必须回滚");
        assertEquals(0, ShopTestFixture.movementCountOfType(jdbc, SKU, "RETURN_INBOUND"));

        // 撤掉注入，再收一次：证明回滚没有留下"事务已中止"之类的半截状态
        Mockito.reset(inventoryRepository);

        assertEquals(201, receive(returnNo, 1, 1, 0, "rc-pg-rollback-retry").status());
        assertEquals(1, ShopTestFixture.receiptCount(jdbc, returnNo));
        assertEquals(stockBefore + 1, ShopTestFixture.stockOf(jdbc, SKU));
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    /** 造一张已批准的退货单（走真实接口，不直接改库）。 */
    private String givenApprovedReturn(String orderNo, int quantity) {
        ShopTestFixture.createSignedOrder(
                jdbc, orderNo, BUYER, SKU, PRODUCT, "机械键盘 K87", 29900L, quantity,
                LocalDateTime.now());

        ShopApiClient.Reply submitted = api.post("/shop/aftersale/returns", BUYER, Map.of(
                "orderNo", orderNo,
                "quantity", quantity,
                "reason", "键盘按键失灵",
                "clientRequestId", "req-b2-pg-" + orderNo));
        assertEquals(201, submitted.status(), submitted.raw());
        String returnNo = submitted.text("returnNo");
        assertNotNull(returnNo, submitted.raw());

        ShopApiClient.Reply approved = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/approve", ADMIN, null);
        assertEquals(200, approved.status(), approved.raw());
        assertEquals("APPROVED", ShopTestFixture.returnStatus(jdbc, returnNo));
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
