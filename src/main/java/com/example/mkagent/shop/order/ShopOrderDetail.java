package com.example.mkagent.shop.order;

import com.example.mkagent.shop.inventory.InventoryMovement;

import java.util.List;

/**
 * 订单详情：订单头 + 明细 + 该订单产生的库存流水。
 *
 * <p>流水放在详情里而不是单独一个接口，是为了让"这个订单到底出库了没有"
 * 一次查询就能核对完——A0 的验收就是"订单、状态、出库记录"三件事同时可见。
 */
public record ShopOrderDetail(
        ShopOrder order,
        ShopOrderItem item,
        List<InventoryMovement> movements
) {

    public ShopOrderDetail {
        movements = movements == null ? List.of() : List.copyOf(movements);
    }
}
