package com.example.mkagent.shop.cart;

import java.time.LocalDateTime;

/**
 * 共享购物车行（商品实时价格、上架状态与当前库存来自商品/库存表）。
 *
 * <p>加购不等于下单，也不锁定库存。
 */
public record ShopCartItem(
        long id,
        String buyerId,
        long productId,
        String sku,
        String productName,
        String subtitle,
        long currentUnitPriceCents,
        int quantity,
        long lineCents,
        int available,
        String productStatus,
        LocalDateTime updatedAt
) {

    public boolean active() {
        return "ACTIVE".equals(productStatus);
    }

    public boolean checkoutReady() {
        return active() && available >= quantity;
    }
}
