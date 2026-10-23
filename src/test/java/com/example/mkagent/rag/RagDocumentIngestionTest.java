package com.example.mkagent.rag;

import com.example.mkagent.support.RagTestDoubles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档入库幂等测试（不联网、不依赖 Spring 上下文）。
 *
 * 验收点：
 * 1. 第二次构建不再调用对话模型（LLM 调用 0 次），也不再重复 Embedding 片段；
 * 2. 只修改一个片段时，只重新处理该片段，且旧向量被删除；
 * 3. 侧车索引记录嵌入模型标识、向量维度、片段 ID 与内容哈希。
 */
class RagDocumentIngestionTest {

    @TempDir
    Path tempDir;

    private RagProperties properties;

    private ChatAppDocumentLoader loader;

    private MyKeywordEnricher enricher;

    private AtomicInteger chatCalls;

    private EmbeddingModel embedding;

    private AtomicInteger embedCalls;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.setStorePath(tempDir.resolve("store.json").toString());
        // 固定模型标识，避免不同桩类名导致"模型变了"而重建
        properties.setEmbeddingModel("test-embedding");

        chatCalls = new AtomicInteger();
        enricher = new MyKeywordEnricher(
                RagTestDoubles.countingChatModel(chatCalls)
        );

        embedCalls = new AtomicInteger();
        embedding = RagTestDoubles.countingEmbeddingModel(embedCalls);

        loader = Mockito.mock(ChatAppDocumentLoader.class);
    }

    @Test
    void secondBuildSkipsExistingChunks() {
        Mockito.when(loader.loadMarkdowns()).thenReturn(List.of(
                chunk("a.md", "片段A1"),
                chunk("a.md", "片段A2"),
                chunk("b.md", "片段B1"),
                chunk("b.md", "片段B2")
        ));

        builder().build(embedding);

        assertEquals(4, chatCalls.get(), "首次构建应为 4 个片段各调用一次对话模型");
        // 4 个片段 + 1 次维度探针
        assertEquals(5, embedCalls.get());

        chatCalls.set(0);
        embedCalls.set(0);

        builder().build(embedding);

        assertEquals(0, chatCalls.get(), "第二次构建不应再调用关键词提取");
        assertEquals(
                1,
                embedCalls.get(),
                "第二次构建只应做一次维度探针，不应再 Embedding 任何片段"
        );
    }

    @Test
    void onlyChangedChunkIsReprocessed() {
        Mockito.when(loader.loadMarkdowns()).thenReturn(List.of(
                chunk("a.md", "片段A1"),
                chunk("a.md", "片段A2"),
                chunk("b.md", "片段B1"),
                chunk("b.md", "片段B2")
        ));

        SimpleVectorStore first = builder().build(embedding);
        assertEquals(4, count(first));

        chatCalls.set(0);

        // 只改 a.md 的第二个片段
        Mockito.when(loader.loadMarkdowns()).thenReturn(List.of(
                chunk("a.md", "片段A1"),
                chunk("a.md", "片段A2-内容已修改"),
                chunk("b.md", "片段B1"),
                chunk("b.md", "片段B2")
        ));

        SimpleVectorStore second = builder().build(embedding);

        assertEquals(1, chatCalls.get(), "只有内容变化的片段需要重新处理");
        assertEquals(
                4,
                count(second),
                "内容变化后旧片段应被按 ID 删除，总数仍为 4"
        );
    }

    @Test
    void sidecarRecordsModelDimensionsAndChunkHashes() throws Exception {
        Mockito.when(loader.loadMarkdowns()).thenReturn(List.of(
                chunk("a.md", "片段A1"),
                chunk("a.md", "片段A2")
        ));

        builder().build(embedding);

        Path indexPath = Path.of(properties.getStorePath())
                .resolveSibling("store.index.json");

        assertTrue(Files.exists(indexPath), "应生成侧车索引文件");

        JsonNode root = new ObjectMapper().readTree(indexPath.toFile());

        assertEquals("test-embedding", root.path("embeddingModel").asText());
        assertEquals(4, root.path("dimensions").asInt());
        assertEquals(2, root.path("chunks").size());

        // 每个片段都要记录内容哈希（16 位十六进制）
        root.path("chunks").forEach(hash ->
                assertEquals(16, hash.asText().length())
        );
    }

    @Test
    void chunkIdIsStableAndContentAddressed() {
        String first = RagChunkIds.of("a.md", 0, "同一段内容");
        String sameContent = RagChunkIds.of("a.md", 0, "同一段内容");
        String changedContent = RagChunkIds.of("a.md", 0, "同一段内容（改）");

        assertEquals(first, sameContent, "相同输入必须得到相同 ID");
        assertNotEquals(first, changedContent, "内容变化必须产生新 ID");
        assertEquals(16, RagChunkIds.contentHash("同一段内容").length());
        assertTrue(first.startsWith("a.md#0-"));
    }

    @Test
    void keywordEnrichSwitchSkipsLlmCalls() {
        properties.getKeywordEnrich().setEnabled(false);

        Mockito.when(loader.loadMarkdowns()).thenReturn(List.of(
                chunk("a.md", "片段A1"),
                chunk("a.md", "片段A2")
        ));

        SimpleVectorStore store = builder().build(embedding);

        assertEquals(0, chatCalls.get(), "关闭关键词提取后不应调用对话模型");
        assertEquals(2, count(store), "关闭关键词提取不影响入库");
    }

    private RagKnowledgeBaseBuilder builder() {
        return new RagKnowledgeBaseBuilder(loader, enricher, properties);
    }

    private static Document chunk(String filename, String text) {
        return new Document(text, Map.of("filename", filename, "status", "入门"));
    }

    private int count(SimpleVectorStore store) {
        List<Document> results = store.similaritySearch(
                SearchRequest.builder()
                        .query("任意查询")
                        .topK(50)
                        .similarityThreshold(0.0)
                        .build()
        );
        assertNotNull(results);
        return results.size();
    }
}
