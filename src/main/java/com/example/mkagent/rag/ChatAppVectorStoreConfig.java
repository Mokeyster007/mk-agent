package com.example.mkagent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * 向量数据库配置（初始化基于内存的向量数据库 bean）。
 *
 * 与旧实现的关键差异：
 * 1. Bean 加了 @Lazy：默认（init-mode=lazy）不再在上下文刷新时构建，
 *    避免"启动即调用大模型"——百炼欠费时应用照样能起来；
 * 2. 构建过程中的任何异常都在本方法内消化：
 *    记录日志 + 标记 RagAvailability 不可用，返回空向量库，
 *    绝不向上抛出拖垮 Spring 上下文（fail-open for startup）；
 * 3. mkagent.rag.enabled=false 时整个配置类不生效，
 *    连 Bean 都不注册（不产生任何模型调用）；
 * 4. init-mode=eager 时由 ApplicationRunner 在启动阶段主动触发一次构建，
 *    失败同样只标记不可用。
 *
 * 请求侧语义是 fail-closed：知识库不可用时，
 * chatApp.doChatWithRag 直接返回 503，而不是"空检索 + 硬答"。
 */
@Configuration
@ConditionalOnProperty(
        prefix = "mkagent.rag",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ChatAppVectorStoreConfig {

    private static final Logger log =
            LoggerFactory.getLogger(ChatAppVectorStoreConfig.class);

    private final ChatAppDocumentLoader chatAppDocumentLoader;

    private final MyKeywordEnricher myKeywordEnricher;

    private final RagAvailability ragAvailability;

    private final RagProperties ragProperties;

    public ChatAppVectorStoreConfig(
            ChatAppDocumentLoader chatAppDocumentLoader,
            MyKeywordEnricher myKeywordEnricher,
            RagAvailability ragAvailability,
            RagProperties ragProperties
    ) {
        this.chatAppDocumentLoader = chatAppDocumentLoader;
        this.myKeywordEnricher = myKeywordEnricher;
        this.ragAvailability = ragAvailability;
        this.ragProperties = ragProperties;
    }

    /**
     * 知识库向量库。
     *
     * @Lazy 的作用：本 Bean 不会被容器预先实例化，
     * 只有第一次被真正取用时（ObjectProvider#getObject）才构建。
     * 这也是 lazy 模式"启动不依赖大模型"的实现基础。
     *
     * 真正的构建逻辑（稳定 ID、增量入库、本地持久化）在
     * {@link RagKnowledgeBaseBuilder} 中，这里只负责把失败降级为
     * "知识库不可用"，绝不向上抛异常拖垮 Spring 上下文。
     */
    @Bean
    @Lazy
    VectorStore chatAppVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
        try {
            RagKnowledgeBaseBuilder builder = new RagKnowledgeBaseBuilder(
                    chatAppDocumentLoader,
                    myKeywordEnricher,
                    ragProperties
            );

            SimpleVectorStore simpleVectorStore = builder.build(dashscopeEmbeddingModel);
            ragAvailability.markAvailable();
            return simpleVectorStore;

        } catch (Exception e) {
            /*
             * 构建失败（欠费、网络、配额、磁盘、文档格式等）不向上抛：
             * 否则 lazy 模式下会变成请求 500，eager 模式下会拖垮整个应用启动。
             *
             * 已成功入库的片段已经由 builder 落盘，下次启动会从断点继续。
             */
            ragAvailability.markUnavailable("向量库初始化失败");
            log.error(
                    "知识库初始化失败，RAG 已标记为不可用；"
                            + "Agent 等不依赖知识库的接口不受影响",
                    e
            );

            // 返回空库，保证 Bean 本身可用（请求侧由 RagAvailability 拦成 503）。
            return SimpleVectorStore.builder(dashscopeEmbeddingModel).build();
        }
    }

    /**
     * eager 模式下在启动阶段主动触发一次构建。
     *
     * 之所以不用 @ConditionalOnProperty 控制这个 Bean：
     * 属性绑定到枚举后大小写不敏感，
     * 用运行时判断可以与 mkagent.rag.init-mode 的绑定规则保持一致。
     */
    @Bean
    ApplicationRunner ragEagerInitializer(
            @Qualifier("chatAppVectorStore")
            ObjectProvider<VectorStore> chatAppVectorStoreProvider,
            RagProperties ragProperties
    ) {
        return args -> {
            if (ragProperties.getInitMode() != RagProperties.InitMode.EAGER) {
                return;
            }

            try {
                chatAppVectorStoreProvider.getObject();
                log.info("mkagent.rag.init-mode=eager：启动阶段已完成知识库初始化");
            } catch (Exception e) {
                /*
                 * 正常情况下 chatAppVectorStore 内部已吞掉构建异常，
                 * 这里兜底的是"Bean 自身创建失败"（如 EmbeddingModel 不可用）。
                 */
                ragAvailability.markUnavailable("向量库初始化失败");
                log.error("启动阶段知识库初始化失败（不影响应用启动）", e);
            }
        };
    }
}
