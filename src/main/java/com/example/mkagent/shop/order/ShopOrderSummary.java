package com.example.mkagent.shop.order;

/**
 * 订单摘要（列表用）：订单头 + 明细，不含库存流水。
 *
 * <p>列表刻意不查流水：一页 20 条订单会多出 20 次查询，
 * 而列表根本不展示流水。需要流水的是订单详情与后台核对。
 */
public record ShopOrderSummary(ShopOrder order, ShopOrderItem item) {
}
