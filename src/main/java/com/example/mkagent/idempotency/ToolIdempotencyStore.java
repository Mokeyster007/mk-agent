package com.example.mkagent.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * 写工具幂等记录的存储层（只做 SQL，不管事务）。
 *
 * <p>表名沿用历史表 {@code music_tool_idempotency}，保持对既有演示库与生产库的非破坏兼容；
 * 它承担的是通用 Agent 写工具（售后退货、导购确认下单等）的框架级幂等存储。
 */
@Component
public class ToolIdempotencyStore {

    private static final Logger log =
            LoggerFactory.getLogger(ToolIdempotencyStore.class);

    private final JdbcTemplate jdbcTemplate;

    private final DataSource dataSource;

    private final long lockTimeoutMillis;

    private volatile Boolean postgres;

    public ToolIdempotencyStore(
            JdbcTemplate jdbcTemplate,
            DataSource dataSource,
            @Value("${mkagent.idempotency.lock-timeout-millis:${mkagent.music.idempotency.lock-timeout-millis:5000}}")
            long lockTimeoutMillis
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.lockTimeoutMillis = lockTimeoutMillis;
    }

    public record Record(String toolName, String requestHash, String resultJson) {
    }

    public record RunGuard(String userId, int executionEpoch, String state) {
    }

    public void beginBoundedWait() {
        if (!isPostgres()) {
            return;
        }
        jdbcTemplate.execute("SET LOCAL lock_timeout = " + lockTimeoutMillis);
    }

    public RunGuard findRun(String runId) {
        return queryRun(runId, "");
    }

    public RunGuard lockRun(String runId) {
        return queryRun(runId, " FOR UPDATE");
    }

    private RunGuard queryRun(String runId, String lockClause) {
        List<RunGuard> rows = jdbcTemplate.query(
                "SELECT user_id, execution_epoch, state FROM agent_run WHERE run_id = ?"
                        + lockClause,
                (rs, rowNum) -> new RunGuard(
                        rs.getString("user_id"),
                        rs.getInt("execution_epoch"),
                        rs.getString("state")
                ),
                runId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertPlaceholder(
            String userId,
            String idempotencyKey,
            String toolName,
            String requestHash,
            String runId,
            int step,
            String toolCallId
    ) {
        jdbcTemplate.update(
                "INSERT INTO music_tool_idempotency "
                        + "(user_id, idempotency_key, tool_name, request_hash, "
                        + " run_id, step, tool_call_id, result_json, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                userId, idempotencyKey, toolName, requestHash,
                runId, step, toolCallId
        );
    }

    public int saveResult(
            String userId,
            String idempotencyKey,
            String requestHash,
            String resultJson
    ) {
        return jdbcTemplate.update(
                "UPDATE music_tool_idempotency "
                        + "SET result_json = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE user_id = ? AND idempotency_key = ? AND request_hash = ?",
                resultJson, userId, idempotencyKey, requestHash
        );
    }

    public Record find(String userId, String idempotencyKey) {
        List<Record> rows = jdbcTemplate.query(
                "SELECT tool_name, request_hash, result_json FROM music_tool_idempotency "
                        + "WHERE user_id = ? AND idempotency_key = ?",
                (rs, rowNum) -> new Record(
                        rs.getString("tool_name"),
                        rs.getString("request_hash"),
                        rs.getString("result_json")
                ),
                userId, idempotencyKey
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean isPostgres() {
        Boolean cached = postgres;
        if (cached != null) {
            return cached;
        }

        boolean detected = false;
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            detected = product != null
                    && product.toLowerCase(Locale.ROOT).contains("postgresql");
        } catch (SQLException e) {
            log.warn("无法探测数据库类型，跳过事务级锁等待设置：{}", e.getMessage());
        }

        postgres = detected;
        return detected;
    }
}
