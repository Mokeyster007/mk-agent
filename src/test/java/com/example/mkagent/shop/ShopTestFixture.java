package com.example.mkagent.shop;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 商城测试夹具：把数据恢复到"刚跑完种子初始化"的状态。
 *
 * <p>测试上下文共用同一个 H2 内存库（{@code DB_CLOSE_DELAY=-1}），
 * 所以每个用例开始前必须显式清订单、清流水、把库存调回种子值——
 * 否则前一个用例发出的订单会污染后一个用例的"库存应为 10"断言。
 *
 * <p>只清业务数据，**不删商品与身份**：它们是种子数据，
 * 删掉会让后续用例拿到 404 而不是它们真正想验证的失败原因。
 */
final class ShopTestFixture {

    private ShopTestFixture() {
    }

    static void reset(JdbcTemplate jdbc) {
        /*
         * 先清售后（A1 / B1），再清订单（A0）：
         * shop_aftersale_return / quota / event / decision 都有外键指向订单与订单行，
         * 顺序反了会直接违反外键约束。
         */
        jdbc.update("DELETE FROM shop_aftersale_event");
        jdbc.update("DELETE FROM shop_aftersale_decision");
        jdbc.update("DELETE FROM shop_aftersale_return");
        jdbc.update("DELETE FROM shop_aftersale_quota");
        jdbc.update("DELETE FROM shop_aftersale_idempotency");

        jdbc.update("DELETE FROM shop_inventory_movement");
        jdbc.update("DELETE FROM shop_order_item");
        jdbc.update("DELETE FROM shop_order");

        setStock(jdbc, ShopSeedInitializer.SKU_KEYBOARD,
                ShopSeedInitializer.PRODUCT_KEYBOARD, ShopSeedInitializer.KEYBOARD_STOCK);
        setStock(jdbc, ShopSeedInitializer.SKU_MOUSE,
                ShopSeedInitializer.PRODUCT_MOUSE, ShopSeedInitializer.MOUSE_STOCK);
        setStock(jdbc, ShopSeedInitializer.SKU_CABLE,
                ShopSeedInitializer.PRODUCT_CABLE, ShopSeedInitializer.CABLE_STOCK);

        ensureUser(jdbc, ShopSeedInitializer.BUYER_ONE, "BUYER");
        ensureUser(jdbc, ShopSeedInitializer.BUYER_TWO, "BUYER");
        ensureUser(jdbc, ShopSeedInitializer.ADMIN, "ADMIN");
    }

    /**
     * 直接设定库存（模拟"只剩最后一件"这类边界）。
     *
     * <p>这是测试夹具，不是业务路径：业务路径只有发货时的条件扣减。
     */
    static void setStock(JdbcTemplate jdbc, String sku, long productId, int available) {
        int updated = jdbc.update(
                "UPDATE shop_inventory_balance SET available = ? WHERE sku = ?",
                available, sku);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO shop_inventory_balance (sku, product_id, available, updated_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    """, sku, productId, available);
        }
    }

    static void ensureUser(JdbcTemplate jdbc, String userId, String role) {
        List<String> existing = jdbc.queryForList(
                "SELECT role FROM shop_user WHERE user_id = ?", String.class, userId);
        if (existing.isEmpty()) {
            jdbc.update(
                    "INSERT INTO shop_user (user_id, role, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                    userId, role);
        }
    }

    static int stockOf(JdbcTemplate jdbc, String sku) {
        List<Integer> values = jdbc.queryForList(
                "SELECT available FROM shop_inventory_balance WHERE sku = ?", Integer.class, sku);
        return values.isEmpty() ? -1 : values.get(0);
    }

    /** 某订单产生的流水条数（按订单号，避免依赖内部 id）。 */
    static int movementCount(JdbcTemplate jdbc, String orderNo) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM shop_inventory_movement m
                JOIN shop_order o ON o.id = m.order_id
                WHERE o.order_no = ?
                """, Integer.class, orderNo);
        return count == null ? 0 : count;
    }

    /** 全库流水条数（断言"没有产生任何出库"时用）。 */
    static int totalMovements(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shop_inventory_movement", Integer.class);
        return count == null ? 0 : count;
    }

    static String statusOf(JdbcTemplate jdbc, String orderNo) {
        List<String> values = jdbc.queryForList(
                "SELECT status FROM shop_order WHERE order_no = ?", String.class, orderNo);
        return values.isEmpty() ? null : values.get(0);
    }

    static String buyerOf(JdbcTemplate jdbc, String orderNo) {
        List<String> values = jdbc.queryForList(
                "SELECT buyer_id FROM shop_order WHERE order_no = ?", String.class, orderNo);
        return values.isEmpty() ? null : values.get(0);
    }

    // ---------------------------------------------------------------
    // A1 售后夹具
    // ---------------------------------------------------------------

    /**
     * 直接造一张"已签收"的订单（自动化边界用例用）。
     *
     * <p>为什么允许夹具：A1 的边界场景（额度争抢、窗口过期、回滚）
     * 需要精确控制签收时间与数量，走完整浏览器链路既慢又无法造出"7 天前签收"。
     * 但**主验收路径仍然必须从 A0 页面下单 → 发货 → 签收产生**，
     * 夹具不能替代那条路径。
     *
     * @param signedAt 签收时间（传 {@code null} 表示用当前时间）
     * @return 订单号
     */
    static String createSignedOrder(
            JdbcTemplate jdbc,
            String orderNo,
            String buyerId,
            String sku,
            long productId,
            String productName,
            long unitPriceCents,
            int quantity,
            java.time.LocalDateTime signedAt
    ) {
        long totalCents = unitPriceCents * quantity;
        jdbc.update("""
                INSERT INTO shop_order
                    (order_no, buyer_id, status, total_cents, payment_mode, created_at, updated_at,
                     shipped_at, shipped_by, signed_at, signed_by)
                VALUES (?, ?, 'SIGNED', ?, 'DEMO', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, 'music-admin-1', ?, 'music-admin-1')
                """, orderNo, buyerId, totalCents,
                java.sql.Timestamp.valueOf(
                        signedAt == null ? java.time.LocalDateTime.now() : signedAt));

        Long orderId = jdbc.queryForObject(
                "SELECT id FROM shop_order WHERE order_no = ?", Long.class, orderNo);

        jdbc.update("""
                INSERT INTO shop_order_item
                    (order_id, product_id, sku, product_name, unit_price_cents,
                     quantity, line_cents, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, orderId, productId, sku, productName, unitPriceCents, quantity, totalCents);

        return orderNo;
    }

    /** 全库退货单条数。 */
    static int totalReturns(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shop_aftersale_return", Integer.class);
        return count == null ? 0 : count;
    }

    /** 某退货单的事件条数。 */
    static int eventCount(JdbcTemplate jdbc, String returnNo) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM shop_aftersale_event e
                JOIN shop_aftersale_return r ON r.id = e.return_id
                WHERE r.return_no = ?
                """, Integer.class, returnNo);
        return count == null ? 0 : count;
    }

    /** 某订单行当前已占用的可退额度（没有额度行时返回 0）。 */
    static int occupiedQty(JdbcTemplate jdbc, String orderNo) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(q.reserved_qty, 0)
                FROM shop_order o
                JOIN shop_order_item i ON i.order_id = o.id
                LEFT JOIN shop_aftersale_quota q ON q.order_item_id = i.id
                WHERE o.order_no = ?
                """, Integer.class, orderNo);
        return value == null ? 0 : value;
    }

    /** 退货单的退货数量（按退货单号）。 */
    static int returnQty(JdbcTemplate jdbc, String returnNo) {
        List<Integer> values = jdbc.queryForList(
                "SELECT quantity FROM shop_aftersale_return WHERE return_no = ?",
                Integer.class, returnNo);
        return values.isEmpty() ? -1 : values.get(0);
    }

    // ---------------------------------------------------------------
    // B1 决策夹具
    // ---------------------------------------------------------------

    /** 退货单当前状态（按退货单号）。 */
    static String returnStatus(JdbcTemplate jdbc, String returnNo) {
        List<String> values = jdbc.queryForList(
                "SELECT status FROM shop_aftersale_return WHERE return_no = ?",
                String.class, returnNo);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 某退货单的决策记录条数（一单一决，正常只可能是 0 或 1）。 */
    static int decisionCount(JdbcTemplate jdbc, String returnNo) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM shop_aftersale_decision d
                JOIN shop_aftersale_return r ON r.id = d.return_id
                WHERE r.return_no = ?
                """, Integer.class, returnNo);
        return count == null ? 0 : count;
    }

    /** 某退货单的决策结果（没有决策时返回 {@code null}）。 */
    static String decisionOf(JdbcTemplate jdbc, String returnNo) {
        List<String> values = jdbc.queryForList("""
                SELECT d.decision
                FROM shop_aftersale_decision d
                JOIN shop_aftersale_return r ON r.id = d.return_id
                WHERE r.return_no = ?
                """, String.class, returnNo);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 某退货单的驳回原因（没有决策或批准时返回 {@code null}）。 */
    static String rejectReasonOf(JdbcTemplate jdbc, String returnNo) {
        List<String> values = jdbc.queryForList("""
                SELECT d.reason
                FROM shop_aftersale_decision d
                JOIN shop_aftersale_return r ON r.id = d.return_id
                WHERE r.return_no = ?
                """, String.class, returnNo);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 某退货单的事件类型序列（按写入顺序）。 */
    static List<String> eventTypes(JdbcTemplate jdbc, String returnNo) {
        return jdbc.queryForList("""
                SELECT e.event_type
                FROM shop_aftersale_event e
                JOIN shop_aftersale_return r ON r.id = e.return_id
                WHERE r.return_no = ?
                ORDER BY e.id
                """, String.class, returnNo);
    }

    /**
     * 全库一致性断言：占用额度必须等于"未被驳回的申请数量之和"。
     *
     * <p>这条不变式是 B1 的核心：批准保留额度、驳回释放额度，
     * 任何一次决策写错都会让它不成立。
     *
     * @return 不成立时返回说明，成立返回 {@code null}
     */
    static String quotaConsistencyError(JdbcTemplate jdbc, String orderNo) {
        int occupied = occupiedQty(jdbc, orderNo);
        Integer expected = jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.quantity), 0)
                FROM shop_aftersale_return r
                JOIN shop_order o ON o.id = r.order_id
                WHERE o.order_no = ? AND r.status <> 'REJECTED'
                """, Integer.class, orderNo);
        int want = expected == null ? 0 : expected;
        return occupied == want ? null : "occupied=" + occupied + " 但有效申请合计=" + want;
    }
}
