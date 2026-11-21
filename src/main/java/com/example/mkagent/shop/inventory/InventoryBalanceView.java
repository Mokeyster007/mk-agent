package com.example.mkagent.shop.inventory;

/**
 * 库存余额视图（后台核对用）。
 *
 * @param sku          SKU
 * @param productId    商品 id
 * @param productName  商品名（联表取，便于人工核对）
 * @param available    当前可售数量
 */
public record InventoryBalanceView(
        String sku,
        long productId,
        String productName,
        int available
) {
}
