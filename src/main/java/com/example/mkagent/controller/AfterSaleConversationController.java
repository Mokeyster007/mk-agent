package com.example.mkagent.controller;

import com.example.mkagent.conversation.ConversationService;
import com.example.mkagent.conversation.ConversationViews;
import com.example.mkagent.conversation.ConversationViews.ConversationDetailVO;
import com.example.mkagent.conversation.ConversationViews.ConversationVO;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.shop.ShopAccessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 售后会话的只读接口 + 新建（买家侧）。
 *
 * <h2>为什么不能复用 /ai/conversations</h2>
 *
 * 那个入口的守卫是 {@link com.example.mkagent.music.MusicAccessGuard#requireUser()}，
 * 要求 {@code music_user} 里有这个人。合法的商城买家完全可能没有开通音乐服务，
 * 于是他在售后页**建不出会话**，追问只能退化成单轮——这是真实的可用性缺陷，
 * 而不是"演示身份恰好两者都有"就能掩盖的。
 *
 * <p>正确做法不是放宽音乐守卫（那会让音乐入口对非音乐用户开放），
 * 也不是给买家补一条 music_user（那是伪造身份），
 * 而是**另开一个领域受限的入口**：守卫换成 {@link ShopAccessGuard#requireBuyerIdentity()}，
 * 会话领域钉死为 {@link AgentType#AFTER_SALES}。
 *
 * <h2>领域边界</h2>
 *
 * 会话在库里带 {@code agent_type}。同一个 {@code user_id} 即使同时是音乐用户和买家，
 * 他的音乐会话与售后会话也是两条互不可见的记录：
 * <ul>
 *   <li>本入口只列 / 只读 AFTER_SALES 的会话；</li>
 *   <li>把音乐会话 id 拿到这里来读，或把售后会话 id 拿到 /ai/conversations 去读，
 *       都返回 404（与"不存在"同一句文案，不泄露它是否属于别人）。</li>
 * </ul>
 *
 * <p>审批的恢复与决定**不走这里**：它们仍然只认 approvalId，
 * 归属由 run 的 owner 判定，与会话入口无关。
 */
@RestController
@RequestMapping("/shop/aftersale/conversations")
public class AfterSaleConversationController {

    private final ConversationService conversationService;
    private final ShopAccessGuard shopAccessGuard;

    public AfterSaleConversationController(
            ConversationService conversationService,
            ShopAccessGuard shopAccessGuard
    ) {
        this.conversationService = conversationService;
        this.shopAccessGuard = shopAccessGuard;
    }

    /**
     * 我的售后会话（最近活动在前）。
     *
     * <p>过滤掉还没有消息的空会话，与音乐入口同一口径。
     */
    @GetMapping
    public List<ConversationVO> list() {
        String userId = shopAccessGuard.requireBuyerIdentity();

        return conversationService.list(userId, AgentType.AFTER_SALES).stream()
                .filter(item -> item.messageCount() > 0)
                .map(ConversationViews::summary)
                .toList();
    }

    /**
     * 新建一个空售后会话。
     *
     * <p>先建再发：前端要在发第一条消息之前就拿到 conversationId，
     * 否则第一条只能落成独立任务，刷新后接不回来。
     */
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

    /**
     * 会话详情（刷新后恢复聊天记录与待确认的审批卡）。
     *
     * <p>不是自己的、或不是售后领域的，一律 404。
     */
    @GetMapping("/{conversationId}")
    public ConversationDetailVO detail(@PathVariable String conversationId) {
        String userId = shopAccessGuard.requireBuyerIdentity();
        return ConversationViews.detail(
                conversationService.detail(userId, conversationId, AgentType.AFTER_SALES));
    }
}
