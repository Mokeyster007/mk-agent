package com.example.mkagent.conversation;

import com.example.mkagent.model.AgentRunContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 从**真实工具结果**里抽取业务引用，累积到当前 run 上下文并写回会话。
 *
 * <p>覆盖电商导购与售后两类真实工具结果：
 * <ul>
 *   <li>{@code search_products} → 有序候选商品列表（支持“第二款加一把”精确绑定 SKU）；</li>
 *   <li>{@code preview_checkout} → 最近一次合格结算预览；</li>
 *   <li>{@code list_my_signed_orders} / {@code get_my_order_detail} → 本人可退订单候选；</li>
 *   <li>{@code preview_return} → 最近一次合格退货试算。</li>
 * </ul>
 */
public final class BusinessRefCollector {

    private static final Logger log =
            LoggerFactory.getLogger(BusinessRefCollector.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BusinessRefCollector() {
    }

    public static boolean collect(AgentRunContext ctx, String toolName, String responseData) {
        if (ctx == null || toolName == null || responseData == null || responseData.isBlank()) {
            return false;
        }

        try {
            JsonNode root = MAPPER.readTree(responseData);
            if (root.isTextual()) {
                root = MAPPER.readTree(root.asText());
            }
            if (!root.path("ok").asBoolean(false)) {
                return false;
            }

            JsonNode data = root.path("data");
            ConversationRefs before = ctx.getConversationRefs();

            switch (toolName) {
                case "search_products" -> {
                    JsonNode items = data.has("items") ? data.path("items") : data;
                    ctx.setConversationRefs(before.withProducts(productsOf(items)));
                }
                case "preview_checkout" -> {
                    if (data.path("eligible").asBoolean(false) && data.path("productId").canConvertToLong()) {
                        ctx.setConversationRefs(before.withLastCheckoutPreview(
                                new ConversationRefs.CheckoutPreviewRef(
                                        data.path("productId").asLong(),
                                        data.path("sku").asText(""),
                                        data.path("productName").asText(""),
                                        data.path("quantity").asInt(1),
                                        data.path("unitPriceCents").asLong(0L),
                                        data.path("totalCents").asLong(0L)
                                )));
                    }
                }
                case "list_my_signed_orders" ->
                        ctx.setConversationRefs(before.withOrders(ordersOf(data)));
                case "get_my_order_detail" -> {
                    String orderNo = data.path("orderNo").asText("");
                    if (!orderNo.isBlank()) {
                        ConversationRefs.OrderRef single = new ConversationRefs.OrderRef(
                                orderNo,
                                data.path("sku").asText(""),
                                data.path("productName").asText(""),
                                data.path("remainingQty").asInt(0),
                                data.path("remainingQty").asInt(0) > 0
                                        && "SIGNED".equals(data.path("status").asText(""))
                        );
                        List<ConversationRefs.OrderRef> merged = new ArrayList<>();
                        merged.add(single);
                        for (ConversationRefs.OrderRef existing : before.orders()) {
                            if (!orderNo.equals(existing.orderNo())) {
                                merged.add(existing);
                            }
                        }
                        ctx.setConversationRefs(before.withOrders(merged));
                    }
                }
                case "preview_return" -> {
                    if (data.path("eligible").asBoolean(false) && !data.path("orderNo").asText("").isBlank()) {
                        ctx.setConversationRefs(before.withLastReturnPreview(
                                new ConversationRefs.ReturnPreviewRef(
                                        data.path("orderNo").asText(""),
                                        data.path("productName").asText(""),
                                        data.path("requestedQty").asInt(1),
                                        data.path("reason").asText(""),
                                        data.path("refundCents").asLong(0L)
                                )));
                    }
                }
                default -> {
                    return false;
                }
            }

            return !ctx.getConversationRefs().equals(before);
        } catch (Exception e) {
            log.debug("业务引用抽取失败（忽略）：tool={}, error={}", toolName, e.toString());
            return false;
        }
    }

    private static List<ConversationRefs.ProductRef> productsOf(JsonNode items) {
        if (!items.isArray()) {
            return List.of();
        }
        List<ConversationRefs.ProductRef> list = new ArrayList<>();
        for (JsonNode n : items) {
            if (!n.path("id").canConvertToLong()) {
                continue;
            }
            String sku = n.path("sku").asText("");
            String name = n.path("name").asText("");
            if (sku.isBlank() || name.isBlank()) {
                continue;
            }
            list.add(new ConversationRefs.ProductRef(
                    n.path("id").asLong(),
                    sku,
                    name,
                    n.path("priceCents").asLong(0L),
                    n.path("available").asInt(0)));
        }
        return list;
    }

    private static List<ConversationRefs.OrderRef> ordersOf(JsonNode data) {
        if (!data.isArray()) {
            return List.of();
        }
        List<ConversationRefs.OrderRef> list = new ArrayList<>();
        for (JsonNode n : data) {
            String orderNo = n.path("orderNo").asText("");
            if (orderNo.isBlank()) {
                continue;
            }
            list.add(new ConversationRefs.OrderRef(
                    orderNo,
                    n.path("sku").asText(""),
                    n.path("productName").asText(""),
                    n.path("remainingQty").asInt(0),
                    n.path("returnable").asBoolean(false)));
        }
        return list;
    }
}
