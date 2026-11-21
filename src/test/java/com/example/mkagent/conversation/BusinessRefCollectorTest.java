package com.example.mkagent.conversation;

import com.example.mkagent.model.AgentRunContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务引用抽取的规则测试。
 *
 * <p>这一层最容易出的不是崩溃，而是**语义漂移**：
 * 引用被悄悄换成另一批对象，模型照样能答，只是答错了。
 * 所以每条断言都锁一个"什么算同一批对象"的决定。
 */
class BusinessRefCollectorTest {

    private static final String SEARCH_RESULT = """
            {"ok":true,"data":[
              {"id":1001,"title":"城市霓虹","artist":"夜航星"},
              {"id":1005,"title":"失眠频率","artist":"夜航星"},
              {"id":1003,"title":"末班地铁","artist":"夜航星"}
            ],"meta":{"count":3}}
            """;

    private static final String PLAYLIST_SONGS = """
            {"ok":true,"data":[
              {"id":1001,"title":"城市霓虹","artist":"夜航星"},
              {"id":1005,"title":"失眠频率","artist":"夜航星"}
            ],"meta":{"count":2}}
            """;

    private static final String PLAYLISTS = """
            {"ok":true,"data":[
              {"id":97,"name":"夜航星精选","songCount":2}
            ],"meta":{"count":1}}
            """;

    private static final String CREATED = """
            {"ok":true,"data":{"playlistId":97,"name":"夜航星精选","songCount":2}}
            """;

    @Test
    @DisplayName("搜索结果按返回顺序进入引用")
    void searchResultKeepsOrder() {
        AgentRunContext ctx = new AgentRunContext();

        assertTrue(BusinessRefCollector.collect(ctx, "search_songs", SEARCH_RESULT));

        ConversationRefs refs = ctx.getConversationRefs();
        assertEquals(3, refs.songs().size());
        assertEquals(1001, refs.songs().get(0).id());
        assertEquals(1003, refs.songs().get(2).id());
        assertTrue(refs.renderBlock().contains("1. 城市霓虹"));
    }

    @Test
    @DisplayName("查看歌单曲目不会顶掉「刚才搜到的那几首」")
    void playlistViewDoesNotClobberSearchRefs() {
        AgentRunContext ctx = new AgentRunContext();
        BusinessRefCollector.collect(ctx, "search_songs", SEARCH_RESULT);

        // 中途查了一次歌单曲目（只有 2 首）
        BusinessRefCollector.collect(ctx, "get_playlist_songs", PLAYLIST_SONGS);

        ConversationRefs refs = ctx.getConversationRefs();
        assertEquals(3, refs.songs().size(),
                "歌单曲目不应覆盖搜索引用，否则「第三首」会凭空消失");
        assertEquals(1003, refs.songs().get(2).id());
    }

    @Test
    @DisplayName("新建歌单记成「最近确认的歌单」，同时保留已有歌单列表")
    void createdPlaylistBecomesLast() {
        AgentRunContext ctx = new AgentRunContext();
        BusinessRefCollector.collect(ctx, "get_user_playlists", PLAYLISTS);
        BusinessRefCollector.collect(ctx, "create_playlist", CREATED);

        ConversationRefs refs = ctx.getConversationRefs();
        assertEquals(97, refs.lastPlaylist().id());
        assertEquals(1, refs.playlists().size(), "已有歌单列表不该被挤掉");
        assertTrue(refs.renderBlock().contains("最近一次创建/确认的歌单"));
    }

    @Test
    @DisplayName("工具失败不产生任何引用")
    void failureProducesNoRefs() {
        AgentRunContext ctx = new AgentRunContext();

        assertFalse(BusinessRefCollector.collect(ctx, "search_songs",
                "{\"ok\":false,\"data\":null,\"meta\":{\"hint\":\"没找到\"}}"));

        assertTrue(ctx.getConversationRefs().isEmpty());
        assertNull(ctx.getConversationRefs().renderBlock());
    }

    @Test
    @DisplayName("二次编码的信封也能解析（框架有时会把结果再套一层字符串）")
    void doubleEncodedEnvelopeIsUnwrapped() {
        AgentRunContext ctx = new AgentRunContext();

        String doubleEncoded = "\"" + SEARCH_RESULT.trim()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";

        assertTrue(BusinessRefCollector.collect(ctx, "search_songs", doubleEncoded));
        assertEquals(3, ctx.getConversationRefs().songs().size());
    }

    @Test
    @DisplayName("引用能原样 JSON 往返，坏数据退化成空引用")
    void jsonRoundTrip() {
        ConversationRefs refs = ConversationRefs.EMPTY
                .withSongs(java.util.List.of(new ConversationRefs.SongRef(1001, "城市霓虹")))
                .withLastPlaylist(new ConversationRefs.PlaylistRef(97, "夜航星精选"));

        ConversationRefs restored = ConversationRefs.fromJson(refs.toJson());

        assertEquals(refs, restored);
        assertTrue(ConversationRefs.fromJson("{不是 JSON").isEmpty());
        assertTrue(ConversationRefs.fromJson(null).isEmpty());
    }
}
