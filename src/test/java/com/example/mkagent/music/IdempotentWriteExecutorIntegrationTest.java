package com.example.mkagent.music;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.music.IdempotentWriteExecutor.Call;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.tools.PlaylistWriteTool;
import com.example.mkagent.tools.ToolResponseEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.support.AopUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等执行器的事务语义（真实 Spring 事务 + 真实 H2 + 真实业务写入）。
 *
 * 这里**不用 mock 的调用次数**证明原子性：每条用例都断言数据库最终状态
 * （业务表有没有行、幂等记录有没有行、结果是什么），
 * 因为"方法被调了几次"和"数据到底提交了没有"是两件事。
 */
@HermeticSpringBootTest
@Timeout(120)
class IdempotentWriteExecutorIntegrationTest {

    private static final String USER = MusicSeedInitializer.USER_ALICE;

    private static final String OTHER = MusicSeedInitializer.USER_BOB;

    private static final String NAME_PREFIX = "幂等-";

    private static final long SONG = 1001L;

    @Autowired
    private IdempotentWriteExecutor executor;

    @Autowired
    private IdempotencyReplayService replayService;

    @Autowired
    private ToolIdempotencyStore store;

    @Autowired
    private PlaylistWriteTool playlistWriteTool;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> runIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (String runId : runIds) {
            jdbcTemplate.update(
                    "DELETE FROM music_tool_idempotency WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM agent_run WHERE run_id = ?", runId);
        }
        runIds.clear();

        jdbcTemplate.update(
                "DELETE FROM music_playlist_song WHERE playlist_id IN "
                        + "(SELECT id FROM music_playlist WHERE name LIKE ?)",
                NAME_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM music_playlist WHERE name LIKE ?", NAME_PREFIX + "%");
    }

    // ==================== 1. 正常提交与回放 ====================

    @Test
    @DisplayName("首次执行：业务写入与幂等记录一起提交，结果可原样回放")
    void firstCallCommitsBusinessAndRecord() {
        String runId = newRun(0);
        String name = uniqueName();
        Call call = createCall(runId, 0, "call-1", name, args(name));

        String result = executor.execute(call);

        assertTrue(ToolResponseEnvelope.isSuccess(result), result);
        assertEquals(1, playlistCount(name), "业务写入必须提交");
        assertEquals(1, recordCount(call.idempotencyKey()), "幂等记录必须提交");

        Map<String, Object> record = record(call.idempotencyKey());
        assertEquals("create_playlist", record.get("tool_name"));
        assertEquals(call.requestHash(), record.get("request_hash"));
        assertEquals(runId, record.get("run_id"));
        assertEquals("call-1", record.get("tool_call_id"));
        assertEquals(result, record.get("result_json"), "结果必须原样保存");
    }

    @Test
    @DisplayName("同一身份再次到达：冲突后回放，业务只写一次")
    void replayReturnsIdenticalResultWithoutSecondWrite() {
        String runId = newRun(0);
        String name = uniqueName();
        Call call = createCall(runId, 0, "call-1", name, args(name));

        String first = executor.execute(call);

        assertThrows(IdempotentWriteExecutor.IdempotencyRecordConflictException.class,
                () -> executor.execute(call));

        String replayed = replayService.replay(call);

        assertEquals(first, replayed, "回放必须与首次结果完全一致");
        assertEquals(1, playlistCount(name), "回放不得再次写业务表");
        assertEquals(1, recordCount(call.idempotencyKey()));
    }

    // ==================== 2. 回滚 ====================

    @Test
    @DisplayName("业务返回失败信封：占位记录一起回滚，不留半截")
    void businessErrorEnvelopeRollsBackPlaceholder() {
        String runId = newRun(0);
        String name = uniqueName();
        // 先手工建一个同名歌单，让真实工具走到"同名"分支
        insertPlaylist(USER, name);

        Call call = createCall(runId, 0, "call-1", name, args(name));

        IdempotentWriteExecutor.BusinessEnvelopeFailure failure = assertThrows(
                IdempotentWriteExecutor.BusinessEnvelopeFailure.class,
                () -> executor.execute(call));

        assertTrue(failure.getEnvelope().contains("同名歌单"),
                "原始错误信封必须原样带出：" + failure.getEnvelope());
        assertEquals(0, recordCount(call.idempotencyKey()), "失败不得留下幂等记录");
        assertEquals(1, playlistCount(name), "业务表只能有手工建的那一条");
    }

    @Test
    @DisplayName("业务抛异常：业务写入与占位记录一起回滚")
    void businessExceptionRollsBackEverything() {
        String runId = newRun(0);
        String name = uniqueName();
        Call call = withBusiness(runId, 0, "call-1", name, args(name), () -> {
            throw new IllegalStateException("模拟业务故障");
        });

        assertThrows(IllegalStateException.class, () -> executor.execute(call));

        assertEquals(0, playlistCount(name), "业务写入必须回滚");
        assertEquals(0, recordCount(call.idempotencyKey()), "占位记录必须回滚");
    }

    @Test
    @DisplayName("成功结果更新影响 0 行：整体回滚，绝不提交没有记录保护的业务写入")
    void zeroRowsOnSaveResultRollsBackBusiness() {
        String runId = newRun(0);
        String name = uniqueName();
        String key = IdempotencyKeys.callKey(
                USER, UUID.fromString(runId), 1, "call-1");

        Call call = withBusiness(runId, 0, "call-1", name, args(name), () -> {
            long playlistId = createPlaylist(name);
            // 在同一事务里把占位行删掉，制造"更新影响 0 行"
            jdbcTemplate.update(
                    "DELETE FROM music_tool_idempotency WHERE user_id = ? AND idempotency_key = ?",
                    USER, key);
            return ToolResponseEnvelope.okSingle(
                    Map.of("playlistId", playlistId, "name", name, "songCount", 1));
        });

        assertThrows(IdempotentWriteExecutor.IdempotencyRecordLostException.class,
                () -> executor.execute(call));

        assertEquals(0, playlistCount(name), "业务写入必须随事务回滚");
        assertEquals(0, recordCount(key));
    }

    // ==================== 3. 冲突分类 ====================

    @Test
    @DisplayName("同一身份、不同请求哈希：冲突，不执行也不回放")
    void sameKeyDifferentHashIsConflict() {
        String runId = newRun(0);
        String name = uniqueName();

        Call first = createCall(runId, 0, "call-1", name, args(name));
        executor.execute(first);

        Call tampered = new Call(
                USER,
                UUID.fromString(runId),
                1,
                "call-1",
                "create_playlist",
                first.idempotencyKey(),
                IdempotencyKeys.requestHash("create_playlist", args(name + "-改")),
                0,
                first.business()
        );

        assertThrows(IdempotentWriteExecutor.IdempotencyRecordConflictException.class,
                () -> executor.execute(tampered));

        assertThrows(IdempotencyReplayService.IdempotencyConflictException.class,
                () -> replayService.replay(tampered));

        assertEquals(1, playlistCount(name), "冲突方不得执行业务");
        assertEquals(0, playlistCount(name + "-改"));
    }

    @Test
    @DisplayName("业务唯一键冲突不是幂等命中：不写幂等记录、不回放")
    void businessUniqueViolationIsNotIdempotencyHit() {
        String runId = newRun(0);
        String name = uniqueName();
        insertPlaylist(USER, name);

        Call call = createCall(runId, 0, "call-1", name, args(name));

        assertThrows(IdempotentWriteExecutor.BusinessEnvelopeFailure.class,
                () -> executor.execute(call));

        assertEquals(0, recordCount(call.idempotencyKey()),
                "业务唯一键冲突不能变成幂等记录");
    }

    // ==================== 4. 代次与身份 ====================

    @Test
    @DisplayName("旧代次不得创建新的业务副作用")
    void staleEpochIsRejected() {
        String runId = newRun(1);
        String name = uniqueName();
        Call call = createCall(runId, 0, "call-1", name, args(name));

        IdempotentWriteExecutor.WriteRejectedException error = assertThrows(
                IdempotentWriteExecutor.WriteRejectedException.class,
                () -> executor.execute(call));

        assertTrue(error.getMessage().contains("代次"), error.getMessage());
        assertEquals(0, playlistCount(name), "旧代次不得写业务表");
        assertEquals(0, recordCount(call.idempotencyKey()), "旧代次不得留下记录");
    }

    @Test
    @DisplayName("跨 epoch 身份稳定：新代次重跑同一调用只会回放，不会二次写入")
    void identityIsStableAcrossEpochs() {
        String runId = newRun(0);
        String name = uniqueName();
        Call call = createCall(runId, 0, "call-1", name, args(name));

        String first = executor.execute(call);

        // 模拟一次恢复：代次 +1
        jdbcTemplate.update(
                "UPDATE agent_run SET execution_epoch = 1 WHERE run_id = ?", runId);

        Call resumed = createCall(runId, 1, "call-1", name, args(name));

        assertEquals(call.idempotencyKey(), resumed.idempotencyKey(),
                "身份不含 epoch，跨恢复必须保持不变");

        assertThrows(IdempotentWriteExecutor.IdempotencyRecordConflictException.class,
                () -> executor.execute(resumed));

        assertEquals(first, replayService.replay(resumed));
        assertEquals(1, playlistCount(name), "新代次不得二次写入");
    }

    // ==================== 4b. 代次守卫的并发语义 ====================

    @Test
    @DisplayName("写入进行中推进代次：推进被行锁挡住，本次写在资格失效前完整提交")
    void epochAdvanceWaitsForInFlightWrite() throws Exception {
        String runId = newRun(0);
        String name = uniqueName();

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> writeError = new AtomicReference<>();

        Call call = withBusiness(runId, 0, "call-epoch", name, args(name), () -> {
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS), "测试自身等待超时");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return businessCall(name);
        });

        Thread writer = new Thread(() -> {
            try {
                executor.execute(call);
            } catch (Throwable t) {
                writeError.set(t);
            }
        }, "idem-writer");
        writer.start();

        assertTrue(entered.await(10, TimeUnit.SECONDS), "写事务未进入业务阶段");

        AtomicReference<Integer> bumpedRows = new AtomicReference<>();
        AtomicReference<Throwable> bumpError = new AtomicReference<>();
        Thread bumper = new Thread(() -> {
            try {
                bumpedRows.set(jdbcTemplate.update(
                        "UPDATE agent_run SET execution_epoch = 1 "
                                + "WHERE run_id = ? AND state = 'RUNNING'",
                        runId));
            } catch (Throwable t) {
                bumpError.set(t);
            }
        }, "epoch-bumper");
        bumper.start();

        /*
         * 写事务已经用 SELECT ... FOR UPDATE 锁住 agent_run 这一行：
         * 推进代次的 UPDATE 必须在这里等待，而不是"读完之后悄悄生效"。
         */
        Thread.sleep(300);
        assertTrue(bumper.isAlive(), "代次推进必须阻塞在行锁上，而不是立即生效");

        release.countDown();
        writer.join(10_000);
        bumper.join(10_000);

        assertNull(writeError.get(), "先拿到行锁的写事务应被允许完成：" + writeError.get());
        assertNull(bumpError.get(), "推进不应失败：" + bumpError.get());
        assertEquals(1, bumpedRows.get(), "推进在写事务提交之后才生效");
        assertEquals(1, playlistCount(name), "业务只写一次");
        assertEquals(1, recordCount(call.idempotencyKey()), "幂等记录只有一条");
    }

    @Test
    @DisplayName("代次先被推进：旧代次连守卫都过不了，零副作用")
    void epochAdvancedFirstRejectsStaleExecution() {
        String runId = newRun(0);
        String name = uniqueName();

        jdbcTemplate.update(
                "UPDATE agent_run SET execution_epoch = 1 WHERE run_id = ?", runId);

        Call stale = createCall(runId, 0, "call-stale", name, args(name));

        IdempotentWriteExecutor.WriteRejectedException error = assertThrows(
                IdempotentWriteExecutor.WriteRejectedException.class,
                () -> executor.execute(stale));

        assertTrue(error.getMessage().contains("代次"), error.getMessage());
        assertEquals(0, playlistCount(name), "旧代次不得写业务表");
        assertEquals(0, recordCount(stale.idempotencyKey()), "旧代次不得留下幂等记录");
    }

    @Test
    @DisplayName("任务不在执行状态（例如已暂停）：即便代次相同也拒绝写入")
    void nonRunningRunRejectsWrite() {
        String runId = newRun(0);
        String name = uniqueName();

        jdbcTemplate.update(
                "UPDATE agent_run SET state = 'WAITING_APPROVAL' WHERE run_id = ?", runId);

        Call call = createCall(runId, 0, "call-paused", name, args(name));

        IdempotentWriteExecutor.WriteRejectedException error = assertThrows(
                IdempotentWriteExecutor.WriteRejectedException.class,
                () -> executor.execute(call));

        assertTrue(error.getMessage().contains("执行状态"), error.getMessage());
        assertEquals(0, playlistCount(name), "非执行状态不得写业务表");
        assertEquals(0, recordCount(call.idempotencyKey()), "非执行状态不得留下幂等记录");
    }

    @Test
    @DisplayName("任务归属不是当前用户：拒绝写入，零副作用")
    void foreignRunRejectsWrite() {
        String runId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO agent_run (id, run_id, user_id, agent_type, state, execution_epoch) "
                        + "VALUES (?, ?, ?, 'MUSIC_USER', 'RUNNING', 0)",
                Math.abs(UUID.randomUUID().getMostSignificantBits()), runId, OTHER);
        runIds.add(runId);

        String name = uniqueName();
        Call call = createCall(runId, 0, "call-foreign", name, args(name));

        IdempotentWriteExecutor.WriteRejectedException error = assertThrows(
                IdempotentWriteExecutor.WriteRejectedException.class,
                () -> executor.execute(call));

        assertTrue(error.getMessage().contains("归属"), error.getMessage());
        assertEquals(0, playlistCount(name), "别人的任务不得写业务表");
    }

    // ==================== 5. 回放权限 ====================

    @Test
    @DisplayName("回放时歌单已转给别人：返回权限错误，不泄露旧结果")
    void replayDeniedWhenPlaylistTransferred() {
        String runId = newRun(0);
        String name = uniqueName();
        Call call = createCall(runId, 0, "call-1", name, args(name));

        String result = executor.execute(call);
        Long playlistId = ToolResponseEnvelope.playlistIdOf(result);
        assertNotNull(playlistId, result);

        jdbcTemplate.update(
                "UPDATE music_playlist SET user_id = ? WHERE id = ?", OTHER, playlistId);

        assertThrows(IdempotencyReplayService.IdempotencyReplayDeniedException.class,
                () -> replayService.replay(call));
    }

    @Test
    @DisplayName("历史记录没有结果 / 结果不是成功信封：失败关闭，不当成成功回放")
    void legacyRowWithoutValidResultFailsClosed() {
        String runId = newRun(0);
        String name = uniqueName();
        Call call = createCall(runId, 0, "call-1", name, args(name));

        jdbcTemplate.update(
                "INSERT INTO music_tool_idempotency "
                        + "(user_id, idempotency_key, tool_name, request_hash, "
                        + " run_id, step, tool_call_id, result_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
                USER, call.idempotencyKey(), call.toolName(), call.requestHash(),
                runId, 1, "call-1");

        assertThrows(IdempotencyReplayService.IdempotencyReplayInvalidException.class,
                () -> replayService.replay(call));
        assertEquals(0, playlistCount(name), "不得因为记录不可回放就重新执行业务");
    }

    @Test
    @DisplayName("历史记录存的是失败信封：同样失败关闭")
    void legacyFailureEnvelopeFailsClosed() {
        String runId = newRun(0);
        String name = uniqueName();
        Call call = createCall(runId, 0, "call-1", name, args(name));

        jdbcTemplate.update(
                "INSERT INTO music_tool_idempotency "
                        + "(user_id, idempotency_key, tool_name, request_hash, "
                        + " run_id, step, tool_call_id, result_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                USER, call.idempotencyKey(), call.toolName(), call.requestHash(),
                runId, 1, "call-1",
                ToolResponseEnvelope.error("历史失败"));

        assertThrows(IdempotencyReplayService.IdempotencyReplayInvalidException.class,
                () -> replayService.replay(call));
    }

    // ==================== 6. 并发 ====================

    @Test
    @DisplayName("同 key 同 hash 并发：只有一个执行者真正写业务，两边结果一致")
    void concurrentSameKeyExecutesOnce() throws Exception {
        String runId = newRun(0);
        String name = uniqueName();

        AtomicInteger businessCalls = new AtomicInteger();
        AtomicReference<String> winnerResult = new AtomicReference<>();
        AtomicReference<Throwable> loserError = new AtomicReference<>();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Runnable task = () -> {
            Call call = withBusiness(runId, 0, "call-1", name, args(name), () -> {
                businessCalls.incrementAndGet();
                long playlistId = createPlaylist(name);
                return ToolResponseEnvelope.okSingle(
                        Map.of("playlistId", playlistId, "name", name, "songCount", 1));
            });
            try {
                start.await(10, TimeUnit.SECONDS);
                winnerResult.set(executor.execute(call));
            } catch (Throwable t) {
                loserError.set(t);
            } finally {
                done.countDown();
            }
        };

        Thread a = new Thread(task, "idem-a");
        Thread b = new Thread(task, "idem-b");
        a.start();
        b.start();
        start.countDown();

        assertTrue(done.await(30, TimeUnit.SECONDS), "并发执行未在超时内结束");
        a.join();
        b.join();

        assertEquals(1, businessCalls.get(), "业务只允许被执行一次");
        assertEquals(1, playlistCount(name), "业务表只允许一行");

        Throwable error = loserError.get();
        assertNotNull(error, "必须有一方拿到冲突");
        assertTrue(error instanceof IdempotentWriteExecutor.IdempotencyRecordConflictException,
                "输家必须是幂等冲突，实际：" + error);

        Call call = createCall(runId, 0, "call-1", name, args(name));
        assertEquals(winnerResult.get(), replayService.replay(call),
                "输家回放的结果必须与赢家一致");
    }

    // ==================== 7. 装配 ====================

    @Test
    @DisplayName("执行器必须是 Spring 代理，否则 @Transactional 不生效")
    void executorIsAopProxy() {
        assertTrue(AopUtils.isAopProxy(executor),
                "IdempotentWriteExecutor 必须由容器注入代理");
    }

    // ==================== 辅助 ====================

    /**
     * MCP 自动配置在测试 profile 下被排除，chatApp 需要的
     * ToolCallbackProvider 由此空桩提供（与其它 Hermetic 测试一致）。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class McpStubConfig {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }

    private Call createCall(
            String runId, int epoch, String callId, String name, String argsJson) {
        return withBusiness(runId, epoch, callId, name, argsJson, () -> businessCall(name));
    }

    private Call withBusiness(
            String runId,
            int epoch,
            String callId,
            String name,
            String argsJson,
            java.util.function.Supplier<String> business
    ) {
        UUID uuid = UUID.fromString(runId);
        return new IdempotentWriteExecutor.Call(
                USER,
                uuid,
                1,
                callId,
                "create_playlist",
                IdempotencyKeys.callKey(USER, uuid, 1, callId),
                IdempotencyKeys.requestHash("create_playlist", argsJson),
                epoch,
                business
        );
    }

    /**
     * 走**真实**写工具：参数校验、归属校验、同名判断、事务代理全都在。
     *
     * 返回工具自己的信封（成功或失败都原样返回），
     * 这样"业务失败"走的就是工具真实产出的那条路径。
     */
    private String businessCall(String name) {
        AgentRunContext ctx = new AgentRunContext();
        ctx.setUserId(USER);

        AgentRunContext previous = AgentRunContextHolder.bind(ctx);
        try {
            return playlistWriteTool.createPlaylist(name, List.of(SONG));
        } finally {
            AgentRunContextHolder.restore(previous);
        }
    }

    /**
     * 需要拿到歌单 ID 的用例：信封不是成功时直接失败，避免测试自己吞掉错误。
     */
    private long createPlaylist(String name) {
        String envelope = businessCall(name);
        Long playlistId = ToolResponseEnvelope.playlistIdOf(envelope);
        if (playlistId == null) {
            throw new IllegalStateException("创建歌单失败：" + envelope);
        }
        return playlistId;
    }

    private String args(String name) {
        return "{\"name\":\"" + name + "\",\"songIds\":[" + SONG + "]}";
    }

    private String uniqueName() {
        return NAME_PREFIX + UUID.randomUUID().toString().substring(0, 8);
    }

    private String newRun(int epoch) {
        String runId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO agent_run (id, run_id, user_id, agent_type, state, execution_epoch) "
                        + "VALUES (?, ?, ?, 'MUSIC_USER', 'RUNNING', ?)",
                Math.abs(UUID.randomUUID().getMostSignificantBits()), runId, USER, epoch);
        runIds.add(runId);
        return runId;
    }

    private void insertPlaylist(String userId, String name) {
        jdbcTemplate.update(
                "INSERT INTO music_playlist (user_id, name, description, is_public) "
                        + "VALUES (?, ?, NULL, FALSE)",
                userId, name);
    }

    private int playlistCount(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_playlist WHERE user_id = ? AND name = ?",
                Integer.class, USER, name);
        return count == null ? 0 : count;
    }

    private int recordCount(String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_tool_idempotency "
                        + "WHERE user_id = ? AND idempotency_key = ?",
                Integer.class, USER, key);
        return count == null ? 0 : count;
    }

    private Map<String, Object> record(String key) {
        return jdbcTemplate.queryForMap(
                "SELECT tool_name, request_hash, run_id, step, tool_call_id, result_json "
                        + "FROM music_tool_idempotency "
                        + "WHERE user_id = ? AND idempotency_key = ?",
                USER, key);
    }

    @Test
    @DisplayName("记录里保存了完整的调用追踪字段，便于排查")
    void recordKeepsTraceFields() {
        String runId = newRun(0);
        String name = uniqueName();
        Call call = createCall(runId, 0, "call-trace", name, args(name));

        executor.execute(call);

        Map<String, Object> record = record(call.idempotencyKey());
        assertEquals(1, ((Number) record.get("step")).intValue());
        assertEquals("call-trace", record.get("tool_call_id"));
        assertEquals(runId, record.get("run_id"));
        assertEquals(call.requestHash(), record.get("request_hash"),
                "request_hash 必须持久化：回放冲突判断只认它");
    }
}
