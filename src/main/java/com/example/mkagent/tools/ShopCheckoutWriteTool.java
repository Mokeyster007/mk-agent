package com.example.mkagent.tools;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.api.ShopViews;
import com.example.mkagent.shop.catalog.ShopProduct;
import com.example.mkagent.shop.catalog.ShopProductRepository;
import com.example.mkagent.shop.order.ShopCheckoutService;
import com.example.mkagent.shop.order.ShopOrderDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 导购结算下单写工具：{@code submit_checkout_order}（必须经人工确认 + 幂等包装）。
 *
 * <p>固定参数执行并在事务内重查最新上架状态、当前单价（比对 expectedUnitPriceCents）、
 * 当前可售库存与买家身份。价格变动 / 商品下架 / 库存不足一律拒绝建单。
 * 仍不接真实支付，沿用 A0 发货才扣库存口径。
 */
@Component
public class ShopCheckoutWriteTool {

    private static final Logger log = LoggerFactory.getLogger(ShopCheckoutWriteTool.class);

    private final ShopCheckoutService checkoutService;
    private final ShopProductRepository productRepository;

    public ShopCheckoutWriteTool(
            ShopCheckoutService checkoutService,
            ShopProductRepository productRepository
    ) {
        this.checkoutService = checkoutService;
        this.productRepository = productRepository;
    }

    @Tool(
            name = "submit_checkout_order",
            description = """
                    为当前买家创建一张演示订单（写操作，必须经用户人工确认卡批准后执行）。
                    仅支持单 SKU 结算；执行时会重查最新可售状态、最新售价与当前库存：
                    若价格已变动、商品下架或库存不足，会拒绝建单并返回原因（价格变动需按新报价重新确认）。
                    下单不预留也不扣减库存，发货时才扣减；演示付款无真实资金交易。
                    """
    )
    @RequiresApproval(reason = "确认结算将创建一张演示订单（单 SKU，下单不锁库存）")
    public String submitCheckoutOrder(
            @ToolParam(description = "明确绑定的 SKU 编码，例如 SKU-KEYBOARD-A75")
            String sku,
            @ToolParam(description = "购买数量，1~99")
            Integer quantity,
            @ToolParam(description = "结算预览时的报价单价（单位：分），用于确认时核对价格是否变动")
            Long expectedUnitPriceCents,
            @ToolParam(description = "成单后是否同步扣减/移除购物车对应商品，默认 true", required = false)
            Boolean removeFromCart
    ) {
        String buyerId = AgentRunContextHolder.requireUserId();

        if (sku == null || sku.isBlank()) {
            return ToolResponseEnvelope.error("缺少要结算的商品 SKU");
        }
        if (quantity == null || quantity < 1) {
            return ToolResponseEnvelope.error("购买数量至少为 1 件");
        }

        ShopProduct product = productRepository.findBySku(sku.trim()).orElse(null);
        if (product == null) {
            return ToolResponseEnvelope.error("商品不存在：" + sku);
        }

        boolean consumeCart = removeFromCart == null || removeFromCart;
        try {
            ShopOrderDetail created = checkoutService.confirmForAgent(
                    buyerId, product.id(), quantity, expectedUnitPriceCents, consumeCart);

            log.info("导购 Agent 确认成单：orderNo={}, sku={}, qty={}, totalCents={}",
                    created.order().orderNo(), created.item().sku(),
                    created.item().quantity(), created.order().totalCents());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("orderNo", created.order().orderNo());
            result.put("order", ShopViews.detail(created));
            return ToolResponseEnvelope.okSingle(result);
        } catch (BusinessException e) {
            return ToolResponseEnvelope.error(e.getMessage());
        }
    }
}
