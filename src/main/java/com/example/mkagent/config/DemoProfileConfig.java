package com.example.mkagent.config;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * demo profile 的占位装配。
 *
 * <p>为什么需要：{@code chatApp} 的构造参数里有 {@code ToolCallbackProvider}
 * （MCP 客户端自动配置提供）。demo 为了"启动期不连任何外部服务"把 MCP
 * 整组排除了，这个 Bean 就没人提供，应用会在启动阶段直接失败。
 *
 * <p>这里补一个**零工具**的实现：
 * <ul>
 *   <li>不会连接 8127，也不会拉起 npx 子进程；</li>
 *   <li>只影响 {@code /ai/chat_app/**} 这类入口（它们本来就没有本地工具），</li>
 *   <li>音乐入口的工具来自 {@code musicUserToolCallbacks} /
 *       {@code musicWriteToolCallbacks}，与这里无关——演示闭环不受影响。</li>
 * </ul>
 *
 * <p>{@code @Profile("demo")}：生产（无 demo profile）不会注册这个 Bean，
 * 仍然由真实 MCP 客户端提供工具。
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoProfileConfig {

    @Bean
    public ToolCallbackProvider demoToolCallbackProvider() {
        return () -> new ToolCallback[0];
    }
}
