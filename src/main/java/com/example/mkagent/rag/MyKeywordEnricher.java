package com.example.mkagent.rag;


import jakarta.annotation.Resource;
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
 */
@Component
public class MyKeywordEnricher {

    @Resource
    private ChatAppDocumentLoader chatAppDocumentLoader;


    @Resource
    private ChatModel dashscopeChatModel;

    List<Document> enrichDocuments(List<Document> documents)  {
        KeywordMetadataEnricher enricher = new KeywordMetadataEnricher(this.dashscopeChatModel,5);

        return  enricher.apply(documents);

    }

    /**
     * 把获取到关键词的内容添加到云信息的  向量数据库
     * @param dashscopeEmbeddingModel
     * @return
    @Bean
    VectorStore chatAppVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel)
                .build();

        List<Document> documents = chatAppDocumentLoader.loadMarkdowns();
        List<Document> enricheddocuments = enrichDocuments(documents);

        simpleVectorStore.add(enricheddocuments);

        return simpleVectorStore;
    }
    */
}
