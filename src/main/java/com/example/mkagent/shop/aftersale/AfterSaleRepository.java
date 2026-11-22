package com.example.mkagent.shop.aftersale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 售后仓库。
 *
 * <h2>三条与 A0 一致的纪律</h2>
 * <ol>
 *   <li><b>买家侧的查询把归属写在 SQL 里</b>（{@code buyer_id = ?}），
 *       不做"先查出来再在 Java 里比对"——后者漏写一个 if 就是数据泄露；</li>
 *   <li><b>写操作都是条件更新</b>：额度扣减带 {@code reserved_qty + ? <= quantity_total}，
 *       返回 0 行即"额度不足"，调用方必须据此失败；</li>
 *   <li><b>建单前先锁订单行</b>（{@code FOR UPDATE}），
 *       让同一订单行的并发申请排队，而不是同时读到同一个剩余额度。</li>
 * </ol>
 *
 * <h2>为什么锁的是订单行而不是退货单</h2>
 *
 * 要保护的资源是"这一行的可退数量"。锁用户会让同一个人的两笔正常申请互相阻塞；
 * 锁退货单表更是无从锁起（新行还不存在）。订单行是唯一自然的串行化点，
 * 而且它与 A0 发货锁的是同一条记录——"同一订单行的实物数量"始终只有一个权威来源。
 */
@Repository
public class AfterSaleRepository {

    /**
     * 一次资格判定所需的全部事实。
     *
     * <p>{@code LEFT JOIN} 额度表：还没人申请过时额度行为空，按 0 占用处理。
     */
    private static final String SELECT_SUBJECT = """
            SELECT o.id AS order_id, o.order_no, o.buyer_id, o.status, o.signed_at,
                   i.id AS item_id, i.sku, i.product_name, i.unit_price_cents, i.quantity,
                   COALESCE(q.reserved_qty, 0) AS reserved_qty
            FROM shop_order o
            JOIN shop_order_item i ON i.order_id = o.id
            LEFT JOIN shop_aftersale_quota q ON q.order_item_id = i.id
            """;

    private static final RowMapper<ReturnSubject> SUBJECT_MAPPER = (rs, rowNum) -> new ReturnSubject(
            rs.getString("order_no"),
            rs.getLong("order_id"),
            rs.getString("buyer_id"),
            com.example.mkagent.shop.order.ShopOrderStatus.parse(rs.getString("status")),
            toLocal(rs.getTimestamp("signed_at")),
            rs.getLong("item_id"),
            rs.getString("sku"),
            rs.getString("product_name"),
            rs.getLong("unit_price_cents"),
            rs.getInt("quantity"),
            rs.getInt("reserved_qty"));

    private static final String SELECT_RETURN = """
            SELECT r.id, r.return_no, o.order_no, r.order_id, r.order_item_id, r.buyer_id,
                   r.sku, r.product_name, r.unit_price_cents, r.quantity, r.refund_cents,
                   r.reason, r.status, r.source, r.created_at, r.updated_at
            FROM shop_aftersale_return r
            JOIN shop_order o ON o.id = r.order_id
            """;

    private static final RowMapper<AfterSaleReturn> RETURN_MAPPER = (rs, rowNum) -> new AfterSaleReturn(
            rs.getLong("id"),
            rs.getString("return_no"),
            rs.getString("order_no"),
            rs.getLong("order_id"),
            rs.getLong("order_item_id"),
            rs.getString("buyer_id"),
            rs.getString("sku"),
            rs.getString("product_name"),
            rs.getLong("unit_price_cents"),
            rs.getInt("quantity"),
            rs.getLong("refund_cents"),
            rs.getString("reason"),
            AfterSaleStatus.parse(rs.getString("status")),
            AfterSaleSource.parse(rs.getString("source")),
            toLocal(rs.getTimestamp("created_at")),
            toLocal(rs.getTimestamp("updated_at")));

    private static final RowMapper<AfterSaleEvent> EVENT_MAPPER = (rs, rowNum) -> new AfterSaleEvent(
            rs.getLong("id"),
            rs.getLong("return_id"),
            rs.getString("event_type"),
            rs.getString("actor_id"),
            rs.getString("note"),
            toLocal(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbcTemplate;

    public AfterSaleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------------------------------------------------------
    // 读：资格判定所需事实
    // ---------------------------------------------------------------

    /**
     * 按订单号 + 买家取判定事实。
     *
     * <p>归属条件写在 SQL 里：别人的订单号查不到，与"不存在"不可区分。
     */
    public Optional<ReturnSubject> findSubject(String orderNo, String buyerId) {
        return jdbcTemplate.query(
                        SELECT_SUBJECT + " WHERE o.order_no = ? AND o.buyer_id = ?",
                        SUBJECT_MAPPER,
                        orderNo,
                        buyerId)
                .stream().findFirst();
    }

    /**
     * 买家的已签收订单（新的在前）。
     *
     * <p>窗口过滤放在 Java 里：列表要展示"已过期"的订单并解释原因，
     * 直接在 SQL 里过滤掉会让用户以为订单消失了。
     */
    public List<ReturnSubject> findSignedOrders(String buyerId, int limit) {
        return jdbcTemplate.query(
                SELECT_SUBJECT
                        + " WHERE o.buyer_id = ? AND o.status = ?"
                        + " ORDER BY o.signed_at DESC, o.id DESC LIMIT ?",
                SUBJECT_MAPPER,
                buyerId,
                com.example.mkagent.shop.order.ShopOrderStatus.SIGNED.name(),
                limit);
    }

    // ---------------------------------------------------------------
    // 读：退货单
    // ---------------------------------------------------------------

    public Optional<AfterSaleReturn> findReturnByNoAndBuyer(String returnNo, String buyerId) {
        return jdbcTemplate.query(
                        SELECT_RETURN + " WHERE r.return_no = ? AND r.buyer_id = ?",
                        RETURN_MAPPER,
                        returnNo,
                        buyerId)
                .stream().findFirst();
    }

    /** 内部回放用（不校验归属，调用方必须已经校验过键的归属）。 */
    public Optional<AfterSaleReturn> findReturnByNo(String returnNo) {
        return jdbcTemplate.query(
                        SELECT_RETURN + " WHERE r.return_no = ?",
                        RETURN_MAPPER,
                        returnNo)
                .stream().findFirst();
    }

    public List<AfterSaleReturn> findReturnsByBuyer(String buyerId, int limit) {
        return jdbcTemplate.query(
                SELECT_RETURN + " WHERE r.buyer_id = ? ORDER BY r.created_at DESC, r.id DESC LIMIT ?",
                RETURN_MAPPER,
                buyerId,
                limit);
    }

    /** 某订单行上所有退货单（含"已提交"的占用），用于详情里解释额度。 */
    public List<AfterSaleReturn> findReturnsByOrderItem(long orderItemId) {
        return jdbcTemplate.query(
                SELECT_RETURN + " WHERE r.order_item_id = ? ORDER BY r.created_at DESC, r.id DESC",
                RETURN_MAPPER,
                orderItemId);
    }

    public List<AfterSaleEvent> findEvents(long returnId) {
        return jdbcTemplate.query(
                """
                        SELECT id, return_id, event_type, actor_id, note, created_at
                        FROM shop_aftersale_event
                        WHERE return_id = ?
                        ORDER BY id
                        """,
                EVENT_MAPPER,
                returnId);
    }

    // ---------------------------------------------------------------
    // 写：事务内（调用方必须已开启事务）
    // ---------------------------------------------------------------

    /**
     * 锁定订单行并读取数量（建单事务的第一步）。
     *
     * <p>没有它，两个并发申请会同时读到"剩余 1 件"，然后各自通过额度条件更新
     * 之前的判定——最终只有一条 UPDATE 能成功，但失败方已经做了一次无谓的
     * 全流程判定，且错误信息会从"额度不足"退化成"约束冲突"。
     * 加锁后，后到的请求在第一步就排队，拿到锁时读到的已是扣减后的额度。
     */
    public Optional<LockedItem> lockOrderItem(long orderItemId) {
        List<LockedItem> rows = jdbcTemplate.query(
                "SELECT id, quantity FROM shop_order_item WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new LockedItem(rs.getLong("id"), rs.getInt("quantity")),
                orderItemId);
        return rows.stream().findFirst();
    }

    /**
     * 确保额度行存在，并把"已购数量"校准为订单行当前值。
     *
     * <p>先 UPDATE 再 INSERT，而不是 {@code INSERT ... WHERE NOT EXISTS}：
     * 两种写法在 H2 与 PostgreSQL 上语义一致，但前者不依赖
     * "无 FROM 的 SELECT + WHERE" 这种两边支持程度不同的语法。
     * 调用方持有订单行锁，因此这两步之间不会插进别的写者。
     */
    public void ensureQuota(long orderItemId, int quantityTotal) {
        int updated = jdbcTemplate.update("""
                UPDATE shop_aftersale_quota
                   SET quantity_total = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE order_item_id = ?
                """, quantityTotal, orderItemId);

        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO shop_aftersale_quota
                        (order_item_id, quantity_total, reserved_qty, updated_at)
                    VALUES (?, ?, 0, CURRENT_TIMESTAMP)
                    """, orderItemId, quantityTotal);
        }
    }

    /**
     * 原子占用额度。
     *
     * @return 影响行数；0 表示剩余额度不足（或额度行不存在）
     */
    public int reserveQuota(long orderItemId, int quantity) {
        return jdbcTemplate.update("""
                UPDATE shop_aftersale_quota
                   SET reserved_qty = reserved_qty + ?, updated_at = CURRENT_TIMESTAMP
                 WHERE order_item_id = ? AND reserved_qty + ? <= quantity_total
                """, quantity, orderItemId, quantity);
    }

    /** 读取额度（事务内用，调用方已持有订单行锁）。 */
    public Optional<AfterSaleQuota> findQuota(long orderItemId) {
        List<AfterSaleQuota> rows = jdbcTemplate.query(
                "SELECT quantity_total, reserved_qty FROM shop_aftersale_quota WHERE order_item_id = ?",
                (rs, rowNum) -> AfterSaleQuota.of(
                        rs.getInt("quantity_total"), rs.getInt("reserved_qty")),
                orderItemId);
        return rows.stream().findFirst();
    }

    /** 插入退货单，返回自增 id。 */
    public long insertReturn(
            String returnNo,
            long orderId,
            long orderItemId,
            String buyerId,
            String sku,
            String productName,
            long unitPriceCents,
            int quantity,
            long refundCents,
            String reason,
            AfterSaleStatus status,
            AfterSaleSource source
    ) {
        jdbcTemplate.update("""
                INSERT INTO shop_aftersale_return
                    (return_no, order_id, order_item_id, buyer_id, sku, product_name,
                     unit_price_cents, quantity, refund_cents, reason, status, source,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                returnNo, orderId, orderItemId, buyerId, sku, productName,
                unitPriceCents, quantity, refundCents, reason, status.name(), source.name());

        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM shop_aftersale_return WHERE return_no = ?", Long.class, returnNo);
        if (id == null) {
            throw new IllegalStateException("退货单插入后取不到 id：" + returnNo);
        }
        return id;
    }

    public void insertEvent(long returnId, String type, String actorId, String note) {
        jdbcTemplate.update("""
                INSERT INTO shop_aftersale_event
                    (return_id, event_type, actor_id, note, created_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, returnId, type, actorId, note);
    }

    // ---------------------------------------------------------------
    // 写：表单幂等记录
    // ---------------------------------------------------------------

    /**
     * 插入幂等占位行（必须是事务里**第一条**写语句）。
     *
     * <p>PostgreSQL 上唯一键冲突会让整个事务进入 aborted 状态，
     * 只有把它放在最前面，"冲突就整体回滚、再换新事务读赢家结果"才成立。
     *
     * @throws org.springframework.dao.DuplicateKeyException 同一键已存在
     */
    public void insertIdempotency(String key, String requestHash, String returnNo) {
        jdbcTemplate.update("""
                INSERT INTO shop_aftersale_idempotency
                    (idempotency_key, request_hash, return_no, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """, key, requestHash, returnNo);
    }

    public Optional<StoredIdempotency> findIdempotency(String key) {
        List<StoredIdempotency> rows = jdbcTemplate.query(
                """
                        SELECT idempotency_key, request_hash, return_no
                        FROM shop_aftersale_idempotency
                        WHERE idempotency_key = ?
                        """,
                (rs, rowNum) -> new StoredIdempotency(
                        rs.getString("idempotency_key"),
                        rs.getString("request_hash"),
                        rs.getString("return_no")),
                key);
        return rows.stream().findFirst();
    }

    private static LocalDateTime toLocal(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /**
     * 事务里锁住的订单行。
     *
     * @param id       订单行 id
     * @param quantity 已购数量（额度上限）
     */
    public record LockedItem(long id, int quantity) {
    }

    /**
     * 已落库的幂等记录。
     *
     * @param key         幂等键
     * @param requestHash 请求哈希（用于检测"同键不同参数"）
     * @param returnNo    首次成功创建的退货单号
     */
    public record StoredIdempotency(String key, String requestHash, String returnNo) {
    }
}
