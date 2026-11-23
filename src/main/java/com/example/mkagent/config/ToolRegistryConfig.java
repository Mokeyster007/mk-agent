package com.example.mkagent.config;

import com.example.mkagent.model.AgentType;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地工具注册配置（按 AgentType 暴露独立工具白名单；不再注册任何音乐工具）。
 */
@Configuration
public class ToolRegistryConfig {

    @Bean("chatToolCallbacks")
    public ToolCallback[] chatToolCallbacks(AgentToolProvider toolProvider) {
        return toolProvider.getTools(AgentType.CHAT);
    }

    @Bean("mkToolCallbacks")
    public ToolCallback[] mkToolCallbacks(AgentToolProvider toolProvider) {
        return toolProvider.getTools(AgentType.MANUS);
    }

    @Bean("fileToolCallbacks")
    public ToolCallback[] fileToolCallbacks(AgentToolProvider toolProvider) {
        return toolProvider.getTools(AgentType.FILE);
    }

    @Bean("afterSaleToolCallbacks")
    public ToolCallback[] afterSaleToolCallbacks(AgentToolProvider toolProvider) {
        return toolProvider.getTools(AgentType.AFTER_SALES);
    }

    @Bean("shopAssistantToolCallbacks")
    public ToolCallback[] shopAssistantToolCallbacks(AgentToolProvider toolProvider) {
        return toolProvider.getTools(AgentType.SHOP_ASSISTANT);
    }
}
