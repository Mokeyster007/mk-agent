package com.example.mkagent.music;

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
 * PostgreSQL 专项：唯一索引上的等待、提交、回滚语义，以及代次守卫依赖的行锁行为。
 *
 * <b>默认构建不会执行</b>（surefire 的 excludedGroups 含 {@code pg}）。
 * 运行方式（需要一个可连接的 PostgreSQL，且**不碰**业务表）：
 *
 * <pre>
 * MKAGENT_PG_URL=jdbc:postgresql://localhost:5432/mk_agent \
 * MKAGENT_PG_USER=postgres MKAGENT_PG_PASSWORD=*** \
 * ./mvnw -o test -Dmkagent.test.excluded-groups=live -Dgroups=pg \
 *     -Dtest=PostgresIdempotencyConcurrencyTest
 * </pre>
 *
 * <h2>隔离方式</h2>
 *
 * 用例只在本类自己建的临时探针表上操作：表名带随机后缀，
 * {@code @BeforeAll} 建、{@code @AfterAll} 删，不读写任何业务表，
 * 也不修改全局数据库配置（{@code SET LOCAL} 只作用于当前事务）。
 * 因此 {@code MKAGENT_PG_URL} 必须指向一个**独立的测试库**，
 * 不要指向业务库；本类不建独立 schema，靠随机表名隔离。
 * 若执行被中断，可能残留 {@code mk_idem_probe_*} / {@code mk_run_probe_*} 两张表，
 * 手工 {@code DROP TABLE} 即可（不要在生产库跑本类）。
 *
 * 为什么这些必须在真 PG 上验证：
 * H2（即使 MODE=PostgreSQL）在约束冲突后允许继续使用同一个事务，
 * 而 PG 会把它变成 aborted——正是这个差异决定了
 * "冲突后必须换新事务读取"这条实现约束。H2 全绿证明不了这件事。
 */
@Tag("pg")
class PostgresIdempotencyConcurrencyTest {

    private static final String URL = System.getenv().getOrDefault(
            "MKAGENT_PG_URL", "jdbc:postgresql://localhost:5432/mk_agent");

    private static final String USER = System.getenv().getOrDefault(
            "MKAGENT_PG_USER", "postgres");

    private static final String PASSWORD = System.getenv().getOrDefault(
            "MKAGENT_PG_PASSWORD", "");

    private static String table;

    private static String runTable;

    @BeforeAll
    static void createProbeTable() throws SQLException {
        table = "mk_idem_probe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        runTable = "mk_run_probe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " ("
                    + "user_id VARCHAR(64) NOT NULL, "
                    + "idempotency_key VARCHAR(128) NOT NULL, "
                    + "result_json TEXT, "
                    + "PRIMARY KEY (user_id, idempotency_key))");

            // 模拟 agent_run 的最小结构：代次守卫依赖的行锁在这里验证
            statement.execute("CREATE TABLE " + runTable + " ("
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
            statement.execute("DROP TABLE IF EXISTS " + runTable);
        }
    }

    @Test
    @DisplayName("并发插入同一主键：第二个连接会等待赢家，然后拿到 23505")
    void secondInsertWaitsThenConflicts() throws Exception {
        try (Connection winner = open(); Connection loser = open()) {
            winner.setAutoCommit(false);
            loser.setAutoCommit(false);

            insert(winner, "u", "k1");

            AtomicReference<Long> elapsed = new AtomicReference<>();
            AtomicReference<SQLException> error = new AtomicReference<>();
            Thread waiter = startWaiter(loser, "u", "k1", elapsed, error);

            Thread.sleep(300);
            winner.commit();
            waiter.join(10_000);

            assertNotNull(error.get(), "第二个连接必须拿到重复键错误");
            assertEquals("23505", error.get().getSQLState(),
                    "必须是唯一键冲突，而不是锁超时：" + error.get().getMessage());
            assertTrue(elapsed.get() >= 250,
                    "第二个 INSERT 应当先等待赢家，实际等待 " + elapsed.get() + "ms");
            System.out.println("[pg-evidence] secondInsert waited=" + elapsed.get()
                    + "ms sqlstate=" + error.get().getSQLState());
        }
    }

    @Test
    @DisplayName("赢家回滚：等待者成为真正的执行者")
    void waiterBecomesExecutorWhenWinnerRollsBack() throws Exception {
        try (Connection winner = open(); Connection waiter = open()) {
            winner.setAutoCommit(false);
            waiter.setAutoCommit(false);

            insert(winner, "u", "k2");

            AtomicReference<Long> elapsed = new AtomicReference<>();
            AtomicReference<SQLException> error = new AtomicReference<>();
            Thread thread = startWaiter(waiter, "u", "k2", elapsed, error);

            Thread.sleep(300);
            winner.rollback();
            thread.join(10_000);

            assertEquals(null, error.get(),
                    "赢家回滚后，等待者应当插入成功：" + error.get());
            waiter.commit();

            assertEquals(1, count("u", "k2"), "最终只应存在一条记录");
        }
    }

    @Test
    @DisplayName("冲突事务已 aborted：同一事务里再读会失败，必须换新事务")
    void abortedTransactionCannotRead() throws Exception {
        try (Connection connection = open()) {
            connection.setAutoCommit(true);
            insert(connection, "u", "k3");

            connection.setAutoCommit(false);
            assertThrows(SQLException.class, () -> insert(connection, "u", "k3"));

            // PG 特性：约束冲突后整个事务进入 aborted，后续语句一律失败
            assertThrows(SQLException.class, () -> countOn(connection, "u", "k3"),
                    "aborted 事务里不能再执行查询");

            connection.rollback();
            connection.setAutoCommit(true);

            assertEquals(1, count("u", "k3"), "换新事务后能读到赢家已提交的行");
        }
    }

    @Test
    @DisplayName("赢家提交：输家退出失败事务后，用干净事务读到赢家结果并回放")
    void loserReplaysWinnerResultAfterRollback() throws Exception {
        try (Connection winner = open(); Connection loser = open()) {
            winner.setAutoCommit(false);
            loser.setAutoCommit(false);

            insert(winner, "u", "k5", "{\"ok\":true,\"winner\":true}");
            winner.commit();

            SQLException conflict = assertThrows(SQLException.class,
                    () -> insert(loser, "u", "k5", "{\"ok\":true,\"winner\":false}"));
            assertEquals("23505", conflict.getSQLState());

            // 失败事务必须整体退出，不能在 aborted 事务里继续读
            assertThrows(SQLException.class, () -> countOn(loser, "u", "k5"));
            loser.rollback();

            // 干净事务读取：拿到的必须是赢家提交的那份结果
            assertEquals("{\"ok\":true,\"winner\":true}", resultOf("u", "k5"));
            assertEquals(1, count("u", "k5"), "输家没有提交任何行");
        }
    }

    @Test
    @DisplayName("行锁串行化代次推进：真实 claim CAS 影响 0 行；无条件推进必须阻塞到写事务提交")
    void rowLockBlocksEpochAdvanceUntilCommit() throws Exception {
        try (Connection writer = open(); Connection claimer = open()) {
            try (Statement statement = writer.createStatement()) {
                statement.executeUpdate("INSERT INTO " + runTable
                        + " (run_id, state, execution_epoch) VALUES ('r-lock', 'RUNNING', 0)");
            }

            writer.setAutoCommit(false);
            try (PreparedStatement lock = writer.prepareStatement(
                    "SELECT state, execution_epoch FROM " + runTable
                            + " WHERE run_id = ? FOR UPDATE")) {
                lock.setString(1, "r-lock");
                lock.executeQuery();
            }

            /*
             * ① 生产里真正的推进语句（ApprovalClaimService.claim 的 CAS）：
             *    条件是 state='WAITING_APPROVAL'。写事务进行中 run 状态是 RUNNING，
             *    这条语句不需要等锁就已经影响 0 行——代次不会推进。
             *    （PG 只对通过 WHERE 的行加锁，所以这里本来就不会阻塞；
             *      用"必须阻塞"去断言它，是把锁语义理解错了。）
             */
            int claimRows;
            try (PreparedStatement update = claimer.prepareStatement(
                    "UPDATE " + runTable + " SET state = 'RUNNING', execution_epoch = 1 "
                            + "WHERE run_id = ? AND state = 'WAITING_APPROVAL' "
                            + "AND execution_epoch = 0")) {
                update.setString(1, "r-lock");
                claimRows = update.executeUpdate();
            }
            assertEquals(0, claimRows, "写事务未结束、run 不是 WAITING_APPROVAL，推进必须 0 行");
            assertEquals(0, epochOf("r-lock"), "代次不得被推进");

            /*
             * ② 真正会与行锁竞争的是"无条件更新这一行"（例如其它写 agent_run 的路径）：
             *    它必须阻塞到写事务提交，证明两条路径在同一行上串行化。
             */
            AtomicReference<Integer> rows = new AtomicReference<>();
            AtomicReference<SQLException> error = new AtomicReference<>();
            Thread advance = new Thread(() -> {
                try {
                    claimer.setAutoCommit(false);
                    try (PreparedStatement update = claimer.prepareStatement(
                            "UPDATE " + runTable + " SET execution_epoch = 1 WHERE run_id = ?")) {
                        update.setString(1, "r-lock");
                        rows.set(update.executeUpdate());
                    }
                    claimer.commit();
                } catch (SQLException e) {
                    error.set(e);
                }
            }, "pg-epoch-advance");
            advance.start();

            Thread.sleep(300);
            assertTrue(advance.isAlive(), "无条件推进必须阻塞在行锁上");

            writer.commit();
            advance.join(10_000);

            assertNull(error.get(), "推进不应报错：" + error.get());
            assertEquals(1, rows.get(), "写事务提交后推进才生效");
            assertEquals(1, epochOf("r-lock"), "提交后推进落地");
        }
    }

    @Test
    @DisplayName("SET LOCAL lock_timeout 限制等待，且回滚/提交后都恢复为连接原始基线")
    void setLocalLockTimeoutBoundsWaitAndDoesNotLeak() throws Exception {
        try (Connection holder = open(); Connection other = open()) {
            /*
             * 基线必须在设置之前、同一条连接上读取：
             * 目标库的默认 lock_timeout 未必是 0，把 "0" 当契约就是硬编码环境值。
             */
            other.setAutoCommit(true);
            String baseline = lockTimeout(other);

            holder.setAutoCommit(false);
            insert(holder, "u", "k4");

            // ---------- 路径 1：事务回滚 ----------
            other.setAutoCommit(false);
            try (Statement statement = other.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = 300");
            }
            assertEquals("300ms", lockTimeout(other),
                    "SET LOCAL 必须在本事务内生效");

            long start = System.currentTimeMillis();
            SQLException error = assertThrows(SQLException.class,
                    () -> insert(other, "u", "k4"));
            long elapsed = System.currentTimeMillis() - start;

            assertEquals("55P03", error.getSQLState(),
                    "应当是 lock timeout，而不是唯一键冲突：" + error.getMessage());
            assertTrue(elapsed < 5_000,
                    "等待必须有界（300ms 设置 + 连接开销），实际 " + elapsed + "ms");
            assertTrue(elapsed >= 250,
                    "必须先真实等待再超时，实际 " + elapsed + "ms");
            System.out.println("[pg-evidence] lockTimeout waited=" + elapsed
                    + "ms sqlstate=" + error.getSQLState()
                    + " baseline=" + baseline);

            other.rollback();
            other.setAutoCommit(true);
            assertEquals(baseline, lockTimeout(other),
                    "回滚后 SET LOCAL 必须失效，恢复到基线 " + baseline);

            // ---------- 路径 2：事务提交 ----------
            other.setAutoCommit(false);
            try (Statement statement = other.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = 400");
                statement.execute("SELECT 1");
            }
            other.commit();
            other.setAutoCommit(true);
            assertEquals(baseline, lockTimeout(other),
                    "提交后 SET LOCAL 同样必须失效，恢复到基线 " + baseline);

            /*
             * 赢家必须**提交**，否则下面断言的"只剩一行"就无从谈起
             * （原写法在这里回滚，等于把赢家自己删掉，断言恒为 0）。
             */
            holder.commit();

            /*
             * 超时的那次插入不得留下任何提交：
             * 只应存在赢家那一行，且结果仍是赢家写的值。
             */
            assertEquals(1, count("u", "k4"), "超时方不得提交任何行");
            assertEquals("{\"ok\":true}", resultOf("u", "k4"),
                    "结果必须是赢家写入的值");
        }
    }

    // ==================== 辅助 ====================

    private static String lockTimeout(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW lock_timeout")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static Thread startWaiter(
            Connection connection,
            String userId,
            String key,
            AtomicReference<Long> elapsed,
            AtomicReference<SQLException> error
    ) {
        Thread thread = new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                insert(connection, userId, key);
            } catch (SQLException e) {
                error.set(e);
            } finally {
                elapsed.set(System.currentTimeMillis() - start);
            }
        }, "pg-waiter");
        thread.start();
        return thread;
    }

    private static void insert(Connection connection, String userId, String key)
            throws SQLException {
        insert(connection, userId, key, "{\"ok\":true}");
    }

    private static void insert(
            Connection connection, String userId, String key, String resultJson)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + table + " (user_id, idempotency_key, result_json) "
                        + "VALUES (?, ?, ?)")) {
            statement.setString(1, userId);
            statement.setString(2, key);
            statement.setString(3, resultJson);
            statement.executeUpdate();
        }
    }

    private static int count(String userId, String key) throws SQLException {
        try (Connection connection = open()) {
            return countOn(connection, userId, key);
        }
    }

    private static String resultOf(String userId, String key) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT result_json FROM " + table
                             + " WHERE user_id = ? AND idempotency_key = ?")) {
            statement.setString(1, userId);
            statement.setString(2, key);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next(), "记录必须存在");
                return rs.getString(1);
            }
        }
    }

    private static int epochOf(String runId) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT execution_epoch FROM " + runTable + " WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next(), "探针任务必须存在");
                return rs.getInt(1);
            }
        }
    }

    private static int countOn(Connection connection, String userId, String key)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id = ? AND idempotency_key = ?")) {
            statement.setString(1, userId);
            statement.setString(2, key);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
