package com.example.mkagent.shop.aftersale;

/**
 * 订单行的可退额度。
 *
 * <p>三个数必须能分开回答，不能只留一个"剩余"：
 * <ul>
 *   <li>{@code purchased}：这行一共买了多少（{@code shop_order_item.quantity}）；</li>
 *   <li>{@code occupied}：已被多少张"已提交"的退货单占用；</li>
 *   <li>{@code remaining}：还能退多少 = purchased − occupied。</li>
 * </ul>
 *
 * <p>为什么要单独记 {@code occupied} 而不是每次 SUM 退货单：
 * 它让"绝不超退"变成数据库可以检查的事实
 * （{@code CHECK (reserved_qty >= 0 AND reserved_qty <= quantity_total)}），
 * 而"SUM 一遍再判断"只能靠应用层每次都写对。
 *
 * @param purchased 已购数量
 * @param occupied  已占用额度
 * @param remaining 剩余可退数量
 */
public record AfterSaleQuota(int purchased, int occupied, int remaining) {

    /**
     * 构造时就把 remaining 算出来，避免三处各自减一遍减错。
     */
    public static AfterSaleQuota of(int purchased, int occupied) {
        return new AfterSaleQuota(purchased, occupied, Math.max(0, purchased - occupied));
    }

    /** 还能不能退至少 1 件。 */
    public boolean hasAny() {
        return remaining > 0;
    }
}
