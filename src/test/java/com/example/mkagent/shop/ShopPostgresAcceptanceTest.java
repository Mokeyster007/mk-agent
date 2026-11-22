package com.example.mkagent.shop;

import com.example.mkagent.shop.inventory.InventoryRepository;
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
import com.example.mkagent.support.HermeticSpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * A0 的真实 PostgreSQL 回归：A / B / C / D / E 五个场景。
 *
 * <h2>为什么必须在真 PG 上再跑一遍</h2>
 * H2（即使 {@code MODE=PostgreSQL}）与 PG 在以下三点上行为不同，
 * 而 A0 的发货正确性恰好依赖这三点：
 * <ol>
 *   <li>{@code SELECT ... FOR UPDATE} 的行锁等待与释放时机；</li>
 *   <li>{@code UPDATE ... WHERE available >= ?} 在 READ COMMITTED 下
 *       被阻塞后**重新判断** WHERE 的行为；</li>
 *   <li>约束冲突会让事务进入 aborted，后续语句一律失败（H2 不会）。</li>
 * </ol>
 *
 * <h2>测试方式</h2>
 * 与 H2 版一致：真实端口（{@code RANDOM_PORT}）+ {@link ShopApiClient} 发真实 HTTP，
 * 每个请求各自开事务、各自拿连接。不用 MockMvc——它共用一个线程和事务，
 * 并发场景测出来的"通过"是假的。
 *
 * <h2>默认构建不执行</h2>
 * 标了 {@code @Tag("pg")}，而 pom 的 {@code mkagent.test.excluded-groups} 默认含 {@code pg}。
 * 运行方式见 {@code docs/shop-a0-pg.md}。
 */
@Tag("pg")
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(180)
class ShopPostgresAcceptanceTest extends AbstractPostgresShopTest {

    private static final String BUYER_A = ShopSeedInitializer.BUYER_ONE;

    private static final String BUYER_B = ShopSeedInitializer.BUYER_TWO;

    private static final String ADMIN = ShopSeedInitializer.ADMIN;

    private static final String SKU = ShopSeedInitializer.SKU_KEYBOARD;

    private static final long PRODUCT = ShopSeedInitializer.PRODUCT_KEYBOARD;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    /**
     * 故障注入点：库存仓库的"写流水"。
     *
     * <p>用 spy 而不是替换整个 Bean：未打桩的方法照常走真实实现，
     * 事务边界仍在 {@code ShopOrderService#ship} 上，不会把被测路径改掉。
     * 生产代码里**没有**任何故障开关。
     */
    @MockitoSpyBean
    private InventoryRepository inventoryRepository;

    private ShopApiClient api;

    @BeforeEach
    void setUp() throws SQLException {
        assertConnectedToRealPostgres();
        api = new ShopApiClient(port);
        ShopTestFixture.reset(jdbc);
        // 上一个用例注入的异常不能带到下一个用例
        Mockito.reset(inventoryRepository);
    }

    // ==================== 连库自证 ====================

    @Test
    @DisplayName("连接自证：数据库产品必须是 PostgreSQL 16，并记录版本与隔离级别")
    void databaseIsRealPostgres() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            String version = scalar(statement, "SELECT version()");
            String versionNum = scalar(statement, "SELECT current_setting('server_version_num')");
            String defaultIsolation = scalar(statement, "SHOW default_transaction_isolation");

            System.out.println("[pg-evidence] product="
                    + connection.getMetaData().getDatabaseProductName()
                    + " version=" + version
                    + " server_version_num=" + versionNum
                    + " default_isolation=" + defaultIsolation
                    + " image=" + POSTGRES_IMAGE);

            assertTrue(version.startsWith("PostgreSQL 16"),
                    "本轮目标是 PostgreSQL 16，实际：" + version);
            assertEquals("read committed", defaultIsolation.toLowerCase(),
                    "A0 的并发正确性建立在 READ COMMITTED 上，"
                            + "换隔离级别需要重新评估条件更新的语义");
        }
    }

    // ==================== A. 主流程 ====================

    @Test
    @DisplayName("A 主流程：下单不扣库存 → 发货扣到 8 且一笔 -2 流水 → 签收不变")
    void mainFlow() {
        assertEquals(ShopSeedInitializer.KEYBOARD_STOCK, stock(),
                "前置：种子库存应为 10");

        String orderNo = placeOrder(BUYER_A, 2);

        assertEquals(10, stock(), "下单后库存必须仍是 10（不预占、不扣减）");
        assertEquals(0, movementCount(orderNo), "下单不得产生流水");

        ShopApiClient.Reply shipped = api.post(shipUrl(orderNo), ADMIN, null);
        assertEquals(200, shipped.status(), shipped.raw());
        assertTrue(shipped.applied(), "首次发货必须真正应用");

        assertEquals(8, stock(), "发货后库存应为 8");
        assertEquals(1, movementCount(orderNo), "必须恰好一笔出库流水");

        Map<String, Object> movement = jdbc.queryForMap("""
                SELECT m.quantity_delta, m.movement_type, m.order_item_id, m.order_id
                  FROM shop_inventory_movement m
                  JOIN shop_order o ON o.id = m.order_id
                 WHERE o.order_no = ?
                """, orderNo);
        assertEquals(-2, ((Number) movement.get("quantity_delta")).intValue(),
                "出库数量必须为 -2");
        assertEquals("OUTBOUND_SHIPMENT", movement.get("movement_type"));
        assertNotNull(movement.get("order_item_id"), "流水必须能追溯到订单行");

        ShopApiClient.Reply signed = api.post(signUrl(orderNo), ADMIN, null);
        assertEquals(200, signed.status(), signed.raw());
        assertTrue(signed.applied());

        assertEquals(8, stock(), "签收不得改变库存");
        assertEquals(1, movementCount(orderNo), "签收不得新增流水");
        assertEquals("SIGNED", statusOf(orderNo));

        Timestamp signedAt = jdbc.queryForObject(
                "SELECT signed_at FROM shop_order WHERE order_no = ?", Timestamp.class, orderNo);
        assertNotNull(signedAt, "签收时间必须已持久化（A1 的售后起算点）");

        // 刷新（重新 GET）读到的必须是库里的状态，不是内存态
        ShopApiClient.Reply detail = api.get("/shop/orders/" + orderNo, BUYER_A);
        assertEquals(200, detail.status(), detail.raw());
        assertEquals("SIGNED", detail.text("status"));
        assertNotNull(detail.text("signedAt"));
        assertEquals(1, detail.body().get("movements").size());
    }

    // ==================== B. 重复发货 ====================

    @Test
    @DisplayName("B1 顺序重复发货：第二次 applied=false，库存与流水都不变")
    void sequentialRetryAppliesOnce() {
        String orderNo = placeOrder(BUYER_A, 2);

        ShopApiClient.Reply first = api.post(shipUrl(orderNo), ADMIN, null);
        assertEquals(200, first.status(), first.raw());
        assertTrue(first.applied());

        ShopApiClient.Reply second = api.post(shipUrl(orderNo), ADMIN, null);
        assertEquals(200, second.status(), second.raw());
        assertFalse(second.applied(), "重复发货必须 applied=false");

        assertEquals(8, stock(), "只能扣一次");
        assertEquals(1, movementCount(orderNo), "只能写一笔流水");
        assertEquals("SHIPPED", statusOf(orderNo));
    }

    @Test
    @DisplayName("B2 同单并发发货：两个请求都 200，但只有一个真正应用")
    void concurrentShipAppliesOnce() throws Exception {
        String orderNo = placeOrder(BUYER_A, 2);

        List<ShopApiClient.Reply> replies = fireTogether(
                2, () -> api.post(shipUrl(orderNo), ADMIN, null));

        replies.forEach(reply -> assertEquals(200, reply.status(), reply.raw()));
        assertEquals(1, replies.stream().filter(ShopApiClient.Reply::applied).count(),
                "两个并发发货请求里，最多只能有一个真正应用");

        System.out.println("[pg-evidence] 同单并发发货："
                + replies.stream()
                .map(r -> "status=" + r.status() + " applied=" + r.applied())
                .toList()
                + " 最终库存=" + stock() + " 流水=" + movementCount(orderNo));
        assertEquals(8, stock(), "并发重复发货只能扣一次");
        assertEquals(1, movementCount(orderNo), "并发重复发货只能写一笔流水");
        assertEquals("SHIPPED", statusOf(orderNo));
    }

    @Test
    @DisplayName("B3 受控锁等待：订单行被外部事务锁住时，发货请求确实阻塞，释放后才生效")
    void shipWaitsForOrderRowLock() throws Exception {
        String orderNo = placeOrder(BUYER_A, 2);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM shop_order WHERE order_no = ? FOR UPDATE")) {
                lock.setString(1, orderNo);
                lock.executeQuery();
            }

            Future<ShopApiClient.Reply> pending =
                    pool.submit(() -> api.post(shipUrl(orderNo), ADMIN, null));

            /*
             * 这里断言的是"真的在等锁"，不是"两个请求同时发出去"。
             * 500ms 远大于一次正常发货的耗时（毫秒级），
             * 若请求已完成，说明 FOR UPDATE 没有起到串行化作用。
             */
            Thread.sleep(500);
            assertFalse(pending.isDone(), "发货必须阻塞在订单行锁上");
            assertEquals(10, stock(), "阻塞期间不得发生扣减");

            long releasedAt = System.currentTimeMillis();
            holder.commit();

            ShopApiClient.Reply reply = pending.get(30, TimeUnit.SECONDS);
            long waited = System.currentTimeMillis() - releasedAt;
            System.out.println("[pg-evidence] rowLock 发货在锁上等待 ≥500ms（阻塞被观测到），"
                    + "释放后 " + waited + "ms 完成，status=" + reply.status()
                    + " applied=" + reply.applied());

            assertEquals(200, reply.status(), reply.raw());
            assertTrue(reply.applied(), "锁释放后发货应当成功");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(8, stock());
        assertEquals(1, movementCount(orderNo));
    }

    // ==================== C. 不同订单抢同一 SKU ====================

    @Test
    @DisplayName("C 并发争抢：库存 3、两单各要 2，只有一单成功，另一单库存不足且留在待发货")
    void twoOrdersCompetingForLastUnits() throws Exception {
        ShopTestFixture.setStock(jdbc, SKU, PRODUCT, 3);

        String orderA = placeOrder(BUYER_A, 2);
        String orderB = placeOrder(BUYER_B, 2);

        List<ShopApiClient.Reply> replies = fireTogether(2, List.of(
                () -> api.post(shipUrl(orderA), ADMIN, null),
                () -> api.post(shipUrl(orderB), ADMIN, null)));

        long applied = replies.stream().filter(ShopApiClient.Reply::applied).count();
        long insufficient = replies.stream()
                .filter(reply -> reply.status() == 409)
                .filter(reply -> "INSUFFICIENT_STOCK".equals(reply.text("code")))
                .count();

        assertEquals(1, applied, "最多且必须有一单发货成功：" + replies.stream()
                .map(r -> r.status() + "/" + r.text("code")).toList());
        assertEquals(1, insufficient, "另一单必须明确返回库存不足");

        System.out.println("[pg-evidence] 争抢最后 3 件（两单各 2）："
                + replies.stream()
                .map(r -> "status=" + r.status() + " code=" + r.text("code")
                        + " applied=" + r.applied())
                .toList()
                + " 最终库存=" + stock());

        assertEquals(1, stock(), "3 - 2 = 1，且不能为负");

        String winner = statusOf(orderA).equals("SHIPPED") ? orderA : orderB;
        String loser = winner.equals(orderA) ? orderB : orderA;

        assertEquals("SHIPPED", statusOf(winner));
        assertEquals(1, movementCount(winner), "成功订单恰好一笔流水");

        assertEquals("PENDING_SHIPMENT", statusOf(loser), "失败订单必须留在待发货");
        assertEquals(0, movementCount(loser), "失败订单不得有出库流水");
    }

    // ==================== D. 事务回滚 ====================

    @Test
    @DisplayName("D 事务回滚：扣减已发生但写流水失败 → 库存恢复、无流水、订单仍待发货；移除故障后正常")
    void shipmentRollsBackWhenMovementWriteFails() {
        String orderNo = placeOrder(BUYER_A, 2);
        assertEquals(10, stock());

        /*
         * 在"写流水"这一步注入失败。此时扣减已经执行过——
         * 下面的 deductedInsideTx 会从**同一个事务**里读到扣减后的值，
         * 用来证明"失败发生在扣减之后"，而不是"库存不足根本没扣成功"。
         */
        AtomicInteger deductedInsideTx = new AtomicInteger(-1);
        doAnswer(invocation -> {
            deductedInsideTx.set(jdbc.queryForObject(
                    "SELECT available FROM shop_inventory_balance WHERE sku = ?",
                    Integer.class, SKU));
            throw new IllegalStateException("injected: 写流水失败（仅测试注入）");
        }).when(inventoryRepository)
                .insertMovement(anyString(), anyInt(), anyString(), any(), any(),
                        anyString(), anyString());

        ShopApiClient.Reply failed = api.post(shipUrl(orderNo), ADMIN, null);

        assertEquals(500, failed.status(),
                "写流水失败应表现为 500（未预期异常）：" + failed.raw());
        assertEquals(8, deductedInsideTx.get(),
                "注入点之前扣减必须真的发生过，否则测的是'没扣成功'而不是回滚");

        System.out.println("[pg-evidence] 故障注入：事务内读到扣减后库存=" + deductedInsideTx.get()
                + "，请求 status=" + failed.status()
                + "；回滚后库存=" + stock() + " 流水=" + movementCount(orderNo)
                + " 订单=" + statusOf(orderNo));

        // 独立查询（新连接、新事务）：一切必须回到发货前
        assertEquals(10, stock(), "事务回滚后库存必须恢复为 10");
        assertEquals(0, movementCount(orderNo), "回滚后不得留下流水");
        assertEquals("PENDING_SHIPMENT", statusOf(orderNo), "订单必须仍是待发货");

        // 移除故障后重试：正常发货，且只扣一次
        Mockito.reset(inventoryRepository);

        ShopApiClient.Reply retried = api.post(shipUrl(orderNo), ADMIN, null);
        assertEquals(200, retried.status(), retried.raw());
        assertTrue(retried.applied());

        assertEquals(8, stock(), "重试后扣减一次");
        assertEquals(1, movementCount(orderNo), "重试后只有一笔流水");
        assertEquals("SHIPPED", statusOf(orderNo));
    }

    // ==================== E. 权限回归 ====================

    @Test
    @DisplayName("E 权限：买家 A 读不到 B 的订单（404），普通买家不能发货（403）")
    void buyerCannotReadOthersOrderNorShip() {
        String orderOfB = placeOrder(BUYER_B, 1);

        ShopApiClient.Reply asA = api.get("/shop/orders/" + orderOfB, BUYER_A);
        assertEquals(404, asA.status(),
                "别人的订单必须表现为不存在（403 会泄露订单号真实存在）：" + asA.raw());

        ShopApiClient.Reply listOfA = api.get("/shop/orders", BUYER_A);
        assertEquals(200, listOfA.status(), listOfA.raw());
        assertEquals(0, listOfA.body().size(), "A 的订单列表里不得出现 B 的订单");

        ShopApiClient.Reply shipAsBuyer = api.post(shipUrl(orderOfB), BUYER_A, null);
        assertEquals(403, shipAsBuyer.status(), shipAsBuyer.raw());
        assertEquals("NOT_ADMIN", shipAsBuyer.text("code"));

        // 被拒绝的请求不得产生任何副作用
        assertEquals(10, stock(), "买家越权发货不得扣库存");
        assertEquals("PENDING_SHIPMENT", statusOf(orderOfB));
        assertEquals(0, movementCount(orderOfB));
    }

    // ==================== 辅助 ====================

    /**
     * 每次测试前自证连的是真 PG。
     *
     * <p>放在 {@code @BeforeEach} 而不是只做一个用例：
     * 一旦 {@code @ServiceConnection} 失效、连接回落到 application-test.yml 的 H2，
     * 每个用例都会立刻失败，不会出现"部分用例在 H2 上通过"的假绿。
     */
    private void assertConnectedToRealPostgres() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            assertEquals("PostgreSQL", product,
                    "本轮是真实 PostgreSQL 回归，连到的却是 " + product
                            + "；容器连接没有生效，拒绝在别的数据库上出结论");
        }
    }

    private String placeOrder(String buyerId, int quantity) {
        ShopApiClient.Reply placed = api.post("/shop/orders", buyerId,
                Map.of("productId", PRODUCT, "quantity", quantity));
        assertEquals(201, placed.status(), placed.raw());
        String orderNo = placed.text("orderNo");
        assertNotNull(orderNo, "下单响应必须带订单号");
        return orderNo;
    }

    private int stock() {
        return ShopTestFixture.stockOf(jdbc, SKU);
    }

    private int movementCount(String orderNo) {
        return ShopTestFixture.movementCount(jdbc, orderNo);
    }

    private String statusOf(String orderNo) {
        return ShopTestFixture.statusOf(jdbc, orderNo);
    }

    private static String shipUrl(String orderNo) {
        return "/shop/admin/orders/" + orderNo + "/ship";
    }

    private static String signUrl(String orderNo) {
        return "/shop/admin/orders/" + orderNo + "/sign";
    }

    private static String scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "查询必须返回一行：" + sql);
            return rs.getString(1);
        }
    }

    /** 用同一道闸门同时放行，尽量让两个请求真正重叠。 */
    private List<ShopApiClient.Reply> fireTogether(
            int threads, Supplier<ShopApiClient.Reply> action) throws Exception {
        return fireTogether(threads, java.util.Collections.nCopies(threads, action));
    }

    private List<ShopApiClient.Reply> fireTogether(
            int threads, List<Supplier<ShopApiClient.Reply>> actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ShopApiClient.Reply>> futures = new ArrayList<>();
        try {
            for (Supplier<ShopApiClient.Reply> action : actions) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return action.get();
                }));
            }
            start.countDown();

            List<ShopApiClient.Reply> replies = new ArrayList<>();
            for (Future<ShopApiClient.Reply> future : futures) {
                // 并发请求必须有上限等待，不能只断言 HTTP 响应而无限阻塞
                replies.add(future.get(60, TimeUnit.SECONDS));
            }
            return replies;
        } finally {
            pool.shutdownNow();
        }
    }
}
