package com.example.mkagent.rag;

import com.example.mkagent.agent.MkManus;
import com.example.mkagent.app.chatApp;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.support.RagTestDoubles;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动隔离测试（eager + 模型不可用）。
 *
 * 场景：百炼账户欠费，任何模型调用都抛异常；
 * mkagent.rag.init-mode=eager 强制在启动阶段构建向量库。
 *
 * 验收点：
 * 1. Spring 上下文可以正常创建（应用能启动并监听端口）；
 * 2. 向量库构建确实被尝试过（说明 eager 生效）；
 * 3. RAG 被标记为不可用，RAG 调用返回 503；
 * 4. 不依赖知识库的 Agent Bean（MkManus）照常装配。
 *
 * 全程不联网、不读取真实密钥：模型与 Embedding 都是内存桩。
 */
@HermeticSpringBootTest(properties = {
        "mkagent.rag.enabled=true",
        "mkagent.rag.init-mode=eager"
})
class RagStartupIsolationTest {

    /**
     * 模型调用次数：用于证明 eager 模式确实尝试过构建向量库。
     */
    private static final AtomicInteger MODEL_CALLS = new AtomicInteger();

    private static final AtomicInteger EMBEDDING_CALLS = new AtomicInteger();

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingModelConfig {

        /**
         * 名为 dashscopeChatModel，与生产 Bean 同名，
         * 保证按名称注入（@Resource）的代码也能拿到桩。
         */
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

        /**
         * MCP 客户端被排除后，ToolCallbackProvider 缺失，
         * 用空实现占位（chatApp.doChatWithMcp 不参与本测试）。
         */
        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private chatApp chatApp;

    @Autowired
    private RagAvailability ragAvailability;

    @Test
    void contextStartsAndRagDegradesTo503() {
        // 1. 上下文创建成功，不依赖知识库的 Agent 也装配成功。
        assertNotNull(applicationContext, "上下文应能创建");
        assertNotNull(
                applicationContext.getBean(MkManus.class),
                "MkManus 不应因为知识库不可用而无法装配"
        );

        // 2. eager 模式确实在启动阶段尝试过构建。
        //    构建第一步是"向量维度探针"，因此 Embedding 调用即为构建入口的可靠证据；
        //    探针失败会直接短路，连关键词提取都不会发起（避免白烧对话模型配额）。
        assertTrue(
                EMBEDDING_CALLS.get() > 0,
                "init-mode=eager 应在启动阶段尝试构建知识库"
        );

        // 3. 构建失败后知识库被标记为不可用。
        assertFalse(
                ragAvailability.isAvailable(),
                "模型不可用时知识库应被标记为不可用"
        );

        // 4. RAG 调用返回 503，而不是 500 或空检索硬答。
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> chatApp.doChatWithRag("零基础怎么学 AI？", "rag-startup-1")
        );

        assertEquals(503, exception.getHttpStatus(), "RAG 不可用应返回 503");
        assertEquals("知识库暂不可用", exception.getMessage());
    }
}
