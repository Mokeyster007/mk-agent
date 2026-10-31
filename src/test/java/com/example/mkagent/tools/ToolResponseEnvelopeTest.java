package com.example.mkagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具返回信封（纯单元测试）。
 *
 * 重点验证三件事：
 * 1. 结构固定（ok / data / meta.count / meta.truncated）；
 * 2. **长度上限真的生效**：任何情况下都不超过 4000 字符，且仍是合法 JSON；
 * 3. 截断时 hint 必须给出可执行的下一步（"缩小查询范围"），
 *    而不是只告诉模型"被截断了"。
 */
class ToolResponseEnvelopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 测试用的最小记录（模拟歌曲视图）。
     */
    record Row(Long id, String title, String artist) {
    }

    @Test
    @DisplayName("正常列表：ok=true，count 等于条目数，truncated=false")
    void okListKeepsCount() throws Exception {
        String json = ToolResponseEnvelope.okList(List.of(
                new Row(1L, "城市霓虹", "夜航星"),
                new Row(2L, "雾中行", "林间有雾")
        ));

        JsonNode root = objectMapper.readTree(json);

        assertTrue(root.path("ok").asBoolean());
        assertEquals(2, root.path("data").size());
        assertEquals(2, root.path("meta").path("count").asInt());
        assertFalse(root.path("meta").path("truncated").asBoolean());
        assertFalse(root.path("meta").has("hint"),
                "正常结果不应带 hint（省 token）");
    }

    @Test
    @DisplayName("单对象：count=1")
    void okSingleHasCountOne() throws Exception {
        String json = ToolResponseEnvelope.okSingle(new Row(1L, "城市霓虹", "夜航星"));

        JsonNode root = objectMapper.readTree(json);
        assertEquals(1, root.path("meta").path("count").asInt());
        assertEquals("城市霓虹", root.path("data").path("title").asText());
    }

    @Test
    @DisplayName("失败信封：ok=false，错误信息在 meta.hint，data 为空")
    void errorEnvelopeCarriesHint() throws Exception {
        String json = ToolResponseEnvelope.error("没有找到匹配的歌曲");

        JsonNode root = objectMapper.readTree(json);

        assertFalse(root.path("ok").asBoolean());
        assertTrue(root.path("data").isNull() || root.path("data").isMissingNode());
        assertEquals(0, root.path("meta").path("count").asInt());
        assertEquals("没有找到匹配的歌曲",
                root.path("meta").path("hint").asText());
    }

    @Test
    @DisplayName("超长列表：丢尾部条目、置 truncated、带缩小范围提示，且不超过上限")
    void oversizedListIsTruncated() throws Exception {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            rows.add(new Row((long) i, "第 " + i + " 首歌的标题", "某位歌手"));
        }

        String json = ToolResponseEnvelope.okList(rows);

        assertTrue(json.length() <= ToolResponseEnvelope.MAX_RESULT_CHARS,
                "长度必须受上限约束，实际：" + json.length());

        JsonNode root = objectMapper.readTree(json);
        assertTrue(root.path("meta").path("truncated").asBoolean(),
                "截断后必须置 truncated=true");

        int count = root.path("meta").path("count").asInt();
        assertTrue(count < 200, "必须真的丢掉了一些条目，实际 count=" + count);
        assertEquals(root.path("data").size(), count,
                "count 应等于实际返回的条目数");

        assertEquals(ToolResponseEnvelope.TRUNCATED_HINT,
                root.path("meta").path("hint").asText(),
                "提示必须指导下一步（缩小范围），不能只说被截断");
    }

    @Test
    @DisplayName("单条超长文本：裁剪文本字段，仍是合法 JSON")
    void oversizedSingleRowIsClipped() throws Exception {
        String huge = "长".repeat(10_000);
        String json = ToolResponseEnvelope.okList(List.of(new Row(1L, huge, "歌手")));

        assertTrue(json.length() <= ToolResponseEnvelope.MAX_RESULT_CHARS,
                "单条超长也必须受上限约束，实际：" + json.length());

        // 关键：不能靠"截断 JSON 字符串"来达标，那样模型侧解析会失败。
        JsonNode root = objectMapper.readTree(json);
        assertTrue(root.path("ok").asBoolean());
        assertTrue(root.path("data").get(0).path("title").asText().endsWith("…"),
                "超长文本应被裁剪并以省略号标记");
        assertTrue(root.path("meta").path("truncated").asBoolean());
    }

    @Test
    @DisplayName("空列表：count=0 且不报错")
    void emptyListIsValid() throws Exception {
        JsonNode root = objectMapper.readTree(
                ToolResponseEnvelope.okList(List.of()));

        assertTrue(root.path("ok").asBoolean());
        assertEquals(0, root.path("meta").path("count").asInt());
    }

    @Test
    @DisplayName("null 列表按空列表处理（不抛异常）")
    void nullListIsTreatedAsEmpty() throws Exception {
        JsonNode root = objectMapper.readTree(
                ToolResponseEnvelope.okList(null));

        assertEquals(0, root.path("meta").path("count").asInt());
    }
}
