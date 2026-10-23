package com.example.mkagent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库构建器：文档 → 稳定 ID → 增量富化 → 向量入库 → 本地落盘。
 *
 * 幂等策略：
 * 1. 每个片段按"文件名 + 序号 + 内容 SHA-256 前 16 位"生成稳定 ID；
 * 2. 入库前先比对侧车索引，已存在的 ID 直接跳过（不再调用对话模型与 Embedding）；
 * 3. 文档被删除或内容变化时，旧 ID 会变成"过期 ID"，按 ID 删除对应向量；
 * 4. 每处理完一个文档就落盘（store 先、索引后），
 *    因此中途失败时已入库的片段不会丢，下次启动只处理剩余片段。
 *
 * 说明：SimpleVectorStore#add 对相同 ID 是 upsert，
 * 但它会在 put 之前无条件调用 EmbeddingModel#embed，
 * 所以"只靠 upsert"并不能省钱，必须显式跳过。
 */
public class RagKnowledgeBaseBuilder {

    private static final Logger log =
            LoggerFactory.getLogger(RagKnowledgeBaseBuilder.class);

    /**
     * 维度探针文本：只用于确认当前 Embedding 模型输出的向量维度，
     * 一次构建只调用一次，成本可忽略。
     */
    private static final String DIMENSION_PROBE = "dimension-probe";

    private final ChatAppDocumentLoader chatAppDocumentLoader;

    private final MyKeywordEnricher myKeywordEnricher;

    private final RagProperties ragProperties;

    private final RagStorePersistence persistence;

    public RagKnowledgeBaseBuilder(
            ChatAppDocumentLoader chatAppDocumentLoader,
            MyKeywordEnricher myKeywordEnricher,
            RagProperties ragProperties
    ) {
        this(
                chatAppDocumentLoader,
                myKeywordEnricher,
                ragProperties,
                new RagStorePersistence(ragProperties.getStorePath())
        );
    }

    /**
     * 允许注入持久化实现，便于单测指定临时目录。
     */
    public RagKnowledgeBaseBuilder(
            ChatAppDocumentLoader chatAppDocumentLoader,
            MyKeywordEnricher myKeywordEnricher,
            RagProperties ragProperties,
            RagStorePersistence persistence
    ) {
        this.chatAppDocumentLoader = chatAppDocumentLoader;
        this.myKeywordEnricher = myKeywordEnricher;
        this.ragProperties = ragProperties;
        this.persistence = persistence;
    }

    /**
     * 构建（或复用）向量库。
     *
     * @throws RuntimeException Embedding 不可用、落盘失败等；
     *                          由调用方决定是否降级为"知识库不可用"
     */
    public SimpleVectorStore build(EmbeddingModel embeddingModel) {
        String signature = resolveSignature(embeddingModel);
        int dimensions = probeDimensions(embeddingModel);

        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        RagIndex index = restore(store, signature, dimensions);

        if (index == null) {
            // 整库重建：丢弃可能被部分加载的实例，换一个干净的。
            store = SimpleVectorStore.builder(embeddingModel).build();
            index = new RagIndex(signature, dimensions);
        }

        List<Document> chunks = prepareChunks(chatAppDocumentLoader.loadMarkdowns());

        boolean dirty = removeObsoleteChunks(store, index, chunks);

        int added = 0;
        int skipped = 0;
        int llmCalls = 0;

        try {
            for (Map.Entry<String, List<Document>> entry
                    : groupBySource(chunks).entrySet()) {

                List<Document> pending = new ArrayList<>();
                for (Document chunk : entry.getValue()) {
                    if (index.getChunks().containsKey(chunk.getId())) {
                        skipped++;
                    } else {
                        pending.add(chunk);
                    }
                }

                if (pending.isEmpty()) {
                    continue;
                }

                for (Document chunk : pending) {
                    Document toStore = enrich(chunk);
                    if (isKeywordEnrichEnabled()) {
                        llmCalls++;
                    }

                    store.add(List.of(toStore));
                    index.put(chunk.getId(), RagChunkIds.contentHash(chunk.getText()));
                    added++;
                }

                /*
                 * 每处理完一个文档就落盘：
                 * 后续文档失败时，本文件已入库的片段不会丢。
                 */
                persistence.save(store, index);
                dirty = false;
            }

        } catch (RuntimeException e) {
            /*
             * 中途失败：把"已经成功入库"的部分尽力落盘，
             * 下次启动只处理剩余片段，避免重复花钱。
             */
            persistQuietly(store, index);
            throw e;
        }

        if (dirty) {
            persistence.save(store, index);
        }

        log.info(
                "本次共 {} 个片段，新增 {} 个，跳过 {} 个，LLM 调用 {} 次",
                chunks.size(),
                added,
                skipped,
                llmCalls
        );

        return store;
    }

    /**
     * 删除已过期片段（文档被删除，或内容变化导致 ID 变化）。
     *
     * @return true 表示发生了删除，需要落盘
     */
    private boolean removeObsoleteChunks(
            SimpleVectorStore store,
            RagIndex index,
            List<Document> chunks
    ) {
        if (index.getChunks().isEmpty()) {
            return false;
        }

        Set<String> currentIds = new LinkedHashSet<>();
        chunks.forEach(chunk -> currentIds.add(chunk.getId()));

        Set<String> obsolete = new LinkedHashSet<>();
        for (String id : index.getChunks().keySet()) {
            if (!currentIds.contains(id)) {
                obsolete.add(id);
            }
        }

        if (obsolete.isEmpty()) {
            return false;
        }

        store.delete(new ArrayList<>(obsolete));
        index.removeAll(obsolete);

        log.info("知识库清理过期片段：{} 个（文档已删除或内容已变化）", obsolete.size());
        return true;
    }

    /**
     * 给文档片段分配稳定 ID，并丢弃空片段。
     */
    private List<Document> prepareChunks(List<Document> documents) {
        List<Document> chunks = new ArrayList<>();
        Map<String, Integer> counters = new HashMap<>();

        if (documents == null) {
            return chunks;
        }

        for (Document document : documents) {
            String text = document.getText();
            if (text == null || text.isBlank()) {
                continue;
            }

            String source = sourceOf(document);
            int index = counters.merge(source, 1, Integer::sum) - 1;
            String id = RagChunkIds.of(source, index, text);

            chunks.add(new Document(
                    id,
                    text,
                    new LinkedHashMap<>(document.getMetadata())
            ));
        }

        return chunks;
    }

    /**
     * 按来源分组，保证落盘粒度为"一个文档"。
     */
    private Map<String, List<Document>> groupBySource(List<Document> chunks) {
        Map<String, List<Document>> grouped = new LinkedHashMap<>();
        for (Document chunk : chunks) {
            grouped.computeIfAbsent(sourceOf(chunk), key -> new ArrayList<>())
                    .add(chunk);
        }
        return grouped;
    }

    private String sourceOf(Document document) {
        Object filename = document.getMetadata().get("filename");
        return filename == null ? "unknown" : String.valueOf(filename);
    }

    /**
     * 关键词富化；关闭开关时直接返回原片段。
     *
     * 无论富化器返回什么 ID，这里都统一用稳定 ID 重建文档，
     * 避免第三方 Transformer 改写 ID 导致索引与向量库不一致。
     */
    private Document enrich(Document chunk) {
        if (!isKeywordEnrichEnabled()) {
            return chunk;
        }

        Document enriched = myKeywordEnricher.enrichDocument(chunk);
        return new Document(
                chunk.getId(),
                enriched.getText(),
                new LinkedHashMap<>(enriched.getMetadata())
        );
    }

    private boolean isKeywordEnrichEnabled() {
        return ragProperties.getKeywordEnrich() != null
                && ragProperties.getKeywordEnrich().isEnabled();
    }

    /**
     * 尝试复用已有向量库。
     *
     * @return 可复用的索引；null 表示需要整库重建
     */
    private RagIndex restore(
            SimpleVectorStore store,
            String signature,
            int dimensions
    ) {
        if (!persistence.storeExists()) {
            log.info("未发现本地向量库文件，将全量构建：{}", persistence.getStorePath());
            return null;
        }

        RagIndex index = persistence.loadIndex();
        if (index == null) {
            log.info("未发现可用的向量库索引文件，将全量构建：{}", persistence.getIndexPath());
            return null;
        }

        if (!index.matches(signature, dimensions)) {
            log.info(
                    "向量库与当前嵌入模型不一致，将整库重建："
                            + "已存 model={}, dims={}；当前 model={}, dims={}",
                    index.getEmbeddingModel(),
                    index.getDimensions(),
                    signature,
                    dimensions
            );
            return null;
        }

        if (!persistence.loadStore(store)) {
            return null;
        }

        return index;
    }

    /**
     * 嵌入模型标识：配置优先，缺省用实现类全限定名。
     */
    private String resolveSignature(EmbeddingModel embeddingModel) {
        String configured = ragProperties.getEmbeddingModel();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return embeddingModel.getClass().getName();
    }

    /**
     * 实测当前 Embedding 模型的向量维度。
     */
    private int probeDimensions(EmbeddingModel embeddingModel) {
        float[] probe = embeddingModel.embed(DIMENSION_PROBE);
        if (probe == null || probe.length == 0) {
            throw new IllegalStateException("Embedding 维度探测失败");
        }
        return probe.length;
    }

    /**
     * 尽力落盘：失败只记日志，不覆盖原始异常。
     */
    private void persistQuietly(SimpleVectorStore store, RagIndex index) {
        try {
            persistence.save(store, index);
        } catch (Exception e) {
            log.error("向量库落盘失败，已入库片段可能需要在下次启动时重建", e);
        }
    }
}
