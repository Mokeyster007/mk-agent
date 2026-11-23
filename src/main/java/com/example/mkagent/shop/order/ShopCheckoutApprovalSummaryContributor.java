package com.example.mkagent.shop.order;

import com.example.mkagent.approval.ApprovalSummary;
import com.example.mkagent.approval.ApprovalSummaryBuilder;
import com.example.mkagent.approval.ApprovalSummaryContributor;
import com.example.mkagent.shop.api.ShopViews;
import com.example.mkagent.shop.catalog.ShopProduct;
import com.example.mkagent.shop.catalog.ShopProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 导购下单确认卡摘要（{@code submit_checkout_order}）。
 *
 * <p>确认卡固定：SKU、商品名称、购买数量、报价单价、订单总额、库存与演示付款口径。
 */
@Component
public class ShopCheckoutApprovalSummaryContributor implements ApprovalSummaryContributor {

    private static final int MAX_VALUE_LENGTH = 120;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ShopProductRepository productRepository;

    public ShopCheckoutApprovalSummaryContributor(ShopProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public boolean supports(String toolName) {
        return "submit_checkout_order".equals(toolName);
    }

    @Override
    public Contribution contribute(String argsJson, String userId) {
        JsonNode args = parse(argsJson);
        String sku = text(args, "sku");
        Integer quantity = integer(args, "quantity");
        Long expectedPrice = longVal(args, "expectedUnitPriceCents");

        ShopProduct product = sku == null ? null : productRepository.findBySku(sku).orElse(null);

        List<ApprovalSummary.Detail> details = new ArrayList<>();
        details.add(detail("商品", product == null ? "未查到商品" : product.name()));
        details.add(detail("SKU", sku == null ? "未提供" : sku));
        details.add(detail("购买数量", quantity == null ? "未指定" : quantity + " 件"));

        Long unitPrice = expectedPrice != null
                ? expectedPrice
                : (product == null ? null : product.priceCents());
        if (unitPrice != null) {
            details.add(detail("确认单价", ShopViews.money(unitPrice)));
            if (quantity != null && quantity > 0) {
                details.add(detail("订单总额", ShopViews.money(Math.multiplyExact(unitPrice, quantity.longValue()))));
            }
        }
        if (product != null) {
            details.add(detail("当前库存", product.available() + " 件（下单不锁库存，发货时扣减）"));
        }
        details.add(detail("结算口径", "单 SKU 演示订单 · 演示付款无真实资金交易"));

        return new Contribution("确认提交演示订单", details);
    }

    private static ApprovalSummary.Detail detail(String label, String value) {
        return new ApprovalSummary.Detail(
                label,
                ApprovalSummaryBuilder.sanitize(value == null ? "未提供" : value, MAX_VALUE_LENGTH));
    }

    private static JsonNode parse(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(argsJson);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode args, String field) {
        if (args == null) {
            return null;
        }
        JsonNode node = args.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Integer integer(JsonNode args, String field) {
        if (args == null) {
            return null;
        }
        JsonNode node = args.get(field);
        return node == null || !node.canConvertToInt() ? null : node.asInt();
    }

    private static Long longVal(JsonNode args, String field) {
        if (args == null) {
            return null;
        }
        JsonNode node = args.get(field);
        return node == null || !node.canConvertToLong() ? null : node.asLong();
    }
}
