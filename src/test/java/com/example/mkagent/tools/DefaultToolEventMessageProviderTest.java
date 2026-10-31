package com.example.mkagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DefaultToolEventMessageProvider 的 web_scrape 文案测试。
 *
 * 重点：抓取失败 / 被拒绝时**不能**显示“网页抓取完成”，
 * 否则前端会把一次失败误读成抓到了正文；
 * 同时文案里不允许出现 URL、路径等原始内容。
 */
class DefaultToolEventMessageProviderTest {

    private final DefaultToolEventMessageProvider provider =
            new DefaultToolEventMessageProvider();

    @Test
    void successfulScrapeShowsCompleted() {
        String raw = "\"页面标题：示例\\n\\n页面正文：正文内容\"";

        assertEquals("网页抓取完成", provider.resultMessage("web_scrape", raw));
    }

    @Test
    void retryableFailureDoesNotShowCompleted() {
        String raw = "\"网页抓取失败：Connection refused\"";

        String message = provider.resultMessage("web_scrape", raw);

        assertEquals("网页抓取失败", message);
        assertFalse(
                message.contains("完成"),
                "失败时不能出现“完成”字样"
        );
    }

    @Test
    void rejectedScrapeDoesNotShowCompletedAndHidesUrl() {
        String raw = "\"网页抓取被拒绝：不允许抓取本机或保留地址\"";

        String message = provider.resultMessage("web_scrape", raw);

        assertEquals("网页抓取被拒绝：该地址不允许抓取", message);
        assertFalse(message.contains("完成"));
        assertFalse(
                message.contains("127.0.0.1"),
                "SSE 文案不得回显被拦截的地址"
        );
    }

    @Test
    void redirectIsReportedAsRedirectNotCompleted() {
        String raw = "\"页面发生重定向，请使用最终 URL：https://example.com/final\"";

        String message = provider.resultMessage("web_scrape", raw);

        assertTrue(message.contains("重定向"), "实际：" + message);
        assertFalse(message.contains("完成"));
        assertFalse(
                message.contains("https://"),
                "SSE 文案不得回显具体 URL"
        );
    }

    @Test
    void emptyResultStillTreatedAsCompleted() {
        assertEquals("网页抓取完成", provider.resultMessage("web_scrape", null));
        assertEquals("网页抓取完成", provider.resultMessage("web_scrape", "  "));
    }

    @Test
    void otherToolsKeepOriginalBehaviour() {
        assertEquals(
                "文件读取完成",
                provider.resultMessage("read_file", "\"ok\"")
        );
        assertEquals(
                "任务结束已确认",
                provider.resultMessage("terminate_task", "\"done\"")
        );
        // 未登记工具走通用兜底。
        assertEquals(
                "工具调用完成",
                provider.resultMessage("unknown_tool", "\"x\"")
        );
    }

    // ==================== web_search：JSON 字符串字面量解码 ====================

    /**
     * 核心回归：搜索结果条数曾经**永远是 1**。
     *
     * 根因：@Tool 返回值经 MethodToolCallback 序列化为 JSON 字符串字面量
     * （外层双引号 + 换行转义成 \n），而代码按真实换行 "\n---\n" 分割，
     * 于是永远只得到 1 段。
     */
    @Test
    void searchResultCountIsCorrectForJsonEncodedResponse() {
        // 3 条结果，真实形态：带外层引号、换行是转义后的 \n
        String raw = "\"标题：A\\n链接：https://a\\n摘要：sa\\n---\\n"
                + "标题：B\\n链接：https://b\\n摘要：sb\\n---\\n"
                + "标题：C\\n链接：https://c\\n摘要：sc\\n\"";

        String message = provider.resultMessage("web_search", raw);

        assertEquals("网页搜索完成，获取 3 条结果", message);
    }

    /**
     * 未编码的原始字符串同样要工作（不是所有调用路径都经过序列化，
     * 例如 RetryableToolCallback 耗尽后返回的是纯文本）。
     */
    @Test
    void searchResultCountIsCorrectForRawResponse() {
        String raw = "标题：A\n链接：https://a\n摘要：sa\n---\n"
                + "标题：B\n链接：https://b\n摘要：sb";

        assertEquals(
                "网页搜索完成，获取 2 条结果",
                provider.resultMessage("web_search", raw)
        );
    }

    /**
     * startsWith("没有找到") 在带引号字符串上曾经永远为 false。
     */
    @Test
    void noResultsDetectedOnJsonEncodedResponse() {
        String raw = "\"没有找到与关键词相关的搜索结果：spring ai\"";

        assertEquals(
                "网页搜索完成，未找到相关结果",
                provider.resultMessage("web_search", raw)
        );
    }

    @Test
    void noResultsDetectedOnRawResponse() {
        assertEquals(
                "网页搜索完成，未找到相关结果",
                provider.resultMessage(
                        "web_search",
                        "没有找到与关键词相关的搜索结果：spring ai"
                )
        );
    }

    /**
     * startsWith("搜索工具执行失败") 在带引号字符串上曾经永远为 false，
     * 于是失败被误报成"搜索完成"。
     */
    @Test
    void searchFailureDetectedOnJsonEncodedResponse() {
        String raw = "\"搜索工具执行失败：Connection reset\"";

        assertEquals("网页搜索失败", provider.resultMessage("web_search", raw));
    }

    @Test
    void searchFailureDetectedOnRawResponse() {
        assertEquals(
                "网页搜索失败",
                provider.resultMessage("web_search", "搜索工具执行失败：Connection reset")
        );
    }

    // ==================== 重试耗尽文案 ====================

    /**
     * 重试耗尽后 RetryableToolCallback 返回纯文本失败文案，
     * 前端文案不能退化成"网页抓取完成"。
     */
    @Test
    void exhaustedScrapeIsNotReportedAsCompleted() {
        String raw = "工具 web_scrape 多次尝试仍失败：Read timed out。"
                + "请换用其他方式，或在无法完成时调用 terminate_task";

        String message = provider.resultMessage("web_scrape", raw);

        assertEquals("网页抓取多次重试后仍失败", message);
        assertFalse(message.contains("完成"), "耗尽时不能出现“完成”字样");
    }

    @Test
    void exhaustedSearchIsNotReportedAsSuccess() {
        String raw = "工具 web_search 多次尝试仍失败：Connection reset。"
                + "请换用其他方式，或在无法完成时调用 terminate_task";

        assertEquals(
                "网页搜索多次重试后仍失败",
                provider.resultMessage("web_search", raw)
        );
    }

    /**
     * 耗尽文案本身是纯文本，但也可能被再次序列化（防御性覆盖）。
     */
    @Test
    void exhaustedMarkerDetectedEvenWhenJsonEncoded() {
        String raw = "\"工具 web_scrape 多次尝试仍失败：Read timed out。"
                + "请换用其他方式，或在无法完成时调用 terminate_task\"";

        assertEquals(
                "网页抓取多次重试后仍失败",
                provider.resultMessage("web_scrape", raw)
        );
    }

    /**
     * 站点级熔断：本次调用根本没执行，前端文案必须是"已跳过"，
     * 既不能说"完成"，也不能说"失败"（会误导用户以为又试了一次）。
     */
    @Test
    void targetBlockedScrapeIsReportedAsSkipped() {
        String raw = "该站点连续失败，请换用其他来源或在无法完成时调用 terminate_task"
                + "（工具 web_scrape，目标 github.com，已连续重试耗尽 2 次，本次未再执行）";

        String message = provider.resultMessage("web_scrape", raw);

        assertEquals("该站点连续失败，已跳过本次抓取", message);
        assertFalse(message.contains("完成"), "熔断不是完成");
        assertFalse(message.contains("重试后仍失败"),
                "熔断与'这次试了但失败'必须区分开");
    }

    @Test
    void targetBlockedSearchIsReportedAsSkipped() {
        String raw = "该站点连续失败，请换用其他来源或在无法完成时调用 terminate_task"
                + "（工具 web_search，目标 同一目标，已连续重试耗尽 2 次，本次未再执行）";

        assertEquals(
                "该站点连续失败，已跳过本次搜索",
                provider.resultMessage("web_search", raw)
        );
    }

    // ==================== 音乐只读工具（M2b） ====================

    /**
     * 开始文案：四个音乐工具都要有专属文案，
     * 不能退化成"正在调用工具：search_songs"（把工具名暴露给用户）。
     */
    @Test
    void musicToolsHaveReadableStartMessages() {
        assertEquals("正在检索歌曲", provider.startMessage("search_songs"));
        assertEquals("正在读取歌曲详情", provider.startMessage("get_song_detail"));
        assertEquals("正在读取我的歌单", provider.startMessage("get_user_playlists"));
        assertEquals("正在读取歌单曲目", provider.startMessage("get_playlist_songs"));
    }

    /**
     * 检索结果的条数来自信封的 meta.count，不是自己数 JSON 数组。
     */
    @Test
    void musicSearchShowsSongCountFromEnvelope() {
        String envelope = ToolResponseEnvelope.okList(List.of(
                song(1001L, "夜航西飞", "林知夏"),
                song(1002L, "无声电台", "陈以安"),
                song(1003L, "雾中列车", "苏迟")
        ));

        assertEquals("找到 3 首歌曲", provider.resultMessage("search_songs", envelope));
    }

    /**
     * 真实链路里 @Tool 的返回值会被 MethodToolCallback 再序列化成
     * JSON 字符串字面量（首尾加引号、内部引号转义）。
     * 不解码就解析不出信封，文案会退化成"工具调用完成"。
     */
    @Test
    void musicEnvelopeIsParsedAfterJsonStringLiteralEncoding() throws Exception {
        String envelope = ToolResponseEnvelope.okList(List.of(
                song(1001L, "夜航西飞", "林知夏"),
                song(1002L, "无声电台", "陈以安")
        ));
        String encoded = new ObjectMapper().writeValueAsString(envelope);

        assertEquals(
                "找到 2 首歌曲",
                provider.resultMessage("search_songs", encoded)
        );
    }

    /**
     * ok=false（关键词无结果）不是"完成"，
     * 否则前端会以为这次检索有数据。
     */
    @Test
    void musicSearchFailureIsNotReportedAsSuccess() {
        String envelope = ToolResponseEnvelope.error("没有找到匹配「zzz」的歌曲");

        String message = provider.resultMessage("search_songs", envelope);

        assertEquals("歌曲检索未获得结果", message);
        assertFalse(message.contains("完成"), "失败时不能出现“完成”字样");
        assertFalse(message.contains("找到"), "失败时不能说“找到”");
    }

    /**
     * 歌单类工具的失败文案各自独立，不能共用"未获得结果"这种含糊说法。
     */
    @Test
    void musicPlaylistFailuresUseDistinctMessages() {
        String envelope = ToolResponseEnvelope.error("当前用户还没有创建任何歌单");

        assertEquals(
                "未获取到歌单",
                provider.resultMessage("get_user_playlists", envelope)
        );
        assertEquals(
                "未获取到歌单曲目",
                provider.resultMessage("get_playlist_songs", envelope)
        );
        assertEquals(
                "未获取到歌曲详情",
                provider.resultMessage("get_song_detail", envelope)
        );
    }

    /**
     * 截断必须在文案上体现：模型拿到的是被砍过的结果，
     * 用户看到"找到 50 首"却以为这就是全部，会误判。
     */
    @Test
    void truncatedMusicResultCarriesSuffix() {
        // 每条都塞满长文本，逼 okList 从尾部丢弃并标记 truncated。
        String filler = "x".repeat(900);
        String envelope = ToolResponseEnvelope.okList(List.of(
                song(1001L, filler, filler),
                song(1002L, filler, filler),
                song(1003L, filler, filler)
        ));

        String message = provider.resultMessage("search_songs", envelope);

        assertTrue(message.contains("结果已截断"), "实际：" + message);
        assertTrue(message.startsWith("找到 "), "实际：" + message);
    }

    /**
     * 详情类只有一条，count=1，文案不应带条数（"找到 1 首"很怪）。
     */
    @Test
    void musicDetailDoesNotShowCount() {
        String envelope = ToolResponseEnvelope.okSingle(song(1001L, "夜航西飞", "林知夏"));

        assertEquals("已获取歌曲详情", provider.resultMessage("get_song_detail", envelope));
    }

    /**
     * 脱敏：事件文案会经 SSE 推给前端，
     * 不允许出现歌曲标题、歌手、userId 或原始 JSON。
     */
    @Test
    void musicEventTextLeaksNoContent() {
        String envelope = ToolResponseEnvelope.okList(List.of(
                song(1001L, "夜航西飞", "林知夏")
        ));

        String message = provider.resultMessage("search_songs", envelope);

        assertFalse(message.contains("夜航西飞"), "不得回显歌曲标题");
        assertFalse(message.contains("林知夏"), "不得回显歌手名");
        assertFalse(message.contains("1001"), "不得回显歌曲 ID");
        assertFalse(message.contains("{" ), "不得回显原始 JSON");
        assertFalse(message.contains("music-user"), "不得回显 userId");
    }

    /**
     * 信封解析失败（工具没按约定返回）时退回通用文案，
     * 绝不能把原始 JSON 推给前端。
     */
    @Test
    void musicMalformedEnvelopeFallsBackToGenericMessage() {
        assertEquals(
                "工具调用完成",
                provider.resultMessage("search_songs", "not a json at all")
        );
        assertEquals(
                "工具调用完成",
                provider.resultMessage("search_songs", null)
        );
    }

    // ==================== 管理员统计工具（M2c） ====================

    @Test
    @DisplayName("管理员工具的开始文案独立，不退化成工具名")
    void adminToolsHaveReadableStartMessages() {
        assertEquals("正在统计播放数据", provider.startMessage("get_play_stats"));
        assertEquals("正在统计歌曲排行", provider.startMessage("get_top_songs"));
    }

    @Test
    @DisplayName("播放总览：成功时只说“已生成”，不暴露具体数字")
    void playStatsResultMessage() {
        String envelope = ToolResponseEnvelope.okSingle(Map.of(
                "totalPlays", 128,
                "activeDays", 7,
                "days", List.of()
        ));

        assertEquals("播放统计已生成",
                provider.resultMessage("get_play_stats", envelope));
    }

    @Test
    @DisplayName("歌曲排行：带条数，截断时追加标记")
    void topSongsResultMessage() {
        String envelope = ToolResponseEnvelope.okList(List.of(
                Map.of("songId", 1001, "title", "夜航西飞", "artist", "林知夏", "plays", 9)
        ));

        String message = provider.resultMessage("get_top_songs", envelope);

        assertEquals("歌曲排行已生成（1 首）", message);
        assertFalse(message.contains("夜航西飞"), "不得回显歌曲标题");
        assertFalse(message.contains("林知夏"), "不得回显歌手名");
    }

    @Test
    @DisplayName("管理员工具失败：各自独立文案，且不显示“完成”")
    void adminToolFailureMessages() {
        String envelope = ToolResponseEnvelope.error(
                AdminAnalyticsTool.NO_PERMISSION_MESSAGE);

        assertEquals("未获取到播放统计",
                provider.resultMessage("get_play_stats", envelope));
        assertEquals("未获取到歌曲排行",
                provider.resultMessage("get_top_songs", envelope));

        assertFalse(
                provider.resultMessage("get_play_stats", envelope).contains("完成"));
    }

    @Test
    @DisplayName("管理员统计文案不含任何用户标识")
    void adminToolMessagesLeakNoUserIdentity() {
        String envelope = ToolResponseEnvelope.okSingle(Map.of(
                "totalPlays", 1, "activeDays", 1, "days", List.of()));

        String message = provider.resultMessage("get_play_stats", envelope);

        assertFalse(message.contains("music-user"), "不得出现 userId");
        assertFalse(message.contains("music-admin"), "不得出现 userId");
    }

    // ==================== 写工具（M2d） ====================

    /**
     * 写工具的文案必须与"读"区分开：用户看到"正在创建歌单"才知道
     * 这次调用会产生副作用（M3 之后前面还会有一次人工确认）。
     */
    @Test
    @DisplayName("写工具的开始与完成文案")
    void writeToolMessages() {
        assertEquals("正在创建歌单", provider.startMessage("create_playlist"));
        assertEquals("正在向歌单添加歌曲",
                provider.startMessage("add_songs_to_playlist"));

        assertEquals("歌单创建成功",
                provider.resultMessage("create_playlist", "{\"ok\":true}"));
        assertEquals("歌曲已加入歌单",
                provider.resultMessage("add_songs_to_playlist", "{\"ok\":true}"));
    }

    /**
     * 写工具的结果文案里绝不能出现歌单名 / 歌曲名：
     * 那是用户输入，可能含个人信息，也可能被塞进注入文本，
     * 而 SSE 文案会直接推到浏览器。
     */
    @Test
    @DisplayName("写工具文案不回显歌单名与歌曲名")
    void writeToolMessagesLeakNoUserInput() throws Exception {
        String envelope = ToolResponseEnvelope.okSingle(Map.of(
                "playlistId", 2004,
                "name", "我的私密歌单-张三",
                "songCount", 2
        ));
        String encoded = new ObjectMapper().writeValueAsString(envelope);

        String message = provider.resultMessage("create_playlist", encoded);

        assertEquals("歌单创建成功", message);
        assertFalse(message.contains("张三"), "不得回显歌单名");
        assertFalse(message.contains("私密"), "不得回显歌单名");
        assertFalse(message.contains("2004"), "不得回显歌单 ID");
    }

    private static Map<String, Object> song(Long id, String title, String artist) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("title", title);
        row.put("artist", artist);
        row.put("album", "示例专辑");
        row.put("durationSeconds", 215);
        row.put("genre", "流行");
        row.put("releaseYear", 2021);
        return row;
    }
}
