package com.example.mkagent.agent;

import com.example.mkagent.approval.PauseResult;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.tools.ToolEventMessageProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * 电商导购与下单助手（{@code AgentType.SHOP_ASSISTANT}）。
 *
 * <p>职责边界：
 * <ul>
 *   <li>硬条件检索与比较由商品服务严格执行，绝不把不合规商品冒充匹配；</li>
 *   <li>工具执行后立刻下发 {@code structured_data} 事件（商品卡、比较表、购物车快照、结算预览、真实成单结果），
 *       前端完全由结构化数据驱动，不反解析聊天文本；</li>
 *   <li>仅在用户明确要求加购时才写购物车；下单必须走 {@code submit_checkout_order} 人工确认卡，
 *       确认前零建单、不锁库存。</li>
 * </ul>
 */
public class ShopAssistantAgent extends ToolCallAgent {

    private static final Logger log = LoggerFactory.getLogger(ShopAssistantAgent.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ATTR_CHECKOUT_PREVIEW = "shop.checkoutPreview";
    private static final String ATTR_CREATED_ORDER_NO = "shop.createdOrderNo";

    private static final Pattern ORDER_SUBMIT_INTENT = Pattern.compile(
            "(确认下单|直接下单|帮我下单|去下单|立即下单|提交订单|确认结算|直接结算|买这[一]?[款把个件])");

    private static final Pattern FALSE_ORDER_CLAIM = Pattern.compile(
            "(正在为您下单|已为您创建订单|订单已创建成功|已为您提交订单)");

    private static final Pattern NEGATION_HINT = Pattern.compile("(未创建|尚未创建|未下单|没有下单|尚未下单)");

    public static final String SYSTEM_PROMPT = """
            你是 MkAgent 电商导购助手，帮助买家按需求选品、对比规格、管理共享购物车、预览结算并在买家确认后创建演示订单。

            必须遵守：

            1. 硬条件与偏好分开：
               - 预算上限/下限、是否无线、是否兼容 Mac、品类、是否有货等是**硬条件**，必须传给 search_products 由商品服务过滤；
               - “最好安静”这类诉求是**软偏好**（preferSilent=true），只用于合规候选排序，绝不能放宽硬条件；
               - 若 search_products 返回空列表（0 款匹配），必须如实说明当前没有符合全部硬条件的商品，
                 并把工具返回的放宽建议告知用户，让用户选择放宽哪一项——严禁推荐不满足硬条件的商品冒充匹配！

            2. 规格与价格必须以实时工具结果为准：
               - 绝不凭记忆编造价格、库存、SKU 或规格；
               - 规格字段为“未知”时，必须明确告诉用户该项规格未知，不得臆测；
               - 推荐或比较商品后，保持候选项编号与工具返回顺序一致，方便用户说“第二款加一把”。

            3. 严守购物车与下单边界：
               - 用户只问推荐或比较时，**禁止**调用 add_to_cart 或 submit_checkout_order；
               - 只有用户明确说加入购物车（如“第二款加一把”）时，才按会话已知对象里的真实 SKU 调用 add_to_cart；
                 若当前候选为空、已变化或指代有歧义，必须先请用户确认具体款式，不得猜 SKU；
               - 加购不等于下单，也不锁定库存。

            4. 单 SKU 结算与人工确认：
               - 当前订单模型只支持单 SKU 订单，结算时从购物车或候选中明确选定一款 SKU 与数量；
               - 先调用 preview_checkout 获取服务端实时报价与库存校验；
               - 当用户明确要下单且 preview_checkout 返回 eligible=true 时，必须在下一步直接调用 submit_checkout_order
                 （传入 sku、quantity、expectedUnitPriceCents），触发人工确认卡；
               - 未经用户批准确认卡并收到真实 orderNo 之前，严禁宣称“已下单 / 正在为您下单”；
               - 若确认执行时遇到价格变动、商品下架或库存不足，如实说明并提示按新报价重新预览确认，不得自动重试；
               - 必须说明本演示为“演示付款（无真实资金交易）”，且“下单不锁定库存，后台发货时才扣减”。

            5. 售后问题引导：
               - 若用户要申请退货或查退货进度，引导前往「售后与退货」页（或先用 get_my_recent_orders 帮用户定位订单号）。
            """;

    public static final String SYSTEM_PROMPT_VERSION = "shop-assistant-phase1/v1";

    public record CheckoutDraft(
            long productId,
            String sku,
            String productName,
            int quantity,
            long unitPriceCents,
            long totalCents
    ) {
    }

    public ShopAssistantAgent(
            ChatModel chatModel,
            ToolCallback[] tools,
            Executor agentExecutor,
            ToolEventMessageProvider toolEventMessageProvider
    ) {
        super(chatModel, tools, agentExecutor, toolEventMessageProvider);
        setName("ShopAssistantAgent");
        setSystemPrompt(SYSTEM_PROMPT);
        setMaxSteps(AgentBudgetPolicy.DEFAULT_MAX_STEPS);
        setMaxToolCalls(AgentBudgetPolicy.DEFAULT_MAX_TOOL_CALLS);
        setTimeout(AgentBudgetPolicy.DEFAULT_TIMEOUT);
    }

    @Override
    protected AgentType getAgentType() {
        return AgentType.SHOP_ASSISTANT;
    }

    public void applyBudget(
            int maxSteps,
            int maxToolCalls,
            Duration timeout,
            Duration sseBuffer
    ) {
        setMaxSteps(AgentBudgetPolicy.maxSteps(maxSteps, "mkagent.agent.shop.max-steps"));
        setMaxToolCalls(AgentBudgetPolicy.maxToolCalls(maxToolCalls, "mkagent.agent.shop.max-tool-calls"));
        setTimeout(AgentBudgetPolicy.timeout(timeout, "mkagent.agent.shop.timeout"));
        setSseBuffer(sseBuffer);
    }

    /**
     * 结构化按钮直接发起真实下单确认卡（复用同一套审批与恢复机制）。
     */
    public PreparedApproval prepareCheckoutApproval(
            String userId,
            String conversationId,
            String sku,
            String productName,
            int quantity,
            long expectedUnitPriceCents,
            boolean removeFromCart
    ) {
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("sku", sku);
            args.put("quantity", quantity);
            args.put("expectedUnitPriceCents", expectedUnitPriceCents);
            args.put("removeFromCart", removeFromCart);
            String argsJson = MAPPER.writeValueAsString(args);
            AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                    "prepare-order-" + UUID.randomUUID(),
                    "function",
                    "submit_checkout_order",
                    argsJson
            );
            String summary = "确认下单：" + productName + "（" + sku + "）× " + quantity;
            return prepareDirectApproval(userId, conversationId, summary, call);
        } catch (Exception e) {
            throw new IllegalStateException("准备下单确认卡失败：" + e.getMessage(), e);
        }
    }

    @Override
    protected void onToolsExecuted(AgentRunContext ctx, ToolResponseMessage toolResponseMessage) {
        for (ToolResponseMessage.ToolResponse item : toolResponseMessage.getResponses()) {
            JsonNode envelope = parseEnvelope(item.responseData());
            if (envelope == null) {
                continue;
            }
            boolean ok = envelope.path("ok").asBoolean(false);
            JsonNode data = envelope.path("data");

            switch (item.name()) {
                case "search_products" -> {
                    if (ok && data.isObject()) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("kind", "product_cards");
                        payload.put("data", data);
                        emitStructuredData(ctx, payload);
                    }
                }
                case "compare_products" -> {
                    if (ok && data.isObject()) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("kind", "product_comparison");
                        payload.put("data", data);
                        emitStructuredData(ctx, payload);
                    }
                }
                case "get_my_cart" -> {
                    if (ok && data.isObject()) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("kind", "cart_snapshot");
                        payload.put("data", data);
                        emitStructuredData(ctx, payload);
                    }
                }
                case "add_to_cart", "update_cart_item", "remove_cart_item" -> {
                    if (ok && data.isObject() && data.has("cart")) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("kind", "cart_snapshot");
                        payload.put("data", data.path("cart"));
                        if (data.has("added")) {
                            payload.put("added", data.path("added"));
                        }
                        emitStructuredData(ctx, payload);
                    }
                }
                case "preview_checkout" -> {
                    if (ok && data.isObject()) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("kind", "checkout_preview");
                        payload.put("data", data);
                        emitStructuredData(ctx, payload);

                        if (data.path("eligible").asBoolean(false)
                                && data.path("productId").canConvertToLong()
                                && !data.path("sku").asText("").isBlank()) {
                            ctx.setAttribute(ATTR_CHECKOUT_PREVIEW, new CheckoutDraft(
                                    data.path("productId").asLong(),
                                    data.path("sku").asText(""),
                                    data.path("productName").asText(""),
                                    data.path("quantity").asInt(1),
                                    data.path("unitPriceCents").asLong(0L),
                                    data.path("totalCents").asLong(0L)
                            ));
                        }
                    }
                }
                case "submit_checkout_order" -> {
                    if (ok && data.isObject() && !data.path("orderNo").asText("").isBlank()) {
                        String orderNo = data.path("orderNo").asText("");
                        ctx.setAttribute(ATTR_CREATED_ORDER_NO, orderNo);
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("kind", "order_created");
                        payload.put("orderNo", orderNo);
                        payload.put("order", data.path("order"));
                        emitStructuredData(ctx, payload);
                    } else {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("kind", "checkout_failed");
                        payload.put("message", envelope.path("error").asText("订单未创建"));
                        emitStructuredData(ctx, payload);
                    }
                }
                default -> {
                    // 其它工具不发结构化卡片
                }
            }
        }
    }

    @Override
    protected List<AssistantMessage.ToolCall> interceptEarlyTextFinish(
            AgentRunContext ctx,
            String assistantText
    ) {
        if (ctx.getResumeMode() != null || ctx.getAttribute(ATTR_CREATED_ORDER_NO, String.class) != null) {
            return List.of();
        }
        CheckoutDraft draft = ctx.getAttribute(ATTR_CHECKOUT_PREVIEW, CheckoutDraft.class);
        if (draft == null) {
            return List.of();
        }
        String userPrompt = latestUserPrompt(ctx);
        boolean wantsOrder = ORDER_SUBMIT_INTENT.matcher(userPrompt).find();
        boolean proseClaimsOrder = claimsOrderInProse(assistantText);
        if (!wantsOrder && !proseClaimsOrder) {
            return List.of();
        }

        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("sku", draft.sku());
            args.put("quantity", draft.quantity());
            args.put("expectedUnitPriceCents", draft.unitPriceCents());
            args.put("removeFromCart", true);
            String argsJson = MAPPER.writeValueAsString(args);
            log.info("结算预览已通过但模型未调 submit_checkout_order，自动桥接到确认卡：runId={}, sku={}, qty={}",
                    ctx.getRunId(), draft.sku(), draft.quantity());
            return List.of(new AssistantMessage.ToolCall(
                    "bridge-checkout-" + UUID.randomUUID(),
                    "function",
                    "submit_checkout_order",
                    argsJson
            ));
        } catch (Exception e) {
            log.warn("构造下单确认桥接失败：runId={}", ctx.getRunId(), e);
            return List.of();
        }
    }

    @Override
    protected void onPausedForApproval(
            AgentRunContext ctx,
            PauseResult result,
            List<AssistantMessage.ToolCall> toolCalls
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "checkout_waiting_confirmation");
        payload.put("approvalId", result.approvalId());
        emitStructuredData(ctx, payload);
    }

    @Override
    protected String sanitizeFinalAnswer(AgentRunContext ctx, String candidateAnswer) {
        if (candidateAnswer == null) {
            return "";
        }
        if (ctx.getAttribute(ATTR_CREATED_ORDER_NO, String.class) == null
                && claimsOrderInProse(candidateAnswer)) {
            return candidateAnswer
                    + "\n\n> 【系统核对】本次尚未生成订单确认卡，也未创建订单（无订单号）。"
                    + "请点击结算预览卡片上的「发起确认下单」或前往购物车完成确认。";
        }
        return candidateAnswer;
    }

    private static boolean claimsOrderInProse(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (NEGATION_HINT.matcher(text).find()) {
            return false;
        }
        return FALSE_ORDER_CLAIM.matcher(text).find();
    }

    private static String latestUserPrompt(AgentRunContext ctx) {
        List<Message> messages = ctx.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage userMsg) {
                return userMsg.getText();
            }
        }
        return "";
    }

    private static JsonNode parseEnvelope(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (node != null && node.isTextual()) {
                node = MAPPER.readTree(node.asText());
            }
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }
}
