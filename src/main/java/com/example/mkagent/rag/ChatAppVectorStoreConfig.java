package com.example.mkagent.rag;


import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 向量数据库配置（初始化基于内存的向量数据库bean）
 * 目前的进程关闭后释放内存会把数据也释放，只有再次启动应用会再次加载读取初始化
 */
@Configuration
public class ChatAppVectorStoreConfig {

   @Resource
    private ChatAppDocumentLoader chatAppDocumentLoader;

   @Resource
   private MyKeywordEnricher myKeywordEnricher;

   @Bean
    VectorStore chatAppVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel)//注入对应的embedding模型，用于将对应的数据转化并存储到向量数据库中
                .build();

        List<Document> documents = chatAppDocumentLoader.loadMarkdowns();
        List<Document> enricheddocument = myKeywordEnricher.enrichDocuments(documents);
        simpleVectorStore.add(enricheddocument);
        return simpleVectorStore;
    }
}
