package com.example.mkagent.music;

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
 * 事务边界由 {@link IdempotentWriteExecutor} 决定：
 * 本类的方法必须在那个事务里执行，否则"占位行 + 业务写入 + 成功结果"
 * 就不再是同生共死的关系。
 *
 * <h2>表结构约定</h2>
 *
 * 主键 {@code (user_id, idempotency_key)} 是并发裁决者：
 * 同一身份的两个执行者同时插入，只有一个能成功，
 * 另一个在唯一索引上等待赢家结束，然后拿到重复键错误。
 *
 * <h2>为什么没有 status 列</h2>
 *
 * 占位、业务写入、成功结果在**同一个事务**里提交，
 * 因此"行存在"就等价于"业务已提交且结果可回放"，不存在"占位但未完成"的中间态。
 * 加一个恒为 DONE 的状态列只会诱使别人写出"先占位、后补结果"的两段式，
 * 那才是真正需要状态机的地方——本批不需要。
 */
@Component
public class ToolIdempotencyStore {

    private static final Logger log =
            LoggerFactory.getLogger(ToolIdempotencyStore.class);

    private final JdbcTemplate jdbcTemplate;

    private final DataSource dataSource;

    /**
     * 等待竞争事务的上限（毫秒）。只对 PostgreSQL 生效，见 {@link #beginBoundedWait()}。
     */
    private final long lockTimeoutMillis;

    /**
     * 是否 PostgreSQL；null 表示尚未探测。
     */
    private volatile Boolean postgres;

    public ToolIdempotencyStore(
            JdbcTemplate jdbcTemplate,
            DataSource dataSource,
            @Value("${mkagent.music.idempotency.lock-timeout-millis:5000}")
            long lockTimeoutMillis
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.lockTimeoutMillis = lockTimeoutMillis;
    }

    /**
     * 幂等记录（只读投影）。
     */
    public record Record(String toolName, String requestHash, String resultJson) {
    }

    /**
     * 执行前的任务守卫信息。
     *
     * @param state 数据库里的任务状态（如 {@code RUNNING}）
     */
    public record RunGuard(String userId, int executionEpoch, String state) {
    }

    /**
     * 本次事务的锁等待上限。
     *
     * PostgreSQL：{@code SET LOCAL lock_timeout} 只影响**当前事务**，
     * 事务结束自动失效，不会污染连接池里的后续请求，也不改全局配置。
     * 唯一索引冲突时，第二个 INSERT 会在赢家的事务上等待；
     * 超过这个上限会抛锁超时（**不是** DuplicateKeyException），
     * 调用方按"结果不明/稍后重试"处理，不重复执行业务。
     *
     * H2：**没有** {@code SET LOCAL}，这里直接跳过，锁等待由连接上的
     * {@code LOCK_TIMEOUT} 决定（H2 2.3.232 默认 2000ms，
     * 本项目的测试数据源未修改该默认值）。注意两者的作用域并不等价：
     * H2 的 {@code SET LOCK_TIMEOUT} 是 **session 级**、回滚也不恢复，
     * 一旦在运行期临时改过，连接归还池后会把设置带给后续请求。
     * 因此本类在 H2 上**不**做任何运行期超时设置，只用测试数据源的默认值；
     * H2 用例证明的是"锁等待有界"这一行为，不能替代 PG 的
     * {@code SET LOCAL} 语义验证（后者只在 {@code @Tag("pg")} 用例里做）。
     *
     * 这里刻意**不**用"重试 N 次、每次 sleep"来声称有界：
     * 真正的等待发生在数据库里，Java 侧的 sleep 次数约束不了它。
     */
    public void beginBoundedWait() {
        if (!isPostgres()) {
            return;
        }
        /*
         * 值来自配置且已解析为 long，不存在注入面；
         * SET LOCAL 不支持占位符绑定。
         */
        jdbcTemplate.execute("SET LOCAL lock_timeout = " + lockTimeoutMillis);
    }

    /**
     * 读取任务归属与代次（**不加锁**，只用于事务外的只读校验，例如回放）。
     *
     * @return 任务不存在时返回 null（调用方失败关闭）
     */
    public RunGuard findRun(String runId) {
        return queryRun(runId, "");
    }

    /**
     * 读取任务归属、代次与状态，并对该行加排他行锁（{@code SELECT ... FOR UPDATE}）。
     *
     * <h2>为什么必须加锁，而不是"事务里 SELECT 一下"</h2>
     *
     * 代次推进（{@code ApprovalClaimService.claim}）是一条
     * {@code UPDATE agent_run SET execution_epoch = ? WHERE run_id = ? AND state = 'WAITING_APPROVAL'}。
     * 普通 SELECT 读到的代次只在读的那一瞬间成立：读完之后、
     * 业务写入提交之前，另一事务完全可能把代次推上去，
     * 于是"旧代次的执行"仍然会写出一次业务副作用。
     *
     * 加行锁之后，两条路径在**同一行**上串行化，只有两种线性化顺序：
     *
     * <ol>
     *   <li>本事务先拿到锁：代次推进的 UPDATE 会阻塞到本事务提交；
     *       届时 run 状态是 {@code RUNNING}，推进语句的
     *       {@code state = 'WAITING_APPROVAL'} 条件不成立，影响 0 行 → 推进失败。
     *       即：本次写在资格失效**之前**已经完整提交，属于合法完成。</li>
     *   <li>推进事务先拿到锁：本事务的加锁读会等它提交，读到的就是新代次，
     *       守卫立刻失败 → 旧执行写不出任何副作用。</li>
     * </ol>
     *
     * 锁顺序：本方法只锁 {@code agent_run} 这一行，之后才写幂等表与业务表；
     * 没有"先锁业务表、再锁 agent_run"的路径，因此不引入新的环。
     */
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

    /**
     * 插入占位记录。冲突（同一身份已有记录）时抛 DuplicateKeyException，
     * 由执行器在**明确的这一处**转成专用异常。
     */
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

    /**
     * 保存成功结果。
     *
     * @return 影响行数；调用方必须校验为 1
     */
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

    /**
     * 按身份读取已提交的记录。
     *
     * 必须在**冲突事务回滚之后**调用（执行器保证：这里是事务代理之外）。
     */
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

    /**
     * 回放前的资源归属复核：歌单当前是否仍属于该用户。
     *
     * 查不到（已删除）与不是本人的都返回 false——与
     * {@code MusicPlaylistWriteService.requireOwned} 的口径一致，
     * 不区分"不存在"和"不是你的"。
     */
    public boolean ownsPlaylist(String userId, long playlistId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_playlist WHERE id = ? AND user_id = ?",
                Integer.class,
                playlistId, userId
        );
        return count != null && count > 0;
    }

    /**
     * 当前数据库是否 PostgreSQL（探测一次后缓存）。
     */
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
