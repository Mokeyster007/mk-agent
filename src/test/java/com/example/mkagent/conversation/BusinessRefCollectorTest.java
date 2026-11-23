package com.example.mkagent.conversation;

import com.example.mkagent.model.AgentRunContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务引用抽取的规则测试（电商商品候选、签收订单、预览上下文 + 历史记录反序列化兼容）。
 */
class BusinessRefCollectorTest {

    private static final String PRODUCT_SEARCH_RESULT = """
            {"ok":true,"data":{
              "items":[
                {"id":2004,"sku":"SKU-KEYBOARD-A75","name":"无线轻音机械键盘 Air75","priceCents":36900,"available":8},
                {"id":2005,"sku":"SKU-KEYBOARD-B68","name":"紧凑蓝牙键盘 B68","priceCents":25900,"available":6}
              ]
            }}
            """;

    private static final String SIGNED_ORDERS_RESULT = """
            {"ok":true,"data":[
              {"orderNo":"SO-2026-0001","productName":"机械键盘 K87","sku":"SKU-KEYBOARD-K87","quantity":2}
            ],"meta":{"count":1}}
            """;

    private static final String RETURN_PREVIEW_RESULT = """
            {"ok":true,"data":{
              "orderNo":"SO-2026-0001","productName":"机械键盘 K87",
              "quantity":1,"reason":"按键回弹偏硬","eligible":true
            }}
            """;

    @Test
    @DisplayName("商品检索结果按顺序进入候选商品引用，支持“第二款加一把”指代绑定")
    void productSearchKeepsOrder() {
        AgentRunContext ctx = new AgentRunContext();

        assertTrue(BusinessRefCollector.collect(ctx, "search_products", PRODUCT_SEARCH_RESULT));

        ConversationRefs refs = ctx.getConversationRefs();
        assertEquals(2, refs.products().size());
        assertEquals(2004L, refs.products().get(0).id());
        assertEquals("SKU-KEYBOARD-B68", refs.products().get(1).sku());
        String block = refs.renderBlock();
        assertTrue(block.contains("1. 无线轻音机械键盘 Air75"), block);
        assertTrue(block.contains("2. 紧凑蓝牙键盘 B68"), block);
    }

    @Test
    @DisplayName("签收订单列表与退货预览都正确沉淀到会话引用")
    void afterSaleOrdersAndPreviewCollected() {
        AgentRunContext ctx = new AgentRunContext();

        assertTrue(BusinessRefCollector.collect(ctx, "list_my_signed_orders", SIGNED_ORDERS_RESULT));
        assertTrue(BusinessRefCollector.collect(ctx, "preview_return", RETURN_PREVIEW_RESULT));

        ConversationRefs refs = ctx.getConversationRefs();
        assertEquals(1, refs.orders().size());
        assertEquals("SO-2026-0001", refs.orders().get(0).orderNo());
        assertEquals("SO-2026-0001", refs.lastReturnPreview().orderNo());
        assertEquals(1, refs.lastReturnPreview().quantity());
    }

    @Test
    @DisplayName("工具失败不产生任何引用")
    void failureProducesNoRefs() {
        AgentRunContext ctx = new AgentRunContext();

        assertFalse(BusinessRefCollector.collect(ctx, "search_products",
                "{\"ok\":false,\"data\":null,\"meta\":{\"hint\":\"没找到\"}}"));

        assertTrue(ctx.getConversationRefs().isEmpty());
        assertNull(ctx.getConversationRefs().renderBlock());
    }

    @Test
    @DisplayName("二次编码的信封也能解析")
    void doubleEncodedEnvelopeIsUnwrapped() {
        AgentRunContext ctx = new AgentRunContext();

        String doubleEncoded = "\"" + PRODUCT_SEARCH_RESULT.trim()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";

        assertTrue(BusinessRefCollector.collect(ctx, "search_products", doubleEncoded));
        assertEquals(2, ctx.getConversationRefs().products().size());
    }

    @Test
    @DisplayName("引用能原样 JSON 往返，且兼容历史库里残留的旧字段")
    void jsonRoundTripAndLegacyCompat() {
        ConversationRefs refs = ConversationRefs.EMPTY
                .withProducts(List.of(new ConversationRefs.ProductRef(
                        2004L, "SKU-KEYBOARD-A75", "无线轻音机械键盘 Air75", 36900L, 8)));

        ConversationRefs restored = ConversationRefs.fromJson(refs.toJson());
        assertEquals(refs, restored);

        // 旧库历史记录里若仍有 songs/playlists 字段，不得反序列化报错，优雅退化为空电商引用
        ConversationRefs legacy = ConversationRefs.fromJson(
                "{\"songs\":[{\"id\":1001,\"title\":\"旧歌\"}],\"playlists\":[]}");
        assertTrue(legacy.isEmpty());
        assertTrue(ConversationRefs.fromJson("{不是 JSON").isEmpty());
        assertTrue(ConversationRefs.fromJson(null).isEmpty());
    }
}
