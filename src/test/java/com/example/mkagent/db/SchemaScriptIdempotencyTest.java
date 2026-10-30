package com.example.mkagent.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema.sql 幂等性（H2 内存库 + PostgreSQL 兼容模式，不依赖外部数据库）。
 *
 * 为什么必须测：
 * 应用启动时 `spring.sql.init.mode=always` 会**每次**执行 schema.sql，
 * 生产 RDS 上表早就存在。任何一句非幂等的 DDL 都会让服务起不来。
 * 而 `CREATE TABLE IF NOT EXISTS` 不会给存量表补列，
 * 所以新增列必须额外写 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`。
 *
 * 本测试连续执行两次脚本：
 * 1. 第二次不得抛异常（列已存在）；
 * 2. tool_trace 列存在且类型为文本。
 */
class SchemaScriptIdempotencyTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:schema_idempotency;MODE=PostgreSQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    @DisplayName("schema.sql 重复执行不报错，tool_trace 列存在")
    void schemaScriptIsIdempotentAndAddsToolTrace() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                JDBC_URL, "sa", "")) {

            // 第一次执行：建表 + 加列。
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/schema.sql"));

            // 第二次执行：所有语句都已在"已存在"状态，必须全部幂等通过。
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/schema.sql"));

            List<String> columns = columnNames(connection, "agent_run");
            assertTrue(columns.contains("tool_trace"),
                    "agent_run 必须包含 tool_trace 列，实际列：" + columns);
            assertTrue(columns.contains("total_tokens"),
                    "既有列不应被改动，实际列：" + columns);

            assertEquals(1, countColumns(connection, "agent_run", "tool_trace"),
                    "tool_trace 只应存在一列（重复执行不得报错或重复添加）");
        }
    }

    @Test
    @DisplayName("存量表（旧版无 tool_trace）可被 ALTER 补齐")
    void alterUpgradesLegacyTable() throws Exception {
        String url = "jdbc:h2:mem:schema_upgrade;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            /*
             * 模拟存量环境：先建一张"旧版" agent_run
             * （= 最初的 CREATE 定义，没有 model / tokens / tool_trace），
             * 再执行 schema.sql —— CREATE TABLE IF NOT EXISTS 不会改已存在的表，
             * 补列只能靠 ALTER TABLE ... ADD COLUMN IF NOT EXISTS。
             */
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE agent_run
                        (
                            id                BIGINT       NOT NULL,
                            run_id            VARCHAR(64)  NOT NULL,
                            user_id           VARCHAR(64)  NOT NULL,
                            agent_type        VARCHAR(32)  NOT NULL,
                            user_prompt       TEXT,
                            state             VARCHAR(32)  NOT NULL,
                            current_step      INT          NOT NULL DEFAULT 0,
                            tool_call_count   INT          NOT NULL DEFAULT 0,
                            final_answer      TEXT,
                            error_message     TEXT,
                            started_at        TIMESTAMP,
                            finished_at       TIMESTAMP,
                            total_cost_millis BIGINT,
                            created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            CONSTRAINT pk_agent_run PRIMARY KEY (id)
                        )
                        """);
            }

            /*
             * 先证明模拟的存量表确实"旧"：
             * 新版本新增的 Usage / 轨迹列必须都不存在，
             * 否则后面的"升级补齐"断言就是空转。
             */
            List<String> legacyColumns = columnNames(connection, "agent_run");
            for (String newColumn : List.of(
                    "tool_trace",
                    "model",
                    "prompt_tokens",
                    "completion_tokens",
                    "total_tokens"
            )) {
                assertFalse(legacyColumns.contains(newColumn),
                        "模拟的旧表不应包含新增列 " + newColumn
                                + "，实际列：" + legacyColumns);
            }

            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/schema.sql"));

            List<String> columns = columnNames(connection, "agent_run");
            for (String newColumn : List.of(
                    "tool_trace",
                    "model",
                    "prompt_tokens",
                    "completion_tokens",
                    "total_tokens"
            )) {
                assertTrue(columns.contains(newColumn),
                        "存量表必须被 ALTER 补齐 " + newColumn
                                + "，实际列：" + columns);
            }
        }
    }

    private List<String> columnNames(Connection connection, String table)
            throws Exception {
        List<String> columns = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(
                null, null, table, null)) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("COLUMN_NAME").toLowerCase());
            }
        }
        return columns;
    }

    private int countColumns(Connection connection, String table, String column)
            throws Exception {
        int count = 0;
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(
                null, null, table, column)) {
            while (resultSet.next()) {
                count++;
            }
        }
        return count;
    }
}
