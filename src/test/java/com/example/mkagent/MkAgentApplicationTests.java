package com.example.mkagent;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.example.mkagent.support.HermeticSpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用上下文冒烟测试。
 *
 * 默认测试必须能在“无 Docker 数据库、无 8127 MCP 服务、无外网”的环境下通过：
 * 1. H2 内存库替代本地 PostgreSQL；
 * 2. 排除 MCP 客户端与传输层自动配置，避免启动时连接 localhost:8127；
 * 3. 排除 DashScope 对话自动配置，确保用的是项目显式装配的 ChatModel；
 * 4. 关闭 pgvector 存储初始化（H2 没有 vector 扩展）。
 *
 * 隔离配置统一由 @HermeticSpringBootTest + application-test.yml 提供，
 * 本类只额外断言"测试确实没有读取 application-local.yml"。
 */
@HermeticSpringBootTest
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

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
    }

    /**
     * 封闭性断言：测试上下文只能激活 test profile，
     * 且属性源里不能出现 application-local.yml（真实密钥所在文件）。
     *
     * 之所以在运行时验证，是因为 application.yml 默认 active=local，
     * 单看注解无法证明 profile 覆盖真的生效。
     */
    @Test
    void contextNeverActivatesLocalProfileOrLoadsLocalConfig() {
        assertThat(environment.getActiveProfiles())
                .as("默认测试只能激活 test profile，否则会读取 application-local.yml")
                .containsExactly("test");

        assertThat(propertySourceNames())
                .as("默认测试的属性源中不应出现 application-local")
                .noneMatch(name -> name.contains("application-local"));
    }

    private List<String> propertySourceNames() {
        ConfigurableEnvironment configurable =
                (ConfigurableEnvironment) environment;
        List<String> names = new ArrayList<>();
        configurable.getPropertySources().forEach(source -> names.add(source.getName()));
        return names;
    }
}
