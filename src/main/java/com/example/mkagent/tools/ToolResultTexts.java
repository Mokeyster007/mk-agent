package com.example.mkagent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工具结果文本的解码工具。
 *
 * 背景（一个真实的坑）：
 * `@Tool` 方法的返回值经 `MethodToolCallback` 序列化后会变成一个 **JSON 字符串字面量**——
 * 外层带双引号、内部换行被转义成 `\n`：
 *
 * <pre>
 * 原始返回值： 标题：A\n链接：B\n---\n标题：C
 * responseData："标题：A\n链接：B\n---\n标题：C"   ← 注意首尾引号与 \n 两个字符
 * </pre>
 *
 * 于是所有对 `responseData` 做 `startsWith` / `split("\n---\n")` 的代码都会静默失效：
 * 字符串以 `"` 开头，`startsWith("没有找到")` 永远为 false；
 * 分隔符是字面上的 `\` + `n`，`split` 只会得到 1 段——这就是
 * "搜索结果条数永远是 1" 的根因。
 *
 * 这个类把"要不要先解码"收敛在一处，避免每个调用点各写一遍。
 * 不是 JSON 字符串字面量（未编码的原始文本、null、空串）时原样返回，
 * 因此对两种形态都安全。
 */
public final class ToolResultTexts {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ToolResultTexts() {
    }

    /**
     * 若入参是 JSON 字符串字面量则解码，否则原样返回。
     *
     * 判定条件刻意保守：首尾都是双引号才尝试解码；
     * 解码失败（不是合法 JSON）时返回原值，绝不因为解析问题丢掉工具结果。
     */
    public static String decode(String raw) {
        if (raw == null) {
            return null;
        }

        String trimmed = raw.trim();
        if (trimmed.length() < 2
                || !trimmed.startsWith("\"")
                || !trimmed.endsWith("\"")) {
            return raw;
        }

        try {
            return OBJECT_MAPPER.readValue(trimmed, String.class);
        } catch (JsonProcessingException e) {
            return raw;
        }
    }
}
