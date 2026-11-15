package com.example.mkagent.approval;

import com.example.mkagent.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动收尾门禁与启动 runner 的状态机（M3 批次 4B）。
 *
 * 不依赖 Spring：这里要证明的是"开关语义"，不是装配。
 * 装配层面的证据在 {@link RecoveryGateEntryPointTest}。
 */
class RecoveryGateTest {

    @Test
    @DisplayName("recovery 默认关闭：门禁一开始就是开的，不改变现有行为")
    void disabledMeansOpen() {
        ApprovalProperties properties = new ApprovalProperties();
        assertFalse(properties.isRecoveryEnabled(), "默认值必须是关闭");

        RecoveryGate gate = new RecoveryGate(properties);

        assertTrue(gate.isOpen());
        assertDoesNotThrow(gate::requireOpen);
        assertEquals("DISABLED", gate.reason());
    }

    @Test
    @DisplayName("启用 recovery：容器启动期即关闭，拒绝时给稳定原因码 503")
    void enabledMeansClosedUntilOpened() {
        ApprovalProperties properties = new ApprovalProperties();
        properties.setRecoveryEnabled(true);

        RecoveryGate gate = new RecoveryGate(properties);

        assertFalse(gate.isOpen(), "启用收尾时，门禁必须在构造期就是关闭的");

        BusinessException error = assertThrows(BusinessException.class, gate::requireOpen);
        assertEquals(503, error.getHttpStatus());
        assertEquals("RECOVERY_IN_PROGRESS", error.getCode());
        assertTrue(error.getMessage().contains("稍后重试"));

        gate.open("STARTUP_SWEEP_COMPLETED");
        assertTrue(gate.isOpen());
        assertDoesNotThrow(gate::requireOpen);
    }

    @Test
    @DisplayName("关闭可重复：收尾未完成时再次关闭仍然是关闭")
    void closeIsIdempotent() {
        ApprovalProperties properties = new ApprovalProperties();
        properties.setRecoveryEnabled(true);

        RecoveryGate gate = new RecoveryGate(properties);
        gate.close("BUDGET_OR_LIMIT_EXHAUSTED");
        gate.close("TRANSIENT_FAILURES");

        assertFalse(gate.isOpen());
        assertEquals("TRANSIENT_FAILURES", gate.reason());
    }

    @Test
    @DisplayName("启动 runner：收尾完成才开门")
    void runnerOpensOnlyWhenSweepCompleted() {
        ApprovalProperties properties = new ApprovalProperties();
        properties.setRecoveryEnabled(true);
        RecoveryGate gate = new RecoveryGate(properties);

        RecoverySweeper sweeper = org.mockito.Mockito.mock(RecoverySweeper.class);
        org.mockito.Mockito.when(sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP))
                .thenReturn(summary(true, "COMPLETED"));

        new RecoveryStartupRunner(sweeper, gate).run(null);

        assertTrue(gate.isOpen());
    }

    @Test
    @DisplayName("启动 runner：收尾未完成时不误开放")
    void runnerKeepsClosedWhenSweepIncomplete() {
        ApprovalProperties properties = new ApprovalProperties();
        properties.setRecoveryEnabled(true);
        RecoveryGate gate = new RecoveryGate(properties);

        RecoverySweeper sweeper = org.mockito.Mockito.mock(RecoverySweeper.class);
        org.mockito.Mockito.when(sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP))
                .thenReturn(summary(false, "BUDGET_OR_LIMIT_EXHAUSTED"));

        new RecoveryStartupRunner(sweeper, gate).run(null);

        assertFalse(gate.isOpen(), "未完成时绝不能开门");
    }

    @Test
    @DisplayName("启动 runner：扫描抛异常时不误开放")
    void runnerKeepsClosedWhenSweepThrows() {
        ApprovalProperties properties = new ApprovalProperties();
        properties.setRecoveryEnabled(true);
        RecoveryGate gate = new RecoveryGate(properties);

        RecoverySweeper sweeper = org.mockito.Mockito.mock(RecoverySweeper.class);
        org.mockito.Mockito.when(sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP))
                .thenThrow(new IllegalStateException("模拟扫描故障"));

        assertDoesNotThrow(() -> new RecoveryStartupRunner(sweeper, gate).run(null),
                "runner 不应让启动直接退出，但必须保持关闭");
        assertFalse(gate.isOpen());
    }

    private RecoverySweeper.Summary summary(boolean completed, String reason) {
        return new RecoverySweeper.Summary(
                RecoverySweeper.Trigger.STARTUP,
                0, 0, 0, 0, 0, 0, 0, completed ? 0 : 1, completed, reason);
    }

    /**
     * 让 Duration 依赖显式存在：properties 默认值变化时这里会先炸，
     * 而不是悄悄变成"启动收尾跑 0 毫秒"。
     */
    @Test
    @DisplayName("默认预算是有界的正数")
    void defaultBudgetsAreBounded() {
        ApprovalProperties properties = new ApprovalProperties();
        assertTrue(properties.getRecoveryMaxDuration().toMillis() > 0);
        assertTrue(properties.getRecoveryLockTimeout().toMillis() > 0);
        assertTrue(properties.getRecoveryBatchSize() > 0);
        assertTrue(properties.getRecoveryMaxRows() >= properties.getRecoveryBatchSize());
        assertEquals(Duration.ofSeconds(10), properties.getRecoveryMaxDuration());
    }
}
