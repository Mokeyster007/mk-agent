package com.example.mkagent.rag;

import com.example.mkagent.app.chatApp;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.support.RagTestDoubles;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * mkagent.rag.enabled=false 的验收测试。
 *
 * 验收点：
 * 1. 不注册 chatAppVectorStore Bean（连构建入口都不存在）；
 * 2. 启动阶段不产生任何模型调用；
 * 3. RAG 调用直接返回 503「知识库未启用」，而不是 500。
 *
 * 这里刻意把模型桩设成"调用即抛异常"：
 * 只要有任何一处漏掉开关、真的调用了模型，测试就会失败。
 */
@SpringBootTest(
        properties = {
                "spring.autoconfigure.exclude="
                        + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.McpToolCallbackAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.StdioTransportAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.SseHttpClientTransportAutoConfiguration",
                "spring.main.allow-bean-definition-overriding=true",
                "spring.datasource.url=jdbc:h2:mem:mk_agent_rag_disabled;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/schema.sql",
                "mkagent.rag.pgvector.enabled=false",
                "mkagent.rag.enabled=false",
                "spring.ai.dashscope.api-key=test-key",
                "spring.ai.dashscope.chat.options.model=qwen-test",
                "search-api.api-key=test-key"
        }
)
@ActiveProfiles("test")
class RagDisabledTest {

    private static final AtomicInteger MODEL_CALLS = new AtomicInteger();

    private static final AtomicInteger EMBEDDING_CALLS = new AtomicInteger();

    @TestConfiguration(proxyBeanMethods = false)
    static class StubModelConfig {

        @Bean("dashscopeChatModel")
        @Primary
        ChatModel dashscopeChatModel() {
            return RagTestDoubles.failingChatModel(MODEL_CALLS);
        }

        @Bean
        @Primary
        EmbeddingModel dashscopeEmbeddingModel() {
            return RagTestDoubles.failingEmbeddingModel(EMBEDDING_CALLS);
        }

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private chatApp chatApp;

    @Test
    void disabledRagRegistersNoStoreAndReturns503() {
        boolean storeRegistered = Arrays.stream(
                        applicationContext.getBeanNamesForType(VectorStore.class)
                )
                .anyMatch("chatAppVectorStore"::equals);

        assertFalse(storeRegistered, "enabled=false 时不应注册 chatAppVectorStore");

        assertEquals(0, MODEL_CALLS.get(), "enabled=false 时启动阶段不应调用模型");
        assertEquals(0, EMBEDDING_CALLS.get(), "enabled=false 时启动阶段不应调用 Embedding");

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> chatApp.doChatWithRag("零基础怎么学 AI？", "rag-disabled-1")
        );

        assertEquals(503, exception.getHttpStatus());
        assertEquals("知识库未启用", exception.getMessage());
    }
}
