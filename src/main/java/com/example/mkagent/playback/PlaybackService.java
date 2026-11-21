package com.example.mkagent.playback;

import com.example.mkagent.conversation.ConversationRefs;
import com.example.mkagent.conversation.ConversationService;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.music.MusicCatalogRepository;
import com.example.mkagent.music.MusicCatalogRepository.SongView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 播放解析：把"曲目 ID"变成"能不能播、怎么播"。
 *
 * <p>职责边界（参考 Twilight Echo 的分层，但不移植原生音频引擎）：
 * <ul>
 *   <li><b>音源解析</b>（本类）：曲目 → 音源映射 → 交付方式与短期地址；</li>
 *   <li><b>播放控制</b>（前端）：播放/暂停/进度/上一首下一首/队列；</li>
 *   <li><b>音频处理</b>（前端 Web Audio）：前级增益 + 三段 EQ + 旁路；</li>
 *   <li><b>实际输出</b>（浏览器）：解码与声卡输出。</li>
 * </ul>
 * 服务端不做解码、不做转码、不代理第三方流——本轮也不引入实时转码。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>用户身份只来自服务端上下文（{@code X-User-Id}），不接受请求体里的 userId；</li>
 *   <li>音源地址只由服务端签发，前端**不能**自带任意 URL 或对象路径；</li>
 *   <li>没有映射 / 映射不可用 / 无权播放，都返回**明确的不可播原因**，
 *       而不是一个空地址让播放器报"未知错误"。</li>
 * </ol>
 */
@Service
public class PlaybackService {

    private static final Logger log = LoggerFactory.getLogger(PlaybackService.class);

    /** 「正在播放」状态：客户端在真实事件后上报，服务端复核。 */
    public static final String STATE_PLAYING = "PLAYING";
    public static final String STATE_PAUSED = "PAUSED";
    public static final String STATE_STOPPED = "STOPPED";

    /** 播放日志的来源标记：区分"播放器真实播放"与将来的其他入口。 */
    private static final String PLAY_SOURCE_PLAYER = "PLAYER";

    private final MusicCatalogRepository catalog;

    private final TrackSourceRepository sources;

    private final MediaFileStore mediaStore;

    private final PlaybackSigner signer;

    private final ConversationService conversationService;

    /** 播放域时钟：让 180 秒失效窗口可测，不依赖 sleep。 */
    private final PlaybackClock clock;

    /** 超过这么久没有心跳，就不再认为"正在播放"（服务端判定）。 */
    private final long activeTtlSeconds;

    /**
     * 应用 context-path（默认 /api）。
     *
     * <p>签名地址必须是"浏览器可直接 GET 的完整路径"，
     * 而前端代码里已经带 /api 前缀。这里从配置读，避免在业务代码里写死。
     */
    private final String contextPath;

    public PlaybackService(
            MusicCatalogRepository catalog,
            TrackSourceRepository sources,
            MediaFileStore mediaStore,
            PlaybackSigner signer,
            ConversationService conversationService,
            PlaybackClock clock,
            @Value("${server.servlet.context-path:}") String contextPath,
            @Value("${mkagent.playback.active-ttl-seconds:180}") long activeTtlSeconds
    ) {
        this.catalog = catalog;
        this.sources = sources;
        this.mediaStore = mediaStore;
        this.signer = signer;
        this.conversationService = conversationService;
        this.clock = clock;
        this.contextPath = contextPath == null ? "" : contextPath;
        this.activeTtlSeconds = Math.max(30, activeTtlSeconds);
    }

    /**
     * 解析一首歌能不能播。
     *
     * @param playlistId 可选：从某个歌单里点播时传入，用于校验歌单归属
     *                   （歌单是私有的，拿别人的歌单 ID 不该能点播其中的歌）
     */
    public ResolveResult resolve(String userId, long songId, Long playlistId) {
        SongView song = catalog.findSong(songId);
        if (song == null) {
            throw new BusinessException(404, "SONG_NOT_FOUND", "曲目不存在：" + songId);
        }

        if (playlistId != null) {
            requireOwnedPlaylist(userId, playlistId);
        }

        Optional<TrackSource> maybeSource = sources.findBestSource(songId);

        if (maybeSource.isEmpty()) {
            /*
             * 种子曲目（1001~1060）没有音源映射：它们继续可查询、可进歌单，
             * 但必须**明确不可播**，不能拿测试音冒充。
             */
            return ResolveResult.unavailable(
                    song,
                    "NO_SOURCE",
                    "这首曲子暂无可用音源（曲库只提供元数据，未收录音频）"
            );
        }

        TrackSource source = maybeSource.get();

        if (!source.accessibleBy(userId)) {
            /*
             * 存在映射但属于别人：这是**权限**问题，不是"不可播"。
             * 用 403 与"暂无音源"区分开，页面才能给出正确提示。
             */
            throw new BusinessException(403, "TRACK_FORBIDDEN", "无权播放该曲目：" + songId);
        }

        return switch (source.mode()) {
            case DIRECT_AUDIO -> resolveDirectAudio(song, source, userId);
            case UNAVAILABLE -> ResolveResult.unavailable(
                    song, "SOURCE_UNAVAILABLE", "该音源已被标记为不可用");
            case PROVIDER_SDK, EXTERNAL_LINK -> ResolveResult.unavailable(
                    song,
                    "PROVIDER_NOT_CONNECTED",
                    "该来源需要官方 SDK 授权，当前未接入（不代理第三方流）"
            );
        };
    }

    private ResolveResult resolveDirectAudio(SongView song, TrackSource source, String userId) {
        Optional<MediaFileStore.MediaHandle> handle = mediaStore.open(source);

        if (handle.isEmpty()) {
            /*
             * 映射在、文件不在：这是配置错误，必须让页面显示"音源不可用"，
             * 而不是让播放器拿到 404 后报一个笼统的网络错误。
             */
            log.warn("音源映射存在但媒体不可读：songId={}, key={}", song.id(), source.objectKey());
            return ResolveResult.unavailable(
                    song, "MEDIA_MISSING", "音源文件不可用，请联系管理员检查媒体目录");
        }

        /*
         * 签名必须绑定**当前请求的用户**，不是映射的 owner。
         *
         * 准确性质：这是短期持有者凭证（bearer token），不是身份绑定。
         * 签名覆盖 songId + userId + 过期时间，所以拿到地址的人改不了曲目、
         * 也延不了期；但**任何拿到地址的人**在有效期内都能读这一首，
         * 因为 /media 只验签、不校验请求方身份（<audio> 也带不了业务请求头）。
         * 转发即等于授权，所以地址不写日志、不进模型消息。
         */
        PlaybackSigner.IssuedToken token = signer.issue(song.id(), userId);

        return new ResolveResult(
                true,
                SongInfo.of(song),
                PlaybackMode.DIRECT_AUDIO.name(),
                source.provider(),
                source.format(),
                handle.get().contentType(),
                contextPath + "/ai/music/playback/media/" + token.token(),
                token.expiresAtEpochSecond(),
                /*
                 * seek / range 由服务端**确实实现**的能力决定（见 PlaybackMediaController），
                 * eq 只对受控来源开放：第三方流不承诺可处理。
                 */
                new Capabilities(true, true, source.processable()),
                null,
                null
        );
    }

    /**
     * 只回答"能不能播"，不签发地址。
     *
     * <p>实现上直接复用 {@link #resolve}：宁可多签一个立刻丢弃的令牌，
     * 也不复制一份权限判定——两处判定一旦漂移，就会出现
     * "解析说能播、点播 403"这种最难排查的不一致。
     */
    public Playability checkPlayable(String userId, long songId) {
        try {
            ResolveResult result = resolve(userId, songId, null);
            return new Playability(
                    result.playable(), result.format(), result.reasonCode(), result.reason());
        } catch (BusinessException e) {
            return new Playability(false, null, e.getCode(), e.getMessage());
        }
    }

    /**
     * 歌单归属校验：与页面「我的歌单」同一口径（只认本人歌单）。
     */
    private void requireOwnedPlaylist(String userId, long playlistId) {
        boolean owned = catalog.findUserPlaylists(userId).stream()
                .anyMatch(view -> view.id() != null && view.id() == playlistId);

        if (!owned) {
            throw new BusinessException(
                    404, "PLAYLIST_NOT_FOUND", "歌单不存在或无权查看：" + playlistId);
        }
    }

    /**
     * 会话内可播放的候选（Agent 点歌卡的数据来源）。
     *
     * <p>只从**会话已存的业务引用**（{@link ConversationRefs}，由真实工具结果写入）
     * 取曲目，不从模型回答里解析 ID——模型可以写错，ID 必须来自数据库。
     *
     * <p>每首都要重新做一次可播性与权限判定：
     * 引用是历史快照，音源可能已被撤下，用户也可能换了。
     */
    public List<Candidate> candidates(String userId, String conversationId) {
        ConversationService.ConversationRow row =
                conversationService.requireOwned(userId, conversationId);

        ConversationRefs refs = ConversationRefs.fromJson(row.refsJson());
        if (refs.songs().isEmpty()) {
            return List.of();
        }

        List<Long> ids = refs.songs().stream().map(ConversationRefs.SongRef::id).toList();
        Map<Long, TrackSource> bestSources = sources.findBestSources(ids);

        List<Candidate> result = new ArrayList<>();
        for (ConversationRefs.SongRef ref : refs.songs()) {
            SongView song = catalog.findSong(ref.id());
            if (song == null) {
                continue;
            }

            TrackSource source = bestSources.get(ref.id());
            boolean playable = source != null
                    && source.mode() == PlaybackMode.DIRECT_AUDIO
                    && source.accessibleBy(userId)
                    && mediaStore.open(source).isPresent();

            result.add(new Candidate(
                    song.id(),
                    song.title(),
                    song.artist(),
                    song.album(),
                    song.durationSeconds(),
                    playable,
                    playable ? source.format() : null,
                    playable ? null : unavailableReason(source, userId)
            ));
        }
        return result;
    }

    private String unavailableReason(TrackSource source, String userId) {
        if (source == null) {
            return "暂无可用音源";
        }
        if (!source.accessibleBy(userId)) {
            return "无权播放该曲目";
        }
        if (source.mode() != PlaybackMode.DIRECT_AUDIO) {
            return "该来源未接入";
        }
        return "音源文件不可用";
    }

    /**
     * 上报「活跃播放」事件。
     *
     * <p>客户端只在**真实媒体事件**之后调用，服务端仍然要复核：
     * 1. 曲目存在；2. 音源存在且可播；3. 当前用户有权限。
     * 否则前端可以声称任意歌曲"正在播放"，把越权曲目塞进歌单。
     *
     * <p>事件语义见 {@link PlaybackEvent}。三条硬约束：
     * <ul>
     *   <li>心跳只能刷新**同一实例**，不能换曲目、不能复活已停止的实例；</li>
     *   <li>暂停 / 停止必须来自当前实例——旧标签页不能停掉新实例；</li>
     *   <li>只有 {@code START} 会写播放日志，重复心跳不写。</li>
     * </ul>
     *
     * @throws BusinessException 409 {@code PLAYBACK_INSTANCE_STALE} 实例不匹配
     */
    public NowPlayingView report(
            String userId, Long songId, PlaybackEvent event, String instanceId) {

        String instance = requireInstanceId(instanceId);

        if (event == PlaybackEvent.START) {
            if (songId == null || songId <= 0) {
                throw new BusinessException(400, "INVALID_SONG_ID", "songId 必须是正整数");
            }

            SongView song = requirePlayableSong(userId, songId);
            Optional<TrackSourceRepository.NowPlaying> previous = sources.findNowPlaying(userId);
            Instant now = clock.now();

            boolean sameInstance = previous
                    .map(row -> instance.equals(row.instanceId()) && row.songId() == songId)
                    .orElse(false);

            if (sameInstance) {
                /*
                 * 同一实例从暂停恢复：保留 started_at，只把状态翻回 PLAYING。
                 * 这不是"新的一次播放"，但确实重新开始出声，所以仍然记一条日志
                 * （下面 newPlay 的判定已经覆盖了 previous != PLAYING 的情况）。
                 */
                sources.setStateIfInstance(userId, instance, STATE_PLAYING, now);
            } else {
                sources.startInstance(userId, songId, instance, now);
            }

            boolean newPlay = previous
                    .map(row -> row.songId() != songId || !STATE_PLAYING.equals(row.state()))
                    .orElse(true);

            if (newPlay) {
                sources.logPlay(userId, songId, PLAY_SOURCE_PLAYER);
            }

            return view(song, STATE_PLAYING, now);
        }

        Optional<TrackSourceRepository.NowPlaying> current = sources.findNowPlaying(userId);

        if (current.isEmpty() || !instance.equals(current.get().instanceId())) {
            throw staleInstance();
        }

        Instant now = clock.now();

        switch (event) {
            case HEARTBEAT -> {
                if (!sources.touchIfPlaying(userId, instance, now)) {
                    /*
                     * 实例匹配但状态不是 PLAYING：说明已经暂停或停止。
                     * 心跳不能把它"复活"——否则关掉播放器后一个迟到的定时器
                     * 就能让服务端重新认为"正在播放"。
                     */
                    throw staleInstance();
                }
            }
            case PAUSE, STOP -> {
                if (!sources.setStateIfInstance(
                        userId, instance, event.resultingState(), now)) {
                    throw staleInstance();
                }
            }
            default -> throw new BusinessException(
                    400, "INVALID_PLAYBACK_EVENT", "不支持的事件：" + event);
        }

        SongView song = catalog.findSong(current.get().songId());
        if (song == null) {
            throw new BusinessException(404, "SONG_NOT_FOUND", "曲目不存在");
        }

        return view(song, event.resultingState(), now);
    }

    /**
     * 当前**有效**的播放事实。
     *
     * <p>三条过滤，缺一不可：
     * <ol>
     *   <li>状态必须是 PLAYING——暂停不是"正在播放"，
     *       否则"把正在播放的歌加入歌单"会在暂停时也能取到曲目；</li>
     *   <li>最后一次心跳必须在 {@code active-ttl-seconds} 之内——
     *       关标签页不会发任何事件，只能靠超时判定；</li>
     *   <li>曲目仍存在。</li>
     * </ol>
     *
     * <p>注意：这条查询**不影响续播快照**。续播是浏览器本地的 12 小时快照，
     * 与服务端的活跃播放是两件独立的事：服务端说"没有正在播放"时，
     * 页面仍然可以显示"上次听到这里"。
     */
    public Optional<NowPlayingView> nowPlaying(String userId) {
        Instant cutoff = clock.now().minusSeconds(activeTtlSeconds);

        return sources.findNowPlaying(userId)
                .filter(row -> STATE_PLAYING.equals(row.state()))
                .filter(row -> row.updatedAt() != null && !row.updatedAt().isBefore(cutoff))
                .flatMap(row -> {
                    SongView song = catalog.findSong(row.songId());
                    if (song == null) {
                        return Optional.empty();
                    }
                    return Optional.of(new NowPlayingView(
                            song.id(), song.title(), song.artist(),
                            row.state(),
                            row.updatedAt().getEpochSecond()
                    ));
                });
    }

    /**
     * 复核"这首能不能由这个用户播放"，返回曲目视图。
     *
     * <p>与 {@link #resolve} 共用同一套判定（不存在 404 / 无音源或无权 403），
     * 避免出现"能播但不能上报"这种自相矛盾的状态。
     */
    private SongView requirePlayableSong(String userId, long songId) {
        SongView song = catalog.findSong(songId);
        if (song == null) {
            throw new BusinessException(404, "SONG_NOT_FOUND", "曲目不存在：" + songId);
        }

        Optional<TrackSource> source = sources.findBestSource(songId);
        if (source.isEmpty()
                || source.get().mode() != PlaybackMode.DIRECT_AUDIO
                || !source.get().accessibleBy(userId)) {
            throw new BusinessException(403, "TRACK_FORBIDDEN", "无权播放该曲目：" + songId);
        }

        return song;
    }

    /**
     * 播放实例标识：客户端生成的随机串。
     *
     * <p>为什么要校验格式：它会进数据库、进日志、进比较。
     * 只允许短的安全字符，长度上限与列宽一致，避免被塞进超长串或控制字符。
     */
    private static String requireInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new BusinessException(
                    400, "INVALID_INSTANCE_ID", "缺少播放实例标识 instanceId");
        }
        String trimmed = instanceId.trim();
        if (!trimmed.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new BusinessException(
                    400, "INVALID_INSTANCE_ID", "播放实例标识格式非法");
        }
        return trimmed;
    }

    private static BusinessException staleInstance() {
        /*
         * 409 而不是 403：这不是"你没权限"，而是"你的播放实例已经不是当前的了"。
         * 客户端收到后应当静默丢弃（旧标签页迟到的心跳），不要弹错误。
         */
        return new BusinessException(
                409,
                "PLAYBACK_INSTANCE_STALE",
                "该播放实例已失效（可能已在别处切换或停止）");
    }

    private static NowPlayingView view(SongView song, String state, Instant at) {
        return new NowPlayingView(
                song.id(), song.title(), song.artist(), state, at.getEpochSecond());
    }

    /** 解析结果。 */
    public record ResolveResult(
            boolean playable,
            SongInfo song,
            String mode,
            String provider,
            String format,
            String contentType,
            /** 短期媒体地址（相对路径，含 context-path）；不可播时为 null */
            String streamUrl,
            /** 地址过期时间（epoch 秒）；不可播时为 null */
            Long expiresAt,
            Capabilities capabilities,
            /** 不可播原因码（稳定，前端只认这个）；可播时为 null */
            String reasonCode,
            /** 不可播的人话说明；可播时为 null */
            String reason
    ) {

        static ResolveResult unavailable(SongView song, String code, String reason) {
            return new ResolveResult(
                    false, SongInfo.of(song), PlaybackMode.UNAVAILABLE.name(),
                    null, null, null, null, null,
                    new Capabilities(false, false, false),
                    code, reason
            );
        }
    }

    /** 曲目信息（播放器显示用，不含任何内部路径）。 */
    public record SongInfo(
            long id,
            String title,
            String artist,
            String album,
            Integer durationSeconds,
            Integer releaseYear
    ) {
        static SongInfo of(SongView view) {
            return new SongInfo(
                    view.id(), view.title(), view.artist(), view.album(),
                    view.durationSeconds(), view.releaseYear());
        }
    }

    /** 播放器能力：只描述**这个来源**确认支持的能力，不做营销式声明。 */
    public record Capabilities(boolean seek, boolean range, boolean eq) {
    }

    /** 会话内候选曲目。 */
    public record Candidate(
            long songId,
            String title,
            String artist,
            String album,
            Integer durationSeconds,
            boolean playable,
            String format,
            String unavailableReason
    ) {
    }

    /** 当前曲目。 */
    public record NowPlayingView(
            long songId,
            String title,
            String artist,
            String state,
            long updatedAtEpochSecond
    ) {
    }

    /** "能不能播"的结论（不含地址，供工具层使用）。 */
    public record Playability(
            boolean playable,
            String format,
            String reasonCode,
            String reason
    ) {
    }
}
