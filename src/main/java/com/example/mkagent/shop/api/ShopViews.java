package com.example.mkagent.shop.api;

import com.example.mkagent.shop.ShopAccessGuard;
import com.example.mkagent.shop.ShopRole;
import com.example.mkagent.shop.cart.ShopCartItem;
import com.example.mkagent.shop.cart.ShopCartService;
import com.example.mkagent.shop.catalog.ShopCatalogService;
import com.example.mkagent.shop.catalog.ShopProduct;
import com.example.mkagent.shop.inventory.InventoryBalanceView;
import com.example.mkagent.shop.inventory.InventoryMovement;
import com.example.mkagent.shop.order.ShopCheckoutService;
import com.example.mkagent.shop.order.ShopOrder;
import com.example.mkagent.shop.order.ShopOrderDetail;
import com.example.mkagent.shop.order.ShopOrderItem;
import com.example.mkagent.shop.order.ShopOrderStatus;
import com.example.mkagent.shop.order.ShopOrderSummary;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 商城接口的响应对象与映射（商品规格、比较、共享购物车、结算预览/确认、订单、库存）。
 */
public final class ShopViews {

    private ShopViews() {
    }

    public record ProductVO(
            long id,
            String sku,
            String name,
            String subtitle,
            long priceCents,
            String priceText,
            int available,
            boolean purchasable,
            String status,
            String category,
            String categoryLabel,
            String connectivity,
            String connectivityLabel,
            boolean wireless,
            Boolean macCompatible,
            String macCompatibleLabel,
            Boolean silentFeature,
            String silentLabel,
            String switchType,
            String batteryInfo,
            Integer weightGrams,
            Map<String, String> specs
    ) {
    }

    public record CatalogSearchVO(
            List<ProductVO> items,
            Map<String, Object> appliedHardFilters,
            List<String> softPreferences,
            List<String> relaxSuggestions
    ) {
    }

    public record ComparisonDimensionVO(String label, List<String> values) {
    }

    public record ComparisonVO(
            List<ProductVO> products,
            List<ComparisonDimensionVO> dimensions
    ) {
    }

    public record CartItemVO(
            long id,
            long productId,
            String sku,
            String productName,
            String subtitle,
            long currentUnitPriceCents,
            String currentUnitPriceText,
            int quantity,
            long lineCents,
            String lineText,
            int available,
            boolean active,
            boolean checkoutReady
    ) {
    }

    public record CartVO(
            List<CartItemVO> items,
            int totalQuantity,
            long totalCents,
            String totalText,
            String singleSkuNotice,
            String stockNotice
    ) {
    }

    public record CheckoutPreviewVO(
            long productId,
            String sku,
            String productName,
            String subtitle,
            int quantity,
            long unitPriceCents,
            String unitPriceText,
            long totalCents,
            String totalText,
            int available,
            boolean active,
            boolean eligible,
            String code,
            String message,
            String stockNotice,
            String singleSkuNotice
    ) {
    }

    public record CheckoutConfirmVO(
            boolean replayed,
            OrderVO order
    ) {
    }

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

    public record MovementVO(
            long id,
            String sku,
            int quantityDelta,
            String type,
            String typeLabel,
            Long orderId,
            Long orderItemId,
            Long receiptId,
            String operatorId,
            LocalDateTime createdAt
    ) {
    }

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

    public record ActionResultVO(boolean applied, String message, OrderVO order) {
    }

    public record SessionVO(
            String userId,
            String role,
            String identityMode,
            boolean canOrder,
            boolean canOperate
    ) {
    }

    public record BalanceVO(String sku, long productId, String productName, int available) {
    }

    public record InventoryVO(List<BalanceVO> balances, List<MovementVO> movements) {
    }

    public record PageVO<T>(List<T> items, int page, int size, long total) {
    }

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
                product.purchasable(),
                product.status(),
                product.category(),
                product.categoryLabel(),
                product.connectivity(),
                product.connectivityLabel(),
                product.isWireless(),
                product.macCompatible(),
                product.macCompatibleLabel(),
                product.silentFeature(),
                product.silentLabel(),
                product.switchTypeLabel(),
                product.batteryLabel(),
                product.weightGrams(),
                product.verifiableSpecs());
    }

    public static CatalogSearchVO catalogSearch(ShopCatalogService.SearchResult result) {
        return new CatalogSearchVO(
                result.items().stream().map(ShopViews::product).toList(),
                result.appliedHardFilters(),
                result.softPreferences(),
                result.relaxSuggestions());
    }

    public static ComparisonVO comparison(ShopCatalogService.ComparisonResult result) {
        return new ComparisonVO(
                result.products().stream().map(ShopViews::product).toList(),
                result.dimensions().stream()
                        .map(d -> new ComparisonDimensionVO(d.label(), d.values()))
                        .toList());
    }

    public static CartItemVO cartItem(ShopCartItem item) {
        return new CartItemVO(
                item.id(),
                item.productId(),
                item.sku(),
                item.productName(),
                item.subtitle(),
                item.currentUnitPriceCents(),
                money(item.currentUnitPriceCents()),
                item.quantity(),
                item.lineCents(),
                money(item.lineCents()),
                item.available(),
                item.active(),
                item.checkoutReady());
    }

    public static CartVO cart(ShopCartService.CartSummary summary) {
        return new CartVO(
                summary.items().stream().map(ShopViews::cartItem).toList(),
                summary.totalQuantity(),
                summary.totalCents(),
                money(summary.totalCents()),
                "当前订单模型支持单 SKU 结算：请从购物车选择一款商品进入结算预览与确认。",
                "加购不等于下单，也不锁定库存；实际库存扣减发生在后台发货时。");
    }

    public static CheckoutPreviewVO checkoutPreview(ShopCheckoutService.CheckoutPreview preview) {
        return new CheckoutPreviewVO(
                preview.productId(),
                preview.sku(),
                preview.productName(),
                preview.subtitle(),
                preview.quantity(),
                preview.unitPriceCents(),
                money(preview.unitPriceCents()),
                preview.totalCents(),
                money(preview.totalCents()),
                preview.available(),
                preview.active(),
                preview.eligible(),
                preview.code(),
                preview.message(),
                preview.stockNotice(),
                preview.singleSkuNotice());
    }

    public static CheckoutConfirmVO checkoutConfirm(ShopCheckoutService.CheckoutConfirmResult result) {
        return new CheckoutConfirmVO(result.replayed(), detail(result.order()));
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
                movementLabel(movement.movementType()),
                movement.orderId(),
                movement.orderItemId(),
                movement.receiptId(),
                movement.operatorId(),
                movement.createdAt());
    }

    public static String movementLabel(String type) {
        return switch (type == null ? "" : type) {
            case InventoryMovement.TYPE_OUTBOUND_SHIPMENT -> "发货出库";
            case InventoryMovement.TYPE_RETURN_INBOUND -> "退货入库";
            default -> type;
        };
    }

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
