package com.example.mkagent.shop.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 商品仓库（商品主数据 + 结构化规格 + 库存余额聚合视图）。
 */
@Repository
public class ShopProductRepository {

    private static final String SELECT_SELLABLE = """
            SELECT p.id, p.sku, p.name, p.subtitle, p.price_cents, p.status,
                   p.category, p.connectivity, p.mac_compatible, p.silent_feature,
                   p.switch_type, p.battery_info, p.weight_grams,
                   COALESCE(b.available, 0) AS available
            FROM shop_product p
            LEFT JOIN shop_inventory_balance b ON b.sku = p.sku
            """;

    private static final RowMapper<ShopProduct> MAPPER = (rs, rowNum) -> new ShopProduct(
            rs.getLong("id"),
            rs.getString("sku"),
            rs.getString("name"),
            rs.getString("subtitle"),
            rs.getLong("price_cents"),
            rs.getInt("available"),
            rs.getString("status"),
            rs.getString("category"),
            rs.getString("connectivity"),
            (Boolean) rs.getObject("mac_compatible"),
            (Boolean) rs.getObject("silent_feature"),
            rs.getString("switch_type"),
            rs.getString("battery_info"),
            rs.getObject("weight_grams") == null ? null : rs.getInt("weight_grams")
    );

    private final JdbcTemplate jdbcTemplate;

    public ShopProductRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 全部上架商品（含已售罄的，available = 0）。 */
    public List<ShopProduct> findActive() {
        return jdbcTemplate.query(
                SELECT_SELLABLE + " WHERE p.status = 'ACTIVE' ORDER BY p.id",
                MAPPER);
    }

    /** 后台管理查看全部商品（含下架商品）。 */
    public List<ShopProduct> findAll() {
        return jdbcTemplate.query(
                SELECT_SELLABLE + " ORDER BY p.id",
                MAPPER);
    }

    /** 按 id 取上架商品。 */
    public Optional<ShopProduct> findActiveById(long id) {
        return jdbcTemplate.query(
                SELECT_SELLABLE + " WHERE p.id = ? AND p.status = 'ACTIVE'",
                MAPPER,
                id).stream().findFirst();
    }

    /** 按 id 取商品（无论上架/下架，用于结算重查准确区分“不存在”与“已下架”）。 */
    public Optional<ShopProduct> findById(long id) {
        return jdbcTemplate.query(
                SELECT_SELLABLE + " WHERE p.id = ?",
                MAPPER,
                id).stream().findFirst();
    }

    /** 按 SKU 取上架商品。 */
    public Optional<ShopProduct> findActiveBySku(String sku) {
        if (sku == null || sku.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                SELECT_SELLABLE + " WHERE UPPER(p.sku) = UPPER(?) AND p.status = 'ACTIVE'",
                MAPPER,
                sku.trim()).stream().findFirst();
    }

    /** 按 SKU 取商品（无论上架/下架）。 */
    public Optional<ShopProduct> findBySku(String sku) {
        if (sku == null || sku.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                SELECT_SELLABLE + " WHERE UPPER(p.sku) = UPPER(?)",
                MAPPER,
                sku.trim()).stream().findFirst();
    }

    /** 后台调价（用于演示价格变动后重新确认；历史订单行存快照不受影响）。 */
    public int updatePriceCents(long id, long priceCents) {
        return jdbcTemplate.update(
                "UPDATE shop_product SET price_cents = ? WHERE id = ?",
                priceCents, id);
    }

    /** 后台上下架（ACTIVE / INACTIVE）。 */
    public int updateStatus(long id, String status) {
        return jdbcTemplate.update(
                "UPDATE shop_product SET status = ? WHERE id = ?",
                status, id);
    }
}
