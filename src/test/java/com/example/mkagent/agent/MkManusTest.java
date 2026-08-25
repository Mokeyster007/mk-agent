package com.example.mkagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MkManus 真实模型端到端测试。
 *
 * 说明：本测试使用真实 DashScope 模型与真实搜索工具，
 * 依赖网络与 API Key，属于学习验证型测试。
 *
 * 工具白名单调整后，MANUS 类型只持有
 * web_search / web_scrape / terminate_task，
 * 不再包含 generate_pdf（PDF 生成属于 FILE 类型白名单），
 * 因此任务场景改为搜索类任务。
 *
 * AgentRun 持久化使用 H2 内存库（PostgreSQL 兼容模式），
 * 避免真实模型测试污染生产数据库。
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url=jdbc:h2:mem:mk_agent_manus;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/schema.sql",
                // 禁用 pgvector 向量库：PgVectorStore 初始化会执行 PostgreSQL 专用的
                // CREATE EXTENSION IF NOT EXISTS vector，H2 不支持该语法。
                "mkagent.rag.pgvector.enabled=false"
        }
)
class MkManusTest {

    @Autowired
    private MkManus mkManus;

    @Test
    void shouldRunMkManusSuccessfully() {
        // 1. 调用 Agent：必须先调用 web_search，再调用 terminate_task。
        String answer = mkManus.run("""
            你必须先调用 web_search 工具。

            搜索关键词：Spring AI Tool Calling
            根据搜索结果，总结 Tool Calling 的作用，
            总结内容至少包括：
            1. Tool Calling 的作用；
            2. ToolCallback 的作用；
            3. ToolCallingManager 的作用。

            总结完成后调用 terminate_task。
            """);

        // 2. 验证 Agent 返回结果。
        assertNotNull(answer);
        assertFalse(answer.isBlank());
    }
}
