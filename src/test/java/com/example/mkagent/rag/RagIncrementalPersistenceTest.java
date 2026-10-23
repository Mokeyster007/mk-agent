package com.example.mkagent.rag;

import com.example.mkagent.support.RagTestDoubles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量落盘测试：入库中途失败，已处理片段必须保留。
 *
 * 场景：第 2 个片段 Embedding 时服务挂掉。
 * 期望：
 * 1. 构建抛出异常（由上层降级为"知识库不可用"）；
 * 2. 第 1 个片段已经落盘（store + 侧车索引都在）；
 * 3. 第二次构建只处理剩余片段，不重复为已入库片段调用对话模型。
 */
class RagIncrementalPersistenceTest {

    @TempDir
    Path tempDir;

    private RagProperties properties;

    private ChatAppDocumentLoader loader;

    private MyKeywordEnricher enricher;

    private AtomicInteger chatCalls;

    private EmbeddingModel healthyEmbedding;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.setStorePath(tempDir.resolve("store.json").toString());
        properties.setEmbeddingModel("test-embedding");

        chatCalls = new AtomicInteger();
        enricher = new MyKeywordEnricher(
                RagTestDoubles.countingChatModel(chatCalls)
        );

        healthyEmbedding = RagTestDoubles.countingEmbeddingModel(
                new AtomicInteger(),
                4
        );

        loader = Mockito.mock(ChatAppDocumentLoader.class);
        Mockito.when(loader.loadMarkdowns()).thenReturn(List.of(
                chunk("a.md", "片段1"),
                chunk("a.md", "片段2"),
                chunk("a.md", "片段3")
        ));
    }

    @Test
    void ingestedChunksSurviveMidBuildFailure() {
        /*
         * 第 1 次调用是维度探针，第 2 次是片段1，
         * 第 3 次（片段2）开始失败。
         */
        EmbeddingModel flaky = RagTestDoubles.failingEmbeddingModelAfter(
                new AtomicInteger(),
                2,
                4
        );

        assertThrows(
                RuntimeException.class,
                () -> builder().build(flaky),
                "Embedding 中途失败应向上抛出，由配置层降级"
        );

        RagStorePersistence persistence =
                new RagStorePersistence(properties.getStorePath());

        assertTrue(persistence.storeExists(), "已入库片段必须落盘");
        RagIndex index = persistence.loadIndex();
        assertNotNull(index, "侧车索引必须同步落盘");
        assertEquals(1, index.getChunks().size(), "只应保留成功入库的 1 个片段");

        // 第二次启动：只剩 2 个片段需要处理
        chatCalls.set(0);
        SimpleVectorStore store = builder().build(healthyEmbedding);

        assertEquals(
                2,
                chatCalls.get(),
                "第二次构建只应处理剩余的 2 个片段"
        );
        assertEquals(3, count(store), "最终应包含全部 3 个片段");
    }

    private RagKnowledgeBaseBuilder builder() {
        return new RagKnowledgeBaseBuilder(loader, enricher, properties);
    }

    private static Document chunk(String filename, String text) {
        return new Document(text, Map.of("filename", filename, "status", "入门"));
    }

    private int count(SimpleVectorStore store) {
        return store.similaritySearch(
                SearchRequest.builder()
                        .query("任意查询")
                        .topK(50)
                        .similarityThreshold(0.0)
                        .build()
        ).size();
    }
}
