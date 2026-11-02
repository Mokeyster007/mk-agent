package com.example.mkagent.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 审批元数据"不确定就失败关闭"的单元约束（批次 1 补充）。
 *
 * 三种"不确定"的形态：
 * 1. 扫描还没跑（容器没刷新完 / 手工 new 的 Agent）；
 * 2. Agent 暴露了扫描结果里没有的工具；
 * 3. 扫描结果本身是空的。
 *
 * 任何一种都**不能**退化成"按普通工具执行"。
 */
class ApprovalToolPolicyUnitTest {

    @Test
    @DisplayName("扫描未完成：查询名单直接失败，不返回空集")
    void notReadyPolicyFailsClosed() {
        // 只给上下文、不触发 ContextRefreshedEvent，模拟"扫描还没跑"
        ApprovalToolPolicy policy = new ApprovalToolPolicy(
                mock(ApplicationContext.class), new ApprovalProperties());

        assertFalse(policy.isReady(), "未触发刷新事件时应处于未就绪状态");

        IllegalStateException error = assertThrows(
                IllegalStateException.class, policy::requiredToolNames);
        assertTrue(error.getMessage().contains("尚未就绪"),
                "异常必须说明是扫描未就绪，实际：" + error.getMessage());

        assertThrows(IllegalStateException.class,
                () -> policy.verifyExposedTools("agent", Set.of("any_tool")));
    }

    @Test
    @DisplayName("暴露的工具不在扫描结果里：失败关闭")
    void unknownExposedToolFailsClosed() {
        ApprovalToolPolicy policy = new ApprovalToolPolicy(
                Set.of("create_playlist"), Set.of("create_playlist", "search_songs"), true);

        // 已知工具：放行
        assertDoesNotThrow(() -> policy.verifyExposedTools(
                "agent", Set.of("create_playlist", "search_songs")));

        // 未知工具：抛异常，且点名具体工具
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> policy.verifyExposedTools(
                        "agent", Set.of("search_songs", "mystery_write")));

        assertTrue(error.getMessage().contains("mystery_write"),
                "异常必须点名未知工具，实际：" + error.getMessage());
        assertFalse(error.getMessage().contains("search_songs"),
                "已知工具不应出现在失败清单里，实际：" + error.getMessage());
    }

    @Test
    @DisplayName("扫描结果为空：任何暴露的工具都会失败关闭")
    void emptyScanFailsClosed() {
        ApprovalToolPolicy policy = new ApprovalToolPolicy(Set.of(), Set.of(), true);

        assertTrue(policy.requiredToolNames().isEmpty(), "空扫描本身是合法结果");
        assertThrows(IllegalStateException.class,
                () -> policy.verifyExposedTools("agent", Set.of("search_songs")));
    }

    @Test
    @DisplayName("审批关闭时 requiresApproval 恒为 false，但写工具仍会被失败关闭拦住")
    void disabledPolicyStillReportsApprovalTools() {
        ApprovalToolPolicy policy = new ApprovalToolPolicy(
                Set.of("create_playlist"), Set.of("create_playlist", "search_songs"), false);

        assertFalse(policy.requiresApproval("create_playlist"),
                "关闭审批时 requiresApproval 为 false（由调用方失败关闭）");
        assertTrue(policy.hitsApprovalToolWhenDisabled(
                        java.util.List.of(toolCall("create_playlist"))),
                "关闭审批时命中写工具必须能被识别，才能失败关闭");
    }

    private static org.springframework.ai.chat.messages.AssistantMessage.ToolCall toolCall(
            String name) {
        return new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                "call-" + name, "function", name, "{}");
    }
}
