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
}
