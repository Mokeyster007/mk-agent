package com.example.mkagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 音乐工具的参数 schema（不启动 Spring、不连数据库）。
 *
 * 核心断言：**schema 里不允许出现 userId / user_id**。
 *
 * 为什么这条必须用 schema 而不是"工具方法签名"来断言：
 * 模型看到的只有 schema。签名里没有、schema 里有，
 * 模型照样会传；反之 schema 里没有，模型连"传"这个动作都做不出来。
 * 这是"userId 不是工具参数"这条安全约束的唯一可验证形式。
 *
 * 断言用 Jackson 解析后按结构判断，而不是比对 JSON 字符串：
 * jsonschema-generator 的输出格式（空格、字段顺序）不属于我们的契约，
 * 字符串比对会因为依赖升级而无故失败。
 */
class MusicToolSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造工具时依赖传 null：schema 只由方法签名生成，不会碰仓库。
     *
     * 曲库工具与歌单工具是两个 Bean（管理员的白名单里没有后者），
     * 所以这里也要分两次注册——顺带证明拆分没有漏掉任何工具。
     */
    private final ToolCallback[] callbacks = concat(
            ToolCallbacks.from(new MusicQueryTool(null)),
            ToolCallbacks.from(new MusicPlaylistQueryTool(null)),
            ToolCallbacks.from(new AdminAnalyticsTool(null, null))
    );

    @Test
    @DisplayName("六个音乐只读工具都注册成功，工具名与设计一致")
    void toolNamesMatchDesign() {
        Set<String> names = Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertEquals(
                Set.of("search_songs", "get_song_detail",
                        "get_user_playlists", "get_playlist_songs",
                        "get_play_stats", "get_top_songs"),
                names
        );
    }

    @Test
    @DisplayName("所有工具的 inputSchema 都不包含 userId / user_id")
    void schemaNeverExposesUserId() {
        for (ToolCallback callback : callbacks) {
            String schema = callback.getToolDefinition().inputSchema();
            String lower = schema.toLowerCase(Locale.ROOT);

            assertFalse(lower.contains("userid"),
                    "schema 不得出现 userId：" + schema);
            assertFalse(lower.contains("user_id"),
                    "schema 不得出现 user_id：" + schema);
        }
    }

    @Test
    @DisplayName("search_songs：keyword 必填、limit 可选")
    void searchSongsSchema() throws Exception {
        JsonNode schema = schemaOf("search_songs");

        assertTrue(schema.path("properties").has("keyword"), schema.toString());
        assertTrue(schema.path("properties").has("limit"), schema.toString());

        List<String> required = objectMapper.convertValue(
                schema.path("required"),
                objectMapper.getTypeFactory().constructCollectionType(
                        List.class, String.class));

        assertTrue(required.contains("keyword"), "keyword 必填：" + required);
        assertFalse(required.contains("limit"), "limit 可选：" + required);
    }

    @Test
    @DisplayName("参数描述写清了默认值与上限（模型据此决定 limit）")
    void parameterDescriptionsCarryBounds() {
        String schema = definitionOf("search_songs").inputSchema();

        assertTrue(schema.contains("默认 10"), "描述要写默认值：" + schema);
        assertTrue(schema.contains("50"), "描述要写上限：" + schema);
    }

    @Test
    @DisplayName("get_user_playlists：没有任何参数（用户身份由服务端提供）")
    void getUserPlaylistsTakesNoArgument() throws Exception {
        JsonNode schema = schemaOf("get_user_playlists");

        assertTrue(schema.path("properties").isEmpty(),
                "不应有任何参数：" + schema);
    }

    @Test
    @DisplayName("ID 类参数描述里写清了来源，避免模型编造 ID")
    void idParametersExplainTheirSource() {
        String songDetail = definitionOf("get_song_detail").description();
        assertTrue(songDetail.contains("不要自己编造"),
                "歌曲详情要提示 ID 来源：" + songDetail);

        String playlistSongs = definitionOf("get_playlist_songs").description();
        assertTrue(playlistSongs.contains("公开歌单"),
                "歌单曲目要说明可见性：" + playlistSongs);
    }

    @Test
    @DisplayName("管理员统计工具：days / limit 可选，且描述写明范围")
    void adminToolSchemaCarriesBounds() throws Exception {
        JsonNode stats = schemaOf("get_play_stats");
        assertTrue(stats.path("properties").has("days"), stats.toString());

        JsonNode top = schemaOf("get_top_songs");
        assertTrue(top.path("properties").has("days"), top.toString());
        assertTrue(top.path("properties").has("limit"), top.toString());

        List<String> required = objectMapper.convertValue(
                top.path("required"),
                objectMapper.getTypeFactory().constructCollectionType(
                        List.class, String.class));
        assertFalse(required.contains("days"), "days 可选：" + required);
        assertFalse(required.contains("limit"), "limit 可选：" + required);

        String schema = definitionOf("get_play_stats").inputSchema();
        assertTrue(schema.contains("1~90"), "描述要写范围：" + schema);
        assertTrue(schema.contains("默认 7"), "描述要写默认值：" + schema);
    }

    private static ToolCallback[] concat(ToolCallback[]... groups) {
        return Arrays.stream(groups)
                .flatMap(Arrays::stream)
                .toArray(ToolCallback[]::new);
    }

    private JsonNode schemaOf(String toolName) throws Exception {
        return objectMapper.readTree(definitionOf(toolName).inputSchema());
    }

    private ToolDefinition definitionOf(String toolName) {
        List<ToolCallback> matched = Arrays.stream(callbacks)
                .filter(callback -> toolName.equals(
                        callback.getToolDefinition().name()))
                .toList();

        assertEquals(1, matched.size(), "工具应恰好注册一次：" + toolName);
        return matched.get(0).getToolDefinition();
    }
}
