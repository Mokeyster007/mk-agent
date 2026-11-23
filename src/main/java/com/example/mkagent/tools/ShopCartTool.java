package com.example.mkagent.tools;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.api.ShopViews;
import com.example.mkagent.shop.cart.ShopCartItem;
import com.example.mkagent.shop.cart.ShopCartService;
import com.example.mkagent.shop.catalog.ShopCatalogService;
import com.example.mkagent.shop.catalog.ShopProduct;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 共享购物车操作工具（普通页面与导购 Agent 调用同一 {@link ShopCartService}）。
 *
 * <p>约束：仅当用户明确要求加购/改数量/移除时才允许调用；只问推荐或比较时严禁调用。
 * 加购不等于下单，也不锁定库存。
 */
@Component
public class ShopCartTool {

    private final ShopCartService cartService;
    private final ShopCatalogService catalogService;

    public ShopCartTool(ShopCartService cartService, ShopCatalogService catalogService) {
        this.cartService = cartService;
        this.catalogService = catalogService;
    }

    @Tool(
            name = "add_to_cart",
            description = """
                    将指定 SKU 加入当前买家的共享购物车（仅在用户明确要求加入购物车时调用，只问推荐或比较时禁止调用）。
                    加购不会创建订单，也不会锁定库存。
                    skuOrId 必须是当前候选列表或商品表中明确对应的 SKU 或商品 ID；若用户指代（如“第二款”）
                    在当前候选中不存在或有歧义，请先向用户确认，不得猜测。
                    """
    )
    public String addToCart(
            @ToolParam(description = "明确绑定的 SKU 编码或商品 ID") String skuOrId,
            @ToolParam(description = "加入数量，1~99，默认 1") Integer quantity
    ) {
        String userId = AgentRunContextHolder.requireUserId();
        int qty = quantity == null ? 1 : quantity;
        try {
            ShopCartItem item = cartService.addBySkuOrId(userId, skuOrId, qty);
            ShopCartService.CartSummary cart = cartService.myCart(userId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("added", ShopViews.cartItem(item));
            result.put("cart", ShopViews.cart(cart));
            return ToolResponseEnvelope.okSingle(result);
        } catch (BusinessException e) {
            return ToolResponseEnvelope.error(e.getMessage());
        }
    }

    @Tool(
            name = "update_cart_item",
            description = "修改当前买家购物车中某款商品的数量（1~99）。"
    )
    public String updateCartItem(
            @ToolParam(description = "SKU 编码或商品 ID") String skuOrId,
            @ToolParam(description = "修改后的数量，1~99") Integer quantity
    ) {
        String userId = AgentRunContextHolder.requireUserId();
        if (quantity == null) {
            return ToolResponseEnvelope.error("请提供修改后的数量（1~99）");
        }
        try {
            ShopProduct product = catalogService.resolveActive(skuOrId)
                    .orElseThrow(() -> new BusinessException(
                            404, "PRODUCT_NOT_FOUND", "商品不存在或已下架：" + skuOrId));
            ShopCartItem item = cartService.updateQuantity(userId, product.id(), quantity);
            ShopCartService.CartSummary cart = cartService.myCart(userId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("updated", ShopViews.cartItem(item));
            result.put("cart", ShopViews.cart(cart));
            return ToolResponseEnvelope.okSingle(result);
        } catch (BusinessException e) {
            return ToolResponseEnvelope.error(e.getMessage());
        }
    }

    @Tool(
            name = "remove_cart_item",
            description = "从当前买家的购物车中移除指定商品。"
    )
    public String removeCartItem(
            @ToolParam(description = "SKU 编码或商品 ID") String skuOrId
    ) {
        String userId = AgentRunContextHolder.requireUserId();
        try {
            ShopProduct product = catalogService.resolveActive(skuOrId)
                    .orElseThrow(() -> new BusinessException(
                            404, "PRODUCT_NOT_FOUND", "商品不存在：" + skuOrId));
            cartService.removeItem(userId, product.id());
            ShopCartService.CartSummary cart = cartService.myCart(userId);
            return ToolResponseEnvelope.okSingle(Map.of("cart", ShopViews.cart(cart)));
        } catch (BusinessException e) {
            return ToolResponseEnvelope.error(e.getMessage());
        }
    }
}
