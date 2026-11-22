package com.example.mkagent.shop.aftersale;

import com.example.mkagent.shop.order.ShopOrderStatus;

import java.time.LocalDateTime;

/**
 * 一次退货资格判定所需的全部事实（只读快照）。
 *
 * <p>字段全部来自数据库：订单归属与状态来自 {@code shop_order}，
 * 数量与商品快照来自 {@code shop_order_item}，已占额度来自 {@code shop_aftersale_quota}。
 * **没有一个字段来自请求体或模型参数**——请求只能提供"想退几件"和"为什么退"。
 *
 * @param orderNo        订单号
 * @param orderId        订单 id（退货单的外键）
 * @param buyerId        订单归属的买家
 * @param status         订单状态
 * @param signedAt       签收时间（售后起算点）
 * @param orderItemId    订单行 id
 * @param sku            SKU 快照
 * @param productName    商品名快照
 * @param unitPriceCents 成交单价快照（分）
 * @param purchasedQty   该行已购数量
 * @param occupiedQty    已被"已提交"退货单占用的数量
 */
public record ReturnSubject(
        String orderNo,
        long orderId,
        String buyerId,
        ShopOrderStatus status,
        LocalDateTime signedAt,
        long orderItemId,
        String sku,
        String productName,
        long unitPriceCents,
        int purchasedQty,
        int occupiedQty
) {

    /** 剩余可退额度。 */
    public AfterSaleQuota quota() {
        return AfterSaleQuota.of(purchasedQty, occupiedQty);
    }
}
