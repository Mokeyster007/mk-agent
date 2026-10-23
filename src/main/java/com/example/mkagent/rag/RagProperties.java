package com.example.mkagent.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 知识库配置。
 *
 * 设计目标：
 * 让应用启动与"大模型可用性"解耦。
 * 向量库构建过程会调用对话模型（关键词提取）与 Embedding 模型，
 * 一旦账户欠费或网络不通，此前会直接拖垮整个 Spring 上下文，
 * 导致连不依赖知识库的 /ai/manus/chat 都无法启动。
 *
 * enabled=false：
 *   完全不构建向量库（连 Bean 都不注册），
 *   RAG 接口直接返回 503「知识库未启用」。
 *
 * init-mode：
 *   LAZY（默认）：第一次真正调用 RAG 时才构建，启动最快；
 *   EAGER      ：启动阶段构建，但失败只标记不可用，不影响应用启动。
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
}
