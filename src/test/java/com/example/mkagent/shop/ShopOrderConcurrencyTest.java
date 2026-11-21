package com.example.mkagent.shop;

import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发货的并发行为（真实 HTTP，两个独立事务）。
 *
 * <p>这一组用例是本轮最容易被"看起来对"骗过去的地方：
 * 顺序重试能过、单线程能过，但两个请求同时到达时，
 * 只有"锁订单行 + 原子条件扣减 + 唯一索引"三件事同时在场才不会重复扣减。
 *
 * <p>用真端口而不是 MockMvc 的原因也在这里：MockMvc 在同一个线程里跑，
 * 两个请求会共用同一个事务，测出来的"并发"是假的。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(180)
class ShopOrderConcurrencyTest {

    private static final String BUYER_A = ShopSeedInitializer.BUYER_ONE;

    private static final String BUYER_B = ShopSeedInitializer.BUYER_TWO;

    private static final String ADMIN = ShopSeedInitializer.ADMIN;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ShopApiClient api;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        api = new ShopApiClient(port);
        ShopTestFixture.reset(jdbcTemplate);
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("同一订单并发发货：只扣一次、只写一笔流水")
    void concurrentShipOfSameOrderDeductsOnce() throws Exception {
        String orderNo = placeOrder(BUYER_A, ShopSeedInitializer.PRODUCT_KEYBOARD, 2);

        List<ShopApiClient.Reply> replies = fireTogether(List.of(
                () -> api.post("/shop/admin/orders/" + orderNo + "/ship", ADMIN, null),
                () -> api.post("/shop/admin/orders/" + orderNo + "/ship", ADMIN, null)));

        long applied = replies.stream().filter(ShopApiClient.Reply::ok)
                .filter(ShopApiClient.Reply::applied)
                .count();

        assertEquals(1, applied,
                "并发发货只能有一次真正生效，实际响应：" + describe(replies));
        assertTrue(replies.stream().allMatch(ShopApiClient.Reply::ok),
                "另一次应是幂等的 200（applied=false），而不是错误：" + describe(replies));

        assertEquals(8, ShopTestFixture.stockOf(jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD));
        assertEquals(1, ShopTestFixture.movementCount(jdbcTemplate, orderNo));
    }

    @Test
    @DisplayName("两个订单并发争抢最后 1 件：一个成功、一个库存不足，库存不为负")
    void concurrentShipCompetingForLastUnit() throws Exception {
        ShopTestFixture.setStock(
                jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, 1);

        String orderA = placeOrder(BUYER_A, ShopSeedInitializer.PRODUCT_KEYBOARD, 1);
        String orderB = placeOrder(BUYER_B, ShopSeedInitializer.PRODUCT_KEYBOARD, 1);

        List<ShopApiClient.Reply> replies = fireTogether(List.of(
                () -> api.post("/shop/admin/orders/" + orderA + "/ship", ADMIN, null),
                () -> api.post("/shop/admin/orders/" + orderB + "/ship", ADMIN, null)));

        long succeeded = replies.stream()
                .filter(r -> r.ok() && r.applied())
                .count();
        long rejected = replies.stream()
                .filter(r -> r.status() == 409 && "INSUFFICIENT_STOCK".equals(r.text("code")))
                .count();

        assertEquals(1, succeeded, "只能有一单能发出去：" + describe(replies));
        assertEquals(1, rejected, "另一单必须明确报库存不足：" + describe(replies));

        int stock = ShopTestFixture.stockOf(jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD);
        assertEquals(0, stock, "最后一件被发走后库存应为 0，且不能为负");

        // 失败的那一单必须仍在待发货，且没有产生流水
        String failedOrder = statusOfFailed(orderA, orderB);
        assertEquals("PENDING_SHIPMENT", ShopTestFixture.statusOf(jdbcTemplate, failedOrder));
        assertEquals(0, ShopTestFixture.movementCount(jdbcTemplate, failedOrder));

        // 全库只应有 1 笔出库流水
        assertEquals(1, ShopTestFixture.totalMovements(jdbcTemplate));
    }

    @Test
    @DisplayName("两单各要 2 件但只剩 3 件：只能发出一单，另一单原样待发货")
    void partialCapacityUnderContention() throws Exception {
        ShopTestFixture.setStock(
                jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, 3);

        String orderA = placeOrder(BUYER_A, ShopSeedInitializer.PRODUCT_KEYBOARD, 2);
        String orderB = placeOrder(BUYER_B, ShopSeedInitializer.PRODUCT_KEYBOARD, 2);

        List<ShopApiClient.Reply> replies = fireTogether(List.of(
                () -> api.post("/shop/admin/orders/" + orderA + "/ship", ADMIN, null),
                () -> api.post("/shop/admin/orders/" + orderB + "/ship", ADMIN, null)));

        long succeeded = replies.stream().filter(r -> r.ok() && r.applied()).count();
        assertEquals(1, succeeded, "3 件只够发一单 2 件：" + describe(replies));

        assertEquals(1, ShopTestFixture.stockOf(jdbcTemplate, ShopSeedInitializer.SKU_KEYBOARD),
                "发出一单 2 件后应剩 1 件");
        assertEquals(1, ShopTestFixture.totalMovements(jdbcTemplate));

        String failedOrder = statusOfFailed(orderA, orderB);
        assertEquals("PENDING_SHIPMENT", ShopTestFixture.statusOf(jdbcTemplate, failedOrder));
    }

    /**
     * 并发执行两个请求，尽量让它们同时到达。
     *
     * <p>用 {@link CountDownLatch} 而不是 {@code sleep}：
     * 两个线程在同一时刻被放行，才有机会真正撞上同一行锁。
     */
    private List<ShopApiClient.Reply> fireTogether(
            List<Supplier<ShopApiClient.Reply>> calls
    ) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ShopApiClient.Reply>> futures = new ArrayList<>();

        for (Supplier<ShopApiClient.Reply> call : calls) {
            futures.add(pool.submit(() -> {
                start.await();
                return call.get();
            }));
        }

        start.countDown();

        List<ShopApiClient.Reply> replies = new ArrayList<>();
        for (Future<ShopApiClient.Reply> future : futures) {
            replies.add(future.get(60, TimeUnit.SECONDS));
        }
        return replies;
    }

    private String placeOrder(String buyerId, long productId, int quantity) {
        ShopApiClient.Reply placed = api.post("/shop/orders", buyerId,
                Map.of("productId", productId, "quantity", quantity));
        assertEquals(201, placed.status(), placed.raw());
        return placed.text("orderNo");
    }

    /** 并发之后无法预知哪一单失败，按数据库里的实际状态判断。 */
    private String statusOfFailed(String orderA, String orderB) {
        return "PENDING_SHIPMENT".equals(ShopTestFixture.statusOf(jdbcTemplate, orderA))
                ? orderA
                : orderB;
    }

    private static String describe(List<ShopApiClient.Reply> replies) {
        StringBuilder builder = new StringBuilder();
        for (ShopApiClient.Reply reply : replies) {
            builder.append('[').append(reply.status()).append(' ')
                    .append(reply.text("code")).append(' ')
                    .append(reply.text("message")).append(']');
        }
        return builder.toString();
    }
}
