package com.example.mkagent.shop;

import com.example.mkagent.shop.aftersale.AfterSaleRepository;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

/**
 * A1 事务回滚：额度已占用、退货单已插入，但"写事件"失败。
 *
 * <h2>为什么必须证明"额度真的被占过"</h2>
 *
 * "额度不足，根本没扣成功" 也会让最终状态看起来"额度没变、没有单"——
 * 那是失败在前，不是回滚。这个用例在注入点**同事务内**读一次额度：
 * 读到 {@code reserved_qty = 1} 才说明扣减真的发生过，
 * 之后的"恢复成 0"才是回滚的结果。
 *
 * <h2>故障注入为什么只在测试里</h2>
 *
 * 用 {@code @MockitoSpyBean} 替换仓库 Bean，在 {@code insertEvent} 上抛异常。
 * **生产代码里没有任何故障开关**：没有 if、没有配置项、没有测试专用分支。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
class AfterSaleRollbackTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private AfterSaleRepository repository;

    private ShopApiClient api;

    @BeforeEach
    void setUp() {
        ShopTestFixture.reset(jdbc);
        api = new ShopApiClient(port);
    }

    @Test
    @DisplayName("写事件失败 → 额度恢复、无退货单残留、无库存变化；移除故障后重试只扣一次")
    void failureAfterQuotaReservationRollsBackEverything() {
        String orderNo = ShopTestFixture.createSignedOrder(
                jdbc, "SO-AS-ROLLBACK", BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);

        int stockBefore = ShopTestFixture.stockOf(jdbc, ShopSeedInitializer.SKU_KEYBOARD);
        int movementsBefore = ShopTestFixture.totalMovements(jdbc);

        // 注入点：额度已扣、退货单已插入，紧接着要写事件。
        AtomicInteger reservedInsideTx = new AtomicInteger(-1);
        doAnswer(invocation -> {
            reservedInsideTx.set(quotaOf(orderNo));
            throw new IllegalStateException("测试注入：写售后事件失败");
        }).when(repository).insertEvent(anyLong(), anyString(), anyString(), anyString());

        ShopApiClient.Reply failed = api.post("/shop/aftersale/returns", BUYER,
                new SubmitBody(orderNo, 1, "注入回滚测试", "req-rollback-01"));

        assertEquals(500, failed.status(), failed.raw());
        assertEquals(1, reservedInsideTx.get(),
                "注入点之前额度必须真的被占用过，否则测的是'没扣成功'而不是回滚");

        // 请求结束后用独立查询核对最终状态
        assertEquals(0, ShopTestFixture.occupiedQty(jdbc, orderNo), "额度必须恢复");
        assertEquals(0, ShopTestFixture.totalReturns(jdbc), "不得残留退货单");
        assertEquals(0, idempotencyCount(), "失败不得留下幂等记录（否则重试永远回放失败）");
        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbc, ShopSeedInitializer.SKU_KEYBOARD),
                "售后不得改变库存");
        assertEquals(movementsBefore, ShopTestFixture.totalMovements(jdbc),
                "售后不得写库存流水");

        // 移除故障，用同一个提交标识重试：应当正常建单，且只扣一次额度
        doCallRealMethod().when(repository).insertEvent(anyLong(), anyString(), anyString(), anyString());

        ShopApiClient.Reply retried = api.post("/shop/aftersale/returns", BUYER,
                new SubmitBody(orderNo, 1, "注入回滚测试", "req-rollback-01"));

        assertEquals(201, retried.status(), retried.raw());
        assertNotNull(retried.text("returnNo"));
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, orderNo), "重试只扣一次额度");
        assertEquals(1, ShopTestFixture.totalReturns(jdbc));
        assertEquals(1, ShopTestFixture.eventCount(jdbc, retried.text("returnNo")));
        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbc, ShopSeedInitializer.SKU_KEYBOARD));
    }

    private int quotaOf(String orderNo) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(q.reserved_qty, 0)
                FROM shop_order o
                JOIN shop_order_item i ON i.order_id = o.id
                LEFT JOIN shop_aftersale_quota q ON q.order_item_id = i.id
                WHERE o.order_no = ?
                """, Integer.class, orderNo);
        return value == null ? 0 : value;
    }

    private int idempotencyCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shop_aftersale_idempotency", Integer.class);
        return count == null ? 0 : count;
    }

    /** 表单提交请求体。 */
    record SubmitBody(String orderNo, Integer quantity, String reason, String clientRequestId) {
    }
}
