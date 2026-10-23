package com.example.mkagent.rag;

import com.example.mkagent.support.RagTestDoubles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 关键词提取失败降级测试。
 *
 * 关键词只是检索增强的辅助信息，提取失败（欠费 / 超时 / 返回格式异常）
 * 不能阻断文档入库，否则知识库会因为一次模型抖动而整体不可用。
 */
class MyKeywordEnricherDegradeTest {

    @TempDir
    Path tempDir;

    @Test
    void enrichDocumentReturnsOriginalWhenModelFails() {
        MyKeywordEnricher enricher = new MyKeywordEnricher(
                RagTestDoubles.failingChatModel(new AtomicInteger())
        );

        Document original = new Document(
                "doc-1",
                "片段正文",
                Map.of("filename", "a.md")
        );

        Document result = enricher.enrichDocument(original);

        assertNotNull(result, "富化失败必须返回原文档，而不是 null");
        assertEquals("doc-1", result.getId());
        assertEquals("片段正文", result.getText());
    }

    @Test
    void ingestionStillSucceedsWhenEnrichmentFails() {
        ChatAppDocumentLoader loader = Mockito.mock(ChatAppDocumentLoader.class);
        Mockito.when(loader.loadMarkdowns()).thenReturn(List.of(
                new Document("片段A1", Map.of("filename", "a.md")),
                new Document("片段A2", Map.of("filename", "a.md"))
        ));

        RagProperties properties = new RagProperties();
        properties.setStorePath(tempDir.resolve("store.json").toString());
        properties.setEmbeddingModel("test-embedding");

        MyKeywordEnricher failingEnricher = new MyKeywordEnricher(
                RagTestDoubles.failingChatModel(new AtomicInteger())
        );

        SimpleVectorStore store = new RagKnowledgeBaseBuilder(
                loader,
                failingEnricher,
                properties
        ).build(RagTestDoubles.countingEmbeddingModel(new AtomicInteger(), 4));

        List<Document> results = store.similaritySearch(
                SearchRequest.builder()
                        .query("任意查询")
                        .topK(10)
                        .similarityThreshold(0.0)
                        .build()
        );

        assertEquals(2, results.size(), "关键词提取失败不应阻断入库");
    }
}
