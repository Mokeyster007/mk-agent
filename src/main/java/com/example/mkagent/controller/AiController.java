package com.example.mkagent.controller;

import com.example.mkagent.agent.AfterSaleAgent;
import com.example.mkagent.agent.AgentTaskCanceller;
import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.MkManus;
import com.example.mkagent.agent.ShopAssistantAgent;
import com.example.mkagent.app.chatApp;
import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.conversation.ConversationSeed;
import com.example.mkagent.conversation.ConversationService;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.model.RunningAgentTask;
import com.example.mkagent.resilience.AgentRequestRateLimiter;
import com.example.mkagent.resilience.RateLimitResult;
import com.example.mkagent.shop.ShopAccessGuard;
import com.example.mkagent.shop.aftersale.AfterSaleService;
import com.example.mkagent.shop.aftersale.ReturnSubject;
import com.example.mkagent.shop.api.ShopViews;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    @Resource
    private chatApp chatApp;

    @Resource
    private MkManus mkManus;

    @Resource(name = "afterSaleAgent")
    private AfterSaleAgent afterSaleAgent;

    @Resource(name = "shopAssistantAgent")
    private ShopAssistantAgent shopAssistantAgent;

    @Resource
    private ShopAccessGuard shopAccessGuard;

    @Resource
    private AfterSaleService afterSaleService;

    @Resource
    private ConversationService conversationService;

    @Resource
    private AgentTaskRegistry agentTaskRegistry;

    @Resource
    private AgentTaskCanceller agentTaskCanceller;

    @Resource
    private AgentRequestRateLimiter agentRequestRateLimiter;

    @GetMapping("/chat_app/chat/sync")
    public String doChatWithChatAppSync(
            @RequestParam String message,
            @RequestParam String chatId
    ) {
        return chatApp.doChat(message, chatId);
    }

    @GetMapping("/chat_app/rag")
    public String doChatWithChatAppRag(
            @RequestParam String message,
            @RequestParam String chatId
    ) {
        return chatApp.doChatWithRag(message, chatId);
    }

    @GetMapping(
            value = "/chat_app/chat/sse",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<String> doChatWithChatAppSSE(
            @RequestParam String message,
            @RequestParam String chatId
    ) {
        return chatApp.doChatWithByStream(message, chatId);
    }

    @GetMapping(
            value = "/chat_app/chat/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<ServerSentEvent<String>> doChatWithChatAppEvents(
            @RequestParam String message,
            @RequestParam String chatId
    ) {
        return chatApp.doChatWithByStream(message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    @GetMapping(
            value = "/chat_app/chat/sse/emitter",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter doChatWithChatAppSseEmitter(
            @RequestParam String message,
            @RequestParam String chatId
    ) {
        SseEmitter emitter = new SseEmitter(180_000L);

        chatApp.doChatWithByStream(message, chatId)
                .subscribe(
                        chunk -> {
                            try {
                                emitter.send(chunk);
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete
                );

        return emitter;
    }

    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(
            String message,
            HttpServletRequest request
    ) {
        requireUserId();
        checkAgentRateLimit(request);
        return mkManus.runStream(message);
    }

    /**
     * 电商导购与下单助手入口（{@code AgentType.SHOP_ASSISTANT}）。
     */
    @GetMapping("/shop/chat")
    public SseEmitter doChatWithShopAssistant(
            String message,
            @RequestParam(required = false) String conversationId,
            HttpServletRequest request
    ) {
        String userId = shopAccessGuard.requireBuyerIdentity();
        checkAgentRateLimit(request);

        if (conversationId == null || conversationId.isBlank()) {
            return shopAssistantAgent.runStream(message);
        }

        UUID runId = UUID.randomUUID();
        ConversationSeed seed = conversationService.beginTurn(
                userId, conversationId, message, runId, AgentType.SHOP_ASSISTANT);

        try {
            return shopAssistantAgent.runStream(message, seed);
        } catch (RuntimeException e) {
            conversationService.dropUserMessage(runId.toString());
            throw e;
        }
    }

    /**
     * 电商售后入口（{@code AgentType.AFTER_SALES}）。
     *
     * <p>可选参数 {@code orderNo}：当用户从订单详情或可退订单卡片进入售后时，
     * 服务端按当前买家身份核验该订单真实事实，并作为权威上下文注入给售后 Agent，
     * 避免模型在多笔订单间猜测或遗漏已知订单号。
     */
    @GetMapping("/aftersale/chat")
    public SseEmitter doChatWithAfterSale(
            String message,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String orderNo,
            HttpServletRequest request
    ) {
        String userId = shopAccessGuard.requireBuyerIdentity();
        checkAgentRateLimit(request);

        String orderContextBlock = buildOrderContextBlock(userId, orderNo);

        if (conversationId == null || conversationId.isBlank()) {
            if (orderContextBlock == null) {
                return afterSaleAgent.runStream(message);
            }
            ConversationSeed contextOnly = new ConversationSeed(
                    null, UUID.randomUUID(), List.of(), orderContextBlock);
            return afterSaleAgent.runStream(message, contextOnly);
        }

        UUID runId = UUID.randomUUID();
        ConversationSeed seed = conversationService.beginTurn(
                userId, conversationId, message, runId, AgentType.AFTER_SALES);

        if (orderContextBlock != null) {
            String mergedRefs = (seed.refsBlock() == null || seed.refsBlock().isBlank())
                    ? orderContextBlock
                    : orderContextBlock + "\n\n" + seed.refsBlock();
            seed = new ConversationSeed(
                    seed.conversationId(), seed.runId(), seed.history(), mergedRefs);
        }

        try {
            return afterSaleAgent.runStream(message, seed);
        } catch (RuntimeException e) {
            conversationService.dropUserMessage(runId.toString());
            throw e;
        }
    }

    @PostMapping("/manus/{runId}/cancel")
    public Map<String, Object> cancelManusTask(@PathVariable String runId) {
        String userId = requireUserId();

        RunningAgentTask task = agentTaskRegistry.get(runId);

        if (task == null || !userId.equals(task.context().getUserId())) {
            throw new BusinessException(404, "任务不存在或已结束：" + runId);
        }

        AgentTaskCanceller.CancelResult result = agentTaskCanceller.cancel(task);

        return Map.of(
                "success", true,
                "runId", runId,
                "state", result.state(),
                "message", result.message()
        );
    }

    private String buildOrderContextBlock(String userId, String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return null;
        }
        Optional<ReturnSubject> subjectOpt = afterSaleService.subject(userId, orderNo.trim());
        if (subjectOpt.isEmpty()) {
            return null;
        }
        ReturnSubject s = subjectOpt.get();
        return "[当前页面关联订单｜已由服务端按当前买家核验]\n"
                + "订单号：" + s.orderNo() + "\n"
                + "状态：" + s.status().label() + "（" + s.status().name() + "）\n"
                + "商品：" + s.productName() + "（SKU=" + s.sku() + "）\n"
                + "成交单价：" + ShopViews.money(s.unitPriceCents()) + "\n"
                + "已购数量：" + s.purchasedQty() + " 件｜已占退货额度：" + s.occupiedQty()
                + " 件｜当前剩余可退：" + s.quota().remaining() + " 件\n"
                + "规则：用户本轮针对此订单提问或退货时，直接沿用该真实订单号，不得再反复询问“是哪一单”。";
    }

    private String requireUserId() {
        String userId = UserContextHolder.get();
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(
                    401,
                    "缺少用户身份，请在请求头中携带 "
                            + UserContextHolder.USER_ID_HEADER
            );
        }
        return userId;
    }

    private void checkAgentRateLimit(HttpServletRequest request) {
        String userId = UserContextHolder.get();

        String rateLimitKey = (userId != null && !userId.isBlank())
                ? "user:" + userId
                : "ip:" + request.getRemoteAddr();

        RateLimitResult result = agentRequestRateLimiter.tryAcquire(rateLimitKey);

        if (!result.allowed()) {
            throw new BusinessException(
                    429,
                    "请求过于频繁，请在约 " + result.waitSeconds()
                            + " 秒后重试（限制：每分钟最多 "
                            + result.limit() + " 次 Agent 请求）。"
            );
        }
    }
}
