package com.example.mkagent.rag;

import com.alibaba.cloud.ai.advisor.DocumentRetrievalAdvisor;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetriever;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatAppRagCloudAdvisorConfig {

    private static final String INDEX_NAME = "云知识库";

    private static final String RETRIEVAL_SYSTEM_TEMPLATE = """
            以下是从知识库检索到的上下文信息：
            ---------------------
            {question_answer_context}
            ---------------------
            请仅根据以上上下文回答用户问题。
            如果上下文中没有足够信息，请明确说明“知识库中没有相关信息”，不要编造答案。
            """;

    @Bean
    public DashScopeApi dashScopeApi(
            @Value("${spring.ai.dashscope.api-key}") String apiKey) {

        return DashScopeApi.builder()
                .apiKey(apiKey)
                .build();
    }

    @Bean
    public Advisor chatAppRagCloudAdvisor(DashScopeApi dashScopeApi) {
        DocumentRetriever documentRetriever = new DashScopeDocumentRetriever(
                dashScopeApi,
                DashScopeDocumentRetrieverOptions.builder()
                        .withIndexName(INDEX_NAME)
                        .build()
        );

        return new DocumentRetrievalAdvisor(
                documentRetriever,
                new PromptTemplate(RETRIEVAL_SYSTEM_TEMPLATE)
        );
    }
}