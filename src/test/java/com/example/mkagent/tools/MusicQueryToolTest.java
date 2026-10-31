package com.example.mkagent.tools;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.music.MusicCatalogRepository;
import com.example.mkagent.music.MusicCatalogRepository.PlaylistView;
import com.example.mkagent.music.MusicCatalogRepository.SongView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 音乐只读工具（桩仓库，不连数据库、不启动 Spring）。
 *
 * 覆盖点：
 * 1. **没有上下文就失败关闭**：抛异常，不回落匿名；
 * 2. 参数校验与 limit 归一化（默认 10 / 上限 50 / 非法值不报错而是夹取）；
 * 3. 返回值一律是信封，业务失败 ok=false 且不抛异常；
 * 4. 仓库异常不外泄（模型看到的是脱敏文案，不是 SQL）。
 */
class MusicQueryToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MusicCatalogRepository repository;

    private MusicQueryTool tool;

    @BeforeEach
    void setUp() {
        repository = mock(MusicCatalogRepository.class);
        tool = new MusicQueryTool(repository);

        AgentRunContext ctx = new AgentRunContext();
        ctx.setUserId("music-user-1");
        AgentRunContextHolder.bind(ctx);
    }

    @AfterEach
    void tearDown() {
        AgentRunContextHolder.clear();
    }

    // ===== 失败关闭 =====

    @Test
    @DisplayName("没有任务上下文：四个工具都抛异常（失败关闭）")
    void allToolsFailClosedWithoutContext() {
        AgentRunContextHolder.clear();

        assertThrows(IllegalStateException.class,
                () -> tool.searchSongs("夜", 5));
        assertThrows(IllegalStateException.class,
                () -> tool.getSongDetail(1001L));
        assertThrows(IllegalStateException.class,
                tool::getUserPlaylists);
        assertThrows(IllegalStateException.class,
                () -> tool.getPlaylistSongs(2001L));

        // 失败关闭意味着"根本没查库"，不是"查了但返回空"。
        verify(repository, never()).searchSongs(anyString(), anyInt());
        verify(repository, never()).findUserPlaylists(anyString());
    }

    // ===== search_songs =====

    @Test
    @DisplayName("空关键词：返回 ok=false，且不查库")
    void blankKeywordIsRejected() throws Exception {
        JsonNode root = objectMapper.readTree(tool.searchSongs("   ", 10));

        assertFalse(root.path("ok").asBoolean());
        assertTrue(root.path("meta").path("hint").asText().contains("不能为空"));
        verify(repository, never()).searchSongs(anyString(), anyInt());
    }

    @Test
    @DisplayName("limit 归一化：null/0/负数 → 10，超过 50 → 50")
    void limitIsClamped() {
        when(repository.searchSongs(anyString(), anyInt()))
                .thenReturn(List.of());

        tool.searchSongs("夜", null);
        tool.searchSongs("夜", 0);
        tool.searchSongs("夜", -3);
        tool.searchSongs("夜", 100);

        /*
         * 用 captor 一次断言整个序列，而不是连续四次 verify：
         * verify(mock) 默认是"累计恰好 1 次"，
         * 第二次调用之后所有 verify 都会变成 TooManyActualInvocations。
         */
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(repository, times(4)).searchSongs(eq("夜"), limits.capture());
        assertEquals(List.of(10, 10, 10, 50), limits.getAllValues());
    }

    @Test
    @DisplayName("有结果：ok=true，count 与条数一致")
    void searchReturnsEnvelope() throws Exception {
        when(repository.searchSongs("夜", 10)).thenReturn(List.of(
                new SongView(1001L, "城市霓虹", "夜航星", "城市霓虹",
                        214, "synthpop", 2021),
                new SongView(1002L, "深夜电台", "夜航星", "城市霓虹",
                        238, "synthpop", 2021)
        ));

        JsonNode root = objectMapper.readTree(tool.searchSongs("夜", 10));

        assertTrue(root.path("ok").asBoolean());
        assertEquals(2, root.path("meta").path("count").asInt());
        assertEquals("城市霓虹", root.path("data").get(0).path("title").asText());
    }

    @Test
    @DisplayName("无结果：ok=false，提示换关键词（不是异常）")
    void emptySearchReturnsErrorEnvelope() throws Exception {
        when(repository.searchSongs(anyString(), anyInt())).thenReturn(List.of());

        JsonNode root = objectMapper.readTree(tool.searchSongs("不存在", 10));

        assertFalse(root.path("ok").asBoolean());
        assertTrue(root.path("meta").path("hint").asText().contains("换个更短的关键词"));
    }

    @Test
    @DisplayName("仓库异常：不抛出，返回脱敏文案")
    void repositoryFailureIsDesensitized() throws Exception {
        when(repository.searchSongs(anyString(), anyInt()))
                .thenThrow(new IllegalStateException(
                        "SELECT * FROM music_song WHERE ... 语法错误"));

        JsonNode root = objectMapper.readTree(tool.searchSongs("夜", 10));

        assertFalse(root.path("ok").asBoolean());
        String hint = root.path("meta").path("hint").asText();
        assertEquals("歌曲检索失败，请稍后重试", hint);
        assertFalse(hint.contains("SELECT"), "错误文案不得外泄 SQL");
    }

    // ===== get_song_detail =====

    @Test
    @DisplayName("歌曲不存在：ok=false")
    void songNotFound() throws Exception {
        when(repository.findSong(anyLong())).thenReturn(null);

        JsonNode root = objectMapper.readTree(tool.getSongDetail(9999L));

        assertFalse(root.path("ok").asBoolean());
        assertTrue(root.path("meta").path("hint").asText().contains("未找到该歌曲"));
    }

    @Test
    @DisplayName("非法歌曲 ID：ok=false，且不查库")
    void invalidSongIdIsRejected() throws Exception {
        JsonNode root = objectMapper.readTree(tool.getSongDetail(0L));

        assertFalse(root.path("ok").asBoolean());
        verify(repository, never()).findSong(anyLong());
    }

    @Test
    @DisplayName("歌曲存在：返回单对象，count=1")
    void songDetailReturnsEnvelope() throws Exception {
        when(repository.findSong(1001L)).thenReturn(
                new SongView(1001L, "城市霓虹", "夜航星", "城市霓虹",
                        214, "synthpop", 2021));

        JsonNode root = objectMapper.readTree(tool.getSongDetail(1001L));

        assertTrue(root.path("ok").asBoolean());
        assertEquals(1, root.path("meta").path("count").asInt());
        assertEquals("夜航星", root.path("data").path("artist").asText());
    }

    // ===== get_user_playlists =====

    @Test
    @DisplayName("我的歌单：按服务端 userId 查询，参数里没有 userId")
    void userPlaylistsUseServerSideUserId() throws Exception {
        when(repository.findUserPlaylists("music-user-1")).thenReturn(List.of(
                new PlaylistView(2001L, "深夜通勤", "地铁上的四十分钟", false, 8)
        ));

        JsonNode root = objectMapper.readTree(tool.getUserPlaylists());

        assertTrue(root.path("ok").asBoolean());
        assertEquals(1, root.path("meta").path("count").asInt());
        assertFalse(root.path("data").get(0).path("isPublic").asBoolean());
        verify(repository).findUserPlaylists("music-user-1");
    }

    @Test
    @DisplayName("没有歌单：ok=false")
    void emptyPlaylists() throws Exception {
        when(repository.findUserPlaylists(anyString())).thenReturn(List.of());

        JsonNode root = objectMapper.readTree(tool.getUserPlaylists());

        assertFalse(root.path("ok").asBoolean());
    }

    // ===== get_playlist_songs =====

    @Test
    @DisplayName("歌单无权查看：与'不存在'同一文案，不泄露歌单是否存在")
    void playlistSongsEmptyMeansNoAccessOrMissing() throws Exception {
        when(repository.findPlaylistSongs("music-user-1", 2003L))
                .thenReturn(List.of());

        JsonNode root = objectMapper.readTree(tool.getPlaylistSongs(2003L));

        assertFalse(root.path("ok").asBoolean());
        assertEquals("歌单不存在或无权查看：2003",
                root.path("meta").path("hint").asText());
    }

    @Test
    @DisplayName("歌单曲目：返回列表并带条数")
    void playlistSongsReturnEnvelope() throws Exception {
        when(repository.findPlaylistSongs("music-user-1", 2001L)).thenReturn(List.of(
                new SongView(1001L, "城市霓虹", "夜航星", "城市霓虹",
                        214, "synthpop", 2021)
        ));

        JsonNode root = objectMapper.readTree(tool.getPlaylistSongs(2001L));

        assertTrue(root.path("ok").asBoolean());
        assertEquals(1, root.path("meta").path("count").asInt());
    }
}
