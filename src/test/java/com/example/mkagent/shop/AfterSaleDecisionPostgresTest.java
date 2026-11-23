package com.example.mkagent.shop;

import com.example.mkagent.shop.aftersale.AfterSaleRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * B1 的真实 PostgreSQL 回归：并发决策、驳回与申请竞争、释放后失败回滚。
 *
 * <h2>为什么 H2 过了还要再跑一遍</h2>
 * 这三类结论只有真 PG 才能如实表现：
 * <ol>
 *   <li>{@code SELECT ... FOR UPDATE} 的**行锁等待**——H2 的锁语义不同，
 *       在 H2 上"两个事务互相阻塞"经常退化成串行执行，看起来永远是对的；</li>
 *   <li>READ COMMITTED 下被阻塞的 {@code UPDATE ... WHERE} 会**重新判断**条件；</li>
 *   <li>条件更新 0 行之后事务仍然可用（不 abort），所以"输了就退出去读详情"成立。</li>
 * </ol>
 *
 * <p>测试方式与 A1-PG 一致：真实端口 + 真实 HTTP，每个请求各自开事务、各自拿连接。
 * 不使用 MockMvc（同一线程、同一事务，并发结论是假的）。
 *
 * <p>默认构建不执行（{@code @Tag("pg")}，被 {@code mkagent.test.excluded-groups} 排除）。
 */
@Tag("pg")
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(180)
class AfterSaleDecisionPostgresTest extends AbstractPostgresShopTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    private static final String ADMIN = ShopSeedInitializer.ADMIN;

    private static final String SKU = ShopSeedInitializer.SKU_KEYBOARD;

    private static final long PRODUCT = ShopSeedInitializer.PRODUCT_KEYBOARD;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 故障注入点：写决策记录。
     *
     * <p>选它是因为它位于"状态已改、额度已释放"之后——
     * 只有在这个位置失败，才能证明回滚把状态与额度一起退回去了。
     * 生产代码里没有任何故障开关，这里用 spy 打桩，不改被测路径。
     */
    @MockitoSpyBean
    private AfterSaleRepository afterSaleRepository;

    private ShopApiClient api;

    @BeforeEach
    void setUp() {
        api = new ShopApiClient(port);
        ShopTestFixture.reset(jdbc);
        Mockito.reset(afterSaleRepository);
    }

    // ==================== 1. 两个并发驳回 ====================

    @Test
    @DisplayName("两个并发驳回：只释放一次、只留一份有效决策")
    void concurrentRejectsReleaseOnce() throws Exception {
        String orderNo = "SO-B1-PG-REJ";
        String returnNo = givenReturn(orderNo, 2, 1, "req-b1-pg-rej-01");

        List<ShopApiClient.Reply> replies = runConcurrently(2, () -> api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN,
                Map.of("reason", "并发驳回")));

        long ok = replies.stream().filter(reply -> reply.status() == 200).count();
        long conflict = replies.stream().filter(reply -> reply.status() == 409).count();
        assertEquals(1, ok, "只能有一个驳回成功：" + statuses(replies));
        assertEquals(1, conflict, "另一个必须是 409：" + statuses(replies));

        String conflictCode = replies.stream()
                .filter(reply -> reply.status() == 409)
                .findFirst()
                .map(reply -> reply.text("code"))
                .orElse(null);
        assertEquals("ALREADY_REJECTED", conflictCode);

        assertEquals("REJECTED", ShopTestFixture.returnStatus(jdbc, returnNo));
        assertEquals(1, ShopTestFixture.decisionCount(jdbc, returnNo), "只能有一份决策");
        assertEquals(2, ShopTestFixture.eventCount(jdbc, returnNo), "不得新增第二条决策事件");
        assertEquals(0, ShopTestFixture.occupiedQty(jdbc, orderNo), "只能释放一次");
        assertNull(ShopTestFixture.quotaConsistencyError(jdbc, orderNo));
        assertEquals(0, ShopTestFixture.totalMovements(jdbc), "B1 不得写库存流水");
    }

    // ==================== 2. 批准与驳回并发 ====================

    @Test
    @DisplayName("同单批准与驳回并发：只有一个终态、一份决策；批准赢不释放，驳回赢只释放一次")
    void concurrentApproveAndRejectHaveSingleWinner() throws Exception {
        String orderNo = "SO-B1-PG-MIX";
        String returnNo = givenReturn(orderNo, 2, 1, "req-b1-pg-mix-01");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        List<ShopApiClient.Reply> replies = new ArrayList<>();
        try {
            Future<ShopApiClient.Reply> approve = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return api.post("/shop/admin/aftersale/returns/" + returnNo + "/approve",
                        ADMIN, null);
            });
            Future<ShopApiClient.Reply> reject = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return api.post("/shop/admin/aftersale/returns/" + returnNo + "/reject",
                        ADMIN, Map.of("reason", "并发驳回"));
            });

            ready.await(10, TimeUnit.SECONDS);
            start.countDown();

            replies.add(approve.get(60, TimeUnit.SECONDS));
            replies.add(reject.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, replies.stream().filter(reply -> reply.status() == 200).count(),
                "只能有一个终态：" + statuses(replies));
        assertEquals(1, replies.stream().filter(reply -> reply.status() == 409).count(),
                "另一个必须被明确拒绝：" + statuses(replies));

        String status = ShopTestFixture.returnStatus(jdbc, returnNo);
        assertEquals(1, ShopTestFixture.decisionCount(jdbc, returnNo), "只能有一份决策");
        assertEquals(2, ShopTestFixture.eventCount(jdbc, returnNo));

        if ("APPROVED".equals(status)) {
            assertEquals(1, ShopTestFixture.occupiedQty(jdbc, orderNo),
                    "批准赢：额度必须保留");
            assertEquals("APPROVED", ShopTestFixture.decisionOf(jdbc, returnNo));
        } else {
            assertEquals("REJECTED", status, "终态只能是批准或驳回");
            assertEquals(0, ShopTestFixture.occupiedQty(jdbc, orderNo),
                    "驳回赢：额度必须释放且只释放一次");
            assertEquals("REJECTED", ShopTestFixture.decisionOf(jdbc, returnNo));
        }
        assertNull(ShopTestFixture.quotaConsistencyError(jdbc, orderNo));
    }

    // ==================== 3. 驳回与同订单行新申请并发 ====================

    @Test
    @DisplayName("驳回与同订单行新申请并发：额度一致、不为负、不超过已购")
    void rejectRacesWithNewApplication() throws Exception {
        String orderNo = "SO-B1-PG-RACE";
        String returnNo = givenReturn(orderNo, 2, 1, "req-b1-pg-race-01");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        List<ShopApiClient.Reply> replies = new ArrayList<>();
        try {
            Future<ShopApiClient.Reply> reject = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return api.post("/shop/admin/aftersale/returns/" + returnNo + "/reject",
                        ADMIN, Map.of("reason", "并发驳回"));
            });
            Future<ShopApiClient.Reply> create = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return api.post("/shop/aftersale/returns", BUYER, Map.of(
                        "orderNo", orderNo,
                        "quantity", 2,
                        "reason", "并发重新申请",
                        "clientRequestId", "req-b1-pg-race-02"));
            });

            ready.await(10, TimeUnit.SECONDS);
            start.countDown();

            replies.add(reject.get(60, TimeUnit.SECONDS));
            replies.add(create.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        ShopApiClient.Reply rejectReply = replies.get(0);
        ShopApiClient.Reply createReply = replies.get(1);

        assertEquals(200, rejectReply.status(), "驳回本身不该失败：" + rejectReply.raw());
        /*
         * 新申请允许成功也允许被 409 拦住——结果取决于谁先拿到订单行锁：
         *   驳回先 → 释放 1 件，剩 2 件可退 → 申请 2 件成功；
         *   申请先 → 只剩 1 件可退 → 409 RETURN_QUOTA_EXCEEDED。
         * 两种都对，**不变式**才是断言对象。
         */
        assertTrue(createReply.status() == 201 || createReply.status() == 409,
                "新申请只能是成功或被额度拦住：" + createReply.raw());
        if (createReply.status() == 409) {
            assertEquals("RETURN_QUOTA_EXCEEDED", createReply.text("code"), createReply.raw());
        }

        int occupied = ShopTestFixture.occupiedQty(jdbc, orderNo);
        assertTrue(occupied >= 0, "额度不得为负：" + occupied);
        assertTrue(occupied <= 2, "额度不得超过已购 2 件：" + occupied);
        assertNull(ShopTestFixture.quotaConsistencyError(jdbc, orderNo),
                "占用额度必须等于未被驳回的申请数量之和");
        assertEquals(0, ShopTestFixture.totalMovements(jdbc), "B1 不得写库存流水");
    }

    // ==================== 4. 额度已释放后失败：整笔回滚 ====================

    @Test
    @DisplayName("额度释放后写决策失败：额度、状态、决策、事件全部回到操作前")
    void failureAfterReleaseRollsBackEverything() {
        String orderNo = "SO-B1-PG-ROLLBACK";
        String returnNo = givenReturn(orderNo, 2, 1, "req-b1-pg-rb-01");

        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);
        int movementsBefore = ShopTestFixture.totalMovements(jdbc);
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, orderNo), "前置：申请已占 1 件");

        /*
         * 注入点在 insertDecision：此时状态已改成 REJECTED、额度已经减掉，
         * 是真正"释放之后失败"的那一刻。
         * 在回调里读一次 reserved_qty，证明释放确实发生过——
         * 否则这条用例证明不了回滚（可能本来就没减）。
         */
        int[] reservedInsideTx = {-1};
        doAnswer(invocation -> {
            reservedInsideTx[0] = ShopTestFixture.occupiedQty(jdbc, orderNo);
            throw new IllegalStateException("模拟写决策记录失败（仅测试）");
        }).when(afterSaleRepository).insertDecision(anyLong(), any(), any(), any());

        ShopApiClient.Reply reply = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN,
                Map.of("reason", "回滚用例"));

        assertEquals(500, reply.status(), "故障注入下请求必须失败：" + reply.raw());
        assertEquals(0, reservedInsideTx[0],
                "前置条件：失败前额度确实已被释放（否则这条用例证明不了回滚）");

        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, orderNo), "额度必须恢复");
        assertEquals("SUBMITTED", ShopTestFixture.returnStatus(jdbc, returnNo), "状态必须回到已提交");
        assertEquals(0, ShopTestFixture.decisionCount(jdbc, returnNo), "不得残留决策记录");
        assertEquals(1, ShopTestFixture.eventCount(jdbc, returnNo), "不得残留驳回事件");
        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbc, SKU), "可售库存不得变化");
        assertEquals(movementsBefore, ShopTestFixture.totalMovements(jdbc), "不得写库存流水");

        // 修好之后可以正常驳回，且只释放一次
        Mockito.reset(afterSaleRepository);

        ShopApiClient.Reply retried = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN,
                Map.of("reason", "回滚用例"));
        assertEquals(200, retried.status(), retried.raw());
        assertEquals(0, ShopTestFixture.occupiedQty(jdbc, orderNo));
        assertEquals(1, ShopTestFixture.decisionCount(jdbc, returnNo));
        assertNull(ShopTestFixture.quotaConsistencyError(jdbc, orderNo));
    }

    // ==================== 5. 真库上的顺序重复驳回 ====================

    @Test
    @DisplayName("真库顺序重复驳回：第二次 409，额度不变、事件不增")
    void sequentialRejectOnRealDatabase() {
        String orderNo = "SO-B1-PG-SEQ";
        String returnNo = givenReturn(orderNo, 2, 1, "req-b1-pg-seq-01");

        assertEquals(200, api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN,
                Map.of("reason", "第一次")).status());

        ShopApiClient.Reply second = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/reject", ADMIN,
                Map.of("reason", "第二次"));

        assertEquals(409, second.status(), second.raw());
        assertEquals("ALREADY_REJECTED", second.text("code"));
        assertEquals(0, ShopTestFixture.occupiedQty(jdbc, orderNo));
        assertEquals(1, ShopTestFixture.decisionCount(jdbc, returnNo));
        assertEquals(2, ShopTestFixture.eventCount(jdbc, returnNo));
        assertEquals("第一次", ShopTestFixture.rejectReasonOf(jdbc, returnNo));
    }

    // ==================== 辅助 ====================

    /** 造一张已签收订单并提交一张退货单，返回退货单号。 */
    private String givenReturn(String orderNo, int purchased, int quantity, String requestId) {
        ShopTestFixture.createSignedOrder(
                jdbc, orderNo, BUYER, SKU, PRODUCT, "机械键盘 K87", 29900L, purchased,
                LocalDateTime.now());

        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", BUYER, Map.of(
                "orderNo", orderNo,
                "quantity", quantity,
                "reason", "键盘按键失灵",
                "clientRequestId", requestId));

        assertEquals(201, reply.status(), reply.raw());
        String returnNo = reply.text("returnNo");
        assertNotNull(returnNo, reply.raw());
        assertEquals(quantity, ShopTestFixture.occupiedQty(jdbc, orderNo), "前置：额度已占用");
        return returnNo;
    }

    private List<ShopApiClient.Reply> runConcurrently(
            int threads,
            ThrowingSupplier<ShopApiClient.Reply> action
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            List<Future<ShopApiClient.Reply>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return action.get();
                }));
            }

            ready.await(10, TimeUnit.SECONDS);
            start.countDown();

            List<ShopApiClient.Reply> replies = new ArrayList<>();
            for (Future<ShopApiClient.Reply> future : futures) {
                replies.add(future.get(60, TimeUnit.SECONDS));
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
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
