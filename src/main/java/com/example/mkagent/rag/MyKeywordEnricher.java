package com.example.mkagent.rag;


import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MyKeywordEnricher {

    @Resource
    private ChatAppDocumentLoader chatAppDocumentLoader;


    @Resource
    DashScopeChatModel dashscopeChatModel;
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
