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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 歌单只读工具（桩仓库，不连数据库、不启动 Spring）。
 *
 * 这个类从 MusicQueryToolTest 拆出来的原因见 MusicPlaylistQueryTool：
 * 白名单粒度是 Bean，管理员不该持有歌单工具，所以它们必须是独立的 Bean。
 *
 * 覆盖点：
 * 1. 没有上下文就失败关闭（抛异常，且不查库）；
 * 2. userId 一律来自服务端上下文，工具没有 userId 参数；
 * 3. "歌单不存在"与"无权查看"是**同一个**返回，不泄露存在性。
 *
 * 真正的访问规则（他人私有 → 读不到；他人公开 → 可读；本人私有 → 可读）
 * 由 SQL 决定，在 {@link com.example.mkagent.music.MusicPlaylistAccessRuleTest}
 * 里用 H2 验证——桩仓库证明不了 SQL 的 WHERE 条件。
 */
class MusicPlaylistQueryToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MusicCatalogRepository repository;

    private MusicPlaylistQueryTool tool;

    @BeforeEach
    void setUp() {
        repository = mock(MusicCatalogRepository.class);
        tool = new MusicPlaylistQueryTool(repository);

        AgentRunContext ctx = new AgentRunContext();
        ctx.setUserId("music-user-1");
        AgentRunContextHolder.bind(ctx);
    }

    @AfterEach
    void tearDown() {
        AgentRunContextHolder.clear();
    }

    @Test
    @DisplayName("没有任务上下文：两个歌单工具都抛异常（失败关闭）")
    void allToolsFailClosedWithoutContext() {
        AgentRunContextHolder.clear();

        assertThrows(IllegalStateException.class, tool::getUserPlaylists);
        assertThrows(IllegalStateException.class,
                () -> tool.getPlaylistSongs(2001L));

        verify(repository, never()).findUserPlaylists(anyString());
        verify(repository, never()).findPlaylistSongs(anyString(), anyLong());
    }

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

    @Test
    @DisplayName("非法歌单 ID：ok=false，且不查库")
    void invalidPlaylistIdIsRejected() throws Exception {
        JsonNode root = objectMapper.readTree(tool.getPlaylistSongs(0L));

        assertFalse(root.path("ok").asBoolean());
        verify(repository, never()).findPlaylistSongs(anyString(), anyLong());
    }

    @Test
    @DisplayName("仓库异常：不抛出，返回脱敏文案")
    void repositoryFailureIsDesensitized() throws Exception {
        when(repository.findPlaylistSongs(anyString(), anyLong()))
                .thenThrow(new IllegalStateException(
                        "SELECT * FROM music_playlist WHERE ... 语法错误"));

        JsonNode root = objectMapper.readTree(tool.getPlaylistSongs(2001L));

        assertFalse(root.path("ok").asBoolean());
        String hint = root.path("meta").path("hint").asText();
        assertEquals("歌单曲目读取失败，请稍后重试", hint);
        assertFalse(hint.contains("SELECT"), "错误文案不得外泄 SQL");
    }
}
