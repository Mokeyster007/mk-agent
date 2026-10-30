package com.example.mkagent.agent;

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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MkManus 预算配置注入测试。
 *
 * 验收点：
 * 1. mkagent.agent.manus.max-steps / max-tool-calls / timeout
 *    真的落到 Spring 容器里的 MkManus 实例上；
 * 2. 方法注入不改变 ToolCallAgent / BaseAgent 的构造器签名
 *    （EvalAgent 手动 super(...) 不受影响）；
 * 3. 默认值 8 / 12 / 2m 由 MkAgentApplicationTests 在默认上下文中断言。
 *
 * 本测试不调用模型、不执行任务，只读配置。
 */
@HermeticSpringBootTest(properties = {
        "mkagent.agent.manus.max-steps=5",
        "mkagent.agent.manus.max-tool-calls=7",
        "mkagent.agent.manus.timeout=45s"
})
class MkManusBudgetConfigTest {

    /**
     * MCP 自动配置被排除后，chatApp 需要的 ToolCallbackProvider 用空桩替代；
     * DashScope 自动配置被排除后，其属性 Bean 需显式启用。
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            DashScopeChatProperties.class,
            DashScopeConnectionProperties.class
    })
    static class ToolCallbackProviderStub {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    @Autowired
    private MkManus mkManus;

    @Test
    void manusBudgetIsInjectedFromGroupedProperties() {
        assertThat(mkManus.getMaxSteps())
                .as("mkagent.agent.manus.max-steps 应注入")
                .isEqualTo(5);

        assertThat(mkManus.getMaxToolCalls())
                .as("mkagent.agent.manus.max-tool-calls 应注入")
                .isEqualTo(7);

        assertThat(mkManus.getTimeout())
                .as("mkagent.agent.manus.timeout 应注入")
                .isEqualTo(Duration.ofSeconds(45));
    }
}
