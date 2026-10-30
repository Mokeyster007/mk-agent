package com.example.mkagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * OpenAI 兼容接口的"按用途分层"配置（`mkagent.model.*`）。
 *
 * 为什么用 OpenAI 兼容协议而不是绑定某个厂商 SDK：
 * 通义、DeepSeek、Kimi、vLLM、Ollama 都提供 `/chat/completions` 兼容端点，
 * 换供应商时只改 base-url 与模型名，不动代码。
 *
 * 为什么按用途拆分（agent / auxiliary / vision）：
 * 一个 Agent 里不同调用的"性价比"要求完全不同——
 * - agent：主推理链，贵但必须强；
 * - auxiliary：评测打分、查询改写这类短调用，用便宜的小模型就够；
 * - vision：看图，只有多模态场景才用得上。
 * 三者共用一套 base-url / api-key，只按用途换模型名。
 *
 * 回退语义（重要）：
 * auxiliary / vision 的 name 留空时，**只回退模型名**，即复用 agent 的 name；
 * 但仍然各自创建独立的客户端实例与独立的 RetryTemplate。
 * 不做"直接复用 agent 那个对象"的回退——见 ModelRetryConfig 类注释里
 * DashScopeImageModel 原地改写共享模板的事故。
 */
@ConfigurationProperties(prefix = "mkagent.model")
public class OpenAiCompatibleModelProperties {

    /**
     * OpenAI 兼容端点，例如 https://dashscope.aliyuncs.com/compatible-mode/v1 。
     *
     * 留空表示"不启用"：此时继续使用 DashScope 原生客户端，
     * 现有部署行为完全不变（见 OpenAiCompatibleConditions）。
     */
    private String baseUrl;

    /**
     * API Key，三个用途共用。
     */
    private String apiKey;

    /**
     * 主推理模型（Agent 用）。
     */
    private ModelSpec agent = new ModelSpec();

    /**
     * 辅助模型（评测评委、查询改写等短调用）。
     */
    private ModelSpec auxiliary = new ModelSpec();

    /**
     * 多模态模型（看图）。
     */
    private ModelSpec vision = new ModelSpec();

    /**
     * 是否启用 OpenAI 兼容客户端：只认 base-url 有没有值。
     */
    public boolean isConfigured() {
        return StringUtils.hasText(baseUrl);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public ModelSpec getAgent() {
        return agent;
    }

    public void setAgent(ModelSpec agent) {
        this.agent = agent;
    }

    public ModelSpec getAuxiliary() {
        return auxiliary;
    }

    public void setAuxiliary(ModelSpec auxiliary) {
        this.auxiliary = auxiliary;
    }

    public ModelSpec getVision() {
        return vision;
    }

    public void setVision(ModelSpec vision) {
        this.vision = vision;
    }

    /**
     * 单个用途的模型参数。
     */
    public static class ModelSpec {

        /**
         * 模型名（如 qwen-plus / qwen-turbo / qwen-vl-plus）。
         *
         * 留空表示回退到 agent 的模型名。
         */
        private String name;

        /**
         * 采样温度；为空则用服务端默认值。
         */
        private Double temperature;

        /**
         * 单次回复的 token 上限；为空则用服务端默认值。
         */
        private Integer maxTokens;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }
    }
}
