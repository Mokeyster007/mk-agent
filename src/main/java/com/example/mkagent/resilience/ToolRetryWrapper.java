package com.example.mkagent.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具重试包装器：把可重试白名单内的工具包装成
 * {@link RetryableToolCallback}，其余工具原样返回。
 *
 * 默认只包装 web_search / web_scrape：
 * 它们是只读、幂等的网络查询，重复执行没有副作用；
 * file_operation / resource_download / pdf_generation
 * 等高风险工具不在白名单内，永不自动重试。
 *
 * 配置项（见 application.yml）：
 * mkagent.tool-retry.enabled / max-attempts / initial-backoff-millis /
 * multiplier / retryable-tools / failure-markers。
 */
@Component
public class ToolRetryWrapper {

    private static final Logger log =
            LoggerFactory.getLogger(ToolRetryWrapper.class);

    private final boolean enabled;

    private final int maxAttempts;

    private final Duration initialBackoff;

    private final double multiplier;

    /**
     * 允许自动重试的工具名白名单。
     */
    private final Set<String> retryableTools;

    /**
     * 工具名 → 失败文案关键词（工具吞异常返回失败文本时据此识别）。
     */
    private final Map<String, List<String>> failureMarkersByTool;

    public ToolRetryWrapper(
            @Value("${mkagent.tool-retry.enabled:true}") boolean enabled,
            @Value("${mkagent.tool-retry.max-attempts:3}") int maxAttempts,
            @Value("${mkagent.tool-retry.initial-backoff-millis:200}")
            long initialBackoffMillis,
            @Value("${mkagent.tool-retry.multiplier:2.0}") double multiplier,
            @Value("${mkagent.tool-retry.retryable-tools:web_search,web_scrape}")
            String retryableToolsCsv,
            @Value("${mkagent.tool-retry.failure-markers:"
                    + "web_search:搜索工具执行失败,"
                    + "web_scrape:网页抓取失败}")
            String failureMarkersCsv
    ) {
        this.enabled = enabled;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = Duration.ofMillis(initialBackoffMillis);
        this.multiplier = multiplier;
        this.retryableTools = parseCsvToSet(retryableToolsCsv);
        this.failureMarkersByTool = parseFailureMarkers(failureMarkersCsv);

        log.info(
                "工具重试包装器初始化：enabled={}, maxAttempts={}, backoff={}ms, multiplier={}, retryableTools={}",
                enabled, maxAttempts, initialBackoffMillis, multiplier, retryableTools
        );
    }

    /**
     * 按白名单包装一组工具；非白名单工具保持原样。
     */
    public ToolCallback[] wrap(ToolCallback[] callbacks) {
        if (!enabled || retryableTools.isEmpty()) {
            return callbacks;
        }

        List<ToolCallback> wrapped = new ArrayList<>(callbacks.length);
        for (ToolCallback callback : callbacks) {
            wrapped.add(wrapIfNeeded(callback));
        }
        return wrapped.toArray(new ToolCallback[0]);
    }

    /**
     * 单个工具：在白名单内则包装，否则原样返回。
     */
    public ToolCallback wrapIfNeeded(ToolCallback callback) {
        String name = callback.getToolDefinition().name();

        if (!enabled || !retryableTools.contains(name)) {
            return callback;
        }

        // 避免重复包装（例如测试多次调用 getTools）。
        if (callback instanceof RetryableToolCallback) {
            return callback;
        }

        return new RetryableToolCallback(
                callback,
                maxAttempts,
                initialBackoff,
                multiplier,
                failureMarkersByTool.getOrDefault(name, List.of())
        );
    }

    private Set<String> parseCsvToSet(String csv) {
        Set<String> result = new java.util.LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return Set.copyOf(result);
    }

    /**
     * 解析 "tool:marker,tool:marker" 格式的失败文案配置。
     * marker 为包含匹配关键词（工具返回值经 MethodToolCallback
     * 序列化后外层带双引号，不能用前缀匹配）。
     */
    private Map<String, List<String>> parseFailureMarkers(String csv) {
        Map<String, List<String>> result = new HashMap<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            int separator = trimmed.indexOf(':');
            if (separator <= 0 || separator >= trimmed.length() - 1) {
                continue;
            }
            String tool = trimmed.substring(0, separator).trim();
            String marker = trimmed.substring(separator + 1).trim();
            result.computeIfAbsent(tool, key -> new ArrayList<>())
                    .add(marker);
        }
        return result;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Set<String> getRetryableTools() {
        return retryableTools;
    }
}
