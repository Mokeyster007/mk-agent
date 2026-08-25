package com.example.mkagent.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 工具白名单装配集成测试。
 *
 * 与 MkManusAsyncSseIntegrationTest 相同的隔离策略：
 * 排除 DashScope / MCP 自动配置、替换模型与文档加载、替换向量库，
 * 上下文完全不触网。
 *
 * 与前者不同的是：本测试【不覆盖】mkToolCallbacks，
 * 让 ToolRegistryConfig + AgentToolProvider 的生产装配真实生效，
 * 验证：
 * 1. MkManus（MANUS 类型）只持有 web_search / web_scrape / terminate_task，
 *    获取不到文件读写、资源下载、PDF 生成等高风险工具；
 * 2. chatToolCallbacks（CHAT 类型）只有 web_search，
 *    获取不到 FileOperationTool；
 * 3. fileToolCallbacks（FILE 类型）只包含文件类工具。
 */
@SpringBootTest(
        properties = {
                "spring.autoconfigure.exclude=com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration,"
                        // 排除 MCP 客户端自动配置：启动时会真实连接外部 MCP 服务，
                        // 测试环境不依赖 MCP，chatApp 需要的 ToolCallbackProvider 由测试桩提供。
                        + "org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.McpToolCallbackAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.StdioTransportAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.SseHttpClientTransportAutoConfiguration",
                // 测试配置需要覆盖用户配置类的同名 Bean（如 chatAppDocumentLoader）
                "spring.main.allow-bean-definition-overriding=true"
        }
)
@ActiveProfiles("local")
@Timeout(60)
class AgentToolWhitelistIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModelConfig {

        /**
         * MyKeywordEnricher 按具体类型 DashScopeChatModel 注入，
         * 这里只注册一个空行为 mock，保证上下文启动不触网。
         * 本测试只校验工具装配，不执行 Agent 任务。
         */
        @Bean("dashscopeChatModel")
        @Primary
        ChatModel dashscopeChatModel() {
            return Mockito.mock(DashScopeChatModel.class);
        }

        /** RAG 文档加载替换为 mock，避免启动时富集调用真实模型。 */
        @Bean("chatAppDocumentLoader")
        ChatAppDocumentLoader chatAppDocumentLoader() {
            ChatAppDocumentLoader mock =
                    Mockito.mock(ChatAppDocumentLoader.class);
            Mockito.when(mock.loadMarkdowns()).thenReturn(List.of());
            return mock;
        }

        /**
         * 排除 MCP 自动配置后，chatApp 注入的 ToolCallbackProvider 由此桩提供，
         * 返回空工具列表，测试完全不依赖外部 MCP 服务。
         */
        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    @Autowired
    private MkManus mkManus;

    @Autowired
    @Qualifier("chatToolCallbacks")
    private ToolCallback[] chatToolCallbacks;

    @Autowired
    @Qualifier("fileToolCallbacks")
    private ToolCallback[] fileToolCallbacks;

    /**
     * 替换 RAG 内存向量库，避免测试触网调用 Embedding API。
     */
    @MockitoBean
    private VectorStore chatAppVectorStore;

    @Test
    void mkManusOnlyHoldsManusWhitelistTools() {
        Set<String> names = toolNames(mkManus.getAvailableTools());

        assertThat(names)
                .as("MkManus 应只持有 MANUS 白名单工具")
                .containsExactlyInAnyOrder(
                        "web_search",
                        "web_scrape",
                        "terminate_task"
                );

        // MkManus 获取不到不需要的高风险工具。
        assertThat(names).doesNotContain(
                "read_file",
                "write_file",
                "download_resource",
                "generate_pdf"
        );
    }

    @Test
    void chatWhitelistDoesNotContainFileOperationTool() {
        Set<String> names = toolNames(chatToolCallbacks);

        assertThat(names)
                .as("CHAT 白名单应只有 web_search")
                .containsExactly("web_search");

        // CHAT Agent 获取不到 FileOperationTool 及其他高风险工具。
        assertThat(names).doesNotContain(
                "read_file",
                "write_file",
                "download_resource",
                "generate_pdf"
        );
    }

    @Test
    void fileWhitelistContainsOnlyFileTools() {
        Set<String> names = toolNames(fileToolCallbacks);

        assertThat(names)
                .as("FILE 白名单应只包含文件类工具")
                .containsExactlyInAnyOrder(
                        "read_file",
                        "write_file",
                        "download_resource",
                        "generate_pdf"
                );
    }

    private static Set<String> toolNames(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
    }
}
