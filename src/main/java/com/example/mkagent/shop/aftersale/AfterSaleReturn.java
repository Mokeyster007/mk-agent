package com.example.mkagent.shop.aftersale;

import java.time.LocalDateTime;

/**
 * 退货申请（退货单）。
 *
 * <p>所有商品字段都是**订单行快照的副本**：{@code sku} / {@code productName} /
 * {@code unitPriceCents} 来自提交那一刻的 {@code shop_order_item}。
 * 不在这里 join 商品表现查——商品改名或调价之后，
 * 已提交的申请必须仍然显示"当时退的是哪件、按什么价"。
 *
 * @param id             内部 id（对外只用 returnNo）
 * @param returnNo       对外退货单号
 * @param orderNo        原订单号（页面与工具都按订单号定位，不暴露订单 id）
 * @param orderId        原订单 id
 * @param orderItemId    原订单行 id（额度与流水都挂在它上面）
 * @param buyerId        买家（服务端身份，不来自请求体）
 * @param sku            SKU 快照
 * @param productName    商品名快照
 * @param unitPriceCents 成交单价快照（分）
 * @param quantity       本次退货数量
 * @param refundCents    估算退款金额（分）= 单价 × 数量
 * @param reason         退货原因（已归一化：去首尾空白、折叠连续空白）
 * @param status         状态（A1 只有 SUBMITTED）
 * @param source         来源（FORM / AGENT）
 * @param createdAt      提交时间
 * @param updatedAt      更新时间
 */
public record AfterSaleReturn(
        long id,
        String returnNo,
        String orderNo,
        long orderId,
        long orderItemId,
        String buyerId,
        String sku,
        String productName,
        long unitPriceCents,
        int quantity,
        long refundCents,
        String reason,
        AfterSaleStatus status,
        AfterSaleSource source,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
