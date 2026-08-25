package com.example.mkagent.context;

/**
 * 当前请求的用户上下文（ThreadLocal）。
 *
 * 现状说明：
 * 项目当前没有完整的认证体系（无 JWT / 登录 / Spring Security），
 * 用户身份由请求头 X-User-Id 占位提供，
 * 由 UserContextInterceptor 在 Web 线程解析写入。
 *
 * 后续接入真实认证（JWT / Session）时，
 * 只需替换拦截器中的解析逻辑，本类的读写方式保持不变。
 *
 * 线程边界：
 * ThreadLocal 只在当前 Web 线程有效。
 * Agent 任务在 agent-* 后台线程执行，
 * 因此 BaseAgent 会在任务创建时（仍在 Web 线程）
 * 把 userId 固化进 AgentRunContext，避免跨线程丢失。
 */
public final class UserContextHolder {

    /**
     * 请求头名称：当前用户身份占位。
     */
    public static final String USER_ID_HEADER = "X-User-Id";

    /**
     * 无用户身份信息时的兜底归属。
     *
     * 仅用于非 HTTP 入口（例如直接调用 run() 的单测）；
     * /ai/runs/** 与 /ai/manus/chat 接口层会强制要求请求头。
     */
    public static final String ANONYMOUS_USER_ID = "anonymous";

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void set(String userId) {
        USER_ID.set(userId);
    }

    /**
     * 获取当前用户，可能为 null（未设置时）。
     */
    public static String get() {
        return USER_ID.get();
    }

    /**
     * 获取当前用户，未设置时返回 anonymous。
     */
    public static String getOrDefault() {
        String userId = USER_ID.get();
        return (userId == null || userId.isBlank()) ? ANONYMOUS_USER_ID : userId;
    }

    /**
     * 请求结束时必须清理，防止线程池复用导致身份串号。
     */
    public static void clear() {
        USER_ID.remove();
    }
}
