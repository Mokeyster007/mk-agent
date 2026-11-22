package com.example.mkagent.shop;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.aftersale.AfterSaleService;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.tools.AfterSaleQueryTool;
import com.example.mkagent.tools.AfterSaleWriteTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 售后 Agent 工具的身份边界（不经过模型）。
 *
 * <h2>要证明的三件事</h2>
 * <ol>
 *   <li><b>没有运行上下文就失败关闭</b>：工具不会退化成"匿名用户"，
 *       否则一次上下文丢失就会变成读别人的订单；</li>
 *   <li><b>买家身份不来自参数</b>：{@code submit_return} 的签名里没有 buyerId，
 *       即使模型被说服去操作别人的订单，也只能拿到 404 同款文案；</li>
 *   <li><b>写工具确实写在自己的单上</b>：建单、占额度、写事件，且来源是 AGENT。</li>
 * </ol>
 *
 * <p>这里刻意不驱动 Agent 循环：审批拦截由
 * {@link AfterSaleApprovalPauseTest} 用真实 {@code ApprovalToolPolicy} 证明，
 * 本类只钉住"工具本身有没有越界"。
 */
@HermeticSpringBootTest
@Timeout(60)
class AfterSaleAgentToolTest {

    private static final String BUYER_A = "music-user-1";

    private static final String BUYER_B = "music-user-2";

    private static final String SKU = "SKU-KEYBOARD-K87";

    private static final String ORDER_A = "SO20261122100000-A001";

    private static final String ORDER_B = "SO20261122100000-B001";

    @Autowired
    private AfterSaleWriteTool writeTool;

    @Autowired
    private AfterSaleQueryTool queryTool;

    @Autowired
    private AfterSaleService afterSaleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 写工具**设计上**只在事务里执行：{@code AfterSaleWriter#create} 是
     * {@code Propagation.MANDATORY}，脱离事务调用会立刻失败。
     * 生产里由 {@code IdempotentWriteExecutor} 开事务；这里用同一个语义包一层，
     * 既能验证"工具确实要求事务"，也不把测试写成依赖事务巧合。
     */
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);

        ShopTestFixture.reset(jdbcTemplate);
        ShopTestFixture.createSignedOrder(
                jdbcTemplate, ORDER_A, BUYER_A, SKU, 2001L, "机械键盘 K87", 29900L, 2,
                LocalDateTime.now());
        ShopTestFixture.createSignedOrder(
                jdbcTemplate, ORDER_B, BUYER_B, SKU, 2001L, "机械键盘 K87", 29900L, 2,
                LocalDateTime.now());
    }

    @AfterEach
    void clearContext() {
        AgentRunContextHolder.clear();
    }

    @Test
    @DisplayName("没有运行上下文：写工具失败关闭，不写任何数据")
    void writeToolFailsClosedWithoutContext() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> writeTool.submitReturn(ORDER_A, 1, "键盘按键失灵"));

        assertEquals(AgentRunContextHolder.NO_CONTEXT_MESSAGE, error.getMessage());
        assertEquals(0, ShopTestFixture.totalReturns(jdbcTemplate));
        assertEquals(0, ShopTestFixture.occupiedQty(jdbcTemplate, ORDER_A));
    }

    @Test
    @DisplayName("上下文里没有用户：同样失败关闭，不回落匿名身份")
    void writeToolFailsClosedWithoutUser() {
        AgentRunContext ctx = new AgentRunContext();
        ctx.setUserId("   ");
        bind(ctx);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> writeTool.submitReturn(ORDER_A, 1, "键盘按键失灵"));

        assertEquals(AgentRunContextHolder.NO_USER_MESSAGE, error.getMessage());
        assertEquals(0, ShopTestFixture.totalReturns(jdbcTemplate));
    }

    @Test
    @DisplayName("买家 A 不能对 B 的订单建单：业务拒绝，零写入")
    void cannotCreateReturnOnAnotherBuyersOrder() {
        /*
         * 工具本身只把上下文里的 userId 透传给服务，归属判定在服务里做。
         * 这里直接断言服务层的拒绝结果：
         * 业务拒绝会让写服务的事务标记回滚（与生产里 IdempotentWriteExecutor
         * 拿到失败信封后回滚的语义一致），因此按"拒绝 + 零写入"来断言。
         */
        BusinessException error = assertThrows(BusinessException.class,
                () -> transactionTemplate.execute(status ->
                        afterSaleService.createForAgent(BUYER_A, ORDER_B, 1, "键盘按键失灵")));

        assertEquals(404, error.getHttpStatus());
        assertEquals("ORDER_NOT_FOUND", error.getCode());
        assertEquals(0, ShopTestFixture.totalReturns(jdbcTemplate));
        assertEquals(0, ShopTestFixture.occupiedQty(jdbcTemplate, ORDER_B));
    }

    @Test
    @DisplayName("买家 A 对自己的订单建单成功：写库、占额度、来源为 AGENT")
    void writeToolCreatesReturnForOwner() {
        bind(contextOf(BUYER_A));

        String result = inTransaction(() -> writeTool.submitReturn(ORDER_A, 1, "键盘按键失灵"));

        assertTrue(result.contains("\"ok\":true"), "必须是成功信封：" + result);
        assertTrue(result.contains("RT"), "必须回传真实退货单号：" + result);
        assertTrue(result.contains("SUBMITTED"), "状态只能是已提交：" + result);
        assertFalse(result.contains("已退款"), "不得声称已退款：" + result);

        assertEquals(1, ShopTestFixture.totalReturns(jdbcTemplate));
        assertEquals(1, ShopTestFixture.occupiedQty(jdbcTemplate, ORDER_A));

        String source = jdbcTemplate.queryForObject("""
                SELECT r.source
                FROM shop_aftersale_return r
                JOIN shop_order o ON o.id = r.order_id
                WHERE o.order_no = ?
                """, String.class, ORDER_A);
        assertEquals("AGENT", source, "Agent 建的单来源必须是 AGENT");

        String returnNo = jdbcTemplate.queryForObject("""
                SELECT r.return_no
                FROM shop_aftersale_return r
                JOIN shop_order o ON o.id = r.order_id
                WHERE o.order_no = ?
                """, String.class, ORDER_A);
        assertEquals(1, ShopTestFixture.eventCount(jdbcTemplate, returnNo));
        assertEquals(1, ShopTestFixture.returnQty(jdbcTemplate, returnNo));
    }

    @Test
    @DisplayName("参数不全时先追问，不发请求也不写库")
    void writeToolAsksForMissingArguments() {
        bind(contextOf(BUYER_A));

        assertTrue(inTransaction(() -> writeTool.submitReturn(null, 1, "坏了")).contains("订单号"));
        assertTrue(inTransaction(() -> writeTool.submitReturn(ORDER_A, 0, "坏了")).contains("退货数量"));
        assertTrue(inTransaction(() -> writeTool.submitReturn(ORDER_A, 1, "   ")).contains("退货原因"));

        assertEquals(0, ShopTestFixture.totalReturns(jdbcTemplate));
    }

    @Test
    @DisplayName("查询工具只返回本人订单：B 看不到 A 的单")
    void queryToolOnlySeesOwnOrders() {
        bind(contextOf(BUYER_B));

        String result = queryTool.listMySignedOrders(10);

        assertTrue(result.contains(ORDER_B), "应包含自己的订单：" + result);
        assertFalse(result.contains(ORDER_A), "不得包含别人的订单：" + result);
    }

    @Test
    @DisplayName("查询工具同样失败关闭")
    void queryToolFailsClosedWithoutContext() {
        assertThrows(IllegalStateException.class, () -> queryTool.listMySignedOrders(10));
        assertThrows(IllegalStateException.class, () -> queryTool.getMyOrderDetail(ORDER_A));
        assertThrows(IllegalStateException.class, () -> queryTool.listMyReturnRequests(null));
    }

    @Test
    @DisplayName("预览不是资格凭证：别人的订单直接 404 同款文案")
    void previewHidesAnotherBuyersOrder() {
        bind(contextOf(BUYER_A));

        String result = queryTool.previewReturn(ORDER_B, 1, "键盘按键失灵");

        assertTrue(result.contains("\"ok\":false"), "必须是失败信封：" + result);
        assertTrue(result.contains("订单不存在"), "应说明订单不可见：" + result);
    }

    // ==================== 辅助 ====================

    private static AgentRunContext contextOf(String userId) {
        AgentRunContext ctx = new AgentRunContext();
        ctx.setUserId(userId);
        return ctx;
    }

    /** 按生产语义在事务里调用写工具（MANDATORY 传播级别要求如此）。 */
    private String inTransaction(java.util.function.Supplier<String> call) {
        return transactionTemplate.execute(status -> call.get());
    }

    private static void bind(AgentRunContext ctx) {
        AgentRunContextHolder.clear();
        AgentRunContextHolder.bind(ctx);
    }
}
