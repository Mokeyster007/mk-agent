package com.example.mkagent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Agent 预算的默认值与校验规则（MkManus / MusicAgent 共用）。
 *
 * 为什么抽出来：两个 Agent 的预算语义完全一样——超界要告警并回退默认值，
 * 而不是把非法值直接塞进运行中的 Agent。复制一份校验代码意味着
 * 以后调上限时必然漏掉一处，而"漏掉的那一处"恰恰是成本失控的入口。
 *
 * 为什么是静态工具类而不是 Spring Bean：
 * MkManus 会被单测用 {@code new} 直接构造（EvalAgent 也是手动 super），
 * 注入一个 Bean 就得给构造器加参数，反而把"方法注入即可配置"的设计打破。
 * 这里没有任何状态，静态方法最省事。
 *
 * 上限依据：
 * <ul>
 *   <li>max-steps=30：每一步都是一次模型调用，30 步已覆盖长任务；</li>
 *   <li>max-tool-calls=60：约为步数上限的 2 倍，覆盖"一轮调多个工具"；</li>
 *   <li>timeout=30m：在线请求超过 30 分钟已无交付意义，还长期占用并发许可。</li>
 * </ul>
 */
public final class AgentBudgetPolicy {

    private static final Logger log =
            LoggerFactory.getLogger(AgentBudgetPolicy.class);

    /**
     * 默认预算：MkManus 与 MusicAgent 一致。
     */
    public static final int DEFAULT_MAX_STEPS = 8;
    public static final int DEFAULT_MAX_TOOL_CALLS = 12;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    /**
     * 允许的上限：防止单位 / 数字笔误（例如多写一个 0）导致单任务成本失控。
     */
    public static final int MAX_ALLOWED_STEPS = 30;
    public static final int MAX_ALLOWED_TOOL_CALLS = 60;
    public static final Duration MAX_ALLOWED_TIMEOUT = Duration.ofMinutes(30);

    private AgentBudgetPolicy() {
    }

    /**
     * 校验最大步数：≤0 或超过 30 → 告警并回退默认值。
     *
     * @param configured 配置值
     * @param configKey  配置项名，只用于日志（不同 Agent 的 key 不同）
     */
    public static int maxSteps(int configured, String configKey) {
        if (configured <= 0 || configured > MAX_ALLOWED_STEPS) {
            log.warn(
                    "{}={} 非法（要求 1~{}），回退默认值 {}",
                    configKey, configured, MAX_ALLOWED_STEPS, DEFAULT_MAX_STEPS
            );
            return DEFAULT_MAX_STEPS;
        }
        return configured;
    }

    /**
     * 校验最大工具调用次数：≤0 或超过 60 → 告警并回退默认值。
     */
    public static int maxToolCalls(int configured, String configKey) {
        if (configured <= 0 || configured > MAX_ALLOWED_TOOL_CALLS) {
            log.warn(
                    "{}={} 非法（要求 1~{}），回退默认值 {}",
                    configKey, configured, MAX_ALLOWED_TOOL_CALLS,
                    DEFAULT_MAX_TOOL_CALLS
            );
            return DEFAULT_MAX_TOOL_CALLS;
        }
        return configured;
    }

    /**
     * 校验单任务超时：null / 0 / 负数 / 超过 30m → 告警并回退默认值。
     *
     * 覆盖 0s、-1m 这类"能解析成 Duration 但不合理"的写法。
     */
    public static Duration timeout(Duration configured, String configKey) {
        if (configured == null
                || configured.isZero()
                || configured.isNegative()
                || configured.compareTo(MAX_ALLOWED_TIMEOUT) > 0) {
            log.warn(
                    "{}={} 非法（要求大于 0 且不超过 {}），回退默认值 {}",
                    configKey, configured, MAX_ALLOWED_TIMEOUT, DEFAULT_TIMEOUT
            );
            return DEFAULT_TIMEOUT;
        }
        return configured;
    }
}
