package com.example.mkagent.shop;

import com.example.mkagent.shop.aftersale.AfterSaleRepository;
import com.example.mkagent.shop.aftersale.AfterSaleService;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * 表单提交的**异常归类**：只有"幂等占位行"的冲突才是同键回放。
 *
 * <h2>要防的错误</h2>
 *
 * {@code submitForm} 原先 catch 了笼统的 {@code DuplicateKeyException}。
 * 于是退货单号重号、事件 {@code (return_id, event_type)} 重复这类**真实写入失败**，
 * 会被当成"同一提交正在处理中"——用户看到的是"稍等再试"，
 * 而实际上那次提交根本没成功，等多久都不会好。
 *
 * <h2>两条路径必须分开</h2>
 *
 * <ul>
 *   <li><b>幂等占位冲突</b> → 回放首次结果（同键同参数返回原单号）；</li>
 *   <li><b>其它唯一键冲突</b> → {@code RETURN_WRITE_CONFLICT}，且**不**回放、**不**留脏数据。</li>
 * </ul>
 *
 * <p>两条都用同一个 {@code clientRequestId} 做对照，证明"分类"而不是"运气"。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
class AfterSaleConflictClassificationTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    private static final String ORDER_NO = "SO-AS-CONFLICT";

    private static final String CLIENT_REQUEST_ID = "req-conflict-0001";

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
        doCallRealMethod().when(repository)
                .insertEvent(anyLong(), anyString(), anyString(), anyString());
        doCallRealMethod().when(repository)
                .insertReturn(anyString(), anyLong(), anyLong(), anyString(), anyString(),
                        anyString(), anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                        anyLong(), anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    // ---------------------------------------------------------------
    // 路径一：真正的同键 → 回放
    // ---------------------------------------------------------------

    @Test
    @DisplayName("同键同参数第二次提交 → 返回原单号，不建第二单")
    void sameKeyReplaysOriginalReturn() {
        createOrder();

        ShopApiClient.Reply first = submit();
        assertEquals(201, first.status(), first.raw());
        String returnNo = first.text("returnNo");
        assertNotNull(returnNo, first.raw());

        ShopApiClient.Reply second = submit();
        assertEquals(201, second.status(), second.raw());
        assertEquals(returnNo, second.text("returnNo"), "同键必须回放同一个单号");
        assertEquals(1, ShopTestFixture.totalReturns(jdbc));
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, ORDER_NO));
    }

    // ---------------------------------------------------------------
    // 路径二：事件唯一键冲突 → 不是幂等命中
    // ---------------------------------------------------------------

    @Test
    @DisplayName("写事件撞唯一键 → RETURN_WRITE_CONFLICT，不冒充同键回放，且零残留")
    void eventUniqueConflictIsNotTreatedAsReplay() {
        createOrder();

        doThrow(new DuplicateKeyException("测试注入：事件唯一键冲突"))
                .when(repository).insertEvent(anyLong(), anyString(), anyString(), anyString());

        ShopApiClient.Reply failed = submit();
        assertEquals(409, failed.status(), failed.raw());
        assertEquals(AfterSaleService.CODE_RETURN_WRITE_CONFLICT, failed.text("code"), failed.raw());
        assertNotEquals("SUBMIT_CONFLICT", failed.text("code"),
                "真实写入失败不能被报成'同一提交处理中'");

        assertNoResidue();

        /*
         * 关键：占位行随事务一起回滚了，所以同一个 clientRequestId 重试
         * 不会被误判成"已经提交过"——它应当正常建单。
         */
        doCallRealMethod().when(repository)
                .insertEvent(anyLong(), anyString(), anyString(), anyString());

        ShopApiClient.Reply retried = submit();
        assertEquals(201, retried.status(), retried.raw());
        assertNotNull(retried.text("returnNo"));
        assertEquals(1, ShopTestFixture.totalReturns(jdbc));
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, ORDER_NO));
    }

    // ---------------------------------------------------------------
    // 路径三：退货单唯一键冲突 → 同样不是幂等命中
    // ---------------------------------------------------------------

    @Test
    @DisplayName("写退货单撞唯一键 → 同样是 RETURN_WRITE_CONFLICT，零残留")
    void returnUniqueConflictIsNotTreatedAsReplay() {
        createOrder();

        doThrow(new DuplicateKeyException("测试注入：退货单唯一键冲突"))
                .when(repository).insertReturn(anyString(), anyLong(), anyLong(), anyString(),
                        anyString(), anyString(), anyLong(),
                        org.mockito.ArgumentMatchers.anyInt(), anyLong(), anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        ShopApiClient.Reply failed = submit();
        assertEquals(409, failed.status(), failed.raw());
        assertEquals(AfterSaleService.CODE_RETURN_WRITE_CONFLICT, failed.text("code"), failed.raw());

        assertNoResidue();
    }

    // ---------------------------------------------------------------

    private void createOrder() {
        ShopTestFixture.createSignedOrder(
                jdbc, ORDER_NO, BUYER, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, "机械键盘 K87", 29900, 2, null);
    }

    private ShopApiClient.Reply submit() {
        return api.post("/shop/aftersale/returns", BUYER,
                new AfterSaleRollbackTest.SubmitBody(
                        ORDER_NO, 1, "冲突归类测试", CLIENT_REQUEST_ID));
    }

    /** 失败后必须：没有退货单、额度未占、没有幂等占位行。 */
    private void assertNoResidue() {
        assertEquals(0, ShopTestFixture.totalReturns(jdbc), "不得残留退货单");
        assertEquals(0, ShopTestFixture.occupiedQty(jdbc, ORDER_NO), "额度不得被占用");
        Integer idempotency = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shop_aftersale_idempotency", Integer.class);
        assertEquals(0, idempotency == null ? 0 : idempotency,
                "占位行必须随事务回滚，否则重试会被误判成回放");
    }
}
