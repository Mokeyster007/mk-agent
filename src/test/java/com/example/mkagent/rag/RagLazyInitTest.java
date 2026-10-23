package com.example.mkagent.rag;

import com.example.mkagent.app.chatApp;
import com.example.mkagent.support.RagTestDoubles;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * lazy 模式正向路径测试。
 *
 * 验收点：
 * 1. 启动阶段 Embedding 调用次数为 0（真正做到了"启动不依赖大模型"）；
 * 2. 第一次 RAG 调用触发向量库构建；
 * 3. 新增的 GET /ai/chat_app/rag 端点返回 200。
 *
 * 模型与 Embedding 均为内存桩，不联网。
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
                "spring.datasource.url=jdbc:h2:mem:mk_agent_rag_lazy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/schema.sql",
                "mkagent.rag.pgvector.enabled=false",
                "mkagent.rag.enabled=true",
                "mkagent.rag.init-mode=lazy",
                "spring.ai.dashscope.api-key=test-key",
                "spring.ai.dashscope.chat.options.model=qwen-test",
                "search-api.api-key=test-key"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RagLazyInitTest {

    private static final AtomicInteger EMBEDDING_CALLS = new AtomicInteger();

    @TestConfiguration(proxyBeanMethods = false)
    static class StubModelConfig {

        /**
         * 固定文本：既作为查询改写结果，也作为最终回答。
         */
        @Bean("dashscopeChatModel")
        @Primary
        ChatModel dashscopeChatModel() {
            return RagTestDoubles.fixedTextChatModel("关键词A,关键词B");
        }

        @Bean
        @Primary
        EmbeddingModel dashscopeEmbeddingModel() {
            return RagTestDoubles.countingEmbeddingModel(EMBEDDING_CALLS);
        }

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    @Autowired
    private chatApp chatApp;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void lazyModeDefersBuildUntilFirstRagCall() throws Exception {
        assertEquals(
                0,
                EMBEDDING_CALLS.get(),
                "lazy 模式下启动阶段不应构建向量库（不应发生任何 Embedding）"
        );

        String answer = chatApp.doChatWithRag("零基础怎么学 AI？", "rag-lazy-1");

        assertNotNull(answer);
        assertFalse(answer.isBlank(), "RAG 回答不应为空");
        assertTrue(
                EMBEDDING_CALLS.get() > 0,
                "第一次 RAG 调用应触发向量库构建"
        );

        // HTTP 层同样可用：新增端点风格与现有 chat 接口一致。
        mockMvc.perform(
                        get("/ai/chat_app/rag")
                                .param("message", "入门阶段先学什么？")
                                .param("chatId", "rag-lazy-2")
                )
                .andExpect(status().isOk());
    }
}
