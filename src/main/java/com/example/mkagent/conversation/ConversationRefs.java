package com.example.mkagent.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话级"业务引用"：让模型能解析"这首歌""前两首""刚才那个歌单"。
 *
 * <p>来源只有**真实工具结果**（{@link BusinessRefCollector} 解析工具返回的信封），
 * 绝不从模型回答里正则抠 ID——模型可以写错、可以编，ID 必须来自数据库。
 *
 * <p>为什么不把工具调用本身存进会话再回放：
 * 回放旧的 tool_call / tool_response 会让模型把历史指令当成本轮指令重新执行
 * （"把前两首建歌单"被重放成再建一次）。这里只保留**对象事实**，
 * 需要写库时模型仍然要走工具 + 审批。
 *
 * <p>只保留"最近一次"：songs 被最近一次搜索/查看覆盖，playlists 被最近一次
 * 歌单列表覆盖。会话跨主题时旧引用自然失效，比无限累积更安全。
 */
public record ConversationRefs(
        List<SongRef> songs,
        List<PlaylistRef> playlists,
        PlaylistRef lastPlaylist
) {

    /** 单首歌的引用：只留 ID 与标题，够模型指代即可。 */
    public record SongRef(long id, String title) {
    }

    /** 歌单引用：ID + 名称。 */
    public record PlaylistRef(long id, String name) {
    }

    public static final ConversationRefs EMPTY =
            new ConversationRefs(List.of(), List.of(), null);

    /** 单次最多保留多少首歌：够覆盖"前三首/前五首"，再多只是白占 token。 */
    private static final int MAX_SONGS = 10;

    private static final int MAX_PLAYLISTS = 10;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public boolean isEmpty() {
        return songs.isEmpty() && playlists.isEmpty() && lastPlaylist == null;
    }

    /**
     * 用新一批搜索结果**替换**歌曲引用。
     *
     * <p>刻意是替换而不是合并：用户又搜了一次却没搜到东西时，
     * 旧的"前两首"必须失效。合并会让模型拿着上一次的结果回答"第一首"，
     * 那是最难发现的错误——用户以为问的是这次的结果。
     */
    public ConversationRefs withSongs(List<SongRef> next) {
        return new ConversationRefs(cap(next, MAX_SONGS), playlists, lastPlaylist);
    }

    /** 用新一批歌单列表替换歌单引用（同上，替换而非合并）。 */
    public ConversationRefs withPlaylists(List<PlaylistRef> next) {
        return new ConversationRefs(songs, cap(next, MAX_PLAYLISTS), lastPlaylist);
    }

    /**
     * 记下"最近创建/确认的歌单"。
     *
     * <p>只动这一项：新建歌单不应该把用户已有歌单列表挤掉，
     * 否则紧接着问"我有哪些歌单"就答不全了。
     */
    public ConversationRefs withLastPlaylist(PlaylistRef playlist) {
        return new ConversationRefs(songs, playlists, playlist);
    }

    /**
     * 渲染成给模型看的一段事实清单。
     *
     * <p>没有任何引用时返回 null（调用方据此决定不注入），
     * 避免给模型一段空的"已知对象"反而诱导它编造。
     */
    public String renderBlock() {
        if (isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[会话已知对象｜来自真实工具结果，不是模型自述]\n");

        if (!songs.isEmpty()) {
            sb.append("最近一次搜索到的歌曲（按返回顺序，序号可用于\"第一首/前两首\"）：\n");
            int index = 1;
            for (SongRef song : songs) {
                sb.append(index++).append(". ")
                        .append(safe(song.title()))
                        .append("（id=").append(song.id()).append("）\n");
            }
        }

        if (lastPlaylist != null) {
            sb.append("最近一次创建/确认的歌单：")
                    .append(safe(lastPlaylist.name()))
                    .append("（id=").append(lastPlaylist.id()).append("）\n");
        }

        if (!playlists.isEmpty()) {
            sb.append("当前用户的歌单：\n");
            for (PlaylistRef playlist : playlists) {
                sb.append("- ")
                        .append(safe(playlist.name()))
                        .append("（id=").append(playlist.id()).append("）\n");
            }
        }

        sb.append("规则：指代不清时按上面的顺序解析；只能使用上面列出的 id，"
                + "不要编造列表以外的 id；真正写库仍然要调用工具并等待用户批准。");

        return sb.toString();
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();

        List<Map<String, Object>> songNodes = new ArrayList<>();
        for (SongRef song : songs) {
            songNodes.add(Map.of("id", song.id(), "title", song.title() == null ? "" : song.title()));
        }
        root.put("songs", songNodes);

        List<Map<String, Object>> playlistNodes = new ArrayList<>();
        for (PlaylistRef playlist : playlists) {
            playlistNodes.add(Map.of(
                    "id", playlist.id(),
                    "name", playlist.name() == null ? "" : playlist.name()));
        }
        root.put("playlists", playlistNodes);

        if (lastPlaylist != null) {
            root.put("lastPlaylist", Map.of(
                    "id", lastPlaylist.id(),
                    "name", lastPlaylist.name() == null ? "" : lastPlaylist.name()));
        }

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从库里读回；坏数据一律当成"没有引用"，不让一条脏 JSON 卡住会话。
     */
    public static ConversationRefs fromJson(String json) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }

        try {
            JsonNode root = MAPPER.readTree(json);

            List<SongRef> songs = new ArrayList<>();
            for (JsonNode node : root.path("songs")) {
                if (node.path("id").canConvertToLong()) {
                    songs.add(new SongRef(node.path("id").asLong(), node.path("title").asText("")));
                }
            }

            List<PlaylistRef> playlists = new ArrayList<>();
            for (JsonNode node : root.path("playlists")) {
                if (node.path("id").canConvertToLong()) {
                    playlists.add(new PlaylistRef(
                            node.path("id").asLong(), node.path("name").asText("")));
                }
            }

            PlaylistRef last = null;
            JsonNode lastNode = root.path("lastPlaylist");
            if (lastNode.isObject() && lastNode.path("id").canConvertToLong()) {
                last = new PlaylistRef(
                        lastNode.path("id").asLong(), lastNode.path("name").asText(""));
            }

            return new ConversationRefs(songs, playlists, last);
        } catch (Exception e) {
            return EMPTY;
        }
    }

    /**
     * 折叠控制字符：这段文本会进入模型输入，
     * 换行/制表是结构的一部分，但其他控制字符（NUL、垂直制表等）
     * 只会把输入搞乱。
     */
    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == '\t') {
                sb.append(' ');
            } else if (Character.isISOControl(cp)) {
                continue;
            } else {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString().trim();
    }

    private static <T> List<T> cap(List<T> list, int max) {
        return list.size() <= max ? List.copyOf(list) : List.copyOf(list.subList(0, max));
    }
}
