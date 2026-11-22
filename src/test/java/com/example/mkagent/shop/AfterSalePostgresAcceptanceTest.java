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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * A1 的真实 PostgreSQL 回归：额度争抢、幂等重放、占用后回滚。
 *
 * <h2>为什么 H2 过了还要再跑一遍</h2>
 * 售后额度的正确性依赖三件只有真 PG 才能如实表现的事：
 * <ol>
 *   <li>{@code SELECT ... FOR UPDATE} 的**行锁等待**（H2 的锁语义不同）；</li>
 *   <li>READ COMMITTED 下 {@code UPDATE ... WHERE reserved_qty + ? <= quantity_total}
 *       被阻塞后**重新判断** WHERE 条件；</li>
 *   <li>唯一索引冲突会让事务进入 aborted，后续语句一律失败——
 *       这决定了"同键并发"能不能优雅退化成重放。</li>
 * </ol>
 *
 * <h2>测试方式</h2>
 * 与 A0-PG 一致：真实端口 + 真实 HTTP，每个请求各自开事务、各自拿连接。
 * 不使用 MockMvc（同一线程、同一事务，并发结论是假的）。
 *
 * <h2>默认构建不执行</h2>
 * 标了 {@code @Tag("pg")}，默认被 {@code mkagent.test.excluded-groups} 排除。
 * 运行方式见 {@code docs/shop-a1.md}。
 */
@Tag("pg")
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(180)
class AfterSalePostgresAcceptanceTest extends AbstractPostgresShopTest {

    private static final String BUYER_A = ShopSeedInitializer.BUYER_ONE;

    private static final String BUYER_B = ShopSeedInitializer.BUYER_TWO;

    private static final String SKU = ShopSeedInitializer.SKU_KEYBOARD;

    private static final long PRODUCT = ShopSeedInitializer.PRODUCT_KEYBOARD;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    /**
     * 故障注入点：写售后事件。
     *
     * <p>选它是因为它位于"额度已经占用、退货单已经插入"之后——
     * 只有在这个位置失败，才能证明回滚把额度一起退了。
     * 生产代码里没有任何故障开关，这里用 spy 打桩，不改被测路径。
     */
    @MockitoSpyBean
    private AfterSaleRepository afterSaleRepository;

    private ShopApiClient api;

    @BeforeEach
    void setUp() throws SQLException {
        assertConnectedToRealPostgres();
        api = new ShopApiClient(port);
        ShopTestFixture.reset(jdbc);
        Mockito.reset(afterSaleRepository);
    }

    // ==================== 连库自证 ====================

    @Test
    @DisplayName("连接自证：数据库产品必须是 PostgreSQL 16，并记录隔离级别")
    void databaseIsRealPostgres() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            String version = scalar(statement, "SELECT version()");
            String versionNum = scalar(statement, "SELECT current_setting('server_version_num')");
            String isolation = scalar(statement, "SHOW transaction_isolation");

            System.out.println("[a1-pg] " + version + " | isolation=" + isolation);

            assertTrue(version.toLowerCase().contains("postgresql"), "必须是 PostgreSQL：" + version);
            assertEquals(160000, Integer.parseInt(versionNum) / 100 * 100,
                    "主版本必须是 16：" + version);
            assertTrue(isolation.toLowerCase().contains("read committed"),
                    "默认隔离级别应为 read committed，实际：" + isolation);
        }
    }

    // ==================== 1. 争抢最后一个名额 ====================

    @Test
    @DisplayName("两个请求争抢最后一个可退名额：只成一单，额度不超卖")
    void lastSlotRaceCreatesExactlyOne() throws Exception {
        // 只买 1 件 → 只有 1 个名额
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO20261122100000-P001", BUYER_A, SKU, PRODUCT,
                "机械键盘 K87", 29900L, 1, LocalDateTime.now());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Future<ShopApiClient.Reply>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String requestId = "req-pg-last-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return api.post("/shop/aftersale/returns", BUYER_A, Map.of(
                            "orderNo", orderNo,
                            "quantity", 1,
                            "reason", "键盘按键失灵",
                            "clientRequestId", requestId));
                }));
            }

            ready.await(10, TimeUnit.SECONDS);
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<ShopApiClient.Reply> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS).status());
            }

            assertEquals(1, statuses.stream().filter(status -> status == 201).count(),
                    "只允许一单成功，实际状态码：" + statuses);
            assertEquals(1, statuses.stream().filter(status -> status == 409).count(),
                    "另一个必须被额度拦住，实际状态码：" + statuses);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, ShopTestFixture.totalReturns(jdbc));
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, orderNo));
        assertEquals(0, ShopTestFixture.totalMovements(jdbc), "A1 不得写库存流水");
    }

    // ==================== 2. 同一提交身份并发 ====================

    @Test
    @DisplayName("同一 clientRequestId 并发提交：两单同号，额度只占一次")
    void sameRequestIdRaceReplaysToOneReturn() throws Exception {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO20261122100000-P002", BUYER_A, SKU, PRODUCT,
                "机械键盘 K87", 29900L, 2, LocalDateTime.now());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        List<String> returnNos = new ArrayList<>();
        try {
            List<Future<ShopApiClient.Reply>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return api.post("/shop/aftersale/returns", BUYER_A, Map.of(
                            "orderNo", orderNo,
                            "quantity", 1,
                            "reason", "键盘按键失灵",
                            "clientRequestId", "req-pg-same-key"));
                }));
            }

            ready.await(10, TimeUnit.SECONDS);
            start.countDown();

            for (Future<ShopApiClient.Reply> future : futures) {
                ShopApiClient.Reply reply = future.get(60, TimeUnit.SECONDS);
                assertEquals(201, reply.status(), "同键重放必须成功，而不是报错：" + reply.raw());
                returnNos.add(reply.text("returnNo"));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(returnNos.get(0), returnNos.get(1), "两次响应必须是同一个退货单号");
        assertEquals(1, ShopTestFixture.totalReturns(jdbc), "只能建一单");
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, orderNo), "额度只能占一次");
    }

    // ==================== 3. 占用之后建单失败：回滚 ====================

    @Test
    @DisplayName("额度占用后写事件失败：额度恢复、无单残留、库存不变")
    void failureAfterQuotaOccupiedRollsBackEverything() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO20261122100000-P003", BUYER_A, SKU, PRODUCT,
                "机械键盘 K87", 29900L, 2, LocalDateTime.now());

        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);
        int movementsBefore = ShopTestFixture.totalMovements(jdbc);

        /*
         * 注入点选在"事件写入"：此时额度已经 UPDATE 成功、退货单已经 INSERT，
         * 是真正"占用后建单失败"的那一刻。
         * 在回调里读一次 reserved_qty，证明扣减确实发生过（否则这条用例证明不了回滚）。
         */
        int[] reservedInsideTx = {-1};
        doAnswer(invocation -> {
            reservedInsideTx[0] = ShopTestFixture.occupiedQty(jdbc, orderNo);
            throw new IllegalStateException("模拟写事件失败（仅测试）");
        }).when(afterSaleRepository).insertEvent(anyLong(), anyString(), anyString(), any());

        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", BUYER_A, Map.of(
                "orderNo", orderNo,
                "quantity", 1,
                "reason", "键盘按键失灵",
                "clientRequestId", "req-pg-rollback-01"));

        assertEquals(500, reply.status(), "故障注入下请求必须失败：" + reply.raw());
        assertEquals(1, reservedInsideTx[0],
                "前置条件：失败前额度确实已被占用（否则这条用例证明不了回滚）");

        assertEquals(0, ShopTestFixture.occupiedQty(jdbc, orderNo), "额度必须恢复");
        assertEquals(0, ShopTestFixture.totalReturns(jdbc), "不得残留退货单");
        assertEquals(0, countIdempotency(), "不得残留幂等记录");
        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbc, SKU), "可售库存不得变化");
        assertEquals(movementsBefore, ShopTestFixture.totalMovements(jdbc), "不得写库存流水");

        // 修好之后同一提交身份可以成功（幂等记录已随事务回滚，不会把失败缓存成结果）
        Mockito.reset(afterSaleRepository);

        ShopApiClient.Reply retried = api.post("/shop/aftersale/returns", BUYER_A, Map.of(
                "orderNo", orderNo,
                "quantity", 1,
                "reason", "键盘按键失灵",
                "clientRequestId", "req-pg-rollback-01"));

        assertEquals(201, retried.status(), retried.raw());
        assertEquals(1, ShopTestFixture.totalReturns(jdbc));
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, orderNo));
    }

    // ==================== 4. 归属隔离 ====================

    @Test
    @DisplayName("B 看不到也退不了 A 的订单：404 且零写入")
    void ownershipIsolatedOnRealDatabase() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO20261122100000-P004", BUYER_A, SKU, PRODUCT,
                "机械键盘 K87", 29900L, 2, LocalDateTime.now());

        ShopApiClient.Reply preview = api.post("/shop/aftersale/preview", BUYER_B,
                Map.of("orderNo", orderNo, "quantity", 1, "reason", "键盘按键失灵"));
        assertEquals(404, preview.status(), preview.raw());

        ShopApiClient.Reply submit = api.post("/shop/aftersale/returns", BUYER_B, Map.of(
                "orderNo", orderNo,
                "quantity", 1,
                "reason", "键盘按键失灵",
                "clientRequestId", "req-pg-owner-01"));
        assertEquals(404, submit.status(), submit.raw());

        assertEquals(0, ShopTestFixture.totalReturns(jdbc));
        assertEquals(0, ShopTestFixture.occupiedQty(jdbc, orderNo));

        ShopApiClient.Reply orders = api.get("/shop/aftersale/orders", BUYER_B);
        assertEquals(200, orders.status(), orders.raw());
        assertEquals(0, orders.body().size(), "B 的可退订单里不应出现 A 的单");
    }

    // ==================== 辅助 ====================

    private int countIdempotency() {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shop_aftersale_idempotency", Integer.class);
        return value == null ? 0 : value;
    }

    private void assertConnectedToRealPostgres() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            String product = scalar(statement, "SELECT current_setting('server_version')");
            assertTrue(product != null && !product.isBlank(), "必须能读到服务端版本");
        }
    }

    private String scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }
}
