package com.example.mkagent.shop.inventory;

import java.time.LocalDateTime;

/**
 * 库存流水（只增不改）。
 *
 * @param id            流水 id
 * @param sku           SKU
 * @param quantityDelta 变动数量：出库为负
 * @param movementType  业务类型（A0 只有 {@code OUTBOUND_SHIPMENT}）
 * @param orderId       来源订单 id（可为空：将来的手工调整没有订单）
 * @param orderItemId   来源订单行 id（重复发货的唯一性靠它 + 类型）
 * @param operatorId    操作人（发货时是后台演示人员）
 * @param note          备注（不含任何凭据或个人敏感信息）
 * @param createdAt     发生时间（服务端时钟）
 */
public record InventoryMovement(
        long id,
        String sku,
        int quantityDelta,
        String movementType,
        Long orderId,
        Long orderItemId,
        String operatorId,
        String note,
        LocalDateTime createdAt
) {

    /** 出库（发货）。 */
    public static final String TYPE_OUTBOUND_SHIPMENT = "OUTBOUND_SHIPMENT";
}
