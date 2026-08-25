package com.example.mkagent.rag;


import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;


/**
 * 创建自定义RAG 检索增强顾问的工厂
 *
 * 配置文档过滤规则,本质类似SQL的WHERE
 * 知识库中包含多个类别的文档，希望限定检索范围	建议为文档 添加标签，知识库检索时会先根据标签筛选相关文档
 * 知识库中有多篇结构相似的文档，‌希望精确定位	    提取元数据，知识库会先使用元数据进行结构化‌搜索，再进行向量检索
 *
 *
 */
@Slf4j
public class ChatAppRagCustomAdvisorFactory {

    /**
     * 创建自定义RAG 检索增强顾问，根据状态来进行查询
     * @param vectorStore 向量存储
     * @param status      状态
     * @return  自定义的RAG检索增强顾问
     */
    public static Advisor createChatAppRagCustomAdvisor(VectorStore vectorStore, String status) {
        Filter.Expression expression = new FilterExpressionBuilder()
                .eq("status", status)
                .build();
        //创建文档检索器
        DocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .filterExpression(expression)
                .similarityThreshold(0.5)
                .topK(3)
                .build();
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)//文档检索器
                .queryAugmenter(ChatAppContextualQueryAugmenter.createInstance())//添加一个能够查询是否存在问题的相关数据的查询增强器
                .build();
    }
}
