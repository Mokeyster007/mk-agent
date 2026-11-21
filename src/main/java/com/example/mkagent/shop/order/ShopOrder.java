package com.example.mkagent.shop.order;

import java.time.LocalDateTime;

/**
 * 销售订单（不含明细）。
 *
 * <p>{@code totalCents} 是下单时算好的金额快照，
 * 不是每次读的时候拿明细重新乘一遍：
 * 明细的单价是成交快照，两者必须一致；重新计算会让"改了一行明细"
 * 悄悄改掉历史订单金额。
 *
 * @param id          订单 id（内部用；对外只用 orderNo）
 * @param orderNo     对外订单号
 * @param buyerId     买家（服务端从身份取，不来自请求体）
 * @param status      状态
 * @param totalCents  订单总额（分）
 * @param paymentMode 付款方式（A0 固定 DEMO）
 * @param createdAt   下单时间
 * @param shippedAt   发货时间（未发货为 null）
 * @param shippedBy   发货操作人
 * @param signedAt    签收时间（未签收为 null）
 * @param signedBy    签收操作人
 */
public record ShopOrder(
        long id,
        String orderNo,
        String buyerId,
        ShopOrderStatus status,
        long totalCents,
        String paymentMode,
        LocalDateTime createdAt,
        LocalDateTime shippedAt,
        String shippedBy,
        LocalDateTime signedAt,
        String signedBy
) {

    /** 演示付款：本轮没有真实资金交易。 */
    public static final String PAYMENT_MODE_DEMO = "DEMO";
}
