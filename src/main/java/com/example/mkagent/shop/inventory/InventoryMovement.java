package com.example.mkagent.shop.inventory;

import java.time.LocalDateTime;

/**
 * 库存流水（只增不改）。
 *
 * @param id            流水 id
 * @param sku           SKU
 * @param quantityDelta 变动数量：出库为负，退货入库为正
 * @param movementType  业务类型（{@code OUTBOUND_SHIPMENT} / {@code RETURN_INBOUND}）
 * @param orderId       来源订单 id（可为空：将来的手工调整没有订单）
 * @param orderItemId   来源订单行 id（出库与退货入库都指向它）
 * @param receiptId     退货入库对应的收货记录 id（出库为 {@code null}）
 * @param dedupeKey     一次业务动作一条流水：{@code OUT:{订单行id}} / {@code IN:{收货记录id}}
 * @param operatorId    操作人（发货 / 收货时的后台演示人员）
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
        Long receiptId,
        String dedupeKey,
        String operatorId,
        String note,
        LocalDateTime createdAt
) {

    /** 出库（发货）。 */
    public static final String TYPE_OUTBOUND_SHIPMENT = "OUTBOUND_SHIPMENT";

    /** 退货入库（仓库验货合格后回补可售库存）。 */
    public static final String TYPE_RETURN_INBOUND = "RETURN_INBOUND";

    /** 出库动作的去重键：同一订单行只可能发一次货。 */
    public static String outboundKey(long orderItemId) {
        return "OUT:" + orderItemId;
    }

    /** 入库动作的去重键：同一张收货记录只可能入一次库。 */
    public static String inboundKey(long receiptId) {
        return "IN:" + receiptId;
    }
}
