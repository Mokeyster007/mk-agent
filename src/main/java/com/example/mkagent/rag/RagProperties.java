package com.example.mkagent.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 知识库配置。
 *
 * 设计目标：
 * 1. 让应用启动与"大模型可用性"解耦（enabled / init-mode）；
 * 2. 让文档入库幂等，避免每次启动重复调用关键词提取与 Embedding（store-path 等）；
 * 3. 保留一键降级开关（keyword-enrich.enabled）。
 */
@Component
@ConfigurationProperties(prefix = "mkagent.rag")
public class RagProperties {

    /**
     * 是否启用知识库。false 时不注册向量库 Bean，也不做任何模型调用。
     */
    private boolean enabled = true;

    /**
     * 向量库构建时机：lazy（默认）/ eager。
     */
    private InitMode initMode = InitMode.LAZY;

    /**
     * 本地向量库持久化文件路径。
     *
     * SimpleVectorStore 是纯内存实现，不落盘就无法跨进程复用，
     * 每次启动都会重新做关键词提取 + Embedding（真实花钱）。
     * 这里保存成 JSON，配合同名 .index.json 侧车文件做增量入库。
     */
    private String storePath = "tmp/vector-store/chat-app-vector-store.json";

    /**
     * 嵌入模型标识，写入侧车文件用于"换模型即整库重建"。
     *
     * 为空时退化为 EmbeddingModel 实现类的全限定名。
     * DashScope 换模型（如 text-embedding-v1 -> v3）时类名不变，
     * 因此换模型时建议显式配置本项，或直接删除 store 目录。
     */
    private String embeddingModel = "";

    /**
     * 关键词提取开关。
     */
    private KeywordEnrich keywordEnrich = new KeywordEnrich();

    /**
     * 构建时机枚举。
     *
     * 配置值大小写不敏感（Spring 宽松绑定），
     * 但 application.yml 中统一写小写，便于阅读。
     */
    public enum InitMode {
        /** 第一次 RAG 调用时构建。 */
        LAZY,
        /** 应用启动时构建（失败不影响启动）。 */
        EAGER
    }

    /**
     * 关键词提取（每片段一次对话模型调用）的独立开关。
     */
    public static class KeywordEnrich {

        /**
         * false 时跳过关键词提取：入库不再调用对话模型，只做 Embedding。
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public InitMode getInitMode() {
        return initMode;
    }

    public void setInitMode(InitMode initMode) {
        this.initMode = initMode;
    }

    public String getStorePath() {
        return storePath;
    }

    public void setStorePath(String storePath) {
        this.storePath = storePath;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel == null ? "" : embeddingModel;
    }

    public KeywordEnrich getKeywordEnrich() {
        return keywordEnrich;
    }

    public void setKeywordEnrich(KeywordEnrich keywordEnrich) {
        this.keywordEnrich = keywordEnrich;
    }
}
