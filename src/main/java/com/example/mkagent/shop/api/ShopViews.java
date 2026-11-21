package com.example.mkagent.shop.api;

import com.example.mkagent.shop.ShopAccessGuard;
import com.example.mkagent.shop.ShopRole;
import com.example.mkagent.shop.catalog.ShopProduct;
import com.example.mkagent.shop.inventory.InventoryBalanceView;
import com.example.mkagent.shop.inventory.InventoryMovement;
import com.example.mkagent.shop.order.ShopOrder;
import com.example.mkagent.shop.order.ShopOrderDetail;
import com.example.mkagent.shop.order.ShopOrderItem;
import com.example.mkagent.shop.order.ShopOrderStatus;
import com.example.mkagent.shop.order.ShopOrderSummary;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商城接口的响应对象与映射。
 *
 * <p>为什么不直接把领域 record 返回给前端：
 * <ol>
 *   <li>领域对象的字段会随内部实现变（例如以后给订单加内部备注），
 *       直接暴露等于把内部结构变成对外契约；</li>
 *   <li>界面需要的派生信息（金额文案、能不能发货、状态中文）应该在服务端算一次，
 *       而不是让三个页面各写一份判断——三份判断迟早会不一致。</li>
 * </ol>
 *
 * <p>时间字段用 {@link LocalDateTime}：Jackson 会序列化成
 * {@code 2026-11-22T00:16:11}，与前端 {@code formatDateTime} 的既有约定一致。
 */
public final class ShopViews {

    private ShopViews() {
    }

    /**
     * 商品。
     *
     * @param purchasable 是否可下单：只由上架状态决定，**不看库存**。
     *                    下单不预占库存，所以"暂时无货"仍可下单；
     *                    库存是否够用由发货时判定，页面只提示风险。
     */
    public record ProductVO(
            long id,
            String sku,
            String name,
            String subtitle,
            long priceCents,
            String priceText,
            int available,
            boolean purchasable
    ) {
    }

    /** 订单明细（成交快照）。 */
    public record OrderItemVO(
            long productId,
            String sku,
            String productName,
            long unitPriceCents,
            String unitPriceText,
            int quantity,
            long lineCents,
            String lineText
    ) {
    }

    /** 库存流水。 */
    public record MovementVO(
            long id,
            String sku,
            int quantityDelta,
            String type,
            Long orderId,
            Long orderItemId,
            String operatorId,
            LocalDateTime createdAt
    ) {
    }

    /**
     * 订单。
     *
     * @param canShip     当前是否允许发货（服务端状态机判定，前端不重复实现）
     * @param canSign     当前是否允许签收
     * @param movements   库存流水；列表接口为 null（未加载），详情接口为数组
     */
    public record OrderVO(
            String orderNo,
            String status,
            String statusLabel,
            long totalCents,
            String totalText,
            String paymentMode,
            String buyerId,
            LocalDateTime createdAt,
            LocalDateTime shippedAt,
            String shippedBy,
            LocalDateTime signedAt,
            String signedBy,
            boolean canShip,
            boolean canSign,
            OrderItemVO item,
            List<MovementVO> movements
    ) {
    }

    /** 发货 / 签收的结果。 */
    public record ActionResultVO(boolean applied, String message, OrderVO order) {
    }

    /** 当前会话：身份、角色、能做什么。 */
    public record SessionVO(
            String userId,
            String role,
            String identityMode,
            boolean canOrder,
            boolean canOperate
    ) {
    }

    /** 库存余额行。 */
    public record BalanceVO(String sku, long productId, String productName, int available) {
    }

    /** 后台库存核对视图。 */
    public record InventoryVO(List<BalanceVO> balances, List<MovementVO> movements) {
    }

    /** 分页结果。 */
    public record PageVO<T>(List<T> items, int page, int size, long total) {
    }

    /**
     * 金额（分）→ {@code ¥299.00}。
     *
     * <p>用 {@link BigDecimal} 而不是 {@code cents / 100.0}：
     * 后者会引入二进制浮点误差，299.00 可能显示成 298.99999999999994。
     */
    public static String money(long cents) {
        return "¥" + BigDecimal.valueOf(cents, 2).toPlainString();
    }

    public static ProductVO product(ShopProduct product) {
        return new ProductVO(
                product.id(),
                product.sku(),
                product.name(),
                product.subtitle(),
                product.priceCents(),
                money(product.priceCents()),
                product.available(),
                product.purchasable());
    }

    public static OrderItemVO item(ShopOrderItem item) {
        return new OrderItemVO(
                item.productId(),
                item.sku(),
                item.productName(),
                item.unitPriceCents(),
                money(item.unitPriceCents()),
                item.quantity(),
                item.lineCents(),
                money(item.lineCents()));
    }

    public static MovementVO movement(InventoryMovement movement) {
        return new MovementVO(
                movement.id(),
                movement.sku(),
                movement.quantityDelta(),
                movement.movementType(),
                movement.orderId(),
                movement.orderItemId(),
                movement.operatorId(),
                movement.createdAt());
    }

    /**
     * 订单摘要（列表用，不含流水）。
     *
     * <p>{@code movements} 显式给 null 而不是空数组：
     * 空数组的意思是"这个订单没有任何流水"，而列表接口根本没查，
     * 两者混同会让页面显示"未出库"——那是一个错误的事实。
     */
    public static OrderVO summary(ShopOrderSummary summary) {
        return order(summary.order(), summary.item(), null);
    }

    public static OrderVO detail(ShopOrderDetail detail) {
        return order(
                detail.order(),
                detail.item(),
                detail.movements().stream().map(ShopViews::movement).toList());
    }

    private static OrderVO order(ShopOrder order, ShopOrderItem item, List<MovementVO> movements) {
        ShopOrderStatus status = order.status();
        return new OrderVO(
                order.orderNo(),
                status == null ? "UNKNOWN" : status.name(),
                status == null ? "未知状态" : status.label(),
                order.totalCents(),
                money(order.totalCents()),
                order.paymentMode(),
                order.buyerId(),
                order.createdAt(),
                order.shippedAt(),
                order.shippedBy(),
                order.signedAt(),
                order.signedBy(),
                status != null && status.canShip(),
                status != null && status.canSign(),
                item == null ? null : item(item),
                movements);
    }

    public static SessionVO session(ShopAccessGuard.ShopSession session) {
        return new SessionVO(
                session.userId(),
                session.role() == ShopRole.UNKNOWN ? "UNKNOWN" : session.role().name(),
                session.identityMode(),
                session.canOrder(),
                session.canOperate());
    }

    public static BalanceVO balance(InventoryBalanceView balance) {
        return new BalanceVO(
                balance.sku(),
                balance.productId(),
                balance.productName(),
                balance.available());
    }
}
