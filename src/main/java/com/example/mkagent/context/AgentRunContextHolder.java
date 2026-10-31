package com.example.mkagent.context;

import com.example.mkagent.model.AgentRunContext;

/**
 * 当前线程正在执行的 Agent 任务的上下文（ThreadLocal）。
 *
 * 为什么需要它：
 * 工具（{@code @Tool} 方法）由 Spring 单例持有，拿不到本次任务的
 * {@link AgentRunContext}；而音乐类工具必须知道"当前用户是谁"，
 * 且 userId **绝不能**作为工具参数（模型可以随便填）。
 *
 * 传递方式与 {@code ToolTargetExhaustionTracker} 一致：
 * {@code ToolCallAgent.act()} 在调用 ToolCallingManager 之前 bind，
 * 执行完在 finally 里 restore。
 *
 * 为什么 finally 里"恢复旧值"而不是直接清除：
 * 工具可能嵌套调用（一个工具内部再触发另一次工具执行，
 * 例如未来的组合工具），直接 remove 会把外层的绑定弄丢。
 * 传入的旧值为 null 时才真正 remove，避免线程池残留。
 *
 * 取不到上下文时**失败关闭**：抛异常让这次工具调用变成错误结果，
 * 绝不回落匿名用户——匿名身份去读"我的歌单"只会读到别人的或空数据，
 * 这类静默降级在生产上极难发现。
 */
public final class AgentRunContextHolder {

    /**
     * 缺少上下文时的错误文案（脱敏，可安全返回给模型）。
     */
    public static final String NO_CONTEXT_MESSAGE =
            "缺少任务上下文，无法确定当前用户，本次操作已拒绝";

    /**
     * 上下文里没有有效用户时的错误文案。
     */
    public static final String NO_USER_MESSAGE =
            "缺少用户身份，无法执行需要用户归属的操作";

    private static final ThreadLocal<AgentRunContext> CURRENT = new ThreadLocal<>();

    private AgentRunContextHolder() {
    }

    /**
     * 绑定当前线程的上下文。
     *
     * @param ctx 本次任务的上下文
     * @return 绑定前的旧值（可能为 null），调用方必须保存并在 finally 中 restore
     */
    public static AgentRunContext bind(AgentRunContext ctx) {
        AgentRunContext previous = CURRENT.get();
        CURRENT.set(ctx);
        return previous;
    }

    /**
     * 恢复绑定前的值；旧值为 null 时清除，防止线程池复用串任务。
     */
    public static void restore(AgentRunContext previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /**
     * 当前线程绑定的上下文；未绑定时返回 null。
     */
    public static AgentRunContext current() {
        return CURRENT.get();
    }

    /**
     * 取当前任务的用户标识，取不到就抛异常（失败关闭）。
     *
     * 工具的固定写法：
     * <pre>
     * String userId = AgentRunContextHolder.requireUserId();  // 放在 try 之外
     * try { ... } catch (Exception e) { return ToolResponseEnvelope.error(...); }
     * </pre>
     * 必须放在 try 之外：否则会被业务 catch 吞掉，
     * 变成"安静地返回一个空列表"，模型会当成"没有数据"而不是"没有权限"。
     */
    public static String requireUserId() {
        AgentRunContext ctx = CURRENT.get();
        if (ctx == null) {
            throw new IllegalStateException(NO_CONTEXT_MESSAGE);
        }

        String userId = ctx.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException(NO_USER_MESSAGE);
        }
        return userId;
    }

    /**
     * 清除绑定（仅测试与线程池收尾使用；业务代码用 restore）。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
