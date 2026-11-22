package com.example.mkagent.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * 业务侧提供的摘要扩展（当前只有售后）。
     *
     * <p>没有实现时行为与之前完全一致——音乐确认卡不受影响。
     */
    private final List<ApprovalSummaryContributor> contributors;

    @Autowired
    public ApprovalSummaryBuilder(
            SongTitleLookup songTitleLookup,
            ApprovalProperties properties,
            List<ApprovalSummaryContributor> contributors
    ) {
        this.songTitleLookup = songTitleLookup;
        this.properties = properties;
        this.contributors = contributors == null ? List.of() : List.copyOf(contributors);
    }

    /**
     * 单测 / 手工构造用：没有业务扩展。
     */
    public ApprovalSummaryBuilder(
            SongTitleLookup songTitleLookup,
            ApprovalProperties properties
    ) {
        this(songTitleLookup, properties, List.of());
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

        /*
         * 业务扩展优先：售后等业务自己知道"确认卡上该固定哪些字段"。
         * 扩展失败（抛异常 / 返回 null）时退回默认渲染，
         * 绝不因为摘要不好看而阻断暂停。
         */
        ApprovalSummaryContributor.Contribution contributed = contribute(primary, userId);
        if (contributed != null) {
            String text = renderGeneric(
                    contributed.label(), contributed.details(), toolCalls.size());
            return new ApprovalSummary(
                    toolName,
                    contributed.label(),
                    null,
                    null,
                    List.of(),
                    toolCalls.size(),
                    text,
                    contributed.details());
        }

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
     * 找第一个认领该工具的扩展并取摘要。
     *
     * <p>任何异常都吞掉并返回 null：摘要只是辅助信息，
     * 不能因为它让整个审批流程失败。真正的一致性由检查点里的参数原文与哈希保证。
     */
    private ApprovalSummaryContributor.Contribution contribute(
            AssistantMessage.ToolCall primary,
            String userId
    ) {
        for (ApprovalSummaryContributor contributor : contributors) {
            try {
                if (!contributor.supports(primary.name())) {
                    continue;
                }
                ApprovalSummaryContributor.Contribution contribution =
                        contributor.contribute(primary.arguments(), userId);
                if (contribution != null) {
                    return contribution;
                }
            } catch (Exception e) {
                // 记一条 warn 就够：审批仍要正常暂停。
                // 不打印参数原文（可能含用户输入）。
                org.slf4j.LoggerFactory.getLogger(ApprovalSummaryBuilder.class)
                        .warn("审批摘要扩展失败，退回默认渲染：tool={}, reason={}",
                                primary.name(), e.getClass().getSimpleName());
            }
        }
        return null;
    }

    /**
     * 通用摘要文本：动作 + 若干"标签 值" + 有效期。
     *
     * <p>为什么不把参数原文拼进来：参数里可能有用户输入的任意文本，
     * 直接回显既难读，也容易把内部标识带到前端。
     */
    private String renderGeneric(
            String label,
            List<ApprovalSummary.Detail> details,
            int roundToolCount
    ) {
        StringBuilder text = new StringBuilder(label);

        if (details != null && !details.isEmpty()) {
            text.append("：");
            for (int i = 0; i < details.size(); i++) {
                if (i > 0) {
                    text.append("；");
                }
                text.append(details.get(i).label())
                        .append(" ")
                        .append(details.get(i).value());
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
            if (isWriteTool(call.name())) {
                return call;
            }
        }
        return toolCalls.get(0);
    }

    /**
     * 是否是需要确认的写工具：音乐写工具（固定名单）+ 任何被业务扩展认领的工具。
     *
     * <p>后者让"新增一个写工具"不需要改审批包的名单——
     * 扩展实现已经声明了它负责哪个工具。
     */
    private boolean isWriteTool(String toolName) {
        if (TOOL_LABELS.containsKey(toolName)) {
            return true;
        }
        for (ApprovalSummaryContributor contributor : contributors) {
            if (contributor.supports(toolName)) {
                return true;
            }
        }
        return false;
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
     *
     * <p>public 是因为业务侧的 {@link ApprovalSummaryContributor} 也要用同一套规则：
     * 摘要会进 SSE 事件，换行会把一条事件拆成两行。
     * 两处各写一份"清洗"迟早不一致。
     */
    public static String sanitize(String value, int maxCodePoints) {
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
