package com.example.mkagent.resilience;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单次任务内的"同一工具 + 同一目标主机"重试耗尽计数器。
 *
 * 要解决的问题（真实运行暴露）：
 * on-exhausted=return 之后，任务不再因为一次抓取失败而终止，
 * 但模型会**反复重试同一个打不开的站点**——
 * 数据库里出现了一条 MAX_STEPS_REACHED、28308 Token 的任务，
 * 全部预算都烧在同一个 URL 上。
 *
 * 因此这里按 (工具名, 目标主机) 计数：
 * 同一目标连续重试耗尽达到阈值后，后续调用不再真正执行底层工具，
 * 直接返回"该站点连续失败，请换用其他来源……"，逼模型换方案或收尾。
 *
 * 生命周期与 {@code AgentRunContext} 一致（每个任务 new 一个）：
 * 绝不能做成单例或静态字段——MkManus 是 Spring 单例，
 * 共享计数会让"上一个任务抓 GitHub 失败"影响"下一个任务"。
 *
 * 线程安全：工具调用可能并发（模型一轮里同时请求多个工具），
 * 计数使用 ConcurrentHashMap 原子累加。
 */
public final class ToolTargetExhaustionTracker {

    /**
     * 当前线程正在执行的工具所属任务的计数器。
     *
     * 为什么用 ThreadLocal 而不是 ToolCallingChatOptions.toolContext：
     * toolContext 是 **Prompt 级** 的，一旦设置，框架会对所有工具回调调用
     * {@code ToolCallback.call(String, ToolContext)}。而该方法在接口里的
     * 默认实现是"context 非空就抛 UnsupportedOperationException
     * （Tool context is not supported!）"——只实现 call(String) 的回调
     * 会直接炸掉。实测确认：
     *   call(input, null)          → 走 call(input)，正常
     *   call(input, 空 Map)        → 走 call(input)，正常
     *   call(input, 非空 Map)      → UnsupportedOperationException
     * 生产工具都是 MethodToolCallback（实现了两参版本）暂时没问题，
     * 但任何自定义 / 第三方回调都会被打断，这个风险不值得冒。
     *
     * ThreadLocal 只在 ToolCallAgent.act() 执行工具的这段时间内绑定，
     * 执行完立刻清除；工具执行本身是同步的（ToolCallingManager 在
     * 调用线程上顺序执行），因此不存在跨线程读不到的问题。
     * 取不到绑定时（单测直接调用、非 Agent 链路）熔断自动降级为不生效，
     * 与改动前行为一致。
     */
    private static final ThreadLocal<ToolTargetExhaustionTracker> CURRENT =
            new ThreadLocal<>();

    /**
     * 目标主机无法从工具参数中解析出来时使用的占位值。
     *
     * 此时退化为"按工具名计数"：例如 web_search 的参数只有 query，
     * 没有主机概念；搜索接口连续两次重试耗尽，说明整个搜索通道不可用，
     * 继续搜只是继续失败。
     */
    public static final String NO_HOST = "";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * targetKey → 已连续重试耗尽的次数。
     */
    private final Map<String, Integer> exhaustedCounts = new ConcurrentHashMap<>();

    /**
     * 绑定当前线程的计数器（由 ToolCallAgent.act 在工具执行前后成对调用）。
     */
    public static void bind(ToolTargetExhaustionTracker tracker) {
        if (tracker == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(tracker);
        }
    }

    /**
     * 当前线程绑定的计数器；未绑定时返回 null。
     */
    public static ToolTargetExhaustionTracker current() {
        return CURRENT.get();
    }

    /**
     * 解除绑定。必须在 finally 中调用，避免线程池复用线程时串任务。
     */
    public static void unbind() {
        CURRENT.remove();
    }

    /**
     * 目标 key：{@code 工具名|主机}。
     *
     * 同一主机不同路径共用同一个 key——
     * "该站点打不开"是主机级现象，按路径区分会让保护形同虚设。
     *
     * @param toolName  工具名（null/空时用 unknown 兜底，避免 key 冲突）
     * @param toolInput 模型给出的工具参数 JSON（可能为 null 或非法 JSON）
     * @return 稳定的目标标识
     */
    public static String targetKey(String toolName, String toolInput) {
        String tool = (toolName == null || toolName.isBlank())
                ? "unknown"
                : toolName;
        return tool + "|" + extractHost(toolInput);
    }

    /**
     * 从 key 里取出主机部分，用于日志与用户可见文案。
     */
    public static String hostOfKey(String targetKey) {
        if (targetKey == null) {
            return NO_HOST;
        }
        int separator = targetKey.indexOf('|');
        return separator < 0 ? NO_HOST : targetKey.substring(separator + 1);
    }

    /**
     * 累加一次耗尽，返回累加后的次数。
     */
    public int record(String targetKey) {
        return exhaustedCounts.merge(targetKey, 1, Integer::sum);
    }

    /**
     * 当前累计耗尽次数（不存在时为 0）。
     */
    public int count(String targetKey) {
        Integer value = exhaustedCounts.get(targetKey);
        return value == null ? 0 : value;
    }

    /**
     * 该目标是否已达到阈值、后续调用应被直接拦下。
     *
     * @param maxExhaustedPerTarget 阈值；小于等于 0 表示关闭该保护
     */
    public boolean isBlocked(String targetKey, int maxExhaustedPerTarget) {
        if (maxExhaustedPerTarget <= 0) {
            return false;
        }
        return count(targetKey) >= maxExhaustedPerTarget;
    }

    /**
     * 只读快照（测试与诊断用）。
     */
    public Map<String, Integer> snapshot() {
        return Map.copyOf(new LinkedHashMap<>(exhaustedCounts));
    }

    /**
     * 清空计数（测试用；生产上随 AgentRunContext 一起丢弃）。
     */
    public void clear() {
        exhaustedCounts.clear();
    }

    /**
     * 从工具参数里解析目标主机。
     *
     * 只认 http/https：其它协议（file:、javascript:）不构成"站点"，
     * 也不该参与站点级熔断。
     *
     * 解析失败一律返回 {@link #NO_HOST}（退化为按工具计数），
     * 绝不因为参数格式问题抛异常打断工具调用。
     */
    static String extractHost(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return NO_HOST;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(toolInput);
            return firstHost(root);
        } catch (JsonProcessingException e) {
            return NO_HOST;
        }
    }

    /**
     * 深度优先找第一个 http/https 主机。
     *
     * 不按字段名硬编码（url / link / href 都有人写），
     * 而是"任何能解析出 http(s) 主机的字符串"——参数是模型给的 JSON，
     * 字段命名不可控。
     */
    private static String firstHost(JsonNode node) {
        if (node == null || node.isNull()) {
            return NO_HOST;
        }

        if (node.isTextual()) {
            return hostOf(node.asText());
        }

        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                String host = firstHost(child);
                if (!host.isEmpty()) {
                    return host;
                }
            }
        }

        return NO_HOST;
    }

    /**
     * 单个字符串 → 小写主机名；不是 http(s) 绝对地址时返回空串。
     */
    private static String hostOf(String value) {
        if (value == null || value.isBlank()) {
            return NO_HOST;
        }

        String candidate = value.trim();
        int schemeSeparator = candidate.indexOf("://");
        if (schemeSeparator <= 0) {
            return NO_HOST;
        }

        String scheme = candidate.substring(0, schemeSeparator)
                .toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return NO_HOST;
        }

        try {
            String host = URI.create(candidate).getHost();
            if (host == null || host.isBlank()) {
                return NO_HOST;
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return NO_HOST;
        }
    }
}
