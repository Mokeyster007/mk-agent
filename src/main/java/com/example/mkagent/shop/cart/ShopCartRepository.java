package com.example.mkagent.shop.cart;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 共享购物车仓库（按 buyer_id 强隔离）。
 */
@Repository
public class ShopCartRepository {

    private static final String SELECT_CART = """
            SELECT c.id, c.buyer_id, c.product_id, p.sku, p.name AS product_name,
                   p.subtitle, p.price_cents, c.quantity, p.status AS product_status,
                   COALESCE(b.available, 0) AS available, c.updated_at
            FROM shop_cart_item c
            JOIN shop_product p ON p.id = c.product_id
            LEFT JOIN shop_inventory_balance b ON b.sku = p.sku
            """;

    private static final RowMapper<ShopCartItem> MAPPER = (rs, rowNum) -> {
        long unitPrice = rs.getLong("price_cents");
        int qty = rs.getInt("quantity");
        return new ShopCartItem(
                rs.getLong("id"),
                rs.getString("buyer_id"),
                rs.getLong("product_id"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getString("subtitle"),
                unitPrice,
                qty,
                Math.multiplyExact(unitPrice, (long) qty),
                rs.getInt("available"),
                rs.getString("product_status"),
                toLocal(rs.getTimestamp("updated_at"))
        );
    };

    private final JdbcTemplate jdbcTemplate;

    public ShopCartRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ShopCartItem> findByBuyer(String buyerId) {
        return jdbcTemplate.query(
                SELECT_CART + " WHERE c.buyer_id = ? ORDER BY c.updated_at DESC, c.id DESC",
                MAPPER,
                buyerId);
    }

    public Optional<ShopCartItem> findByBuyerAndProduct(String buyerId, long productId) {
        return jdbcTemplate.query(
                SELECT_CART + " WHERE c.buyer_id = ? AND c.product_id = ?",
                MAPPER,
                buyerId,
                productId).stream().findFirst();
    }

    public void insert(String buyerId, long productId, String sku, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO shop_cart_item
                    (buyer_id, product_id, sku, quantity, created_at, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, buyerId, productId, sku, quantity);
    }

    public int updateQuantity(String buyerId, long productId, int quantity) {
        return jdbcTemplate.update("""
                UPDATE shop_cart_item
                   SET quantity = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE buyer_id = ? AND product_id = ?
                """, quantity, buyerId, productId);
    }

    public int delete(String buyerId, long productId) {
        return jdbcTemplate.update(
                "DELETE FROM shop_cart_item WHERE buyer_id = ? AND product_id = ?",
                buyerId, productId);
    }

    private static LocalDateTime toLocal(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
