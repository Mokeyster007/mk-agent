package com.example.mkagent.shop.aftersale;

/**
 * 退货预览：**只读**的"如果现在提交会怎样"。
 *
 * <p>它与真正的提交共用同一个判定函数（{@link AfterSaleEligibility}），
 * 但**不写任何东西**：不占额度、不建单、不写事件。
 *
 * <p>预览的唯一职责是让用户/模型看懂"退几件、退多少钱、为什么不能退"。
 * 它**不是资格凭证**——从生成预览到用户点确认之间，额度可能被另一笔申请用掉，
 * 因此提交时必须在事务内重新判定一次（见 {@code AfterSaleWriter}）。
 *
 * @param orderNo        订单号
 * @param orderItemId    订单行 id
 * @param sku            SKU 快照
 * @param productName    商品名快照
 * @param unitPriceCents 成交单价快照（分）
 * @param requestedQty   本次想退的数量
 * @param refundCents    估算退款金额（分）；不可退时为 0
 * @param quota          当前额度（已购 / 已占用 / 剩余）
 * @param eligible       当前是否可以提交
 * @param code           不可提交时的稳定原因码
 * @param message        给用户看的说明
 */
public record AfterSalePreview(
        String orderNo,
        long orderItemId,
        String sku,
        String productName,
        long unitPriceCents,
        int requestedQty,
        long refundCents,
        AfterSaleQuota quota,
        boolean eligible,
        String code,
        String message
) {
}
