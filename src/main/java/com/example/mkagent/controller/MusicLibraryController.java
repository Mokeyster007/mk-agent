package com.example.mkagent.controller;

import com.example.mkagent.agent.MusicAgent;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.music.MusicAccessGuard;
import com.example.mkagent.music.MusicCatalogRepository;
import com.example.mkagent.music.MusicCatalogRepository.PlaylistView;
import com.example.mkagent.music.MusicCatalogRepository.SongView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 音乐库只读接口：给页面用的"我的歌单"与能力探测。
 *
 * <p>为什么需要这一层：模型回答是**自由文本**，页面不能把它当业务对象解析
 * （"已为你创建歌单"这句话既不能证明歌单存在，也拿不到歌单 ID）。
 * 因此凡是页面要展示"当前真实状态"的地方，都走结构化查询：
 *
 * <ul>
 *   <li>{@code GET /ai/music/playlists}：本人歌单（含歌曲数）；</li>
 *   <li>{@code GET /ai/music/playlists/{id}/songs}：某个本人歌单的曲目；</li>
 *   <li>{@code GET /ai/music/capabilities}：当前装配是否开放审批写。</li>
 * </ul>
 *
 * <p>三条约束：
 * <ol>
 *   <li><b>身份只来自请求头</b>（{@link MusicAccessGuard}），不接受 userId 参数，
 *       也不信任任何请求里声明的角色；</li>
 *   <li><b>只读</b>：本类不提供任何写入口。写操作只有一条路——
 *       模型调用带 {@code @RequiresApproval} 的工具 + 用户批准；</li>
 *   <li><b>归属</b>：只返回 {@code p.user_id = 当前用户} 的歌单。
 *       公开歌单的读取能力在工具层（{@code get_playlist_songs}）保留，
 *       页面侧的"我的歌单"不做跨用户浏览。</li>
 * </ol>
 */
@RestController
@RequestMapping("/ai/music")
public class MusicLibraryController {

    /**
     * 页面用的歌单视图。
     *
     * <p>字段与 {@link PlaylistView} 一致，但不直接暴露仓储内部类型：
     * 仓储结构变化（例如以后加 ownerName）不应该连带改前端契约。
     */
    public record PlaylistVO(
            Long id,
            String name,
            String description,
            boolean isPublic,
            int songCount
    ) {
    }

    /**
     * 页面用的歌曲视图。
     */
    public record SongVO(
            Long id,
            String title,
            String artist,
            String album,
            Integer durationSeconds,
            String genre,
            Integer releaseYear
    ) {
    }

    /**
     * 能力探测结果。
     *
     * <p>{@code writeApproval} 与 Agent 装配读的是**同一个**开关
     * （{@code mkagent.agent.music.write-enabled}），不是前端另写一份常量——
     * 否则页面会显示"审批写"而后端其实没给工具，或者反过来。
     *
     * <p>{@code promptVersion} 让"界面显示的提示词版本"可核对：
     * 只读与写版提示词约束不同（写版允许写、只读版明确说不支持）。
     */
    public record CapabilitiesVO(
            boolean writeApproval,
            String promptVersion
    ) {
    }

    private final MusicAccessGuard accessGuard;

    private final MusicCatalogRepository repository;

    private final boolean writeEnabled;

    public MusicLibraryController(
            MusicAccessGuard accessGuard,
            MusicCatalogRepository repository,
            @Value("${mkagent.agent.music.write-enabled:false}") boolean writeEnabled
    ) {
        this.accessGuard = accessGuard;
        this.repository = repository;
        this.writeEnabled = writeEnabled;
    }

    /**
     * 当前用户自己创建的歌单。
     *
     * <p>没有歌单时返回空数组而不是 404：空是正常状态，
     * 页面据此显示"还没有歌单"，而不是一个看起来像出错的提示。
     */
    @GetMapping("/playlists")
    public List<PlaylistVO> myPlaylists() {
        String userId = accessGuard.requireUser();

        return repository.findUserPlaylists(userId).stream()
                .map(view -> new PlaylistVO(
                        view.id(),
                        view.name(),
                        view.description(),
                        view.isPublic(),
                        view.songCount()
                ))
                .toList();
    }

    /**
     * 某个歌单的曲目（按歌单内顺序）。
     *
     * <p>先判归属再取曲目，而不是"取到空就当不存在"：
     * 一个刚创建但曲目读取失败的歌单，不应该被报成 404。
     * 归属判断复用 {@code findUserPlaylists}，与列表接口同一口径，
     * 不额外写一条 SQL（避免两处 where 条件慢慢漂移）。
     */
    @GetMapping("/playlists/{playlistId}/songs")
    public List<SongVO> playlistSongs(@PathVariable long playlistId) {
        String userId = accessGuard.requireUser();

        if (playlistId <= 0) {
            throw new BusinessException(400, "INVALID_PLAYLIST_ID", "歌单 ID 必须是正整数");
        }

        boolean owned = repository.findUserPlaylists(userId).stream()
                .anyMatch(view -> view.id() != null && view.id() == playlistId);

        if (!owned) {
            /*
             * 与工具层同一句文案：不区分"不存在"与"不是你的"，
             * 避免用歌单 ID 探测他人歌单。
             */
            throw new BusinessException(
                    404, "PLAYLIST_NOT_FOUND", "歌单不存在或无权查看：" + playlistId);
        }

        return repository.findPlaylistSongs(userId, playlistId).stream()
                .map(MusicLibraryController::toVO)
                .toList();
    }

    /**
     * 当前装配的能力。
     *
     * <p>仍然要求身份：能力信息本身不敏感，但这个接口一旦匿名可访问，
     * 就会变成"探测部署形态"的免费入口。
     */
    @GetMapping("/capabilities")
    public CapabilitiesVO capabilities() {
        accessGuard.requireUser();

        return new CapabilitiesVO(
                writeEnabled,
                writeEnabled
                        ? MusicAgent.USER_WRITE_SYSTEM_PROMPT_VERSION
                        : MusicAgent.USER_SYSTEM_PROMPT_VERSION
        );
    }

    private static SongVO toVO(SongView view) {
        return new SongVO(
                view.id(),
                view.title(),
                view.artist(),
                view.album(),
                view.durationSeconds(),
                view.genre(),
                view.releaseYear()
        );
    }
}
