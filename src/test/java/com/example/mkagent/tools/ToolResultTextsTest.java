package com.example.mkagent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 工具结果文本解码（离线）。
 *
 * 这个类存在的唯一原因：@Tool 返回值经 MethodToolCallback 序列化后
 * 是 JSON 字符串字面量（外层双引号 + 换行转义成 \n），
 * 所有 startsWith / split / contains 都会静默失效。
 */
class ToolResultTextsTest {

    @Test
    @DisplayName("带引号的 JSON 字符串字面量被解码")
    void decodesJsonStringLiteral() {
        String raw = "\"标题：A\\n链接：B\\n---\\n标题：C\"";

        String decoded = ToolResultTexts.decode(raw);

        assertEquals("标题：A\n链接：B\n---\n标题：C", decoded);
        assertEquals(
                2,
                decoded.split("\n---\n").length,
                "解码后 split 才能得到正确条数"
        );
    }

    @Test
    @DisplayName("未编码的原始文本原样返回")
    void keepsRawTextAsIs() {
        String raw = "没有找到与关键词相关的搜索结果：spring ai";

        assertEquals(raw, ToolResultTexts.decode(raw));
    }

    @Test
    @DisplayName("null / 空串 / 单个引号都安全")
    void handlesDegenerateInputs() {
        assertNull(ToolResultTexts.decode(null));
        assertEquals("", ToolResultTexts.decode(""));
        assertEquals("\"", ToolResultTexts.decode("\""));
    }

    @Test
    @DisplayName("看起来像 JSON 但解析失败时返回原值，绝不丢结果")
    void invalidJsonReturnsRawValue() {
        String raw = "\"未闭合的引号";

        assertEquals(raw, ToolResultTexts.decode(raw));
    }

    @Test
    @DisplayName("首尾空白被容忍（序列化后可能带换行）")
    void toleratesSurroundingWhitespace() {
        String raw = "  \"搜索结果：ok\"\n";

        assertEquals("搜索结果：ok", ToolResultTexts.decode(raw));
    }
}
