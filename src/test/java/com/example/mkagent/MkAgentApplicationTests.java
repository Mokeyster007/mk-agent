package com.example.mkagent;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 应用上下文冒烟测试。
 *
 * 默认测试必须能在“无 Docker 数据库、无 8127 MCP 服务、无外网”的环境下通过：
 * 1. H2 内存库替代本地 PostgreSQL；
 * 2. 排除 MCP 客户端与传输层自动配置，避免启动时连接 localhost:8127；
 * 3. 排除 DashScope 对话自动配置，确保用的是项目显式装配的 ChatModel；
 * 4. 关闭 pgvector 存储初始化（H2 没有 vector 扩展）。
 *
 * 注意：这里显式声明 spring.autoconfigure.exclude 会**覆盖** application.yml
 * 中的同名列表，而不是合并，因此必须把 yml 里已有的
 * DashScopeChatAutoConfiguration 一并列出。
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration,"
                + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration,"
                + "org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration,"
                + "org.springframework.ai.mcp.client.autoconfigure.McpToolCallbackAutoConfiguration,"
                + "org.springframework.ai.mcp.client.autoconfigure.StdioTransportAutoConfiguration,"
                + "org.springframework.ai.mcp.client.autoconfigure.SseHttpClientTransportAutoConfiguration",
        "spring.datasource.url=jdbc:h2:mem:mk_agent_context;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql",
        "mkagent.rag.pgvector.enabled=false"
})
class MkAgentApplicationTests {

    /**
     * MCP 自动配置被排除后，chatApp 需要的 ToolCallbackProvider 不再存在，
     * 这里补一个空桩（不连任何 MCP 服务）。
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            DashScopeChatProperties.class,
            DashScopeConnectionProperties.class
    })
    static class ToolCallbackProviderStub {

        /**
         * DashScopeChatAutoConfiguration 被排除后，这两个属性 Bean 不再有人注册，
         * 而项目显式的 DashScopeChatModelConfig 需要它们。
         */
        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    @Test
    void contextLoads() {
    }
}
