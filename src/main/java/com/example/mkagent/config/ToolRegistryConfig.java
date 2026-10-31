package com.example.mkagent.config;

import com.example.mkagent.model.AgentType;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地工具注册配置。
 *
 * 不再把所有工具注册进同一个大数组，
 * 而是通过 AgentToolProvider 按 Agent 类型暴露独立工具白名单：
 *
 * 1. chatToolCallbacks：CHAT 类型（仅 web_search）
 * 2. mkToolCallbacks  ：MANUS 类型（MkManus 使用）
 * 3. fileToolCallbacks：FILE 类型（预留给后续 FILE Agent）
 * 4. musicUserToolCallbacks ：MUSIC_USER（曲库 + 歌单，只读）
 * 5. musicAdminToolCallbacks：MUSIC_ADMIN（曲库 + 聚合统计，无歌单）
 *
 * 测试专用工具（DemoInventoryTool）位于 src/test 测试目录，
 * 由集成测试的 @TestConfiguration 单独注册并覆盖
 * mkToolCallbacks Bean，不会进入生产环境。
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

    /**
     * 音乐普通用户：曲库只读 + 歌单只读。
     *
     * 写工具（建歌单、加歌）**不在这里**，见 docs/music-permissions.md。
     */
    @Bean("musicUserToolCallbacks")
    public ToolCallback[] musicUserToolCallbacks(AgentToolProvider toolProvider) {
        return toolProvider.getTools(AgentType.MUSIC_USER);
    }

    /**
     * 音乐管理员：曲库只读 + 聚合统计（无歌单类工具、无写工具）。
     */
    @Bean("musicAdminToolCallbacks")
    public ToolCallback[] musicAdminToolCallbacks(AgentToolProvider toolProvider) {
        return toolProvider.getTools(AgentType.MUSIC_ADMIN);
    }
}
