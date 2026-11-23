package com.example.mkagent.shop.cart;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.ShopProperties;
import com.example.mkagent.shop.catalog.ShopCatalogService;
import com.example.mkagent.shop.catalog.ShopProduct;
import com.example.mkagent.shop.catalog.ShopProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 共享购物车服务：普通页面与导购 Agent 共用唯一实现。
 *
 * <p>核心口径：
 * <ul>
 *   <li>加购不等于下单，也不锁定或扣减库存；</li>
 *   <li>当前订单模型为单 SKU 订单，购物车可暂存多款商品，但结算时选择一款 SKU 确认下单；</li>
 *   <li>所有读写按服务端解析出的 {@code buyerId} 隔离。</li>
 * </ul>
 */
@Service
public class ShopCartService {

    private final ShopCartRepository cartRepository;
    private final ShopProductRepository productRepository;
    private final ShopCatalogService catalogService;
    private final ShopProperties properties;

    public ShopCartService(
            ShopCartRepository cartRepository,
            ShopProductRepository productRepository,
            ShopCatalogService catalogService,
            ShopProperties properties
    ) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.catalogService = catalogService;
        this.properties = properties;
    }

    public record CartSummary(
            List<ShopCartItem> items,
            int totalQuantity,
            long totalCents
    ) {
    }

    public CartSummary myCart(String buyerId) {
        List<ShopCartItem> items = cartRepository.findByBuyer(buyerId);
        int totalQty = 0;
        long totalCents = 0L;
        for (ShopCartItem item : items) {
            totalQty += item.quantity();
            totalCents = Math.addExact(totalCents, item.lineCents());
        }
        return new CartSummary(items, totalQty, totalCents);
    }

    @Transactional
    public ShopCartItem addItem(String buyerId, long productId, int quantity) {
        validateQuantity(quantity);

        ShopProduct product = productRepository.findActiveById(productId)
                .orElseThrow(() -> new BusinessException(
                        404, "PRODUCT_NOT_FOUND", "商品不存在或已下架：" + productId));

        Optional<ShopCartItem> existing = cartRepository.findByBuyerAndProduct(buyerId, product.id());
        if (existing.isPresent()) {
            int merged = existing.get().quantity() + quantity;
            int max = maxQty();
            if (merged > max) {
                throw new BusinessException(
                        400, "INVALID_QUANTITY", "同一商品购物车最多 " + max + " 件");
            }
            cartRepository.updateQuantity(buyerId, product.id(), merged);
        } else {
            cartRepository.insert(buyerId, product.id(), product.sku(), quantity);
        }

        return cartRepository.findByBuyerAndProduct(buyerId, product.id())
                .orElseThrow(() -> new IllegalStateException("购物车写入后读取失败"));
    }

    @Transactional
    public ShopCartItem addBySkuOrId(String buyerId, String skuOrId, int quantity) {
        ShopProduct product = catalogService.resolveActive(skuOrId)
                .orElseThrow(() -> new BusinessException(
                        404, "PRODUCT_NOT_FOUND", "商品不存在或已下架：" + skuOrId));
        return addItem(buyerId, product.id(), quantity);
    }

    @Transactional
    public ShopCartItem updateQuantity(String buyerId, long productId, int quantity) {
        validateQuantity(quantity);

        int updated = cartRepository.updateQuantity(buyerId, productId, quantity);
        if (updated == 0) {
            throw new BusinessException(404, "CART_ITEM_NOT_FOUND", "购物车中未找到该商品");
        }
        return cartRepository.findByBuyerAndProduct(buyerId, productId)
                .orElseThrow(() -> new BusinessException(404, "CART_ITEM_NOT_FOUND", "购物车中未找到该商品"));
    }

    @Transactional
    public void removeItem(String buyerId, long productId) {
        int deleted = cartRepository.delete(buyerId, productId);
        if (deleted == 0) {
            throw new BusinessException(404, "CART_ITEM_NOT_FOUND", "购物车中未找到该商品");
        }
    }

    /** 结算成单后按购买数量扣减或清空购物车对应行（不报错）。 */
    @Transactional
    public void consumeAfterOrder(String buyerId, long productId, int orderedQty) {
        Optional<ShopCartItem> existing = cartRepository.findByBuyerAndProduct(buyerId, productId);
        if (existing.isEmpty()) {
            return;
        }
        int remaining = existing.get().quantity() - orderedQty;
        if (remaining <= 0) {
            cartRepository.delete(buyerId, productId);
        } else {
            cartRepository.updateQuantity(buyerId, productId, remaining);
        }
    }

    private void validateQuantity(int quantity) {
        int max = maxQty();
        if (quantity < 1 || quantity > max) {
            throw new BusinessException(
                    400, "INVALID_QUANTITY", "商品数量必须在 1 ~ " + max + " 之间");
        }
    }

    private int maxQty() {
        return properties.getMaxQuantityPerOrder() > 0 ? properties.getMaxQuantityPerOrder() : 99;
    }
}
