package com.example.mkagent.resilience;

/**
 * Agent 请求限流器抽象。
 *
 * 为什么抽接口：
 * 1. 当前项目没有 Redis，使用进程内固定窗口实现
 *    （InMemoryAgentRateLimiter）；
 * 2. 后续引入 Redis 后，只需新增一个实现类
 *    （基于 INCR + EXPIRE 或 Lua 脚本保证原子性）并替换 Bean，
 *    限流键的解析与调用方代码完全不用改动。
 *
 * 限流键约定：
 * 有用户身份时使用 "user:{userId}"；
 * 无用户身份时使用 "ip:{remoteAddr}"（临时方案）。
 * 由调用方（Controller）负责解析并传入，本接口不关心键的来源。
 */
public interface AgentRequestRateLimiter {

    /**
     * 判断某个限流键当前窗口内是否还允许一次请求。
     *
     * 实现必须保证原子性：并发调用时不能出现
     * "读计数 → 判断 → 写计数" 之间的竞态超发。
     *
     * @param rateLimitKey 限流键，例如 user:1001 或 ip:127.0.0.1
     */
    RateLimitResult tryAcquire(String rateLimitKey);
}
