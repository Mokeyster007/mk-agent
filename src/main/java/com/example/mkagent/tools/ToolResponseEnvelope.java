package com.example.mkagent.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具返回的统一信封。
 *
 * 结构固定为：
 * <pre>
 * {"ok":true,"data":...,"meta":{"count":n,"truncated":false,"hint":null}}
 * </pre>
 *
 * 为什么统一信封而不是各工具自己拼字符串：
 * 1. 模型侧只要看 ok 就知道成功与否，不用猜"这句话是不是错误信息"；
 * 2. 前端 / 事件文案可以直接读 meta.count，不必按工具写正则；
 * 3. 长度上限只有一处实现，不会出现"这个工具忘了截断"。
 *
 * 失败也用信封（ok=false），错误信息放在 meta.hint：
 * 结构一致，模型不需要区分两种返回形态。
 *
 * 长度上限 {@link #MAX_RESULT_CHARS} 是**整个 JSON 字符串**的长度：
 * 工具结果会原样进入下一轮上下文，不设上限时一次查询就可能把上下文塞满。
 */
public final class ToolResponseEnvelope {

    /**
     * 单次工具结果的最大字符数。
     *
     * 4000 字符 ≈ 一次"10 首歌 + 歌单名"的量级，
     * 足够模型据此回答，又不会挤占后续步骤的上下文。
     */
    public static final int MAX_RESULT_CHARS = 4000;

    /**
     * 截断时给模型的提示：必须能指导下一步动作，而不是只说"被截断了"。
     */
    public static final String TRUNCATED_HINT =
            "结果过长已截断，请缩小查询范围（更具体的关键词或更小的 limit）";

    /**
     * 溢出兜底时单个文本字段的保留长度。
     *
     * 只有在"单条结果本身就超过总上限"时才会用到：
     * 把超长文本裁到这个长度，保证 JSON 仍然合法可解析
     * （直接截断 JSON 字符串会产出非法 JSON，模型侧会解析失败）。
     */
    private static final int MAX_TEXT_NODE_CHARS = 300;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolResponseEnvelope() {
    }

    /**
     * 信封的元信息。
     *
     * hint 为 null 时不出现在 JSON 里（@JsonInclude），
     * 避免每一条正常结果都带一个 "hint":null 浪费 token。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(int count, boolean truncated, String hint) {
    }

    /**
     * 信封本体。
     */
    public record Envelope(boolean ok, Object data, Meta meta) {
    }

    /**
     * 列表结果：超限时从尾部丢弃条目，并标记 truncated。
     *
     * @param items 结果列表（元素为 record / Map 等可序列化对象）
     * @return JSON 字符串，长度不超过 {@link #MAX_RESULT_CHARS}
     */
    public static String okList(List<?> items) {
        List<Object> source = new ArrayList<>();
        if (items != null) {
            source.addAll(items);
        }

        String json = render(source, false, null);
        if (json.length() <= MAX_RESULT_CHARS) {
            return json;
        }

        /*
         * 从尾部丢：前面的条目是"更相关"的（SQL 已按相关度/顺序排好），
         * 丢尾部比丢头部保留的信息更多。
         */
        List<Object> kept = new ArrayList<>(source);
        while (kept.size() > 1) {
            kept.remove(kept.size() - 1);
            json = render(kept, true, TRUNCATED_HINT);
            if (json.length() <= MAX_RESULT_CHARS) {
                return json;
            }
        }

        // 单条就超限：裁剪文本字段，保证仍是合法 JSON。
        if (!kept.isEmpty()) {
            kept.set(0, clipText(kept.get(0)));
            json = render(kept, true, TRUNCATED_HINT);
            if (json.length() <= MAX_RESULT_CHARS) {
                return json;
            }
        }

        // 兜底：宁可返回空列表，也不要返回超长或非法 JSON。
        return render(List.of(), true, TRUNCATED_HINT);
    }

    /**
     * 单对象结果（详情类）。
     */
    public static String okSingle(Object data) {
        String json = render(data, false, null);
        if (json.length() <= MAX_RESULT_CHARS) {
            return json;
        }

        json = render(clipText(data), true, TRUNCATED_HINT);
        if (json.length() <= MAX_RESULT_CHARS) {
            return json;
        }

        return render(null, true, TRUNCATED_HINT);
    }

    /**
     * 失败信封。
     *
     * message 必须是**脱敏**的用户/模型可读文案：
     * 不含 SQL、堆栈、路径、userId。
     */
    public static String error(String message) {
        return render(false, null, false,
                message == null ? "工具执行失败" : message);
    }

    /**
     * 判断一个工具返回值是否是**成功**信封。
     *
     * 幂等执行器只缓存成功结果：失败信封（ok=false）不能变成可回放的历史结果，
     * 否则一次"歌单重名"的失败会被永久当成这次调用的答案。
     *
     * 兼容两种形态：直接是信封 JSON，或（MethodToolCallback 把 String 返回值
     * 再序列化后的）带引号的 JSON 字符串。解析不了按"不是成功"处理——失败关闭。
     */
    public static boolean isSuccess(String resultJson) {
        JsonNode root = parseEnvelope(resultJson);
        return root != null
                && root.get("ok") != null
                && root.get("ok").isBoolean()
                && root.get("ok").booleanValue();
    }

    /**
     * 从工具结果里取歌单 ID（没有就返回 null）。
     *
     * 幂等回放要按"当前访问规则"复核资源归属，先得知道结果涉及哪个歌单。
     */
    public static Long playlistIdOf(String resultJson) {
        JsonNode root = parseEnvelope(resultJson);
        if (root == null) {
            return null;
        }

        JsonNode data = root.get("data");
        if (data == null || !data.isObject()) {
            return null;
        }

        JsonNode playlistId = data.get("playlistId");
        if (playlistId == null || !playlistId.isNumber()) {
            return null;
        }
        return playlistId.asLong();
    }

    /**
     * 解析信封；无法解析或不是对象时返回 null（调用方按失败处理）。
     */
    private static JsonNode parseEnvelope(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }

        try {
            JsonNode node = MAPPER.readTree(resultJson);
            if (node != null && node.isTextual()) {
                node = MAPPER.readTree(node.asText());
            }
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 序列化信封。count 取实际返回的条目数（截断后就是"实际给了几条"）。
     */
    private static String render(Object data, boolean truncated, String hint) {
        return render(true, data, truncated, hint);
    }

    private static String render(
            boolean ok,
            Object data,
            boolean truncated,
            String hint
    ) {
        int count = countOf(data);

        try {
            return MAPPER.writeValueAsString(new Envelope(
                    ok, data, new Meta(count, truncated, hint)
            ));
        } catch (Exception e) {
            /*
             * 序列化失败说明 data 里有不可序列化的类型（编程错误）。
             * 这里不能抛：抛出去会让整次工具调用变成框架级错误，
             * 而模型其实只需要知道"这次没拿到数据"。
             */
            try {
                return MAPPER.writeValueAsString(new Envelope(
                        false,
                        null,
                        new Meta(0, false, "结果序列化失败，请稍后重试")
                ));
            } catch (Exception ignored) {
                return "{\"ok\":false,\"data\":null,"
                        + "\"meta\":{\"count\":0,\"truncated\":false}}";
            }
        }
    }

    private static int countOf(Object data) {
        if (data == null) {
            return 0;
        }
        if (data instanceof List<?> list) {
            return list.size();
        }
        return 1;
    }

    /**
     * 把对象里超长的文本字段裁短（保留合法 JSON 结构）。
     */
    private static JsonNode clipText(Object data) {
        JsonNode node = MAPPER.valueToTree(data);
        clipNode(node);
        return node;
    }

    private static void clipNode(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            List<String> names = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(names::add);

            for (String name : names) {
                JsonNode child = objectNode.get(name);
                if (child != null && child.isTextual()
                        && child.asText().length() > MAX_TEXT_NODE_CHARS) {
                    objectNode.put(name,
                            child.asText().substring(0, MAX_TEXT_NODE_CHARS) + "…");
                } else if (child != null) {
                    clipNode(child);
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            for (JsonNode child : arrayNode) {
                clipNode(child);
            }
        }
    }
}
