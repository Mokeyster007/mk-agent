package com.example.mkagent.shop.order;

import java.time.LocalDateTime;

/**
 * 订单明细（成交快照）。
 *
 * <p>{@code sku} / {@code productName} / {@code unitPriceCents} 都是**下单当时**的值：
 * 商品改名、调价之后，历史订单仍然显示"当时买的是什么、多少钱"。
 * {@code productId} 只用于追溯，不用来现查价格。
 *
 * @param id             明细 id（库存流水的唯一性挂在它上面）
 * @param orderId        所属订单
 * @param productId      商品 id（追溯用）
 * @param sku            SKU 快照（出库按它扣库存）
 * @param productName    商品名快照
 * @param unitPriceCents 成交单价快照（分）
 * @param quantity       购买数量
 * @param lineCents      行小计 = 单价 × 数量
 * @param createdAt      创建时间
 */
public record ShopOrderItem(
        long id,
        long orderId,
        long productId,
        String sku,
        String productName,
        long unitPriceCents,
        int quantity,
        long lineCents,
        LocalDateTime createdAt
) {
}
