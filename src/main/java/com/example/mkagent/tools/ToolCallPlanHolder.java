package com.example.mkagent.tools;

/**
 * 当前线程正在执行的那一轮工具调用计划（ThreadLocal）。
 *
 * 与 {@code AgentRunContextHolder} 同一套路：
 * 执行工具前 bind，执行完在 finally 里 restore（不是 remove）——
 * 嵌套执行时不能把外层的计划弄丢，线程池复用时要清干净。
 *
 * 只在 {@code ToolCallAgent.act()} 与 {@code executeApprovedRound()}
 * 调用 {@code ToolCallingManager} 的这段窗口内有效。
 */
public final class ToolCallPlanHolder {

    private static final ThreadLocal<ToolCallPlan> CURRENT = new ThreadLocal<>();

    private ToolCallPlanHolder() {
    }

    /**
     * 绑定本轮计划。
     *
     * @return 绑定前的旧值（可能为 null），调用方必须在 finally 中 restore
     */
    public static ToolCallPlan bind(ToolCallPlan plan) {
        ToolCallPlan previous = CURRENT.get();
        CURRENT.set(plan);
        return previous;
    }

    /**
     * 恢复绑定前的值；旧值为 null 时清除。
     */
    public static void restore(ToolCallPlan previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /**
     * 当前线程绑定的计划；未绑定时返回 null。
     */
    public static ToolCallPlan current() {
        return CURRENT.get();
    }

    /**
     * 清除绑定（仅测试与线程池收尾使用）。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
