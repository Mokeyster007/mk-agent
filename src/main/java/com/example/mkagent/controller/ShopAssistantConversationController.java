package com.example.mkagent.controller;

import com.example.mkagent.agent.ShopAssistantAgent;
import com.example.mkagent.agent.ToolCallAgent;
import com.example.mkagent.conversation.ConversationService;
import com.example.mkagent.conversation.ConversationViews;
import com.example.mkagent.conversation.ConversationViews.ConversationDetailVO;
import com.example.mkagent.conversation.ConversationViews.ConversationVO;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.shop.ShopAccessGuard;
import com.example.mkagent.shop.order.ShopCheckoutService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 电商导购会话接口 + 结构化下单确认卡发起入口（买家侧）。
 */
@RestController
@RequestMapping("/shop/assistant/conversations")
public class ShopAssistantConversationController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConversationService conversationService;
    private final ShopAccessGuard shopAccessGuard;
    private final ShopCheckoutService checkoutService;
    private final ShopAssistantAgent shopAssistantAgent;

    public ShopAssistantConversationController(
            ConversationService conversationService,
            ShopAccessGuard shopAccessGuard,
            ShopCheckoutService checkoutService,
            @Qualifier("shopAssistantAgent") ShopAssistantAgent shopAssistantAgent
    ) {
        this.conversationService = conversationService;
        this.shopAccessGuard = shopAccessGuard;
        this.checkoutService = checkoutService;
        this.shopAssistantAgent = shopAssistantAgent;
    }

    public record PrepareCheckoutApprovalRequest(
            Long productId,
            Integer quantity,
            Boolean removeFromCart,
            String conversationId
    ) {
    }

    @GetMapping
    public List<ConversationVO> list() {
        String userId = shopAccessGuard.requireBuyerIdentity();
        return conversationService.list(userId, AgentType.SHOP_ASSISTANT).stream()
                .filter(item -> item.messageCount() > 0)
                .map(ConversationViews::summary)
                .toList();
    }

    @PostMapping
    public Map<String, Object> create() {
        String userId = shopAccessGuard.requireBuyerIdentity();
        String conversationId = conversationService.create(userId, AgentType.SHOP_ASSISTANT);
        return Map.of(
                "conversationId", conversationId,
                "title", "",
                "messages", List.of()
        );
    }

    @GetMapping("/{conversationId}")
    public ConversationDetailVO detail(@PathVariable String conversationId) {
        String userId = shopAccessGuard.requireBuyerIdentity();
        return ConversationViews.detail(
                conversationService.detail(userId, conversationId, AgentType.SHOP_ASSISTANT));
    }

    /**
     * 结构化按钮发起真实下单确认卡（确认前零建单、不锁库存）。
     */
    @PostMapping("/prepare-checkout-approval")
    public Map<String, Object> prepareCheckoutApproval(
            @RequestBody(required = false) PrepareCheckoutApprovalRequest request
    ) {
        String userId = shopAccessGuard.requireBuyerIdentity();
        if (request == null || request.productId() == null) {
            throw new BusinessException(400, "INVALID_REQUEST", "请先选择要结算的商品");
        }
        int qty = request.quantity() == null ? 1 : request.quantity();
        ShopCheckoutService.CheckoutPreview preview = checkoutService.preview(request.productId(), qty);
        if (!preview.eligible()) {
            throw new BusinessException(409,
                    preview.code() == null ? "CHECKOUT_NOT_ELIGIBLE" : preview.code(),
                    preview.message());
        }

        String convId = null;
        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            conversationService.requireOwned(userId, request.conversationId(), AgentType.SHOP_ASSISTANT);
            convId = request.conversationId().trim();
        }

        boolean removeFromCart = request.removeFromCart() == null || request.removeFromCart();
        ToolCallAgent.PreparedApproval prepared = shopAssistantAgent.prepareCheckoutApproval(
                userId, convId, preview.sku(), preview.productName(),
                preview.quantity(), preview.unitPriceCents(), removeFromCart);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", prepared.runId());
        body.put("approvalId", prepared.approvalId());
        try {
            JsonNode payload = MAPPER.readTree(prepared.eventJson());
            body.put("payload", payload);
        } catch (Exception ignored) {
            body.put("payloadJson", prepared.eventJson());
        }
        return body;
    }
}
