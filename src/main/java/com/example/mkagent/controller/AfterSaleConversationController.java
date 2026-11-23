package com.example.mkagent.controller;

import com.example.mkagent.agent.AfterSaleAgent;
import com.example.mkagent.agent.ToolCallAgent;
import com.example.mkagent.conversation.ConversationService;
import com.example.mkagent.conversation.ConversationViews;
import com.example.mkagent.conversation.ConversationViews.ConversationDetailVO;
import com.example.mkagent.conversation.ConversationViews.ConversationVO;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.shop.ShopAccessGuard;
import com.example.mkagent.shop.aftersale.AfterSaleEligibility;
import com.example.mkagent.shop.aftersale.AfterSalePreview;
import com.example.mkagent.shop.aftersale.AfterSaleService;
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
 * 售后会话接口 + 结构化“发起确认卡”入口（买家侧）。
 */
@RestController
@RequestMapping("/shop/aftersale/conversations")
public class AfterSaleConversationController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConversationService conversationService;
    private final ShopAccessGuard shopAccessGuard;
    private final AfterSaleService afterSaleService;
    private final AfterSaleAgent afterSaleAgent;

    public AfterSaleConversationController(
            ConversationService conversationService,
            ShopAccessGuard shopAccessGuard,
            AfterSaleService afterSaleService,
            @Qualifier("afterSaleAgent") AfterSaleAgent afterSaleAgent
    ) {
        this.conversationService = conversationService;
        this.shopAccessGuard = shopAccessGuard;
        this.afterSaleService = afterSaleService;
        this.afterSaleAgent = afterSaleAgent;
    }

    public record PrepareApprovalRequest(
            String orderNo,
            Integer quantity,
            String reason,
            String conversationId
    ) {
    }

    @GetMapping
    public List<ConversationVO> list() {
        String userId = shopAccessGuard.requireBuyerIdentity();

        return conversationService.list(userId, AgentType.AFTER_SALES).stream()
                .filter(item -> item.messageCount() > 0)
                .map(ConversationViews::summary)
                .toList();
    }

    @PostMapping
    public Map<String, Object> create() {
        String userId = shopAccessGuard.requireBuyerIdentity();
        String conversationId = conversationService.create(userId, AgentType.AFTER_SALES);

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
                conversationService.detail(userId, conversationId, AgentType.AFTER_SALES));
    }

    /**
     * 显式可执行的“准备退货确认卡”入口：校验订单、数量、原因与退货资格后，
     * 直接通过同一套审批检查点机制创建待确认卡（确认前零建单、零占额度）。
     */
    @PostMapping("/prepare-approval")
    public Map<String, Object> prepareApproval(
            @RequestBody(required = false) PrepareApprovalRequest request
    ) {
        String userId = shopAccessGuard.requireBuyerIdentity();
        if (request == null || request.orderNo() == null || request.orderNo().isBlank()) {
            throw new BusinessException(400, "INVALID_REQUEST", "请先选择要退货的订单号");
        }
        String orderNo = request.orderNo().trim();
        Integer quantity = request.quantity();
        String normalizedReason = AfterSaleEligibility.normalizeReason(request.reason());

        AfterSalePreview preview = afterSaleService.preview(userId, orderNo, quantity, normalizedReason);
        if (!preview.eligible()) {
            throw new BusinessException(409,
                    preview.code() == null ? "RETURN_NOT_ELIGIBLE" : preview.code(),
                    preview.message() == null ? "当前订单不满足退货条件" : preview.message());
        }

        String convId = null;
        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            conversationService.requireOwned(userId, request.conversationId(), AgentType.AFTER_SALES);
            convId = request.conversationId().trim();
        }

        ToolCallAgent.PreparedApproval prepared = afterSaleAgent.prepareReturnApproval(
                userId, convId, orderNo, preview.requestedQty(), normalizedReason);

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
