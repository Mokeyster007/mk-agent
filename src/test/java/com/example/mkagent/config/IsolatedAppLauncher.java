package com.example.mkagent.config;

import com.example.mkagent.MkAgentApplication;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用**隔离参数**启动完整应用上下文的测试夹具（不连业务库、不连 MCP、不发起任何外部请求）。
 *
 * <p>为什么不用 {@code ApplicationContextRunner}：它不处理 Spring Boot 的
 * config data（application.yml / application-local.yml / 外部 config 目录），
 * 而本轮要验证的恰恰是"配置从哪个源来"。所以这里用真实的
 * {@link SpringApplicationBuilder}，只把"隔离"相关的参数显式钉死：
 *
 * <ul>
 *   <li><b>数据库</b>：命令行参数指向独立 H2 内存库（优先级最高，压过任何配置文件）；</li>
 *   <li><b>配置位置</b>：{@code spring.config.location} 只留 classpath，
 *       从而**屏蔽工作目录下的 {@code ./config/application-local.yml}**——
 *       本机真实凭据文件绝不能进入测试环境；</li>
 *   <li><b>MCP / Embedding / pgvector</b>：自动配置整组排除，且
 *       {@code spring.ai.mcp.client.enabled=false}，避免启动时连接本机 8127
 *       或拉起 npx 子进程；</li>
 *   <li><b>环境变量</b>：用同名 {@link SystemEnvironmentPropertySource} 替换真实
 *       systemEnvironment，测试只认自己给的假变量，不受本机环境影响。</li>
 * </ul>
 *
 * <p>本类只服务于测试，不进入生产代码。
 */
final class IsolatedAppLauncher {

    /** 搜索凭据的假值：只用于满足装配校验，不发起任何请求。 */
    static final String FAKE_SEARCH_KEY = "fake-search-key-not-real";

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    /**
     * 与 {@code src/test/resources/application-test.yml} 保持同一份排除清单。
     * 少一项就可能真的去连 MCP 或创建 Embedding 模型。
     */
    private static final String EXCLUDES = String.join(",",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingAutoConfiguration",
            "org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration",
            "org.springframework.ai.mcp.client.autoconfigure.McpToolCallbackAutoConfiguration",
            "org.springframework.ai.mcp.client.autoconfigure.StdioTransportAutoConfiguration",
            "org.springframework.ai.mcp.client.autoconfigure.SseHttpClientTransportAutoConfiguration",
            "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration"
    );

    private IsolatedAppLauncher() {
    }

    /**
     * 启动上下文。调用方负责 {@code close()}（或用 try-with-resources）。
     */
    static ConfigurableApplicationContext start(Map<String, String> simulatedEnv,
                                                String... extraArgs) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(
                MkAgentApplication.class, StubBeans.class)
                .web(WebApplicationType.NONE)
                .environment(environmentWith(simulatedEnv));
        return builder.run(allArgs(extraArgs));
    }

    /**
     * 构造隔离参数。测试可以追加参数覆盖默认值（追加项在命令行里优先级相同，
     * 因此**不要**用它去覆盖同名键，需要不同取值请改这里或换键）。
     */
    static String[] allArgs(String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=local",
                // 只从 classpath 读配置：屏蔽 ./config/ 与 ./ 下的真实凭据文件
                "--spring.config.location=optional:classpath:/,optional:classpath:/config/",
                "--spring.datasource.url=jdbc:h2:mem:mk_agent_smoke_" + DB_SEQ.incrementAndGet()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.sql.init.mode=always",
                "--spring.sql.init.schema-locations=classpath:db/schema.sql",
                "--spring.autoconfigure.exclude=" + EXCLUDES,
                "--spring.ai.mcp.client.enabled=false",
                "--mkagent.rag.pgvector.enabled=false",
                "--search-api.api-key=" + FAKE_SEARCH_KEY
        ));
        args.addAll(List.of(extraArgs));
        return args.toArray(String[]::new);
    }

    /**
     * 用可控的假环境变量替换真实 systemEnvironment。
     *
     * <p>替换而不是追加：本机若真的设置了 {@code AI_DASHSCOPE_API_KEY}，
     * 测试会变成"看机器脸色"。替换后，测试里的"只有某个变量"就是字面意思。
     */
    static ConfigurableEnvironment environmentWith(Map<String, String> simulatedEnv) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new LinkedHashMap<String, Object>(simulatedEnv)
        ));
        return environment;
    }

    /**
     * 某个属性**第一次出现的属性源名称**（即"来源标签"）。
     *
     * <p>注意：这是"属性在哪定义"，不是"最终解析出什么值"。
     * 值可能是 {@code ${...}} 占位符，最终取值由占位符解析决定——
     * 两者是不同层次，报告里必须分开说。
     */
    static String sourceOf(ConfigurableEnvironment environment, String key) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            // Spring Boot 会在最前面套一层 ConfigurationPropertySourcesPropertySource，
            // 它对任何属性都 containsProperty=true，是个包装层、不是真实来源。
            if ("configurationProperties".equals(source.getName())) {
                continue;
            }
            if (source.containsProperty(key)) {
                return source.getName();
            }
        }
        return "<none>";
    }

    /** 取最内层异常消息（Spring 会把业务异常包多层）。 */
    static String deepestMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }

    /**
     * MCP 自动配置被排除后，{@code chatApp} 注入的 {@code ToolCallbackProvider}
     * 就没有提供者了。这里补一个**空桩**（零工具），与其它集成测试的做法一致：
     * 既满足注入，又不连任何 MCP 服务。
     */
    @Configuration(proxyBeanMethods = false)
    static class StubBeans {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }
}
