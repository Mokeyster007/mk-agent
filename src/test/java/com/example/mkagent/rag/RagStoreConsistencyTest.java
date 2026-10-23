package com.example.mkagent.rag;

import com.example.mkagent.support.RagTestDoubles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量库与侧车索引的一致性测试。
 *
 * 任一条件不满足都必须"整库重建"，绝不允许出现
 * "索引说有、向量库里没有"的静默缺数据：
 * 1. store 文件缺失 / 损坏；
 * 2. 侧车索引缺失 / 损坏；
 * 3. 嵌入模型标识变化；
 * 4. 向量维度变化。
 */
class RagStoreConsistencyTest {

    @TempDir
    Path tempDir;

    private RagProperties properties;

    private ChatAppDocumentLoader loader;

    private MyKeywordEnricher enricher;

    private AtomicInteger chatCalls;

    private EmbeddingModel fourDimensions;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.setStorePath(tempDir.resolve("store.json").toString());
        properties.setEmbeddingModel("test-embedding");

        chatCalls = new AtomicInteger();
        enricher = new MyKeywordEnricher(
                RagTestDoubles.countingChatModel(chatCalls)
        );

        fourDimensions = RagTestDoubles.countingEmbeddingModel(
                new AtomicInteger(),
                4
        );

        loader = Mockito.mock(ChatAppDocumentLoader.class);
        Mockito.when(loader.loadMarkdowns()).thenReturn(List.of(
                chunk("a.md", "片段A1"),
                chunk("a.md", "片段A2")
        ));
    }

    @Test
    void rebuildsWhenStoreFileMissing() throws Exception {
        builder().build(fourDimensions);
        assertEquals(2, chatCalls.get());

        Files.delete(Path.of(properties.getStorePath()));

        chatCalls.set(0);
        builder().build(fourDimensions);

        assertEquals(2, chatCalls.get(), "store 文件缺失必须整库重建");
    }

    @Test
    void rebuildsWhenIndexFileMissing() throws Exception {
        builder().build(fourDimensions);
        assertEquals(2, chatCalls.get());

        Files.delete(indexPath());

        chatCalls.set(0);
        builder().build(fourDimensions);

        assertEquals(2, chatCalls.get(), "侧车索引缺失必须整库重建");
    }

    @Test
    void rebuildsWhenIndexFileCorrupted() throws Exception {
        builder().build(fourDimensions);

        Files.writeString(indexPath(), "{ not-a-json", StandardCharsets.UTF_8);

        chatCalls.set(0);
        builder().build(fourDimensions);

        assertEquals(2, chatCalls.get(), "侧车索引损坏必须整库重建");
    }

    @Test
    void rebuildsWhenStoreFileCorrupted() throws Exception {
        builder().build(fourDimensions);

        Files.writeString(
                Path.of(properties.getStorePath()),
                "corrupted",
                StandardCharsets.UTF_8
        );

        chatCalls.set(0);
        builder().build(fourDimensions);

        assertEquals(2, chatCalls.get(), "store 文件损坏必须整库重建");
    }

    @Test
    void rebuildsWhenEmbeddingModelChanged() {
        builder().build(fourDimensions);
        assertEquals(2, chatCalls.get());

        properties.setEmbeddingModel("another-embedding-model");

        chatCalls.set(0);
        builder().build(fourDimensions);

        assertEquals(2, chatCalls.get(), "嵌入模型标识变化必须整库重建");
    }

    @Test
    void rebuildsWhenVectorDimensionsChanged() {
        builder().build(fourDimensions);
        assertEquals(2, chatCalls.get());

        EmbeddingModel eightDimensions = RagTestDoubles.countingEmbeddingModel(
                new AtomicInteger(),
                8
        );

        chatCalls.set(0);
        builder().build(eightDimensions);

        assertEquals(2, chatCalls.get(), "向量维度变化必须整库重建");

        RagIndex index = new RagStorePersistence(properties.getStorePath())
                .loadIndex();
        assertNotNull(index);
        assertEquals(8, index.getDimensions());
        assertTrue(
                index.getChunks().size() == 2,
                "重建后索引应记录全部片段"
        );
    }

    private Path indexPath() {
        return Path.of(properties.getStorePath())
                .resolveSibling("store.index.json");
    }

    private RagKnowledgeBaseBuilder builder() {
        return new RagKnowledgeBaseBuilder(loader, enricher, properties);
    }

    private static Document chunk(String filename, String text) {
        return new Document(text, Map.of("filename", filename, "status", "入门"));
    }
}
