package com.example.mkagent.shop.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 订单仓库（含明细）。
 *
 * <p>三个刻意的设计：
 * <ol>
 *   <li><b>所有查询都带 buyer_id 条件</b>（买家侧），越权在 SQL 层就挡住了，
 *       而不是"先查出来再在 Java 里比对"——后者一旦漏写一个 if 就是数据泄露；</li>
 *   <li><b>状态变更都是条件更新</b>（{@code WHERE status = 期望值}），
 *       返回 0 行即代表状态已变化，调用方必须据此失败，不能当成功；</li>
 *   <li><b>发货前先锁订单行</b>（{@code FOR UPDATE}），
 *       让同一订单的并发发货排队，后到的那次会看到已发货并直接返回幂等结果。</li>
 * </ol>
 */
@Repository
public class ShopOrderRepository {

    private static final String SELECT_ORDER = """
            SELECT id, order_no, buyer_id, status, total_cents, payment_mode,
                   created_at, shipped_at, shipped_by, signed_at, signed_by
            FROM shop_order
            """;

    private static final RowMapper<ShopOrder> ORDER_MAPPER = (rs, rowNum) -> new ShopOrder(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getString("buyer_id"),
            ShopOrderStatus.parse(rs.getString("status")),
            rs.getLong("total_cents"),
            rs.getString("payment_mode"),
            toLocal(rs.getTimestamp("created_at")),
            toLocal(rs.getTimestamp("shipped_at")),
            rs.getString("shipped_by"),
            toLocal(rs.getTimestamp("signed_at")),
            rs.getString("signed_by"));

    private static final RowMapper<ShopOrderItem> ITEM_MAPPER = (rs, rowNum) -> new ShopOrderItem(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getLong("product_id"),
            rs.getString("sku"),
            rs.getString("product_name"),
            rs.getLong("unit_price_cents"),
            rs.getInt("quantity"),
            rs.getLong("line_cents"),
            toLocal(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbcTemplate;

    public ShopOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入订单，返回自增 id。
     *
     * <p>不在这里生成 id：订单 id 是纯内部标识，用 identity 最简单，
     * 也避免和音乐模块的雪花方案纠缠。对外一律用 orderNo。
     */
    public long insertOrder(
            String orderNo,
            String buyerId,
            ShopOrderStatus status,
            long totalCents,
            String paymentMode
    ) {
        jdbcTemplate.update("""
                INSERT INTO shop_order
                    (order_no, buyer_id, status, total_cents, payment_mode, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, orderNo, buyerId, status.name(), totalCents, paymentMode);

        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM shop_order WHERE order_no = ?", Long.class, orderNo);

        if (id == null) {
            throw new IllegalStateException("订单插入后取不到 id：" + orderNo);
        }
        return id;
    }

    /** 插入明细（成交快照）。 */
    public void insertItem(
            long orderId,
            long productId,
            String sku,
            String productName,
            long unitPriceCents,
            int quantity,
            long lineCents
    ) {
        jdbcTemplate.update("""
                INSERT INTO shop_order_item
                    (order_id, product_id, sku, product_name, unit_price_cents,
                     quantity, line_cents, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, orderId, productId, sku, productName, unitPriceCents, quantity, lineCents);
    }

    /** 按订单号取订单（不校验归属，调用方必须自己加 buyer 条件）。 */
    public Optional<ShopOrder> findByOrderNo(String orderNo) {
        return jdbcTemplate.query(SELECT_ORDER + " WHERE order_no = ?", ORDER_MAPPER, orderNo)
                .stream().findFirst();
    }

    /** 买家本人的订单（归属写在 SQL 里）。 */
    public Optional<ShopOrder> findByOrderNoAndBuyer(String orderNo, String buyerId) {
        return jdbcTemplate.query(
                        SELECT_ORDER + " WHERE order_no = ? AND buyer_id = ?",
                        ORDER_MAPPER,
                        orderNo,
                        buyerId)
                .stream().findFirst();
    }

    /** 买家的订单列表（新的在前）。 */
    public List<ShopOrder> findByBuyer(String buyerId, int limit) {
        return jdbcTemplate.query(
                SELECT_ORDER + " WHERE buyer_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                ORDER_MAPPER,
                buyerId,
                limit);
    }

    /** 后台：按状态分页（status 为 null 表示全部）。 */
    public List<ShopOrder> findForAdmin(ShopOrderStatus status, int limit, int offset) {
        if (status == null) {
            return jdbcTemplate.query(
                    SELECT_ORDER + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                    ORDER_MAPPER,
                    limit,
                    offset);
        }
        return jdbcTemplate.query(
                SELECT_ORDER
                        + " WHERE status = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                ORDER_MAPPER,
                status.name(),
                limit,
                offset);
    }

    /** 后台：总数（分页用）。 */
    public long countForAdmin(ShopOrderStatus status) {
        Long count = status == null
                ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shop_order", Long.class)
                : jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM shop_order WHERE status = ?",
                        Long.class,
                        status.name());
        return count == null ? 0 : count;
    }

    /**
     * 锁定订单行并读取当前状态（发货事务的第一步）。
     *
     * <p>为什么一定要 {@code FOR UPDATE}：
     * 没有它，两个并发发货会同时读到"待发货"，然后都去扣库存——
     * 虽然后面的状态条件更新会让其中一个回滚，但两个事务都已经对库存行加过锁，
     * 无谓地放大了争抢。加行锁后，后到的那个在第一步就排队，
     * 拿到锁时看到的是"已发货"，直接走幂等分支，一次库存都不碰。
     */
    public Optional<LockedOrder> lockByOrderNo(String orderNo) {
        List<LockedOrder> rows = jdbcTemplate.query("""
                SELECT id, buyer_id, status
                FROM shop_order
                WHERE order_no = ?
                FOR UPDATE
                """, (rs, rowNum) -> new LockedOrder(
                rs.getLong("id"),
                rs.getString("buyer_id"),
                ShopOrderStatus.parse(rs.getString("status"))), orderNo);
        return rows.stream().findFirst();
    }

    /** 订单明细（A0 每单一条，取第一条）。 */
    public Optional<ShopOrderItem> findItem(long orderId) {
        return jdbcTemplate.query(
                        """
                                SELECT id, order_id, product_id, sku, product_name,
                                       unit_price_cents, quantity, line_cents, created_at
                                FROM shop_order_item
                                WHERE order_id = ?
                                ORDER BY id
                                """,
                        ITEM_MAPPER,
                        orderId)
                .stream().findFirst();
    }

    /**
     * 条件更新为已发货。
     *
     * @return 影响行数；0 表示状态已不是待发货（并发下必然发生）
     */
    public int markShipped(long orderId, String operatorId) {
        return jdbcTemplate.update("""
                UPDATE shop_order
                   SET status = ?, shipped_at = CURRENT_TIMESTAMP, shipped_by = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = ?
                """,
                ShopOrderStatus.SHIPPED.name(),
                operatorId,
                orderId,
                ShopOrderStatus.PENDING_SHIPMENT.name());
    }

    /**
     * 条件更新为已签收（只允许从已发货过来）。
     *
     * @return 影响行数；0 表示状态不是已发货
     */
    public int markSigned(String orderNo, String operatorId) {
        return jdbcTemplate.update("""
                UPDATE shop_order
                   SET status = ?, signed_at = CURRENT_TIMESTAMP, signed_by = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE order_no = ? AND status = ?
                """,
                ShopOrderStatus.SIGNED.name(),
                operatorId,
                orderNo,
                ShopOrderStatus.SHIPPED.name());
    }

    private static LocalDateTime toLocal(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /**
     * 发货事务里锁住的订单行。
     *
     * @param id      订单 id
     * @param buyerId 买家
     * @param status  加锁时读到的状态
     */
    public record LockedOrder(long id, String buyerId, ShopOrderStatus status) {
    }
}
