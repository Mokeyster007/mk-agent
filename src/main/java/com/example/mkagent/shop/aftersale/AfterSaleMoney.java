package com.example.mkagent.shop.aftersale;

/**
 * 退款金额计算：**预览与提交共用同一个实现**。
 *
 * <h2>为什么必须共用</h2>
 *
 * 两处各写一行乘法，就会出现"预览显示一个数、提交算出另一个数"，
 * 或者更糟——预览溢出回绕成负数（页面上显示「预计退款 -¥21474836.48」），
 * 而提交时因为用了 {@code multiplyExact} 直接拒绝。
 * 用户看到的是两个互相矛盾的系统。
 *
 * <h2>为什么是整数分</h2>
 *
 * 金额一律用 {@code long} 分表示，不出现浮点：{@code 29900 × 3} 永远是 89700，
 * 不存在 0.1 + 0.2 这类问题。展示时才由 {@code ShopViews.money} 转成两位小数。
 *
 * <h2>溢出</h2>
 *
 * {@link Math#multiplyExact(long, long)} 在超出 {@code long} 范围时抛
 * {@link ArithmeticException}，而不是静默回绕。调用方**必须**处理：
 * 预览把它转成"不可提交 + 明确原因"，提交把它转成 400 并整体回滚。
 * 绝不允许一个算错的金额落库。
 */
public final class AfterSaleMoney {

    private AfterSaleMoney() {
    }

    /**
     * 退款金额（分）= 成交单价（分）× 数量。
     *
     * @throws ArithmeticException 结果超出 {@code long} 范围
     */
    public static long refundCents(long unitPriceCents, int quantity) {
        return Math.multiplyExact(unitPriceCents, (long) quantity);
    }
}
