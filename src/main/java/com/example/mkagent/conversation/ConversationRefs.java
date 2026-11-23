package com.example.mkagent.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话级“业务引用”：让模型能确定性地解析“第二款加一把”“就是这一单”“刚才那份预览”。
 *
 * <p>来源只有**真实工具结果**（{@link BusinessRefCollector} 解析工具信封），
 * 绝不从模型回答里正则抠 ID/SKU/订单号。
 * 兼容旧库里的历史 JSON（未知字段自动忽略，绝不因历史记录卡住会话）。
 */
public record ConversationRefs(
        List<ProductRef> products,
        List<OrderRef> orders,
        ReturnPreviewRef lastReturnPreview,
        CheckoutPreviewRef lastCheckoutPreview
) {

    /** 候选商品引用：保留顺序以支持“第一款/第二款”绑定到具体 SKU。 */
    public record ProductRef(long id, String sku, String name, long priceCents, int available) {
    }

    /** 订单引用：来自 list_my_signed_orders 或 get_my_order_detail。 */
    public record OrderRef(
            String orderNo,
            String sku,
            String productName,
            int remainingQty,
            boolean returnable
    ) {
    }

    /** 最近一次合格的退货预览引用（不代表已提交）。 */
    public record ReturnPreviewRef(
            String orderNo,
            String productName,
            int quantity,
            String reason,
            long refundCents
    ) {
    }

    /** 最近一次合格的结算预览引用（不代表已下单）。 */
    public record CheckoutPreviewRef(
            long productId,
            String sku,
            String productName,
            int quantity,
            long unitPriceCents,
            long totalCents
    ) {
    }

    public static final ConversationRefs EMPTY =
            new ConversationRefs(List.of(), List.of(), null, null);

    private static final int MAX_ITEMS = 12;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public boolean isEmpty() {
        return products.isEmpty()
                && orders.isEmpty()
                && lastReturnPreview == null
                && lastCheckoutPreview == null;
    }

    /** 用新一批检索结果替换候选商品（新搜索无结果时清空旧候选，避免错绑）。 */
    public ConversationRefs withProducts(List<ProductRef> next) {
        return new ConversationRefs(cap(next, MAX_ITEMS), orders, lastReturnPreview, lastCheckoutPreview);
    }

    /** 用新一批订单列表替换候选订单。 */
    public ConversationRefs withOrders(List<OrderRef> next) {
        return new ConversationRefs(products, cap(next, MAX_ITEMS), lastReturnPreview, lastCheckoutPreview);
    }

    public ConversationRefs withLastReturnPreview(ReturnPreviewRef next) {
        return new ConversationRefs(products, orders, next, lastCheckoutPreview);
    }

    public ConversationRefs withLastCheckoutPreview(CheckoutPreviewRef next) {
        return new ConversationRefs(products, orders, lastReturnPreview, next);
    }

    /** 渲染成注入给模型的事实块；无引用时返回 null。 */
    public String renderBlock() {
        if (isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[会话已知对象｜来自真实工具结果，不是模型自述]\n");

        if (!products.isEmpty()) {
            sb.append("最近一次筛选出的候选商品（严格按返回顺序编号，用于解析“第一款/第二款”）：\n");
            int index = 1;
            for (ProductRef p : products) {
                sb.append(index++).append(". ")
                        .append(safe(p.name()))
                        .append("（sku=").append(safe(p.sku()))
                        .append(", productId=").append(p.id())
                        .append(", 现价=").append(money(p.priceCents()))
                        .append(", 库存=").append(p.available()).append(" 件）\n");
            }
        }

        if (lastCheckoutPreview != null) {
            sb.append("最近一次结算预览（尚未下单）：")
                    .append(safe(lastCheckoutPreview.productName()))
                    .append("（sku=").append(safe(lastCheckoutPreview.sku()))
                    .append(", productId=").append(lastCheckoutPreview.productId())
                    .append(", 数量=").append(lastCheckoutPreview.quantity())
                    .append(", 单价=").append(money(lastCheckoutPreview.unitPriceCents()))
                    .append(", 总额=").append(money(lastCheckoutPreview.totalCents()))
                    .append("）\n");
        }

        if (!orders.isEmpty()) {
            sb.append("最近查询到的本人已签收/关联订单（用于确认退货目标）：\n");
            int index = 1;
            for (OrderRef o : orders) {
                sb.append(index++).append(". 订单号 ").append(safe(o.orderNo()))
                        .append("｜").append(safe(o.productName()))
                        .append("（sku=").append(safe(o.sku()))
                        .append(", 剩余可退=").append(o.remainingQty())
                        .append(" 件, 当前可退=").append(o.returnable() ? "是" : "否")
                        .append("）\n");
            }
        }

        if (lastReturnPreview != null) {
            sb.append("最近一次合格的退货试算（尚未提交）：订单号 ")
                    .append(safe(lastReturnPreview.orderNo()))
                    .append("，商品 ").append(safe(lastReturnPreview.productName()))
                    .append("，数量 ").append(lastReturnPreview.quantity())
                    .append(" 件，原因「").append(safe(lastReturnPreview.reason()))
                    .append("」，预计退款 ").append(money(lastReturnPreview.refundCents()))
                    .append("\n");
        }

        sb.append("规则：仅可使用上方列出的真实 sku / productId / 订单号，不得编造；"
                + "指代有歧义或列表为空时必须先向用户确认；下单与提交退货均需经人工确认卡。");
        return sb.toString();
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();

        List<Map<String, Object>> productNodes = new ArrayList<>();
        for (ProductRef p : products) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("sku", p.sku() == null ? "" : p.sku());
            m.put("name", p.name() == null ? "" : p.name());
            m.put("priceCents", p.priceCents());
            m.put("available", p.available());
            productNodes.add(m);
        }
        root.put("products", productNodes);

        List<Map<String, Object>> orderNodes = new ArrayList<>();
        for (OrderRef o : orders) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderNo", o.orderNo() == null ? "" : o.orderNo());
            m.put("sku", o.sku() == null ? "" : o.sku());
            m.put("productName", o.productName() == null ? "" : o.productName());
            m.put("remainingQty", o.remainingQty());
            m.put("returnable", o.returnable());
            orderNodes.add(m);
        }
        root.put("orders", orderNodes);

        if (lastReturnPreview != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderNo", lastReturnPreview.orderNo());
            m.put("productName", lastReturnPreview.productName());
            m.put("quantity", lastReturnPreview.quantity());
            m.put("reason", lastReturnPreview.reason());
            m.put("refundCents", lastReturnPreview.refundCents());
            root.put("lastReturnPreview", m);
        }

        if (lastCheckoutPreview != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", lastCheckoutPreview.productId());
            m.put("sku", lastCheckoutPreview.sku());
            m.put("productName", lastCheckoutPreview.productName());
            m.put("quantity", lastCheckoutPreview.quantity());
            m.put("unitPriceCents", lastCheckoutPreview.unitPriceCents());
            m.put("totalCents", lastCheckoutPreview.totalCents());
            root.put("lastCheckoutPreview", m);
        }

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return null;
        }
    }

    public static ConversationRefs fromJson(String json) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        try {
            JsonNode root = MAPPER.readTree(json);

            List<ProductRef> products = new ArrayList<>();
            for (JsonNode n : root.path("products")) {
                if (n.path("id").canConvertToLong() && !n.path("sku").asText("").isBlank()) {
                    products.add(new ProductRef(
                            n.path("id").asLong(),
                            n.path("sku").asText(""),
                            n.path("name").asText(""),
                            n.path("priceCents").asLong(0L),
                            n.path("available").asInt(0)));
                }
            }

            List<OrderRef> orders = new ArrayList<>();
            for (JsonNode n : root.path("orders")) {
                String orderNo = n.path("orderNo").asText("");
                if (!orderNo.isBlank()) {
                    orders.add(new OrderRef(
                            orderNo,
                            n.path("sku").asText(""),
                            n.path("productName").asText(""),
                            n.path("remainingQty").asInt(0),
                            n.path("returnable").asBoolean(false)));
                }
            }

            ReturnPreviewRef returnPreview = null;
            JsonNode rp = root.path("lastReturnPreview");
            if (rp.isObject() && !rp.path("orderNo").asText("").isBlank()) {
                returnPreview = new ReturnPreviewRef(
                        rp.path("orderNo").asText(""),
                        rp.path("productName").asText(""),
                        rp.path("quantity").asInt(1),
                        rp.path("reason").asText(""),
                        rp.path("refundCents").asLong(0L));
            }

            CheckoutPreviewRef checkoutPreview = null;
            JsonNode cp = root.path("lastCheckoutPreview");
            if (cp.isObject() && cp.path("productId").canConvertToLong()) {
                checkoutPreview = new CheckoutPreviewRef(
                        cp.path("productId").asLong(),
                        cp.path("sku").asText(""),
                        cp.path("productName").asText(""),
                        cp.path("quantity").asInt(1),
                        cp.path("unitPriceCents").asLong(0L),
                        cp.path("totalCents").asLong(0L));
            }

            return new ConversationRefs(products, orders, returnPreview, checkoutPreview);
        } catch (Exception e) {
            return EMPTY;
        }
    }

    private static String money(long cents) {
        return "¥" + BigDecimal.valueOf(cents, 2).toPlainString();
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == '\t') {
                sb.append(' ');
            } else if (!Character.isISOControl(cp)) {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString().trim();
    }

    private static <T> List<T> cap(List<T> list, int max) {
        return list.size() <= max ? List.copyOf(list) : List.copyOf(list.subList(0, max));
    }
}
