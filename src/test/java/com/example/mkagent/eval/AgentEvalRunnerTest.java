package com.example.mkagent.eval;

import com.example.mkagent.mapper.AgentRunMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MkManus 离线评测运行入口。
 *
 * 默认不运行（避免误消耗真实模型与搜索 API 费用）：
 *   只有在 JVM 系统属性 mkagent.eval.enabled=true 时才启用。
 *
 * 运行命令：
 *   mvn -Dtest=AgentEvalRunnerTest -Dmkagent.eval.enabled=true test
 *
 * 该测试会：
 * 1. 通过 Spring 注入生产 ChatModel、MANUS 工具白名单与 EvalProperties；
 * 2. 调用 AgentEvaluator.evaluateAll() 串行执行全部 JSON 用例；
 * 3. 写出 target/eval-report/agent-eval-report.md；
 * 4. 断言评测前后 agent_run 表行数不变（评测不污染线上运行记录）。
 *
 * 测试数据库使用 H2 内存库，不连接生产 RDS；
 * MCP 自动配置被排除，避免启动时连接外部 MCP 服务。
 */
@SpringBootTest(
        properties = {
                "spring.autoconfigure.exclude=com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.McpToolCallbackAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.StdioTransportAutoConfiguration,"
                        + "org.springframework.ai.mcp.client.autoconfigure.SseHttpClientTransportAutoConfiguration",
                "spring.main.allow-bean-definition-overriding=true",
                "spring.datasource.url=jdbc:h2:mem:mk_agent_eval;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/schema.sql",
                "mkagent.rag.pgvector.enabled=false",
                "mkagent.eval.enabled=true",
                "mkagent.eval.max-concurrency=2",
                "mkagent.eval.cases-path=classpath:eval/cases.json",
                "mkagent.eval.report-path=target/eval-report/agent-eval-report.md",
                "mkagent.eval.judge-enabled=true"
        }
)
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "mkagent.eval.enabled", matches = "true")
@Timeout(3600)
class AgentEvalRunnerTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class EvalContextConfig {

        /**
         * 排除 MCP 自动配置后，chatApp 注入的 ToolCallbackProvider 由此桩提供，
         * 评测本身只使用 MANUS 白名单工具。
         */
        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    @Autowired
    private AgentEvaluator agentEvaluator;

    @Autowired
    private AgentRunMapper agentRunMapper;

    /**
     * 替换 RAG 内存向量库，避免启动时调用真实 Embedding API。
     * 评测只关心 Agent 的工具调用链，不依赖 RAG。
     */
    @MockitoBean
    private VectorStore chatAppVectorStore;

    @Test
    void runAllCasesAndWriteMarkdownReport() throws Exception {
        long before = agentRunMapper.selectCount(null);

        EvalReport report = agentEvaluator.evaluateAll();

        long after = agentRunMapper.selectCount(null);

        assertThat(report.getTotalCount())
                .as("评测用例数量")
                .isGreaterThanOrEqualTo(30);

        /*
         * 关键隔离断言：评测 Agent 未注入 AgentRunRecorder，
         * 因此整轮评测不会新增 agent_run 记录。
         */
        assertThat(after)
                .as("评测前后 agent_run 行数不能变化")
                .isEqualTo(before);

        Path reportPath = Path.of(
                "target/eval-report/agent-eval-report.md"
        );
        assertThat(reportPath)
                .as("Markdown 报告应已写出")
                .exists();

        assertThat(Files.readString(reportPath))
                .contains("MkManus 离线评测报告")
                .contains("总通过率")
                .contains("分类别通过率")
                .contains("P95 耗时")
                .contains("失败用例清单");
    }
}
