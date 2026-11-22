package com.example.mkagent.controller;

import com.example.mkagent.conversation.ConversationService;
import com.example.mkagent.conversation.ConversationViews;
import com.example.mkagent.conversation.ConversationViews.ConversationDetailVO;
import com.example.mkagent.conversation.ConversationViews.ConversationVO;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.music.MusicAccessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 音乐会话（同一窗口连续聊天）的只读接口 + 新建。
 *
 * <p>**这里没有"继续上一轮"的接口**：续聊不是"复活旧 run"，
 * 而是带着 conversationId 再发一条 /ai/music/chat——
 * 后端会新建 runId，把历史文本与业务引用注入给模型。
 *
 * <p>归属与领域校验都在 {@link ConversationService}：本入口只认
 * {@link AgentType#MUSIC_USER} 的会话，售后会话在这里同样是 404。
 * 售后入口在 {@code AfterSaleConversationController}，守卫与领域都不同。
 *
 * <p>不存在与不是本人共用同一句 404 文案，外部无法据此枚举他人的会话。
 */
@RestController
@RequestMapping("/ai/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final MusicAccessGuard musicAccessGuard;

    public ConversationController(
            ConversationService conversationService,
            MusicAccessGuard musicAccessGuard
    ) {
        this.conversationService = conversationService;
        this.musicAccessGuard = musicAccessGuard;
    }

    /**
     * 会话列表（最近活动在前）。
     *
     * <p>过滤掉还没有任何消息的会话：新建会话是"点一下就建"的动作，
     * 建了没用（比如发消息前就改了主意）不该在列表里留一条空壳。
     */
    @GetMapping
    public List<ConversationVO> list() {
        String userId = musicAccessGuard.requireUser();

        return conversationService.list(userId, AgentType.MUSIC_USER).stream()
                .filter(item -> item.messageCount() > 0)
                .map(ConversationViews::summary)
                .toList();
    }

    /**
     * 新建一个空会话。
     *
     * <p>为什么先建再用：前端要在**发第一条消息之前**就知道 conversationId，
     * 否则第一条消息只能落成独立任务，之后再也接不上。
     */
    @PostMapping
    public Map<String, Object> create() {
        String userId = musicAccessGuard.requireUser();
        String conversationId = conversationService.create(userId, AgentType.MUSIC_USER);

        return Map.of(
                "conversationId", conversationId,
                "title", "",
                "messages", List.of()
        );
    }

    /**
     * 会话详情：消息按时间正序。
     *
     * <p>刷新页面后靠这个接口恢复同一会话的聊天记录，
     * 而不是靠前端内存（内存一刷就没）。
     */
    @GetMapping("/{conversationId}")
    public ConversationDetailVO detail(@PathVariable String conversationId) {
        String userId = musicAccessGuard.requireUser();
        return ConversationViews.detail(
                conversationService.detail(userId, conversationId, AgentType.MUSIC_USER));
    }
}
