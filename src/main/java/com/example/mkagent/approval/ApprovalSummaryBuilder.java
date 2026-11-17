package com.example.mkagent.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 构造审批摘要（给用户看）。
 *
 * 原则：
 * 1. **绝不**把工具参数原文拼进摘要——参数里可能有用户输入的任意文本，
 *    直接回显既难读，也容易把不该展示的内容（路径、内部 ID）带到前端；
 * 2. 控制字符（换行、制表、NUL 等）一律折叠成空格：
 *    摘要会出现在 SSE 事件里，换行会把一条事件拆成两行，前端解析直接坏掉；
 * 3. 长度硬截断到 {@link ApprovalSummary#MAX_TEXT_LENGTH} 个码点
 *    （不是 char，避免把 emoji 截成半个代理对）。
 */
@Component
public class ApprovalSummaryBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 摘要里最多展示几首歌。
     */
    private static final int MAX_PREVIEW_SONGS = 5;

    /**
     * 单首歌名在摘要里的最大长度。
     */
    private static final int MAX_SONG_TITLE_LENGTH = 60;

    private static final Map<String, String> TOOL_LABELS = Map.of(
            "create_playlist", "创建歌单",
            "add_songs_to_playlist", "向歌单添加歌曲"
    );

    private final SongTitleLookup songTitleLookup;

    private final ApprovalProperties properties;

    public ApprovalSummaryBuilder(
            SongTitleLookup songTitleLookup,
            ApprovalProperties properties
    ) {
        this.songTitleLookup = songTitleLookup;
        this.properties = properties;
    }

    /**
     * 根据本轮待执行的工具调用构造摘要（无用户上下文）。
     *
     * <p>保留这个单参重载是为了既有调用方与单测：拿不到用户时，
     * 需要反查歌单名的场景会退化成"未指定歌单名"——宁可不显示，
     * 也不跨用户查。
     *
     * @param toolCalls 本轮全部工具调用（整轮暂停，可能多于一个）
     */
    public ApprovalSummary build(List<AssistantMessage.ToolCall> toolCalls) {
        return build(toolCalls, null);
    }

    /**
     * 根据本轮待执行的工具调用构造摘要。
     *
     * @param toolCalls 本轮全部工具调用（整轮暂停，可能多于一个）
     * @param userId    当前任务的用户；用于反查"要改的是哪个歌单"，可为 null
     */
    public ApprovalSummary build(
            List<AssistantMessage.ToolCall> toolCalls,
            String userId
    ) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalArgumentException("审批摘要缺少工具调用");
        }

        AssistantMessage.ToolCall primary = pickPrimary(toolCalls);
        String toolName = primary.name();
        String label = TOOL_LABELS.getOrDefault(toolName, "执行工具 " + toolName);

        String playlistName = null;
        Integer songCount = null;
        List<String> songs = List.of();

        JsonNode args = parseArgs(primary.arguments());
        if (args != null) {
            if (args.hasNonNull("name")) {
                playlistName = sanitize(args.get("name").asText(), 60);
            } else if (args.hasNonNull("playlistId")) {
                /*
                 * 加歌类工具的参数只有 playlistId。审批卡必须让用户看清
                 * "往哪个歌单里加"，所以这里补一次只读查询（只查本人歌单）。
                 *
                 * 查不到（别人的歌单 / 已删除 / 无上下文）时保持 null，
                 * 前端会显示"未指定歌单名"——不猜、不编。
                 */
                playlistName = lookupPlaylistName(args.get("playlistId").asLong(), userId);
            }
            List<Long> songIds = readSongIds(args);
            if (!songIds.isEmpty()) {
                songCount = songIds.size();
                songs = previewTitles(songIds);
            }
        }

        String text = render(
                label, playlistName, songCount, songs, toolCalls.size());

        return new ApprovalSummary(
                toolName, label, playlistName, songCount, songs, toolCalls.size(), text);
    }

    /**
     * 按歌单 ID 取歌单名（仅本人）。
     *
     * <p>userId 由调用方显式传入，**不读 ThreadLocal**：摘要是在"模型刚返回
     * 工具调用、尚未执行任何工具"的时刻构造的，那时 Agent 运行上下文
     * 并没有绑定到当前线程（绑定只包住工具执行那一小段），
     * 靠 {@code AgentRunContextHolder.current()} 只会永远拿到 null。
     *
     * <p>查询失败返回 null：摘要只是辅助信息，不能因为它让审批流程失败。
     */
    private String lookupPlaylistName(long playlistId, String userId) {
        if (playlistId <= 0 || userId == null || userId.isBlank()) {
            return null;
        }

        try {
            String name = songTitleLookup.playlistName(playlistId, userId);
            return name == null ? null : sanitize(name, 60);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 选"主工具"：优先写工具，否则第一个。
     *
     * 一轮里可能既有读又有写，摘要必须指向真正需要确认的那个，
     * 不能拿读工具的名字去问用户"要不要执行"。
     */
    private AssistantMessage.ToolCall pickPrimary(
            List<AssistantMessage.ToolCall> toolCalls
    ) {
        for (AssistantMessage.ToolCall call : toolCalls) {
            if (TOOL_LABELS.containsKey(call.name())) {
                return call;
            }
        }
        return toolCalls.get(0);
    }

    private String render(
            String label,
            String playlistName,
            Integer songCount,
            List<String> songs,
            int roundToolCount
    ) {
        StringBuilder text = new StringBuilder(label);

        if (playlistName != null) {
            text.append("《").append(playlistName).append("》");
        }
        if (songCount != null) {
            text.append("，共 ").append(songCount).append(" 首");
            if (!songs.isEmpty()) {
                text.append("：").append(String.join("、", songs));
                if (songCount > songs.size()) {
                    text.append(" 等");
                }
            }
        }
        if (roundToolCount > 1) {
            text.append("（本轮共 ").append(roundToolCount).append(" 个操作，一并等待确认）");
        }
        text.append("。有效期 ")
                .append(properties.getTtl().toMinutes())
                .append(" 分钟。");

        return sanitize(text.toString(), ApprovalSummary.MAX_TEXT_LENGTH);
    }

    private List<Long> readSongIds(JsonNode args) {
        JsonNode node = args.get("songIds");
        List<Long> ids = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return ids;
        }
        for (JsonNode item : node) {
            if (item.canConvertToLong()) {
                ids.add(item.asLong());
            }
        }
        return ids;
    }

    private List<String> previewTitles(List<Long> songIds) {
        List<String> titles = songTitleLookup.titles(songIds);
        List<String> preview = new ArrayList<>();
        for (String title : titles) {
            if (preview.size() >= MAX_PREVIEW_SONGS) {
                break;
            }
            preview.add(sanitize(title, MAX_SONG_TITLE_LENGTH));
        }
        return preview;
    }

    private JsonNode parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(arguments);
        } catch (Exception e) {
            /*
             * 参数不是合法 JSON 属于异常情况（框架会序列化合法 JSON）。
             * 这里返回 null，摘要退化成"执行工具 X"，
             * 不因为"摘要不好看"就阻断暂停——真正的一致性由
             * 检查点里的参数原文与哈希保证。
             */
            return null;
        }
    }

    /**
     * 去控制字符、折叠空白、按码点截断。
     */
    static String sanitize(String value, int maxCodePoints) {
        if (value == null) {
            return "";
        }

        StringBuilder cleaned = new StringBuilder(value.length());
        boolean lastWasSpace = false;

        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);

            if (Character.isISOControl(codePoint) || codePoint == 0x2028
                    || codePoint == 0x2029) {
                if (!lastWasSpace && cleaned.length() > 0) {
                    cleaned.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }

            if (codePoint == ' ') {
                if (lastWasSpace || cleaned.length() == 0) {
                    continue;
                }
                cleaned.append(' ');
                lastWasSpace = true;
                continue;
            }

            cleaned.appendCodePoint(codePoint);
            lastWasSpace = false;
        }

        String result = cleaned.toString().trim();

        if (result.codePointCount(0, result.length()) <= maxCodePoints) {
            return result;
        }

        int end = result.offsetByCodePoints(0, maxCodePoints);
        return result.substring(0, end) + "…";
    }
}
