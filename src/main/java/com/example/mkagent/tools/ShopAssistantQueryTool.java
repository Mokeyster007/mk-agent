package com.example.mkagent.tools;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.api.ShopViews;
import com.example.mkagent.shop.cart.ShopCartService;
import com.example.mkagent.shop.catalog.ShopCatalogService;
import com.example.mkagent.shop.catalog.ShopProduct;
import com.example.mkagent.shop.order.ShopCheckoutService;
import com.example.mkagent.shop.order.ShopOrderService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 电商导购只读工具（{@code AgentType.SHOP_ASSISTANT}）。
 *
 * <p>严格边界：
 * <ul>
 *   <li>只读商品、对比、查看购物车、结算预览、查最近订单，不修改任何订单或库存；</li>
 *   <li>硬条件全部交由 {@link ShopCatalogService} 实时过滤数据库商品，绝不把不合规商品冒充匹配；</li>
 *   <li>缺失规格明确返回“未知”，不得凭印象编造；</li>
 *   <li>买家身份只来自 {@link AgentRunContextHolder#requireUserId()}。</li>
 * </ul>
 */
@Component
public class ShopAssistantQueryTool {

    private final ShopCatalogService catalogService;
    private final ShopCartService cartService;
    private final ShopCheckoutService checkoutService;
    private final ShopOrderService orderService;

    public ShopAssistantQueryTool(
            ShopCatalogService catalogService,
            ShopCartService cartService,
            ShopCheckoutService checkoutService,
            ShopOrderService orderService
    ) {
        this.catalogService = catalogService;
        this.cartService = cartService;
        this.checkoutService = checkoutService;
        this.orderService = orderService;
    }

    @Tool(
            name = "search_products",
            description = """
                    按硬条件与软偏好检索在售商品，返回实时价格、库存与可核对规格。
                    硬条件（maxPriceYuan、minPriceYuan、wirelessOnly、macCompatible、category、inStockOnly、keyword）
                    会由商品服务严格过滤：无匹配时 items 为空，并给出可放宽的建议，绝不返回不满足硬条件的商品。
                    软偏好（preferSilent）只影响排序，不放宽硬条件。
                    任何规格为“未知”时必须如实告知用户未知，不得编造。
                    注意：本工具只查询商品，不会加入购物车。
                    """
    )
    public String searchProducts(
            @ToolParam(description = "可选关键词，如商品名、轴体或型号；不要把“键盘/鼠标”重复填进 keyword 和 category", required = false)
            String keyword,
            @ToolParam(description = "可选品类：KEYBOARD（键盘）/ MOUSE（鼠标）/ ACCESSORY（配件）", required = false)
            String category,
            @ToolParam(description = "硬条件：预算上限（元），例如 400 表示 ≤ ¥400.00", required = false)
            Double maxPriceYuan,
            @ToolParam(description = "硬条件：预算下限（元）", required = false)
            Double minPriceYuan,
            @ToolParam(description = "硬条件：是否必须为无线连接（蓝牙 / 2.4G / 三模）", required = false)
            Boolean wirelessOnly,
            @ToolParam(description = "硬条件：是否必须兼容 Mac", required = false)
            Boolean macCompatible,
            @ToolParam(description = "软偏好：是否优先静音/低噪款式（仅用于排序）", required = false)
            Boolean preferSilent,
            @ToolParam(description = "硬条件：是否仅看当前有库存的商品", required = false)
            Boolean inStockOnly
    ) {
        AgentRunContextHolder.requireUserId();

        Long maxCents = toCents(maxPriceYuan);
        Long minCents = toCents(minPriceYuan);
        ShopCatalogService.SearchResult result = catalogService.search(
                new ShopCatalogService.SearchCriteria(
                        keyword, category, maxCents, minCents,
                        wirelessOnly, macCompatible, preferSilent, inStockOnly));

        return ToolResponseEnvelope.okSingle(ShopViews.catalogSearch(result));
    }

    @Tool(
            name = "compare_products",
            description = """
                    对比 2~3 款具体商品的实时价格、库存与结构化规格（连接方式、Mac 兼容、静音特性、轴体/微动、续航、重量）。
                    参数 skusOrIds 用英文逗号分隔具体的 SKU 或商品 ID（必须来自 search_products 结果，不得编造）。
                    注意：比较商品不会写入购物车。
                    """
    )
    public String compareProducts(
            @ToolParam(description = "2~3 个 SKU 或商品 ID，逗号分隔，例如 SKU-KEYBOARD-A75,SKU-KEYBOARD-B68")
            String skusOrIds
    ) {
        AgentRunContextHolder.requireUserId();
        if (skusOrIds == null || skusOrIds.isBlank()) {
            return ToolResponseEnvelope.error("请提供至少 2 款商品的 SKU 或商品 ID 进行比较");
        }
        List<String> keys = Arrays.stream(skusOrIds.split("[,，\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        try {
            ShopCatalogService.ComparisonResult result = catalogService.compare(keys);
            return ToolResponseEnvelope.okSingle(ShopViews.comparison(result));
        } catch (BusinessException e) {
            return ToolResponseEnvelope.error(e.getMessage());
        }
    }

    @Tool(
            name = "get_my_cart",
            description = "查看当前买家的共享购物车内容（实时单价、数量、小计、库存及是否可结算）。"
    )
    public String getMyCart() {
        String userId = AgentRunContextHolder.requireUserId();
        return ToolResponseEnvelope.okSingle(ShopViews.cart(cartService.myCart(userId)));
    }

    @Tool(
            name = "preview_checkout",
            description = """
                    计算单 SKU 结算预览（由服务端读取实时售价与库存并计算总额，不写库、不锁库存、不创建订单）。
                    当前订单模型仅支持单 SKU 结算。
                    重要：本工具只做预览，不会生成确认卡，也不会下单！
                    若用户已明确要求确认下单且返回 eligible=true，必须在下一步立即调用 submit_checkout_order
                    触发人工确认卡，不得只回复“正在为您下单”。
                    """
    )
    public String previewCheckout(
            @ToolParam(description = "具体 SKU 编码或商品 ID，必须来自商品筛选或购物车结果")
            String skuOrId,
            @ToolParam(description = "结算数量，至少 1 件")
            Integer quantity
    ) {
        AgentRunContextHolder.requireUserId();
        int qty = quantity == null ? 1 : quantity;
        try {
            ShopProduct product = catalogService.resolveActive(skuOrId)
                    .orElseThrow(() -> new BusinessException(
                            404, "PRODUCT_NOT_FOUND", "商品不存在或已下架：" + skuOrId));
            ShopCheckoutService.CheckoutPreview preview = checkoutService.preview(product.id(), qty);
            return ToolResponseEnvelope.okSingle(ShopViews.checkoutPreview(preview));
        } catch (BusinessException e) {
            return ToolResponseEnvelope.error(e.getMessage());
        }
    }

    @Tool(
            name = "get_my_recent_orders",
            description = "查询当前买家最近的订单列表（订单号、状态、商品、数量与总额），方便引导查看订单详情或进入售后。"
    )
    public String getMyRecentOrders() {
        String userId = AgentRunContextHolder.requireUserId();
        List<Object> rows = orderService.myOrders(userId).stream()
                .limit(10)
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("orderNo", s.order().orderNo());
                    m.put("status", s.order().status().name());
                    m.put("statusLabel", s.order().status().label());
                    m.put("sku", s.item().sku());
                    m.put("productName", s.item().productName());
                    m.put("quantity", s.item().quantity());
                    m.put("totalText", ShopViews.money(s.order().totalCents()));
                    return (Object) m;
                })
                .toList();
        return ToolResponseEnvelope.okList(rows);
    }

    private static Long toCents(Double yuan) {
        if (yuan == null) {
            return null;
        }
        if (yuan < 0) {
            return 0L;
        }
        return BigDecimal.valueOf(yuan).multiply(BigDecimal.valueOf(100)).longValue();
    }
}
