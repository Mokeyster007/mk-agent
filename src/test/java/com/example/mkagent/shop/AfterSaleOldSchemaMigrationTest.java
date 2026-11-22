package com.example.mkagent.shop;

import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧库升级：**把 schema 退回 A1 的形状**，放一张已经存在的 {@code SUBMITTED} 单，
 * 再跑一次 {@code db/schema.sql}，验证 B1 的迁移在"老库 + 老数据"上真的成立。
 *
 * <p>为什么必须单独测这件事：B1 改了三个东西，而
 * {@code CREATE TABLE IF NOT EXISTS} 对老库**完全无效**——
 * 表已经存在时它什么也不做，老的两条 {@code CHECK} 会继续把
 * {@code APPROVED} / {@code REJECTED} 拒在门外。所以：
 *
 * <ul>
 *   <li>状态 / 事件的 {@code CHECK} 必须 {@code DROP CONSTRAINT IF EXISTS} + {@code ADD}；</li>
 *   <li>决策表是纯新增，{@code CREATE TABLE IF NOT EXISTS} 就够；</li>
 *   <li>迁移必须**幂等**（每次启动都跑一遍，不能越跑越错）。</li>
 * </ul>
 *
 * <p>本类用"先退回、再迁移"的方式在同一个库上复现这次升级，
 * 断言的是**老数据在迁移后仍然可决策**，而不是"新库能建表"。
 */
@HermeticSpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AfterSaleOldSchemaMigrationTest {

    private static final String BUYER = ShopSeedInitializer.BUYER_ONE;

    private static final String ADMIN = ShopSeedInitializer.ADMIN;

    private static final String SKU = ShopSeedInitializer.SKU_KEYBOARD;

    private static final long PRODUCT = ShopSeedInitializer.PRODUCT_KEYBOARD;

    private static final String ORDER_NO = "SO-AS-OLD-0001";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private ShopApiClient api;

    @BeforeEach
    void setUp() {
        // 先迁移再清数据：上一个用例可能把决策表退掉了。
        migrate();
        ShopTestFixture.reset(jdbc);
        api = new ShopApiClient(port);
    }

    @AfterEach
    void tearDown() {
        // 上下文是共享的，别把退回去的 schema 留给后面的测试类。
        migrate();
    }

    // ---------------------------------------------------------------
    // 用例
    // ---------------------------------------------------------------

    @Test
    @DisplayName("老库原样跑不了 B1：状态、事件、决策表三处都会被拒")
    void oldSchemaCannotRunB1() {
        String returnNo = oldSubmittedReturn(2);

        rollbackToA1Schema();

        // 1. 状态：老的 CHECK 只允许 SUBMITTED
        assertThrows(DataAccessException.class, () -> jdbc.update(
                        "UPDATE shop_aftersale_return SET status = 'APPROVED' WHERE return_no = ?",
                        returnNo),
                "老库不该能写 APPROVED —— 这正是必须迁移的原因");

        // 2. 事件：老的 CHECK 只允许 SUBMITTED
        Long returnId = jdbc.queryForObject(
                "SELECT id FROM shop_aftersale_return WHERE return_no = ?", Long.class, returnNo);
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                        INSERT INTO shop_aftersale_event (return_id, event_type, actor_id, note)
                        VALUES (?, 'REJECTED', ?, '老库写不进来')
                        """, returnId, ADMIN));

        // 3. 决策表：老库根本没有这张表
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                        INSERT INTO shop_aftersale_decision (return_id, decision, decided_by)
                        VALUES (?, 'APPROVED', ?)
                        """, returnId, ADMIN));

        // 数据没被弄脏
        assertEquals("SUBMITTED", ShopTestFixture.returnStatus(jdbc, returnNo));
        assertEquals(0, decisionTableCount(), "老库确实没有决策表");
    }

    @Test
    @DisplayName("迁移后：老的 SUBMITTED 单能批准，额度保留，决策记录落库")
    void oldSubmittedReturnCanBeApprovedAfterMigration() {
        String returnNo = oldSubmittedReturn(2);
        rollbackToA1Schema();

        migrate();

        // 老数据还在，且还是 SUBMITTED —— 迁移不改业务数据
        assertEquals("SUBMITTED", ShopTestFixture.returnStatus(jdbc, returnNo));
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, ORDER_NO));

        ShopApiClient.Reply reply = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/approve", ADMIN, null);

        assertEquals(200, reply.status(), reply.raw());
        assertEquals("APPROVED", reply.text("status"));
        assertEquals(ADMIN, reply.body().get("decision").get("decidedBy").asText(),
                "决策人来自服务端身份上下文");
        assertEquals("APPROVED", ShopTestFixture.decisionOf(jdbc, returnNo));
        assertEquals(List.of("SUBMITTED", "APPROVED"),
                ShopTestFixture.eventTypes(jdbc, returnNo));

        // 批准保留额度
        assertEquals(1, ShopTestFixture.occupiedQty(jdbc, ORDER_NO));
        assertNull(ShopTestFixture.quotaConsistencyError(jdbc, ORDER_NO));
    }

    @Test
    @DisplayName("迁移后：老的 SUBMITTED 单能驳回，额度 1 → 2，库存与流水不变")
    void oldSubmittedReturnReleasesQuotaOnRejectAfterMigration() {
        String returnNo = oldSubmittedReturn(2);
        int stockBefore = ShopTestFixture.stockOf(jdbc, SKU);
        int movementsBefore = ShopTestFixture.totalMovements(jdbc);

        rollbackToA1Schema();
        migrate();

        ShopApiClient.Reply reply = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/reject",
                ADMIN, Map.of("reason", "老单驳回：超过 7 天无理由期限"));

        assertEquals(200, reply.status(), reply.raw());
        assertEquals("REJECTED", reply.text("status"));
        assertEquals("REJECTED", ShopTestFixture.decisionOf(jdbc, returnNo));
        assertEquals("老单驳回：超过 7 天无理由期限", ShopTestFixture.rejectReasonOf(jdbc, returnNo));

        // 额度释放：1 件退回可退
        assertEquals(0, ShopTestFixture.occupiedQty(jdbc, ORDER_NO));
        assertNull(ShopTestFixture.quotaConsistencyError(jdbc, ORDER_NO));

        assertEquals(stockBefore, ShopTestFixture.stockOf(jdbc, SKU), "B1 不得修改可售库存");
        assertEquals(movementsBefore, ShopTestFixture.totalMovements(jdbc), "B1 不得写库存流水");
    }

    @Test
    @DisplayName("迁移幂等：连跑两次 schema.sql，老数据与约束都还是对的")
    void migrationIsIdempotent() {
        String returnNo = oldSubmittedReturn(2);
        rollbackToA1Schema();

        migrate();
        migrate();
        migrate();

        assertEquals("SUBMITTED", ShopTestFixture.returnStatus(jdbc, returnNo));
        assertEquals(1, ShopTestFixture.totalReturns(jdbc), "重复迁移不该多出退货单");
        assertEquals(0, ShopTestFixture.decisionCount(jdbc, returnNo));
        assertEquals(1, decisionTableCount(), "迁移后决策表必须存在（且只有一张）");

        // 约束确实被换成了新的：能写 APPROVED
        ShopApiClient.Reply reply = api.post(
                "/shop/admin/aftersale/returns/" + returnNo + "/approve", ADMIN, null);
        assertEquals(200, reply.status(), reply.raw());
        assertNotNull(reply.body().get("decision"));
        assertTrue(ShopTestFixture.eventTypes(jdbc, returnNo).contains("APPROVED"));
    }

    @Test
    @DisplayName("旧流水表升级：补 dedupe_key 并回填，且允许同一订单行多笔退货入库")
    void oldMovementTableMigratesWithoutClearingData() {
        ShopTestFixture.createSignedOrder(
                jdbc, ORDER_NO, BUYER, SKU, PRODUCT, "机械键盘 K87", 29900, 2, null);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM shop_order WHERE order_no = ?", Long.class, ORDER_NO);
        Long itemId = jdbc.queryForObject(
                "SELECT id FROM shop_order_item WHERE order_id = ?", Long.class, orderId);

        rollbackMovementTableToA0(itemId);

        // 老库：同一订单行 + 同类型只能一条 → 第一批入库可以，第二批被挡下来
        jdbc.update("""
                INSERT INTO shop_inventory_movement
                    (sku, quantity_delta, movement_type, order_id, order_item_id,
                     operator_id, note)
                VALUES (?, 1, 'RETURN_INBOUND', ?, ?, ?, '第一批')
                """, SKU, orderId, itemId, ADMIN);

        assertThrows(DataAccessException.class, () -> jdbc.update("""
                        INSERT INTO shop_inventory_movement
                            (sku, quantity_delta, movement_type, order_id, order_item_id,
                             operator_id, note)
                        VALUES (?, 1, 'RETURN_INBOUND', ?, ?, ?, '第二批')
                        """, SKU, orderId, itemId, ADMIN),
                "老索引 (order_item_id, movement_type) 会把合法的第二批挡掉");

        migrate();

        // 老出库流水被回填成稳定的去重键，数据一条没丢
        String backfilled = jdbc.queryForObject("""
                SELECT dedupe_key FROM shop_inventory_movement
                 WHERE order_item_id = ? AND movement_type = 'OUTBOUND_SHIPMENT'
                """, String.class, itemId);
        assertEquals("OUT:" + itemId, backfilled);

        // 第一批入库流水也被回填（老库没有 dedupe_key，用主键兜底而不是丢掉）
        String legacyInbound = jdbc.queryForObject("""
                SELECT dedupe_key FROM shop_inventory_movement
                 WHERE order_item_id = ? AND movement_type = 'RETURN_INBOUND'
                """, String.class, itemId);
        assertTrue(legacyInbound.startsWith("LEGACY:"), legacyInbound);

        // 新的去重键按"收货记录"生效：同一订单行可以有多笔退货入库
        jdbc.update("""
                INSERT INTO shop_inventory_movement
                    (sku, quantity_delta, movement_type, order_id, order_item_id,
                     dedupe_key, operator_id, note)
                VALUES (?, 1, 'RETURN_INBOUND', ?, ?, 'IN:2', ?, '第二批')
                """, SKU, orderId, itemId, ADMIN);

        Integer inbound = jdbc.queryForObject("""
                SELECT COUNT(*) FROM shop_inventory_movement
                 WHERE order_item_id = ? AND movement_type = 'RETURN_INBOUND'
                """, Integer.class, itemId);
        assertEquals(2, inbound);

        // 同一次入库写两遍仍然会被唯一索引挡住
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                        INSERT INTO shop_inventory_movement
                            (sku, quantity_delta, movement_type, order_id, order_item_id,
                             dedupe_key, operator_id, note)
                        VALUES (?, 1, 'RETURN_INBOUND', ?, ?, 'IN:2', ?, '重复')
                        """, SKU, orderId, itemId, ADMIN));
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    /** 走真实买家接口建一张"老库里的" SUBMITTED 单（老数据来自 A1 的真实路径）。 */
    private String oldSubmittedReturn(int purchased) {
        ShopTestFixture.createSignedOrder(
                jdbc, ORDER_NO, BUYER, SKU, PRODUCT, "机械键盘 K87", 29900, purchased, null);

        ShopApiClient.Reply reply = api.post("/shop/aftersale/returns", BUYER, Map.of(
                "orderNo", ORDER_NO,
                "quantity", 1,
                "reason", "A1 时期提交的退货申请",
                "clientRequestId", "req-old-schema-0001"));
        assertEquals(201, reply.status(), reply.raw());

        String returnNo = reply.text("returnNo");
        assertNotNull(returnNo, reply.raw());
        assertEquals("SUBMITTED", ShopTestFixture.returnStatus(jdbc, returnNo));
        return returnNo;
    }

    /** 把库退回 A1 的形状：两条 CHECK 只允许 SUBMITTED，且没有决策表。 */
    private void rollbackToA1Schema() {
        jdbc.execute("""
                ALTER TABLE shop_aftersale_return
                    DROP CONSTRAINT IF EXISTS ck_shop_aftersale_return_status
                """);
        jdbc.execute("""
                ALTER TABLE shop_aftersale_return
                    ADD CONSTRAINT ck_shop_aftersale_return_status
                        CHECK (status IN ('SUBMITTED'))
                """);
        jdbc.execute("""
                ALTER TABLE shop_aftersale_event
                    DROP CONSTRAINT IF EXISTS ck_shop_aftersale_event_type
                """);
        jdbc.execute("""
                ALTER TABLE shop_aftersale_event
                    ADD CONSTRAINT ck_shop_aftersale_event_type
                        CHECK (event_type IN ('SUBMITTED'))
                """);
        jdbc.execute("DROP TABLE IF EXISTS shop_aftersale_decision");
    }

    /** 决策表在不在（退回 A1 形状后应为 0；迁移后为 1）。 */
    private int decisionTableCount() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                 WHERE UPPER(TABLE_NAME) = 'SHOP_AFTERSALE_DECISION'
                """, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 把库存流水表退回 A0 的形状。
     *
     * <p>三个动作缺一不可：去掉新的去重索引、去掉 dedupe_key 列、
     * 恢复 A0 的 {@code (order_item_id, movement_type)} 唯一索引。
     * 然后写一条"老出库流水"，用来验证迁移能把它回填而不是清掉。
     */
    private void rollbackMovementTableToA0(long orderItemId) {
        jdbc.execute("DROP INDEX IF EXISTS uk_shop_inventory_movement_dedupe");
        jdbc.execute("""
                ALTER TABLE shop_inventory_movement
                    DROP CONSTRAINT IF EXISTS ck_shop_inventory_movement_dedupe
                """);
        jdbc.execute("ALTER TABLE shop_inventory_movement DROP COLUMN IF EXISTS dedupe_key");
        jdbc.execute("""
                CREATE UNIQUE INDEX uk_shop_inventory_movement_source
                    ON shop_inventory_movement (order_item_id, movement_type)
                """);

        Long orderId = jdbc.queryForObject("""
                SELECT order_id FROM shop_order_item WHERE id = ?
                """, Long.class, orderItemId);
        jdbc.update("""
                INSERT INTO shop_inventory_movement
                    (sku, quantity_delta, movement_type, order_id, order_item_id,
                     operator_id, note)
                VALUES (?, -2, 'OUTBOUND_SHIPMENT', ?, ?, ?, '老库出库流水')
                """, SKU, orderId, orderItemId, ADMIN);
    }

    /** 跑一遍真实的建表 / 迁移脚本（与启动时 `spring.sql.init` 用的是同一个文件）。 */
    private void migrate() {
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);
    }
}
