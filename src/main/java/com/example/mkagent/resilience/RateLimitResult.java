package com.example.mkagent.resilience;

/**
 * 一次限流判定的结果。
 *
 * @param allowed      本次请求是否放行
 * @param limit        窗口内允许的最大请求数
 * @param waitSeconds  被拒绝时，建议客户端等待的秒数（放行时为 0）
 */
public record RateLimitResult(
        boolean allowed,
        int limit,
        long waitSeconds
) {

    public static RateLimitResult allow(int limit) {
        return new RateLimitResult(true, limit, 0);
    }

    public static RateLimitResult reject(int limit, long waitSeconds) {
        return new RateLimitResult(false, limit, Math.max(waitSeconds, 1));
    }
}
