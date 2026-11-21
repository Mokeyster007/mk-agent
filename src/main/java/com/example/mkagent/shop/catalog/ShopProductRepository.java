package com.example.mkagent.shop.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 商品只读仓库。
 *
 * <p>为什么用 JdbcTemplate 而不是 MyBatis-Plus：
 * 商品列表需要 LEFT JOIN 库存余额（聚合视图），本项目的约定是
 * "按主键取单行用 MyBatis-Plus，联表/聚合用 JdbcTemplate"（见 MusicCatalogRepository）。
 * 商城写路径全是条件更新与行锁，也在 JdbcTemplate 上，保持一处风格。
 *
 * <p>下架（INACTIVE）商品对买家不可见，但仍然留在表里：
 * 历史订单行存的是快照，不依赖商品是否还在售。
 */
@Repository
public class ShopProductRepository {

    /**
     * 商品 + 可售库存。
     *
     * <p>{@code LEFT JOIN} + {@code COALESCE}：没有库存行时按 0 可售处理，
     * 而不是让商品从列表里消失——"卖完了"和"不存在"必须能区分。
     */
    private static final String SELECT_SELLABLE = """
            SELECT p.id, p.sku, p.name, p.subtitle, p.price_cents, p.status,
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
            rs.getString("status"));

    private final JdbcTemplate jdbcTemplate;

    public ShopProductRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 全部上架商品（含已售罄的，{@code available = 0}）。
     *
     * <p>不在这里过滤库存：页面需要显示"已售罄"，
     * 直接筛掉会让用户以为商品被删了。
     */
    public List<ShopProduct> findActive() {
        return jdbcTemplate.query(
                SELECT_SELLABLE + " WHERE p.status = 'ACTIVE' ORDER BY p.id",
                MAPPER);
    }

    /**
     * 按 id 取上架商品（下单时用）。
     *
     * <p>下架商品返回空：下单请求不能靠"客户端拿到了 id"就通过。
     */
    public Optional<ShopProduct> findActiveById(long id) {
        List<ShopProduct> rows = jdbcTemplate.query(
                SELECT_SELLABLE + " WHERE p.id = ? AND p.status = 'ACTIVE'",
                MAPPER,
                id);
        return rows.stream().findFirst();
    }
}
