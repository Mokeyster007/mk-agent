package com.example.mkagent.shop.order;

/**
 * 后台操作（发货 / 签收）的结果。
 *
 * <p>{@code applied} 是关键字段：
 * <ul>
 *   <li>{@code true}：本次请求真的改变了状态（扣了库存 / 记了签收时间）；</li>
 *   <li>{@code false}：状态本来就已经到位，本次**什么都没写**。</li>
 * </ul>
 *
 * <p>为什么重复操作返回 200 而不是 409：
 * 重试是正常行为（网络抖动、用户连点、运维脚本重跑）。
 * 对"已经是目标状态"的请求返回错误，会让人误以为操作失败、
 * 进而去手工改库——那才是真正的数据事故来源。
 * 但必须让调用方**能看出**这次没做事，所以给 {@code applied} 和一句明确文案，
 * 而不是假装成功。
 */
public record OrderActionResult(boolean applied, String message, ShopOrderDetail detail) {

    public static OrderActionResult applied(String message, ShopOrderDetail detail) {
        return new OrderActionResult(true, message, detail);
    }

    public static OrderActionResult notApplied(String message, ShopOrderDetail detail) {
        return new OrderActionResult(false, message, detail);
    }
}
