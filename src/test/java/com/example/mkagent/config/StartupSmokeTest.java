package com.example.mkagent.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.tools.WebSearchTool;
import com.zaxxer.hikari.HikariDataSource;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 受控启动冒烟：用隔离参数把完整上下文拉起来，再正常关掉。
 *
 * <p>边界（任何一条不满足就不该跑这个测试）：
 * <ul>
 *   <li>数据库：命令行强制 H2 内存库，<b>不连业务库</b>；</li>
 *   <li>MCP：自动配置整组排除 + {@code enabled=false}，<b>不连本机 8127、不拉起 npx</b>；</li>
 *   <li>Embedding / pgvector：自动配置排除，<b>不创建向量模型、不发起请求</b>；</li>
 *   <li>模型与搜索：只做客户端装配，<b>不调用</b>（构造阶段没有网络 IO）；</li>
 *   <li>配置：只读 classpath，<b>不加载</b> 工作目录下的 {@code ./config/application-local.yml}。</li>
 * </ul>
 *
 * <p>所有凭据都是假值，失败信息里也不允许出现它们。
 */
class StartupSmokeTest {

    private static final String FAKE_MODEL_KEY = "fake-model-key-for-smoke";

    @Test
    @DisplayName("隔离启动：配置绑定成功、上下文就绪、正常关闭")
    void startsWithIsolatedConfigAndClosesCleanly() {
        ConfigurableApplicationContext context = IsolatedAppLauncher.start(
                Map.of("MKAGENT_MODEL_API_KEY", FAKE_MODEL_KEY));

        try {
            assertThat(context.isActive()).isTrue();

            // 1) 必要配置确实绑定到了 Bean 上
            assertThat(context.getBean(DashScopeChatModel.class)).isNotNull();
            assertThat(context.getBean(WebSearchTool.class).getEngine()).isEqualTo("baidu");

            ConfigurableEnvironment env = context.getEnvironment();
            assertThat(env.getProperty("spring.ai.dashscope.chat.options.model"))
                    .isEqualTo("qwen-plus");
            assertThat(env.getProperty("spring.ai.dashscope.chat.options.enable-thinking"))
                    .isEqualTo("false");

            // 2) 隔离真的生效：数据库是 H2，且没有任何"外部文件"配置源被加载
            DataSource dataSource = context.getBean(DataSource.class);
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            assertThat(((HikariDataSource) dataSource).getJdbcUrl())
                    .startsWith("jdbc:h2:mem:")
                    .contains("mk_agent_smoke_");

            List<String> sourceNames = new ArrayList<>();
            for (PropertySource<?> source : env.getPropertySources()) {
                sourceNames.add(source.getName());
            }
            assertThat(sourceNames)
                    .as("不得加载工作目录下的真实凭据文件")
                    .noneMatch(name -> name.startsWith("Config resource 'file ["));
            assertThat(IsolatedAppLauncher.sourceOf(env, "spring.datasource.url"))
                    .isEqualTo("commandLineArgs");

            // 3) MCP / Embedding 没有装配（也就没有连接外部服务的可能）
            assertThat(env.getProperty("spring.ai.mcp.client.enabled")).isEqualTo("false");
            assertThat(context.getBeanNamesForType(McpSyncClient.class))
                    .as("MCP 客户端 Bean 必须为 0：否则启动阶段会去连 8127")
                    .isEmpty();
        } finally {
            context.close();
        }

        assertThat(context.isActive()).isFalse();
    }

    @Test
    @DisplayName("负向：缺模型凭据时启动失败，报错明确且脱敏")
    void missingCredentialFailsFastWithRedactedMessage() {
        assertThatThrownBy(() -> IsolatedAppLauncher.start(Map.of()).close())
                .satisfies(failure -> {
                    String message = IsolatedAppLauncher.deepestMessage(failure);

                    assertThat(message)
                            .as("必须点名该设哪个环境变量，而不是库原生的 DashScope API key must be set")
                            .contains("未配置模型凭据")
                            .contains("MKAGENT_MODEL_API_KEY")
                            .contains("config/application-local.yml");

                    assertThat(message)
                            .as("报错不得回显任何凭据值")
                            .doesNotContain("sk-")
                            .doesNotContain(FAKE_MODEL_KEY)
                            .doesNotContain(IsolatedAppLauncher.FAKE_SEARCH_KEY);
                });
    }
}
