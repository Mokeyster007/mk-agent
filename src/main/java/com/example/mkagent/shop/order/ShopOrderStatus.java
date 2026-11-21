package com.example.mkagent.shop.order;

/**
 * 订单状态。
 *
 * <p>只有三条边：{@code PENDING_SHIPMENT → SHIPPED → SIGNED}。
 * 没有取消、没有回退、没有"部分发货"——本轮刻意不做：
 * 每多一条边，"谁在什么条件下能走"就要多定义一次，
 * 而 A0 的目标是把"发货扣库存"这条链路的并发与事务做对。
 *
 * <p>同一套字面量同时写在数据库的 {@code ck_shop_order_status} 里：
 * 应用层写错一个字符串会直接失败，而不是产生一个谁也不认识的状态。
 */
public enum ShopOrderStatus {

    /** 待发货：下单后的唯一初始状态。此时库存**未**扣减。 */
    PENDING_SHIPMENT("待发货"),

    /** 已发货：库存已扣减、出库流水已写。 */
    SHIPPED("已发货"),

    /** 已签收：只记录签收时间，不再变动库存。 */
    SIGNED("已签收");

    private final String label;

    ShopOrderStatus(String label) {
        this.label = label;
    }

    /** 中文标签（界面直接显示；接口同时返回枚举值，前端不靠文案分支）。 */
    public String label() {
        return label;
    }

    /** 是否可以发货。 */
    public boolean canShip() {
        return this == PENDING_SHIPMENT;
    }

    /** 是否可以签收（只有已发货可以）。 */
    public boolean canSign() {
        return this == SHIPPED;
    }

    /**
     * 解析数据库/接口里的状态字符串。
     *
     * @return 匹配的枚举；不认识的值返回 null（调用方决定报错还是忽略）
     */
    public static ShopOrderStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        for (ShopOrderStatus status : values()) {
            if (status.name().equals(normalized)) {
                return status;
            }
        }
        return null;
    }
}
