package com.example.mkagent.approval;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL 专项：启动收尾扫描依赖的两条 PG 语义——事务级锁等待、被阻塞 UPDATE 的重新求值。
 *
 * <p><b>默认构建不会执行</b>（surefire 的 excludedGroups 含 {@code pg}）。
 * 运行方式（需要一个可连接的 PostgreSQL，且**不碰**业务表）：
 *
 * <pre>
 * MKAGENT_PG_URL=jdbc:postgresql://localhost:5432/mk_agent \
 * MKAGENT_PG_USER=postgres MKAGENT_PG_PASSWORD=*** \
 * ./mvnw -o test -Dmkagent.test.excluded-groups=live -Dgroups=pg \
 *     -Dtest=PostgresRecoveryConcurrencyTest
 * </pre>
 *
 * <h2>为什么必须在真 PG 上验证</h2>
 *
 * <ul>
 *   <li>H2 没有 {@code SET LOCAL}；H2 的 {@code LOCK_TIMEOUT} 是 session 级、回滚不恢复，
 *       和 PG 的事务级作用域<b>不等价</b>，H2 全绿证明不了 PG 上不泄漏；</li>
 *   <li>"扫描器先赢、旧执行者的终态写入拿 0 行"这条 G7 结论，
 *       依赖 PG 在 READ COMMITTED 下对阻塞后 UPDATE <b>重新求值 WHERE</b> 的行为。
 *       H2 可能直接抛并发更新异常，语义不同。</li>
 * </ul>
 *
 * <h2>隔离方式</h2>
 *
 * 只在本类自建的临时探针表上操作（表名带随机后缀，{@code @BeforeAll} 建、
 * {@code @AfterAll} 删），不读写任何业务表，不改全局数据库配置。
 * {@code MKAGENT_PG_URL} 必须指向**独立的测试库**，不要指向业务库。
 * 执行中断可能残留 {@code mk_recovery_probe_*}，手工 {@code DROP TABLE} 即可。
 *
 * <p><b>执行状态（2026-11-15）</b>：已在隔离 PG 16.2 实例（127.0.0.1:55432，独立探针库）上实际运行通过。
 * 注意本类只是**原语探针**：它证明"PG 会这样"，不证明"我们的服务在这套数据库上确实这样"——
 * 后者由 {@link PostgresRecoveryServiceTest}（真实 Spring 事务 + 真实服务 Bean）负责。
 */
@Tag("pg")
class PostgresRecoveryConcurrencyTest {

    private static final String URL = System.getenv().getOrDefault(
            "MKAGENT_PG_URL", "jdbc:postgresql://localhost:5432/mk_agent");

    private static final String USER = System.getenv().getOrDefault(
            "MKAGENT_PG_USER", "postgres");

    private static final String PASSWORD = System.getenv().getOrDefault(
            "MKAGENT_PG_PASSWORD", "");

    /** 与 {@code agent_run} 相关列同形的最小探针表。 */
    private static String table;

    @BeforeAll
    static void createProbeTable() throws SQLException {
        table = "mk_recovery_probe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " ("
                    + "run_id VARCHAR(64) NOT NULL PRIMARY KEY, "
                    + "state VARCHAR(32) NOT NULL, "
                    + "execution_epoch INT NOT NULL)");
        }
    }

    @AfterAll
    static void dropProbeTable() throws SQLException {
        if (table == null) {
            return;
        }
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    @DisplayName("事务级锁等待有界：撞上别人持有的行锁，按 55P03 失败而不是无限等")
    void lockTimeoutBoundsTheWait() throws Exception {
        String runId = insertRun("RUNNING", 1);

        try (Connection holder = open(); Connection sweeper = open()) {
            holder.setAutoCommit(false);
            sweeper.setAutoCommit(false);

            // 模拟一个长时间持有 agent_run 行锁的写事务（例如正在提交终态）
            update(holder, runId, "FAILED", "RUNNING", 1);

            AtomicReference<Long> elapsed = new AtomicReference<>();
            AtomicReference<SQLException> error = new AtomicReference<>();
            Thread blocked = startBlockedUpdate(sweeper, runId, "FAILED", elapsed, error);

            /*
             * 必须让 holder 持有到锁超时之后。
             * 早先这里 sleep(200) 短于 lock_timeout(300)，锁在超时前就被释放，
             * 被阻塞的 UPDATE 于是正常命中，用例失败——那是**测试自身的时序错误**，
             * 不是生产行为。这里改为远大于超时的等待。
             */
            Thread.sleep(1_200);
            holder.rollback();
            blocked.join(15_000);

            assertNotNull(error.get(), "锁等待必须超时失败，而不是等到 holder 回滚");
            assertEquals("55P03", error.get().getSQLState(),
                    "必须是锁超时，而不是别的错误：" + error.get().getMessage());
            assertTrue(elapsed.get() >= 250 && elapsed.get() < 10_000,
                    "等待应当有界，实际 " + elapsed.get() + "ms");
            System.out.println("[pg-evidence] lockTimeout waited=" + elapsed.get()
                    + "ms sqlstate=" + error.get().getSQLState());
        }
    }

    @Test
    @DisplayName("SET LOCAL 不泄漏：超时事务回滚后，同一连接的下一个事务不再带超时")
    void lockTimeoutDoesNotLeakAfterRollback() throws Exception {
        String runId = insertRun("RUNNING", 1);

        try (Connection holder = open(); Connection sweeper = open()) {
            sweeper.setAutoCommit(false);
            try (Statement statement = sweeper.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = 200");
            }

            holder.setAutoCommit(false);
            update(holder, runId, "FAILED", "RUNNING", 1);

            SQLException timeout = assertThrows(SQLException.class,
                    () -> update(sweeper, runId, "FAILED", "RUNNING", 1));
            assertEquals("55P03", timeout.getSQLState());
            sweeper.rollback();

            // holder 释放锁之后，同一个连接上的新事务不应再受上一次 SET LOCAL 影响
            holder.rollback();

            sweeper.setAutoCommit(false);
            long start = System.currentTimeMillis();
            assertEquals(1, update(sweeper, runId, "FAILED", "RUNNING", 1),
                    "锁已释放，本次更新应当命中");
            sweeper.commit();

            assertTrue(System.currentTimeMillis() - start < 5_000,
                    "SET LOCAL 只作用于上一个事务，不应把超时带到新事务");
        }
    }

    @Test
    @DisplayName("G7：赢家先提交 FAILED，输家带 state='RUNNING' 的 UPDATE 重新求值后拿 0 行")
    void blockedUpdateReevaluatesWhereAndReturnsZeroRows() throws Exception {
        String runId = insertRun("RUNNING", 1);

        try (Connection winner = open(); Connection loser = open()) {
            winner.setAutoCommit(false);
            loser.setAutoCommit(false);

            // 赢家（扫描器）：RUNNING → FAILED，尚未提交，持有行锁
            assertEquals(1, update(winner, runId, "FAILED", "RUNNING", 1));

            AtomicReference<Integer> rows = new AtomicReference<>();
            AtomicReference<SQLException> error = new AtomicReference<>();
            Thread blocked = startBlockedUpdateRows(loser, runId, "SUCCEEDED", rows, error);

            Thread.sleep(300);
            winner.commit();
            blocked.join(15_000);

            assertNull(error.get(), "输家应当正常返回而不是报错：" + error.get());
            assertEquals(0, rows.get(),
                    "PG 在锁释放后重新求值 WHERE，state 已不是 RUNNING，必须 0 行");
            loser.commit();

            assertEquals("FAILED", stateOf(runId), "终态不得被旧执行覆盖");
            System.out.println("[pg-evidence] staleTerminalUpdate rows=" + rows.get());
        }
    }

    // ==================== 辅助 ====================

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static String insertRun(String state, int epoch) throws SQLException {
        String runId = UUID.randomUUID().toString();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO " + table + " (run_id, state, execution_epoch) VALUES (?, ?, ?)")) {
            statement.setString(1, runId);
            statement.setString(2, state);
            statement.setInt(3, epoch);
            statement.executeUpdate();
        }
        return runId;
    }

    private static int update(
            Connection connection, String runId, String newState, String expectedState, int epoch)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + table + " SET state = ? "
                        + "WHERE run_id = ? AND state = ? AND execution_epoch = ?")) {
            statement.setString(1, newState);
            statement.setString(2, runId);
            statement.setString(3, expectedState);
            statement.setInt(4, epoch);
            return statement.executeUpdate();
        }
    }

    private static Thread startBlockedUpdate(
            Connection connection,
            String runId,
            String newState,
            AtomicReference<Long> elapsed,
            AtomicReference<SQLException> error
    ) {
        Thread thread = new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                connection.createStatement().execute("SET LOCAL lock_timeout = 300");
                update(connection, runId, newState, "RUNNING", 1);
            } catch (SQLException e) {
                error.set(e);
            } finally {
                elapsed.set(System.currentTimeMillis() - start);
            }
        }, "pg-recovery-sweeper");
        thread.start();
        return thread;
    }

    private static Thread startBlockedUpdateRows(
            Connection connection,
            String runId,
            String newState,
            AtomicReference<Integer> rows,
            AtomicReference<SQLException> error
    ) {
        Thread thread = new Thread(() -> {
            try {
                rows.set(update(connection, runId, newState, "RUNNING", 1));
            } catch (SQLException e) {
                error.set(e);
            }
        }, "pg-recovery-stale");
        thread.start();
        return thread;
    }

    private static String stateOf(String runId) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT state FROM " + table + " WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next(), "探针记录必须存在");
                return rs.getString(1);
            }
        }
    }
}
