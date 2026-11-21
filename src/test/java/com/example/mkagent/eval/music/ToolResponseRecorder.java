package com.example.mkagent.eval.music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评测侧的工具响应采集器（**只在测试代码里**，不进入生产链路）。
 *
 * <p>为什么需要它：q02 被判"缺少 get_song_detail"，但年份其实来自
 * {@code search_songs} 的响应体。要判断"答案有没有查询依据"，
 * 就必须看到工具**真实返回了什么**——只看 {@code tool_trace}（工具名列表）
 * 和最终回答，是判不出来的。
 *
 * <p>三条自我约束：
 * <ol>
 *   <li><b>不重构生产日志</b>：只是在评测装配时把 ToolCallback 包一层，
 *       生产装配一个字节都不改；</li>
 *   <li><b>不落原文</b>：响应原文只在本次任务的内存里用于比对，
 *       写进观测对象的是脱敏摘要（工具名 / 字段名 / 命中的标记 / 字符数）；</li>
 *   <li><b>不改变行为</b>：包装层原样返回委托结果，异常照常抛出，
 *       不吞、不改写、不重试。</li>
 * </ol>
 */
final class ToolResponseRecorder {

    /**
     * 中文语境下模型标注作品名的固定写法，误伤面最小。
     */
    private static final Pattern TITLE_PATTERN = Pattern.compile("《([^》]{1,40})》");

    /**
     * 报告里最多列出几个无依据标题，避免长回答把报告撑爆。
     */
    private static final int MAX_REPORTED_TITLES = 8;

    /**
     * 只到第一层；数组看前几个元素即可覆盖同构条目。
     */
    private static final int ARRAY_SAMPLE = 3;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<String> watchMarkers;

    private final List<MusicEvalObservation.ToolEvidence> evidence = new CopyOnWriteArrayList<>();

    /**
     * 响应原文：仅用于本次任务内的"依据"比对，不对外暴露。
     */
    private final List<String> rawResponses = new CopyOnWriteArrayList<>();

    /**
     * @param watchMarkers 任务关心的证据标记（来自 toolFallbacks.responseMustContain 的并集）
     */
    ToolResponseRecorder(List<String> watchMarkers) {
        this.watchMarkers = watchMarkers == null ? List.of() : List.copyOf(watchMarkers);
    }

    /**
     * 包装一组工具回调。
     *
     * <p><b>只包装读工具</b>，写工具必须原样保留。原因不是"写工具不需要证据"，
     * 而是执行器按**具体类型**识别写工具：
     * {@code ToolCallAgent#idempotentToolNames()} 用
     * {@code callback instanceof IdempotentToolCallback} 判断哪些工具要进幂等计划。
     * 外面套一层透明代理后类型判定失败，计划里就少了这个工具，
     * 幂等执行器随即 fail-closed 拒绝执行——
     * 首轮验证里 a01 的 create_playlist 正是因此被拒（零写入、任务失败）。
     *
     * <p>这是评测侧发现的真实脆弱点：任何包装都会让写操作静默变成"被拒绝"。
     * 本轮不改生产代码（改动面超出"评测侧最小补充"），只在报告里记为残余风险；
     * 需要采证的读路径（q02、c03）不经过这一层，因此不受影响。
     */
    ToolCallback[] wrapReadOnly(ToolCallback[] readCallbacks) {
        if (readCallbacks == null || readCallbacks.length == 0) {
            return readCallbacks == null ? new ToolCallback[0] : readCallbacks;
        }

        ToolCallback[] wrapped = new ToolCallback[readCallbacks.length];
        for (int i = 0; i < readCallbacks.length; i++) {
            wrapped[i] = readCallbacks[i] == null
                    ? null
                    : new RecordingCallback(readCallbacks[i]);
        }
        return wrapped;
    }

    /**
     * 脱敏证据摘要。
     */
    List<MusicEvalObservation.ToolEvidence> evidence() {
        return List.copyOf(evidence);
    }

    /**
     * 回答里出现、但在本次所有工具响应中都找不到的曲目标题。
     *
     * <p>空结果的含义要说清楚：它表示"按书名号提取的标题都有依据"，
     * 不等于"回答里每一个歌名都有依据"——不用书名号写歌名的回答
     * 不在本规则的覆盖范围内，这是刻意的取舍（宁可漏判，也不误伤）。
     */
    List<String> ungroundedTitles(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }

        String joined = String.join("\n", rawResponses);
        Set<String> ungrounded = new LinkedHashSet<>();

        Matcher matcher = TITLE_PATTERN.matcher(answer);
        while (matcher.find() && ungrounded.size() < MAX_REPORTED_TITLES) {
            String title = matcher.group(1).trim();
            if (title.isEmpty() || joined.contains(title)) {
                continue;
            }
            ungrounded.add(title);
        }
        return List.copyOf(ungrounded);
    }

    /**
     * 记录一次调用。
     */
    private String record(String tool, String response) {
        String text = response == null ? "" : response;

        rawResponses.add(text);
        evidence.add(new MusicEvalObservation.ToolEvidence(
                tool,
                topLevelFields(text),
                matchedMarkers(text),
                text.length()
        ));

        return response;
    }

    private List<String> matchedMarkers(String text) {
        List<String> matched = new ArrayList<>();
        for (String marker : watchMarkers) {
            if (!marker.isBlank() && text.contains(marker)) {
                matched.add(marker);
            }
        }
        return matched;
    }

    /**
     * 响应 JSON 里出现的字段名。
     *
     * <p>要处理两种形态：直接是信封 JSON，或（框架把 String 返回值再序列化后的）
     * 带引号的 JSON 字符串——后者在首轮验证里表现为 {@code fields=[]}，
     * 因为文本以 {@code "} 开头，不是 {@code \{}。这与
     * {@code ToolResponseEnvelope.isSuccess} 的处理口径保持一致。
     *
     * <p>解析失败（纯文本错误、被截断等）返回空列表——
     * 字段名只是报告里的佐证，不该因为解析不了就让任务失败。
     */
    private List<String> topLevelFields(String text) {
        String json = unwrapQuoted(text).stripLeading();
        if (json.isEmpty() || (json.charAt(0) != '{' && json.charAt(0) != '[')) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            Set<String> names = new LinkedHashSet<>();
            collectFields(root, names);
            return List.copyOf(names);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 去掉外层引号并反转义；不是"被序列化的字符串"就原样返回。
     */
    private String unwrapQuoted(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.length() >= 2
                && trimmed.charAt(0) == '"'
                && trimmed.charAt(trimmed.length() - 1) == '"') {
            try {
                return objectMapper.readValue(trimmed, String.class);
            } catch (Exception e) {
                return trimmed;
            }
        }
        return trimmed;
    }

    /**
     * 收集字段名：信封外壳的键（ok/data/meta）之外，还要下钻一层到 {@code data}。
     *
     * <p>只报外壳是没有意义的——{@code {ok,data,meta}} 对"这条响应到底带了什么字段"
     * 一个字都没说明。业务字段都在 {@code data} 里（单对象或数组）。
     */
    private void collectFields(JsonNode node, Set<String> names) {
        if (node == null || !node.isObject()) {
            return;
        }

        node.fieldNames().forEachRemaining(names::add);

        JsonNode data = node.get("data");
        if (data == null) {
            return;
        }

        if (data.isObject()) {
            data.fieldNames().forEachRemaining(names::add);
            return;
        }

        if (data.isArray()) {
            int limit = Math.min(data.size(), ARRAY_SAMPLE);
            for (int i = 0; i < limit; i++) {
                JsonNode item = data.get(i);
                if (item != null && item.isObject()) {
                    item.fieldNames().forEachRemaining(names::add);
                }
            }
        }
    }

    /**
     * 透明代理：原样转发，只旁路记录。
     */
    private final class RecordingCallback implements ToolCallback {

        private final ToolCallback delegate;

        private final String name;

        private RecordingCallback(ToolCallback delegate) {
            this.delegate = delegate;
            this.name = delegate.getToolDefinition() == null
                    ? "unknown"
                    : delegate.getToolDefinition().name();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return record(name, delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return record(name, delegate.call(toolInput, toolContext));
        }
    }
}
