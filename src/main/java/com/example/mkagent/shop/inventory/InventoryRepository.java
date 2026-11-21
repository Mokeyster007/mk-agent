package com.example.mkagent.shop.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 库存余额与流水仓库。
 *
 * <h2>为什么扣减是一条带条件的 UPDATE</h2>
 *
 * 常见写法是"先查 available，判断够不够，再 UPDATE 成 available - n"。
 * 两个事务同时读到 10、各自减 6，就会写出 -2。
 * 这里改成<b>一条原子语句</b>：
 *
 * <pre>
 * UPDATE shop_inventory_balance
 *    SET available = available - ?
 *  WHERE sku = ? AND available >= ?
 * </pre>
 *
 * 影响行数为 0 就代表"不够扣"，不需要先读。
 * 在 READ COMMITTED 下，第二个事务会等第一个提交后**重新判断** WHERE，
 * 因此不会出现"两个都判断通过"的窗口。
 *
 * <p>数据库上还有 {@code CHECK (available >= 0)} 兜底：即使有人绕过本方法直接写 SQL，
 * 也不会产生负库存。约束负责"绝不为负"，本方法负责"给出可读的库存不足"。
 */
@Repository
public class InventoryRepository {

    private static final RowMapper<InventoryMovement> MOVEMENT_MAPPER = (rs, rowNum) -> {
        Object orderId = rs.getObject("order_id");
        Object orderItemId = rs.getObject("order_item_id");
        return new InventoryMovement(
                rs.getLong("id"),
                rs.getString("sku"),
                rs.getInt("quantity_delta"),
                rs.getString("movement_type"),
                orderId == null ? null : ((Number) orderId).longValue(),
                orderItemId == null ? null : ((Number) orderItemId).longValue(),
                rs.getString("operator_id"),
                rs.getString("note"),
                rs.getTimestamp("created_at").toLocalDateTime());
    };

    private final JdbcTemplate jdbcTemplate;

    public InventoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 原子扣减：够才扣。
     *
     * @return 影响行数；0 表示可售库存不足（或该 SKU 没有库存行）
     */
    public int deductIfEnough(String sku, int quantity) {
        return jdbcTemplate.update("""
                UPDATE shop_inventory_balance
                   SET available = available - ?, updated_at = CURRENT_TIMESTAMP
                 WHERE sku = ? AND available >= ?
                """, quantity, sku, quantity);
    }

    /**
     * 写一条流水。
     *
     * <p>用 {@link PreparedStatement} 显式设置可空 BIGINT：
     * 直接把 {@code null} 当 Object 传，PostgreSQL 会报
     * "could not determine data type of parameter"（H2 不会，所以这种错只会在生产暴露）。
     *
     * <p>同一订单行 + 同类型的重复写入会撞唯一索引
     * {@code uk_shop_inventory_movement_source}。这里**不捕获**该异常：
     * 唯一索引是兜底，触发即代表状态机被绕过，应当让事务整体失败。
     */
    public void insertMovement(
            String sku,
            int quantityDelta,
            String movementType,
            Long orderId,
            Long orderItemId,
            String operatorId,
            String note
    ) {
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO shop_inventory_movement
                        (sku, quantity_delta, movement_type, order_id, order_item_id, operator_id, note)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setString(1, sku);
            ps.setInt(2, quantityDelta);
            ps.setString(3, movementType);
            if (orderId == null) {
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setLong(4, orderId);
            }
            if (orderItemId == null) {
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(5, orderItemId);
            }
            ps.setString(6, operatorId);
            ps.setString(7, note);
            return ps;
        });
    }

    /** 某个 SKU 的可售数量；没有库存行时为 0（不是 -1，也不是 null）。 */
    public int availableOf(String sku) {
        List<Integer> values = jdbcTemplate.queryForList(
                "SELECT available FROM shop_inventory_balance WHERE sku = ?",
                Integer.class,
                sku);
        return values.isEmpty() ? 0 : values.get(0);
    }

    /** 后台核对用：全部库存余额（含商品名）。 */
    public List<InventoryBalanceView> findAllBalances() {
        return jdbcTemplate.query("""
                SELECT b.sku, b.product_id, p.name AS product_name, b.available
                FROM shop_inventory_balance b
                LEFT JOIN shop_product p ON p.id = b.product_id
                ORDER BY b.sku
                """, (rs, rowNum) -> new InventoryBalanceView(
                rs.getString("sku"),
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getInt("available")));
    }

    /** 某个订单产生的全部流水（从订单追溯到出库记录）。 */
    public List<InventoryMovement> findByOrderId(long orderId) {
        return jdbcTemplate.query("""
                SELECT id, sku, quantity_delta, movement_type, order_id, order_item_id,
                       operator_id, note, created_at
                FROM shop_inventory_movement
                WHERE order_id = ?
                ORDER BY id
                """, MOVEMENT_MAPPER, orderId);
    }

    /** 最近流水（后台首页核对用）。 */
    public List<InventoryMovement> findRecent(int limit) {
        return jdbcTemplate.query("""
                SELECT id, sku, quantity_delta, movement_type, order_id, order_item_id,
                       operator_id, note, created_at
                FROM shop_inventory_movement
                ORDER BY id DESC
                LIMIT ?
                """, MOVEMENT_MAPPER, limit);
    }

    /** 某个 SKU 的最近流水（按 SKU 核对）。 */
    public List<InventoryMovement> findBySku(String sku, int limit) {
        return jdbcTemplate.query("""
                SELECT id, sku, quantity_delta, movement_type, order_id, order_item_id,
                       operator_id, note, created_at
                FROM shop_inventory_movement
                WHERE sku = ?
                ORDER BY id DESC
                LIMIT ?
                """, MOVEMENT_MAPPER, sku, limit);
    }

    /** 库存行是否存在（种子与排查用）。 */
    public boolean hasBalance(String sku) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_inventory_balance WHERE sku = ?",
                Integer.class,
                sku);
        return count != null && count > 0;
    }

    /** 直接设定可售数量（仅种子与测试夹具使用，业务路径不得调用）。 */
    public void resetAvailable(String sku, long productId, int available) {
        int updated = jdbcTemplate.update("""
                UPDATE shop_inventory_balance
                   SET available = ?, updated_at = ?
                 WHERE sku = ?
                """, available, LocalDateTime.now(), sku);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO shop_inventory_balance (sku, product_id, available, updated_at)
                    VALUES (?, ?, ?, ?)
                    """, sku, productId, available, LocalDateTime.now());
        }
    }

    /** 取一条流水的 id（测试断言"只产生一笔"时用）。 */
    public Optional<Long> findMovementId(long orderItemId, String movementType) {
        List<Long> ids = jdbcTemplate.query("""
                SELECT id FROM shop_inventory_movement
                WHERE order_item_id = ? AND movement_type = ?
                """, (rs, rowNum) -> rs.getLong(1), orderItemId, movementType);
        return ids.stream().findFirst();
    }
}
