package com.example.mkagent.shop.catalog;

/**
 * 可售商品视图（商品 + 当前可售库存）。
 *
 * <p>{@code available} 来自库存余额表，不是商品表上的冗余计数：
 * 商品是"卖什么"，库存是"还有多少"，两者的变更频率与责任方都不同。
 *
 * <p>没有库存行的商品 {@code available = 0}：
 * "没有库存记录"和"库存为零"在**可售性**上是同一件事，
 * 但在排查时要能分开——所以库存表缺行会在管理端单独看得出来（余额列表里没有这一行）。
 *
 * @param id         商品 id（服务端生成的固定种子 id）
 * @param sku        SKU 编码（订单行与库存流水都用它做关联）
 * @param name       商品名
 * @param subtitle   副标题（卖点 / 规格简述）
 * @param priceCents 售价（分）
 * @param available  当前可售数量
 * @param status     商品状态（ACTIVE / INACTIVE）
 */
public record ShopProduct(
        long id,
        String sku,
        String name,
        String subtitle,
        long priceCents,
        int available,
        String status
) {

    /**
     * 是否可下单。
     *
     * <p>只看是否上架，**不看库存**：A0 的规则是"下单不预占、不扣库存"，
     * 因此"有没有货"不是下单的前提条件，而是发货时才校验的条件。
     * 如果这里按库存拦截，浏览器里就永远走不到"库存不足 → 发货失败"这条路径，
     * 验收里的那条规则也就无法被真实复现。
     *
     * <p>页面仍然展示 {@code available}，让用户在点之前知道风险，
     * 但"能不能点"与"点了会不会成功"是两件事。
     */
    public boolean purchasable() {
        return "ACTIVE".equals(status);
    }
}
