package com.example.mkagent.idempotency;

import com.example.mkagent.idempotency.IdempotentWriteExecutor.Call;
import com.example.mkagent.idempotency.IdempotentWriteExecutor.IdempotencyRecordConflictException;
import com.example.mkagent.idempotency.IdempotentWriteExecutor.WriteRejectedException;
import com.example.mkagent.shop.ShopSeedInitializer;
import com.example.mkagent.shop.order.ShopOrderService;
import com.example.mkagent.support.HermeticSpringBootTest;
import com.example.mkagent.tools.ToolResponseEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用写工具幂等执行器的事务语义回归（真实 Spring 事务 + 真实 H2 + 真实商城订单写入）。
 */
@HermeticSpringBootTest
@Timeout(120)
class IdempotentWriteExecutorIntegrationTest {

    private static final String USER = ShopSeedInitializer.BUYER_SHOP_ONLY;

    @Autowired
    private IdempotentWriteExecutor executor;

    @Autowired
    private IdempotencyReplayService replayService;

    @Autowired
    private ShopOrderService orderService;

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
                "DELETE FROM shop_order_item WHERE order_id IN (SELECT id FROM shop_order WHERE buyer_id = ?)", USER);
        jdbcTemplate.update("DELETE FROM shop_order WHERE buyer_id = ?", USER);
    }

    @Test
    @DisplayName("事务代理生效，首次执行把业务订单与幂等记录提交在同一事务中")
    void firstCallCommitsBusinessAndRecord() {
        assertTrue(AopUtils.isAopProxy(executor), "IdempotentWriteExecutor 必须是事务代理");

        UUID runId = newRun(0);
        Call call = createOrderCall(runId, 0, "call-1", "{\"productId\":2004,\"quantity\":1}");

        String result = executor.execute(call);

        assertTrue(ToolResponseEnvelope.isSuccess(result), result);
        assertEquals(1, userOrderCount(), "业务订单必须提交");
        assertEquals(1, recordCount(call.idempotencyKey()), "幂等记录必须提交");

        Map<String, Object> record = record(call.idempotencyKey());
        assertEquals("submit_checkout_order", record.get("tool_name"));
        assertEquals(call.requestHash(), record.get("request_hash"));
        assertEquals(runId.toString(), record.get("run_id"));
        assertEquals(result, record.get("result_json"));
    }

    @Test
    @DisplayName("同一身份再次到达：冲突后经 replayService 原样回放，业务只建一张订单")
    void replayReturnsIdenticalResultWithoutSecondWrite() {
        UUID runId = newRun(0);
        Call call = createOrderCall(runId, 0, "call-1", "{\"productId\":2004,\"quantity\":1}");

        String first = executor.execute(call);
        assertThrows(IdempotencyRecordConflictException.class, () -> executor.execute(call));

        String replayed = replayService.replay(call);
        assertEquals(first, replayed, "回放必须返回一模一样的结果");
        assertEquals(1, userOrderCount(), "回放不得多建订单");
    }

    @Test
    @DisplayName("同键不同参数哈希：回放校验拒绝，既不执行也不覆盖")
    void sameKeyWithDifferentRequestHashFails() {
        UUID runId = newRun(0);
        Call first = createOrderCall(runId, 0, "call-1", "{\"productId\":2004,\"quantity\":1}");
        executor.execute(first);

        Call mutated = createOrderCall(runId, 0, "call-1", "{\"productId\":2004,\"quantity\":2}");
        assertThrows(IdempotencyReplayService.IdempotencyConflictException.class,
                () -> replayService.replay(mutated));
        assertEquals(1, userOrderCount());
    }

    @Test
    @DisplayName("业务抛异常时：业务订单与幂等记录一起回滚，允许后续重试成功")
    void businessFailureRollsBackBothOrderAndIdempotencyRecord() {
        UUID runId = newRun(0);
        String args = "{\"productId\":2004,\"quantity\":1}";
        String key = IdempotencyKeys.callKey(USER, runId, 1, "call-boom");
        String hash = IdempotencyKeys.requestHash("submit_checkout_order", args);

        Call failing = new Call(USER, runId, 1, "call-boom", "submit_checkout_order",
                key, hash, 0, () -> {
            orderService.placeOrder(USER, ShopSeedInitializer.PRODUCT_KEYBOARD_AIR75, 1);
            throw new IllegalStateException("模拟下游故障");
        });

        assertThrows(IllegalStateException.class, () -> executor.execute(failing));
        assertEquals(0, userOrderCount(), "异常后业务订单必须回滚");
        assertEquals(0, recordCount(key), "异常后幂等记录必须一起回滚");

        Call retry = new Call(USER, runId, 1, "call-boom", "submit_checkout_order",
                key, hash, 0, () -> {
            var order = orderService.placeOrder(USER, ShopSeedInitializer.PRODUCT_KEYBOARD_AIR75, 1);
            return ToolResponseEnvelope.okSingle(Map.of("orderNo", order.order().orderNo()));
        });
        String ok = executor.execute(retry);
        assertTrue(ToolResponseEnvelope.isSuccess(ok), ok);
        assertEquals(1, userOrderCount());
    }

    @Test
    @DisplayName("旧执行代次被拒：代次守卫拦下写入，不写业务也不写幂等表")
    void staleEpochRejectedBeforeWrite() {
        UUID runId = newRun(2);
        Call stale = createOrderCall(runId, 1, "call-stale", "{\"productId\":2004,\"quantity\":1}");

        assertThrows(WriteRejectedException.class, () -> executor.execute(stale));
        assertEquals(0, userOrderCount());
        assertEquals(0, recordCount(stale.idempotencyKey()));
    }

    private Call createOrderCall(UUID runId, int epoch, String callId, String args) {
        String key = IdempotencyKeys.callKey(USER, runId, 1, callId);
        String hash = IdempotencyKeys.requestHash("submit_checkout_order", args);
        return new Call(USER, runId, 1, callId, "submit_checkout_order", key, hash, epoch, () -> {
            var order = orderService.placeOrder(USER, ShopSeedInitializer.PRODUCT_KEYBOARD_AIR75, 1);
            return ToolResponseEnvelope.okSingle(Map.of("orderNo", order.order().orderNo()));
        });
    }

    private UUID newRun(int epoch) {
        UUID runId = UUID.randomUUID();
        long rowId = Math.abs(runId.getMostSignificantBits());
        jdbcTemplate.update("""
                INSERT INTO agent_run
                    (id, run_id, user_id, agent_type, user_prompt, state,
                     current_step, tool_call_count, execution_epoch, created_at)
                VALUES (?, ?, ?, 'SHOP_ASSISTANT', '测试下单', 'RUNNING', 1, 0, ?, CURRENT_TIMESTAMP)
                """, rowId, runId.toString(), USER, epoch);
        runIds.add(runId.toString());
        return runId;
    }

    private int userOrderCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_order WHERE buyer_id = ?", Integer.class, USER);
        return n == null ? 0 : n;
    }

    private int recordCount(String key) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music_tool_idempotency WHERE user_id = ? AND idempotency_key = ?",
                Integer.class, USER, key);
        return n == null ? 0 : n;
    }

    private Map<String, Object> record(String key) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM music_tool_idempotency WHERE user_id = ? AND idempotency_key = ?",
                USER, key);
        assertNotNull(row);
        return row;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class McpStub {
        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            return () -> new ToolCallback[0];
        }
    }
}
