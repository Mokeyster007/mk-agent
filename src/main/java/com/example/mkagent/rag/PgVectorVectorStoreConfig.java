package com.example.mkagent.rag;


import jakarta.annotation.Resource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

/**
 * pgvector 向量库配置。
 *
 * 开关 mkagent.rag.pgvector.enabled（默认 true，保持既有行为）：
 * PgVectorStore 初始化时会执行 PostgreSQL 专用语句
 * CREATE EXTENSION IF NOT EXISTS vector，
 * 因此使用 H2 等内存库的测试必须显式置为 false，
 * 否则会因 SQL 语法不兼容导致 Spring 上下文加载失败。
 * 主代码没有按名注入该 Bean（chatApp 使用内存版 chatAppVectorStore），
 * 禁用后不影响 Agent / SSE / 任务管理功能。
 */
@Configuration
@ConditionalOnProperty(
        prefix = "mkagent.rag.pgvector",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class PgVectorVectorStoreConfig {


    @Resource
    public ChatAppDocumentLoader chatAppDocumentLoader;

    @Bean
    public VectorStore PgVectorVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1536)                    // Optional: defaults to model dimensions or 1536
                .distanceType(COSINE_DISTANCE)       // Optional: defaults to COSINE_DISTANCE
                .indexType(HNSW)                     // Optional: defaults to HNSW
                .initializeSchema(true)              // Optional: defaults to false
                .schemaName("public")                // Optional: defaults to "public"
                .vectorTableName("vector_store")     // Optional: defaults to "vector_store"
                .maxDocumentBatchSize(10000)         // Optional: defaults to 10000
                .build();
    }
}
