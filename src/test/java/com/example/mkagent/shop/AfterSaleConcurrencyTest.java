package com.example.mkagent.shop;

import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 售后的并发与额度争抢（真实 HTTP + 独立事务）。
 *
 * <p>为什么必须走真实端口：并发用例要的是**两个真正独立的事务**。
 * MockMvc 在同一个线程/事务里跑，"两个请求同时抢最后一个名额"
 * 测出来的"通过"是假的——它们根本没有竞争。
 *
 * <p>三个场景对应三条不同的保证：
 * <ol>
 *   <li><b>同一提交标识并发</b> → 幂等键裁决，只建一单、返回同一单号；</li>
 *   <li><b>不同请求抢同一份额度</b> → 订单行锁 + 额度条件更新，只有一个成功；</li>
 *   <li><b>预览之后额度被别人用掉</b> → 提交时重算，旧预览不能当资格凭证。</li>
 * </ol>
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
class AfterSaleConcurrencyTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private ShopApiClient api;

    @BeforeEach
    void setUp() {
        ShopTestFixture.reset(jdbc);
        api = new ShopApiClient(port);
    }

    @Test
    @DisplayName("两个不同请求抢最后一个可退名额：只成功一个，额度恰好用掉 1")
    void twoRequestsCompeteForLastQuota() throws Exception {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-RACE", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 1, null);

        int stockBefore = ShopTestFixture.stockOf(jdbc, ShopSeedInitializer.SKU_KEYBOARD);
        int movementsBefore = ShopTestFixture.totalMovements(jdbc);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<ShopApiClient.Reply>> futures = new ArrayList<>();

        try {
            for (int i = 1; i <= 2; i++) {
                String key = "req-race-000" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return api.post("/shop/aftersale/returns", BUYER,
                            new SubmitBody(orderNo, 1, "抢最后一件", key));
                }));
            }

            start.countDown();

            int success = 0;
            int insufficient = 0;
            for (Future<ShopApiClient.Reply> future : futures) {
                ShopApiClient.Reply reply = future.get(30, TimeUnit.SECONDS);
                if (reply.status() == 201) {
                    success++;
                } else if (reply.status() == 409
                        && "RETURN_QUOTA_EXCEEDED".equals(reply.text("code"))) {
                    insufficient++;
                }
            }

            assertEquals(1, success, "只能有一个请求真正建成退货单");
            assertEquals(1, insufficient, "另一个必须明确返回额度不足");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, ShopTestFixture.totalReturns(jdbc), "最终只能有一张退货单");
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, orderNo), "额度恰好用掉 1");
        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbc, ShopSeedInitializer.SKU_KEYBOARD),
                "售后申请不得修改可售库存");
        assertEquals(movementsBefore, ShopTestFixture.totalMovements(jdbc),
                "售后申请不得写库存流水");
    }

    @Test
    @DisplayName("同一提交标识并发：只建一单，两个响应拿到同一单号")
    void sameKeyConcurrentYieldsOneReturn() throws Exception {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-SAMEKEY", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<ShopApiClient.Reply>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return api.post("/shop/aftersale/returns", BUYER,
                            new SubmitBody(orderNo, 1, "同一次提交", "req-samekey-001"));
                }));
            }

            start.countDown();

            List<String> returnNos = new ArrayList<>();
            for (Future<ShopApiClient.Reply> future : futures) {
                ShopApiClient.Reply reply = future.get(30, TimeUnit.SECONDS);
                assertEquals(201, reply.status(), reply.raw());
                returnNos.add(reply.text("returnNo"));
            }

            assertEquals(1, returnNos.stream().distinct().count(),
                    "并发同键必须返回同一个退货单号：" + returnNos);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, ShopTestFixture.totalReturns(jdbc), "并发同键只能建一单");
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, orderNo), "并发同键只能占一次额度");
    }

    @Test
    @DisplayName("预览说能退，额度却被另一请求用掉：提交必须被重新校验拦住")
    void stalePreviewCannotAuthorizeSubmit() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-STALE", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 1, null);

        // 1. 旧"确认卡"：此刻额度 1，预览判定可退
        ShopApiClient.Reply preview = api.post("/shop/aftersale/preview", BUYER,
                new PreviewBody(orderNo, 1, "预览时的理由"));
        assertEquals(200, preview.status(), preview.raw());
        assertTrue(preview.bool("eligible"), preview.raw());

        // 2. 另一个请求先用掉了这 1 个名额
        ShopApiClient.Reply competitor = api.post("/shop/aftersale/returns", BUYER,
                new SubmitBody(orderNo, 1, "另一个人先提交", "req-stale-0002"));
        assertEquals(201, competitor.status(), competitor.raw());

        // 3. 旧预览对应的提交必须失败——预览不是资格凭证
        ShopApiClient.Reply stale = api.post("/shop/aftersale/returns", BUYER,
                new SubmitBody(orderNo, 1, "预览时的理由", "req-stale-0001"));

        assertEquals(409, stale.status(), stale.raw());
        assertEquals("RETURN_QUOTA_EXCEEDED", stale.text("code"));
        assertEquals(1, ShopTestFixture.totalReturns(jdbc));
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, orderNo));
    }

    @Test
    @DisplayName("受控锁等待：订单行被别的事务锁住时，提交必须阻塞（证明行锁真的生效）")
    void submitWaitsForOrderItemLock() throws Exception {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-LOCK", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        Long itemId = jdbc.queryForObject("""
                SELECT i.id FROM shop_order_item i
                JOIN shop_order o ON o.id = i.order_id
                WHERE o.order_no = ?
                """, Long.class, orderNo);
        assertNotNull(itemId);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<ShopApiClient.Reply> pending = null;

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);

            /*
             * 用独立连接锁住订单行，模拟"另一个事务正在这一行上工作"。
             * 这不是并发屏障，而是**可控的锁占用**：
             * 只有真的存在行锁，后面的提交才会停在这里。
             */
            try (PreparedStatement ps = holder.prepareStatement(
                    "SELECT id FROM shop_order_item WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "订单行必须存在");
                }
            }

            pending = pool.submit(() -> api.post("/shop/aftersale/returns", BUYER,
                    new SubmitBody(orderNo, 1, "锁等待测试", "req-lock-00001")));

            Thread.sleep(500);
            assertFalse(pending.isDone(),
                    "提交必须阻塞在订单行锁上；若已完成说明行锁没有生效");
            assertEquals(0, ShopTestFixture.totalReturns(jdbc),
                    "阻塞期间不得建单");

            holder.rollback();
        }

        ShopApiClient.Reply reply = pending.get(30, TimeUnit.SECONDS);
        assertEquals(201, reply.status(), reply.raw());
        assertEquals(1, ShopTestFixture.totalReturns(jdbc));
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, orderNo));

        pool.shutdownNow();
    }

    /** 表单提交请求体。 */
    record SubmitBody(String orderNo, Integer quantity, String reason, String clientRequestId) {
    }

    /** 预览请求体。 */
    record PreviewBody(String orderNo, Integer quantity, String reason) {
    }
}
