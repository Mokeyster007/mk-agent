package com.example.mkagent.rag;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档关键词富化器。
 *
 * 依赖声明为 ChatModel 而不是 DashScopeChatModel：
 * 1. 便于单测注入桩模型，不需要构造真实 DashScope 客户端；
 * 2. 后续接入"辅助模型"（mkagent.model.auxiliary）时不需要改注入类型。
 *
 * 关键语义：关键词只是检索增强的辅助信息，
 * 提取失败（欠费、超时、返回格式异常）不能阻断文档入库，
 * 因此单片段富化失败时降级为"不带关键词的文档"。
 */
@Component
public class MyKeywordEnricher {

    private static final Logger log =
            LoggerFactory.getLogger(MyKeywordEnricher.class);

    /**
     * 每个片段提取的关键词数量，与原 KeywordMetadataEnricher 用法保持一致。
     */
    private static final int KEYWORD_COUNT = 5;

    private final ChatModel chatModel;

    public MyKeywordEnricher(ChatModel dashscopeChatModel) {
        this.chatModel = dashscopeChatModel;
    }

    /**
     * 批量富化（保留原有入口）。
     *
     * 注意：批量入口不吞异常，异常由调用方决定如何处理；
     * 需要"失败降级"语义时请使用 {@link #enrichDocument(Document)}。
     */
    List<Document> enrichDocuments(List<Document> documents) {
        KeywordMetadataEnricher enricher =
                new KeywordMetadataEnricher(this.chatModel, KEYWORD_COUNT);

        return enricher.apply(documents);
    }

    /**
     * 单片段富化，失败降级。
     *
     * @param document 原始片段
     * @return 带 excerpt_keywords 的新片段；提取失败时原样返回入参
     */
    public Document enrichDocument(Document document) {
        try {
            List<Document> enriched = new KeywordMetadataEnricher(
                    this.chatModel,
                    KEYWORD_COUNT
            ).apply(List.of(document));

            return enriched.isEmpty() ? document : enriched.get(0);

        } catch (Exception e) {
            /*
             * 只记录文档 ID 与异常类型：
             * 不打印文档正文，也不打印提示词原文。
             */
            log.warn(
                    "关键词提取失败，降级为不带关键词的片段：documentId={}, error={}",
                    document.getId(),
                    e.toString()
            );
            return document;
        }
    }
}
