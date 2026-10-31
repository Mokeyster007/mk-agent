package com.example.mkagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 默认工具事件消息提供者。
 *
 * 为每个已知工具维护一组用户可读文案；
 * 未登记的工具走通用兜底文案。
 * 所有文案都经过脱敏：不包含路径、密钥、完整原始结果。
 */
@Component
public class DefaultToolEventMessageProvider
        implements ToolEventMessageProvider {

    /**
     * 音乐只读工具：完成文案需要读信封里的 meta.count，不能只查表。
     */
    private static final Set<String> MUSIC_TOOLS = Set.of(
            "search_songs",
            "get_song_detail",
            "get_user_playlists",
            "get_playlist_songs",
            "get_play_stats",
            "get_top_songs"
    );

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 工具名称 → 调用开始文案。
     */
    private static final Map<String, String> START_MESSAGES = Map.ofEntries(
            Map.entry("web_search", "正在搜索互联网公开资料"),
            Map.entry("web_scrape", "正在抓取网页正文"),
            Map.entry("read_file", "正在读取文件内容"),
            Map.entry("write_file", "正在写入文件"),
            Map.entry("generate_pdf", "正在生成 PDF 文件"),
            Map.entry("download_resource", "正在下载公开资源"),
            Map.entry("terminate_task", "任务收尾中"),
            Map.entry("demo_inventory_check", "正在查询库存信息"),
            Map.entry("search_songs", "正在检索歌曲"),
            Map.entry("get_song_detail", "正在读取歌曲详情"),
            Map.entry("get_user_playlists", "正在读取我的歌单"),
            Map.entry("get_playlist_songs", "正在读取歌单曲目"),
            Map.entry("get_play_stats", "正在统计播放数据"),
            Map.entry("get_top_songs", "正在统计歌曲排行"),
            /*
             * 写工具：文案里**不出现歌单名与歌曲名**。
             * 这些是用户输入，可能含个人信息，也可能被塞进注入文本；
             * 而 SSE 文案会直接推到浏览器。
             */
            Map.entry("create_playlist", "正在创建歌单"),
            Map.entry("add_songs_to_playlist", "正在向歌单添加歌曲")
    );

    /**
     * 工具名称 → 调用完成文案（不需要根据结果细分时使用）。
     *
     * web_scrape 不在此表中：它的完成文案必须结合返回值判断
     * （成功 / 可重试失败 / 被拒绝 / 重定向），见 webScrapeResultMessage。
     * 音乐类工具同理：文案要带条数，见 musicResultMessage。
     */
    private static final Map<String, String> RESULT_MESSAGES = Map.of(
            "read_file", "文件读取完成",
            "write_file", "文件写入完成",
            "generate_pdf", "PDF 生成完成",
            "download_resource", "资源下载完成",
            "terminate_task", "任务结束已确认",
            "demo_inventory_check", "库存查询完成",
            "create_playlist", "歌单创建成功",
            "add_songs_to_playlist", "歌曲已加入歌单"
    );

    @Override
    public String startMessage(String toolName) {
        return START_MESSAGES.getOrDefault(
                toolName,
                "正在调用工具：" + toolName
        );
    }

    @Override
    public String resultMessage(String toolName, String rawResult) {
        /*
         * 必须先解码：
         * @Tool 返回值经 MethodToolCallback 序列化后是 JSON 字符串字面量
         * （首尾带引号、换行转义成 \n）。不解码的话：
         *   - startsWith("没有找到") 永远 false；
         *   - split("\n---\n") 永远只得到 1 段 → “搜索结果永远是 1 条”。
         * ToolResultTexts 对未编码的原始文本同样安全（原样返回）。
         */
        String result = ToolResultTexts.decode(rawResult);

        if ("web_search".equals(toolName)) {
            return webSearchResultMessage(result);
        }

        if ("web_scrape".equals(toolName)) {
            return webScrapeResultMessage(result);
        }

        if (MUSIC_TOOLS.contains(toolName)) {
            return musicResultMessage(toolName, result);
        }

        return RESULT_MESSAGES.getOrDefault(toolName, "工具调用完成");
    }

    /**
     * 音乐只读工具的完成文案：只暴露"条数"，不暴露内容。
     *
     * 信封是 {@link ToolResponseEnvelope} 产出的 JSON：
     * ok=false 说明业务上没拿到数据（关键词无结果 / 歌单无权查看），
     * 不能显示成"完成"，否则前端会以为查到了。
     */
    private String musicResultMessage(String toolName, String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return "工具调用完成";
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawResult);

            if (!root.path("ok").asBoolean(false)) {
                return switch (toolName) {
                    case "search_songs" -> "歌曲检索未获得结果";
                    case "get_song_detail" -> "未获取到歌曲详情";
                    case "get_user_playlists" -> "未获取到歌单";
                    case "get_play_stats" -> "未获取到播放统计";
                    case "get_top_songs" -> "未获取到歌曲排行";
                    default -> "未获取到歌单曲目";
                };
            }

            int count = root.path("meta").path("count").asInt(0);
            boolean truncated =
                    root.path("meta").path("truncated").asBoolean(false);
            String suffix = truncated ? "（结果已截断）" : "";

            return switch (toolName) {
                case "search_songs" -> "找到 " + count + " 首歌曲" + suffix;
                case "get_user_playlists" -> "找到 " + count + " 个歌单" + suffix;
                case "get_playlist_songs" -> "歌单中有 " + count + " 首歌" + suffix;
                case "get_play_stats" -> "播放统计已生成";
                case "get_top_songs" -> "歌曲排行已生成（" + count + " 首）" + suffix;
                default -> "已获取歌曲详情";
            };
        } catch (Exception e) {
            /*
             * 解析失败只可能是"工具没按信封返回"（编程错误）。
             * 退回通用文案，绝不把原始 JSON 推给前端。
             */
            return "工具调用完成";
        }
    }

    /**
     * 网页抓取结果摘要。
     *
     * 抓取工具用两种前缀区分结果：
     * - "网页抓取失败："：可重试的临时故障（网络异常、5xx）
     * - "网页抓取被拒绝："：不可重试的拒绝（SSRF 校验、4xx、重定向被拒）
     * - "页面发生重定向"：需要模型换最终地址再抓
     *
     * 这些情况都不能显示“网页抓取完成”，
     * 否则前端会误以为抓到了正文。
     */
    private String webScrapeResultMessage(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return "网页抓取完成";
        }

        /*
         * 站点级熔断（"该站点连续失败……本次未再执行"）。
         *
         * 必须最先判断：这条文案既不含"网页抓取失败"也不含
         * "多次尝试仍失败"，漏判就会退到最后的"网页抓取完成"，
         * 前端会误以为抓到了正文。
         */
        if (rawResult.contains(ToolResultMarkers.TARGET_BLOCKED)) {
            return "该站点连续失败，已跳过本次抓取";
        }

        /*
         * 重试耗尽文案（"工具 web_scrape 多次尝试仍失败：..."）。
         *
         * 必须放在最前面判断：它既不含"网页抓取失败"也不含
         * "网页抓取被拒绝"，漏判就会退到最后的"网页抓取完成"，
         * 前端会误以为抓到了正文。
         */
        if (rawResult.contains(ToolResultMarkers.EXHAUSTED)) {
            return "网页抓取多次重试后仍失败";
        }

        if (rawResult.contains("网页抓取被拒绝")) {
            return "网页抓取被拒绝：该地址不允许抓取";
        }

        if (rawResult.contains("网页抓取失败")) {
            return "网页抓取失败";
        }

        if (rawResult.contains("页面发生重定向")) {
            return "页面发生重定向，等待模型改用最终地址";
        }

        return "网页抓取完成";
    }

    /**
     * 网页搜索结果摘要：只暴露结果条数，不暴露具体内容。
     */
    private String webSearchResultMessage(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return "网页搜索完成";
        }

        if (rawResult.contains(ToolResultMarkers.TARGET_BLOCKED)) {
            return "该站点连续失败，已跳过本次搜索";
        }

        if (rawResult.contains(ToolResultMarkers.EXHAUSTED)) {
            return "网页搜索多次重试后仍失败";
        }

        if (rawResult.startsWith("没有找到")) {
            return "网页搜索完成，未找到相关结果";
        }

        if (rawResult.startsWith("搜索工具执行失败")) {
            return "网页搜索失败";
        }

        // WebSearchTool 以 "\n---\n" 分隔每条结果，据此统计条数。
        int count = rawResult.split("\n---\n").length;

        return "网页搜索完成，获取 " + count + " 条结果";
    }
}
