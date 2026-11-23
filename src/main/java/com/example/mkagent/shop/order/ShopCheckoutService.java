package com.example.mkagent.shop.order;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.ShopProperties;
import com.example.mkagent.shop.api.ShopViews;
import com.example.mkagent.shop.cart.ShopCartService;
import com.example.mkagent.shop.catalog.ShopProduct;
import com.example.mkagent.shop.catalog.ShopProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 结算预览与确认下单服务（普通页面与导购 Agent 共用）。
 *
 * <h2>关键规则</h2>
 * <ol>
 *   <li><b>单 SKU 订单限制</b>：沿用 A0 订单模型（{@code uk_shop_order_item_order}），
 *       每次结算针对一个明确 SKU 与数量，不伪装成多商品同单结算；</li>
 *   <li><b>确认时全量重查</b>：重查最新上架状态、当前售价、当前可售库存与买家身份；
 *       价格变动拒绝落单并返回最新报价要求重新确认；下架或库存不足拒绝落单；</li>
 *   <li><b>重复确认绝不建第二单</b>：页面路径用 {@code shop_order_idempotency}，
 *       Agent 路径叠加 {@code IdempotentToolCallback} 与 {@code ApprovalDecisionService}；</li>
 *   <li><b>沿用 A0 发货才扣库存口径</b>：结算校验当前库存但不预留、不扣减库存。</li>
 * </ol>
 */
@Service
public class ShopCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(ShopCheckoutService.class);

    public static final String CODE_PRODUCT_UNAVAILABLE = "PRODUCT_UNAVAILABLE";
    public static final String CODE_INSUFFICIENT_STOCK_AT_CHECKOUT = "INSUFFICIENT_STOCK_AT_CHECKOUT";
    public static final String CODE_PRICE_CHANGED = "PRICE_CHANGED";
    public static final String CODE_IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{8,64}$");

    private final ShopProductRepository productRepository;
    private final ShopOrderService orderService;
    private final ShopCartService cartService;
    private final ShopProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public ShopCheckoutService(
            ShopProductRepository productRepository,
            ShopOrderService orderService,
            ShopCartService cartService,
            ShopProperties properties,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.productRepository = productRepository;
        this.orderService = orderService;
        this.cartService = cartService;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public record CheckoutPreview(
            long productId,
            String sku,
            String productName,
            String subtitle,
            int quantity,
            long unitPriceCents,
            long totalCents,
            int available,
            boolean active,
            boolean eligible,
            String code,
            String message,
            String stockNotice,
            String singleSkuNotice
    ) {
    }

    public record CheckoutConfirmResult(
            boolean replayed,
            ShopOrderDetail order
    ) {
    }

    /**
     * 服务端计算结算预览（只读，不写库、不锁库存）。
     */
    public CheckoutPreview preview(long productId, int quantity) {
        validateQuantity(quantity);

        ShopProduct product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(
                        404, "PRODUCT_NOT_FOUND", "商品不存在：" + productId));

        long totalCents;
        try {
            totalCents = Math.multiplyExact(product.priceCents(), (long) quantity);
        } catch (ArithmeticException e) {
            throw new BusinessException(400, "AMOUNT_OVERFLOW", "订单金额超出可表示范围");
        }

        boolean active = product.purchasable();
        boolean enoughStock = product.available() >= quantity;
        boolean eligible = active && enoughStock;
        String code = null;
        String message = "可确认下单（演示付款，无真实资金交易）。";

        if (!active) {
            code = CODE_PRODUCT_UNAVAILABLE;
            message = "该商品已下架，无法提交订单。";
        } else if (!enoughStock) {
            code = CODE_INSUFFICIENT_STOCK_AT_CHECKOUT;
            message = "当前可售库存仅剩 " + product.available() + " 件，不足以结算 " + quantity + " 件。";
        }

        return new CheckoutPreview(
                product.id(),
                product.sku(),
                product.name(),
                product.subtitle(),
                quantity,
                product.priceCents(),
                totalCents,
                product.available(),
                active,
                eligible,
                code,
                message,
                "结算时校验当前可售库存，但不预留、不扣减库存；实际扣减发生在后台发货时。",
                "当前订单模型支持单 SKU 结算：每次确认生成一张单 SKU 订单。"
        );
    }

    /**
     * 页面路径确认下单（带幂等键、最新报价校验与库存/上架状态重查）。
     */
    public CheckoutConfirmResult confirmFromPage(
            String buyerId,
            long productId,
            int quantity,
            Long expectedUnitPriceCents,
            boolean removeFromCart,
            String clientRequestId
    ) {
        validateQuantity(quantity);
        if (clientRequestId == null || !REQUEST_ID_PATTERN.matcher(clientRequestId.trim()).matches()) {
            throw new BusinessException(400, "INVALID_REQUEST_ID", "确认标识不合法，请刷新后重试");
        }
        String key = buyerId + ":" + clientRequestId.trim();
        String reqHash = requestHash(productId, quantity, expectedUnitPriceCents, removeFromCart);

        Optional<StoredOrderIdempotency> existing = findIdempotency(key);
        if (existing.isPresent()) {
            return replayOrder(buyerId, existing.get(), reqHash);
        }

        try {
            ShopOrderDetail created = transactionTemplate.execute(status -> executeConfirmTx(
                    buyerId, productId, quantity, expectedUnitPriceCents,
                    removeFromCart, key, reqHash));
            return new CheckoutConfirmResult(false, created);
        } catch (IdempotencyHitException hit) {
            StoredOrderIdempotency winner = findIdempotency(key)
                    .orElseThrow(() -> new BusinessException(
                            409, "SUBMIT_CONFLICT", "同一确认请求正在处理中，请刷新订单列表查看"));
            return replayOrder(buyerId, winner, reqHash);
        }
    }

    /**
     * Agent 确认卡恢复后执行下单（幂等由框架 IdempotentToolCallback + 事务保证）。
     */
    @Transactional
    public ShopOrderDetail confirmForAgent(
            String buyerId,
            long productId,
            int quantity,
            Long expectedUnitPriceCents,
            boolean removeFromCart
    ) {
        validateQuantity(quantity);
        return placeCheckedOrder(buyerId, productId, quantity, expectedUnitPriceCents, removeFromCart);
    }

    private ShopOrderDetail executeConfirmTx(
            String buyerId,
            long productId,
            int quantity,
            Long expectedUnitPriceCents,
            boolean removeFromCart,
            String idempotencyKey,
            String requestHash
    ) {
        ShopOrderDetail created = placeCheckedOrder(
                buyerId, productId, quantity, expectedUnitPriceCents, removeFromCart);
        try {
            jdbcTemplate.update("""
                    INSERT INTO shop_order_idempotency
                        (idempotency_key, buyer_id, request_hash, order_no, created_at)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, idempotencyKey, buyerId, requestHash, created.order().orderNo());
        } catch (DuplicateKeyException e) {
            throw new IdempotencyHitException();
        }
        return created;
    }

    private ShopOrderDetail placeCheckedOrder(
            String buyerId,
            long productId,
            int quantity,
            Long expectedUnitPriceCents,
            boolean removeFromCart
    ) {
        ShopProduct live = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(
                        404, "PRODUCT_NOT_FOUND", "商品不存在：" + productId));

        if (!live.purchasable()) {
            throw new BusinessException(
                    409, CODE_PRODUCT_UNAVAILABLE,
                    "商品「" + live.name() + "」已下架，本次未创建订单。");
        }

        if (live.available() < quantity) {
            throw new BusinessException(
                    409, CODE_INSUFFICIENT_STOCK_AT_CHECKOUT,
                    "商品「" + live.name() + "」当前仅剩 " + live.available()
                            + " 件（需要 " + quantity + " 件），库存不足，本次未创建订单。");
        }

        if (expectedUnitPriceCents != null && live.priceCents() != expectedUnitPriceCents) {
            long newTotal = Math.multiplyExact(live.priceCents(), (long) quantity);
            throw new BusinessException(
                    409, CODE_PRICE_CHANGED,
                    "商品价格已变动：原报价 " + ShopViews.money(expectedUnitPriceCents)
                            + "，最新单价 " + ShopViews.money(live.priceCents())
                            + "（最新总额 " + ShopViews.money(newTotal)
                            + "）。本次未创建订单，请按最新报价重新确认。");
        }

        // 复用 A0 下单逻辑（不预留/不扣减库存，保持发货才扣减口径）
        ShopOrderDetail detail = orderService.placeOrder(buyerId, live.id(), quantity);
        if (removeFromCart) {
            cartService.consumeAfterOrder(buyerId, live.id(), quantity);
        }

        log.info("结算确认成单：orderNo={}, buyer={}, sku={}, qty={}, unitPriceCents={}",
                detail.order().orderNo(), buyerId, live.sku(), quantity, live.priceCents());
        return detail;
    }

    private CheckoutConfirmResult replayOrder(
            String buyerId,
            StoredOrderIdempotency stored,
            String requestHash
    ) {
        if (!stored.requestHash().equals(requestHash)) {
            throw new BusinessException(
                    409, CODE_IDEMPOTENCY_KEY_REUSED,
                    "同一确认标识被用于不同的下单参数，已拒绝执行");
        }
        ShopOrderDetail detail = orderService.myOrder(buyerId, stored.orderNo())
                .orElseThrow(() -> new BusinessException(
                        409, "SUBMIT_CONFLICT", "原订单已不可访问，请刷新我的订单列表"));
        return new CheckoutConfirmResult(true, detail);
    }

    private Optional<StoredOrderIdempotency> findIdempotency(String key) {
        List<StoredOrderIdempotency> rows = jdbcTemplate.query("""
                SELECT idempotency_key, buyer_id, request_hash, order_no
                FROM shop_order_idempotency
                WHERE idempotency_key = ?
                """, (rs, rowNum) -> new StoredOrderIdempotency(
                rs.getString("idempotency_key"),
                rs.getString("buyer_id"),
                rs.getString("request_hash"),
                rs.getString("order_no")), key);
        return rows.stream().findFirst();
    }

    private void validateQuantity(int quantity) {
        int max = properties.getMaxQuantityPerOrder() > 0 ? properties.getMaxQuantityPerOrder() : 99;
        if (quantity < 1 || quantity > max) {
            throw new BusinessException(400, "INVALID_QUANTITY", "购买数量必须在 1 ~ " + max + " 之间");
        }
    }

    private static String requestHash(
            long productId,
            int quantity,
            Long expectedUnitPriceCents,
            boolean removeFromCart
    ) {
        String raw = productId + "|" + quantity + "|"
                + (expectedUnitPriceCents == null ? "ANY" : expectedUnitPriceCents)
                + "|" + removeFromCart;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record StoredOrderIdempotency(
            String key,
            String buyerId,
            String requestHash,
            String orderNo
    ) {
    }

    private static class IdempotencyHitException extends RuntimeException {
    }
}
