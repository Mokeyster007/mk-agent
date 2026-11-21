package com.example.mkagent.conversation;

import com.example.mkagent.model.AgentRunContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 从**真实工具结果**里抽取业务引用，累积到当前 run 的上下文上。
 *
 * <p>只认工具返回的信封 JSON（{@code {"ok":true,"data":...}}），
 * 不读模型的话。原因很直接：模型可以把歌名说错、把 id 编出来，
 * 而工具结果是数据库查出来的。
 *
 * <p>为什么是静态工具类而不是 Spring Bean：它没有状态、没有依赖，
 * 唯一需要落库的动作（{@code ConversationService#saveRefs}）由调用方
 * （{@code ToolCallAgent}）在同一个位置顺手做掉。少一层注入，
 * 就少一处"装配忘了配"的失败点。
 *
 * <p>失败一律静默：引用只是"让下一轮能听懂指代"的辅助信息，
 * 不能因为它让工具调用失败，也不能因为它改变工具结果本身。
 */
public final class BusinessRefCollector {

    private static final Logger log =
            LoggerFactory.getLogger(BusinessRefCollector.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BusinessRefCollector() {
    }

    /**
     * 收集一次工具结果。
     *
     * @param ctx          当前 run 上下文（引用会累积到它上面）
     * @param toolName     工具名
     * @param responseData 工具返回的原始 JSON 字符串
     * @return 引用是否发生变化；调用方据此决定要不要落库
     */
    public static boolean collect(AgentRunContext ctx, String toolName, String responseData) {
        if (ctx == null || toolName == null || responseData == null || responseData.isBlank()) {
            return false;
        }

        try {
            JsonNode root = MAPPER.readTree(responseData);

            /*
             * 工具结果是"信封 JSON 的字符串"。某些链路（重试包装 / 框架序列化）
             * 会再套一层 JSON 字符串，于是 readTree 得到的是一个 TextNode。
             * 这里剥掉这层，否则 root.path("ok") 永远是缺失的，
             * 引用会被静默地当成"工具失败"全部丢掉——而且没有任何报错。
             */
            if (root.isTextual()) {
                root = MAPPER.readTree(root.asText());
            }

            if (!root.path("ok").asBoolean(false)) {
                // 失败结果不产生任何事实：查不到就是查不到，不能拿错误信息当引用
                return false;
            }

            JsonNode data = root.path("data");
            ConversationRefs before = ctx.getConversationRefs();

            switch (toolName) {
                /*
                 * 只有**搜索**结果进入"歌曲引用"。
                 *
                 * 不让 get_playlist_songs / get_song 覆盖它，是因为用户说的
                 * "刚才第三首"通常指"刚才搜出来的第三首"。如果中途查了一次
                 * 某个歌单的曲目（2 首），引用被替换成那 2 首，"第三首"就直接
                 * 不存在了——真实联调里模型因此答成了"歌单里只有两首"。
                 * 歌单曲目本来就能用 playlistId 现场查，不需要占用这份引用。
                 */
                case "search_songs" ->
                        ctx.setConversationRefs(before.withSongs(songsOf(data)));
                case "get_user_playlists" ->
                        ctx.setConversationRefs(before.withPlaylists(playlistsOf(data)));
                case "create_playlist" -> {
                    ConversationRefs.PlaylistRef created = createdPlaylistOf(data);
                    if (created != null) {
                        ctx.setConversationRefs(before.withLastPlaylist(created));
                    }
                }
                default -> {
                    // 其他工具不产生引用：
                    // 它们不改变"用户指的是哪首歌/哪个歌单"。
                    return false;
                }
            }

            return !ctx.getConversationRefs().equals(before);
        } catch (Exception e) {
            log.debug("业务引用抽取失败（忽略）：tool={}, error={}", toolName, e.toString());
            return false;
        }
    }

    /**
     * 歌曲列表：{@code [{"id":1001,"title":"城市霓虹",...}]}。
     *
     * <p>保留顺序——"第一首""前两首"全靠这个顺序。
     * 查不到（空数组）时返回空列表，表示"这次没有结果"，
     * 由 {@link ConversationRefs#withSongs} 清掉旧引用。
     */
    private static List<ConversationRefs.SongRef> songsOf(JsonNode data) {
        if (!data.isArray()) {
            return List.of();
        }

        List<ConversationRefs.SongRef> songs = new ArrayList<>();
        for (JsonNode node : data) {
            if (!node.path("id").canConvertToLong()) {
                continue;
            }
            String title = node.path("title").asText("");
            if (title.isBlank()) {
                // 没标题的条目对"指代"没有意义，宁可不收
                continue;
            }
            songs.add(new ConversationRefs.SongRef(node.path("id").asLong(), title));
        }

        return songs;
    }

    /**
     * 歌单列表：{@code [{"id":2,"name":"周末清晨","songCount":6}]}。
     */
    private static List<ConversationRefs.PlaylistRef> playlistsOf(JsonNode data) {
        if (!data.isArray()) {
            return List.of();
        }

        List<ConversationRefs.PlaylistRef> playlists = new ArrayList<>();
        for (JsonNode node : data) {
            if (!node.path("id").canConvertToLong()) {
                continue;
            }
            String name = node.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            playlists.add(new ConversationRefs.PlaylistRef(node.path("id").asLong(), name));
        }

        return playlists;
    }

    /**
     * 新建歌单：{@code {"playlistId":33,"name":"...","songCount":1}}。
     *
     * <p>注意字段名是 {@code playlistId} 而不是 {@code id}——
     * 写工具返回的是"刚建出来的歌单"，与查询工具的形状不同。
     */
    private static ConversationRefs.PlaylistRef createdPlaylistOf(JsonNode data) {
        if (!data.isObject() || !data.path("playlistId").canConvertToLong()) {
            return null;
        }

        String name = data.path("name").asText("");
        if (name.isBlank()) {
            return null;
        }

        return new ConversationRefs.PlaylistRef(
                data.path("playlistId").asLong(), name);
    }
}
