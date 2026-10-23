package com.example.mkagent.rag;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 向量库侧车索引（{@code *.index.json}）。
 *
 * 记录三类信息：
 * 1. 嵌入模型标识与向量维度 —— 任一不一致即整库重建；
 * 2. 已入库片段的稳定 ID → 内容哈希 —— 用于增量入库与过期片段删除；
 * 3. 更新时间 —— 便于排查。
 *
 * 为什么需要侧车文件：
 * SimpleVectorStore 没有"按 ID 查询是否存在"的公开 API
 * （内部只是一个 protected Map），且 save/load 的 JSON 结构属于框架内部格式。
 * 与其反射私有字段，不如自己维护一份显式索引：
 * 结构可控、可读、可测试，也便于将来替换成 pgvector 时对照迁移。
 */
public class RagIndex {

    /**
     * 嵌入模型标识（配置优先，缺省为 EmbeddingModel 实现类名）。
     */
    private String embeddingModel = "";

    /**
     * 向量维度（构建时用探针 Embedding 实测）。
     */
    private int dimensions;

    /**
     * 片段稳定 ID → 内容哈希。
     *
     * 用 LinkedHashMap 保持插入顺序，方便人工查看侧车文件。
     */
    private Map<String, String> chunks = new LinkedHashMap<>();

    /**
     * 最近一次落盘时间（ISO-8601）。
     */
    private String updatedAt = "";

    public RagIndex() {
    }

    public RagIndex(String embeddingModel, int dimensions) {
        this.embeddingModel = embeddingModel == null ? "" : embeddingModel;
        this.dimensions = dimensions;
    }

    /**
     * 记录一个已入库片段。
     */
    public void put(String id, String contentHash) {
        chunks.put(id, contentHash);
    }

    /**
     * 移除一批过期片段（文档被删除或内容变化）。
     */
    public void removeAll(Set<String> ids) {
        ids.forEach(chunks::remove);
    }

    /**
     * 是否与给定的模型标识 + 维度匹配。
     */
    public boolean matches(String expectedModel, int expectedDimensions) {
        return expectedModel != null
                && expectedModel.equals(embeddingModel)
                && expectedDimensions > 0
                && expectedDimensions == dimensions;
    }

    /**
     * 刷新落盘时间。
     */
    public void touch() {
        this.updatedAt = Instant.now().toString();
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel == null ? "" : embeddingModel;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public Map<String, String> getChunks() {
        return chunks;
    }

    public void setChunks(Map<String, String> chunks) {
        this.chunks = chunks == null ? new LinkedHashMap<>() : new LinkedHashMap<>(chunks);
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt == null ? "" : updatedAt;
    }
}
