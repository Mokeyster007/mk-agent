package com.example.mkagent.controller;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.music.MusicAccessGuard;
import com.example.mkagent.playback.MediaFileStore;
import com.example.mkagent.playback.PlaybackEvent;
import com.example.mkagent.playback.PlaybackService;
import com.example.mkagent.playback.PlaybackSigner;
import com.example.mkagent.playback.TrackSource;
import com.example.mkagent.playback.TrackSourceRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 播放接口：解析 → 取流。
 *
 * <p>两个接口的鉴权方式**不同**，这是刻意的：
 * <ul>
 *   <li>{@code /resolve}、{@code /candidates}、{@code /now-playing} 走请求头
 *       {@code X-User-Id}（与其它业务接口一致）；</li>
 *   <li>{@code /media/{token}} 走**签名令牌**：{@code <audio src>} 无法附加自定义请求头，
 *       所以身份、曲目、过期时间被签进地址，服务端验签后再读文件。</li>
 * </ul>
 *
 * <p>媒体交付的三条实测要求（都实现了，见 PlaybackMediaTest）：
 * <ol>
 *   <li>{@code Content-Type} 如实返回 {@code audio/wav}；</li>
 *   <li>{@code Accept-Ranges: bytes}，{@code Range} 请求返回 206 + {@code Content-Range}，
 *       这是浏览器拖动进度条的前提；</li>
 *   <li>非法区间返回 416 而不是 200，避免播放器把整文件当成"从 0 开始"。</li>
 * </ol>
 *
 * <p>不把受保护文件放进公开静态目录：媒体只在 classpath {@code media/} 或
 * 配置的 {@code mkagent.playback.media-dir} 下，只能经本类读取。
 */
@RestController
@RequestMapping("/ai/music/playback")
public class PlaybackController {

    private static final Logger log = LoggerFactory.getLogger(PlaybackController.class);

    /** 分块写出的缓冲区大小；媒体文件小，8KB 足够，也不会一次占太多堆。 */
    private static final int BUFFER_SIZE = 8192;

    private final MusicAccessGuard accessGuard;

    private final PlaybackService playbackService;

    private final PlaybackSigner signer;

    private final TrackSourceRepository sourceRepository;

    private final MediaFileStore mediaStore;

    public PlaybackController(
            MusicAccessGuard accessGuard,
            PlaybackService playbackService,
            PlaybackSigner signer,
            TrackSourceRepository sourceRepository,
            MediaFileStore mediaStore
    ) {
        this.accessGuard = accessGuard;
        this.playbackService = playbackService;
        this.signer = signer;
        this.sourceRepository = sourceRepository;
        this.mediaStore = mediaStore;
    }

    /**
     * 解析点播请求。
     *
     * <p>请求体只有 songId（与可选的 playlistId）——**没有** url、path、provider 字段：
     * 音源地址只能由服务端产生，前端自带地址的请求在这里就没有对应的入参。
     */
    @PostMapping("/resolve")
    public PlaybackService.ResolveResult resolve(@RequestBody ResolveRequest body) {
        String userId = accessGuard.requireUser();

        if (body == null || body.songId() == null || body.songId() <= 0) {
            throw new BusinessException(400, "INVALID_SONG_ID", "songId 必须是正整数");
        }

        return playbackService.resolve(userId, body.songId(), body.playlistId());
    }

    /**
     * 会话内可播放的候选（Agent 点歌卡的数据来源）。
     *
     * <p>数据只来自会话已存的业务引用（真实工具结果），不做模型文本解析。
     */
    @GetMapping("/candidates")
    public List<PlaybackService.Candidate> candidates(
            @RequestParam String conversationId
    ) {
        String userId = accessGuard.requireUser();
        return playbackService.candidates(userId, conversationId);
    }

    /**
     * 上报活跃播放事件（客户端在真实 playing / pause / ended 事件后调用）。
     *
     * <p>请求体带 {@code instanceId}：它把"这个请求属于哪一次播放"说清楚，
     * 从而让服务端能拒绝旧标签页的迟到心跳。语义见 {@code PlaybackEvent}。
     */
    @PostMapping("/now-playing")
    public PlaybackService.NowPlayingView updateNowPlaying(@RequestBody NowPlayingRequest body) {
        String userId = accessGuard.requireUser();

        if (body == null) {
            throw new BusinessException(400, "INVALID_PLAYBACK_EVENT", "缺少请求体");
        }

        PlaybackEvent event = PlaybackEvent.parse(body.event(), body.state());

        return playbackService.report(userId, body.songId(), event, body.instanceId());
    }

    /**
     * 当前曲目（没有则 204）。
     *
     * <p>用 204 而不是 200 + null：前端据此区分"没有当前曲目"和"接口返回了空对象"。
     */
    @GetMapping("/now-playing")
    public ResponseEntity<PlaybackService.NowPlayingView> nowPlaying() {
        String userId = accessGuard.requireUser();
        return playbackService.nowPlaying(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 媒体交付：支持 Range，用于进度拖拽与 seek。
     *
     * <p>不使用 {@code ResourceRegion} + 消息转换器：
     * 手写区间写出更直观（状态码、Content-Range、Content-Length 都在一处），
     * 也便于用真实 HTTP 断言 206 / 416。
     */
    @GetMapping("/media/{token}")
    public void media(
            @PathVariable String token,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {

        Optional<PlaybackSigner.Claims> claims = signer.verify(token);

        if (claims.isEmpty()) {
            /*
             * 统一 403 + 一句人话：过期与签名不符不区分，
             * 否则可以拿返回码探测"这个 songId 是否存在"。
             */
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    "MEDIA_TOKEN_INVALID", "媒体地址已失效，请重新点击播放");
            return;
        }

        long songId = claims.get().songId();
        String userId = claims.get().userId();

        Optional<TrackSource> maybeSource = sourceRepository.findBestSource(songId);
        if (maybeSource.isEmpty() || !maybeSource.get().accessibleBy(userId)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    "TRACK_FORBIDDEN", "无权播放该曲目");
            return;
        }

        Optional<MediaFileStore.MediaHandle> maybeHandle = mediaStore.open(maybeSource.get());
        if (maybeHandle.isEmpty()) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND,
                    "MEDIA_MISSING", "音源文件不可用");
            return;
        }

        MediaFileStore.MediaHandle handle = maybeHandle.get();
        long length = handle.length();

        /*
         * 这几个头必须在写 body 之前设置：一旦开始写，响应就提交了。
         * no-store：短期地址 + 私有内容，不让中间层缓存。
         */
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentType(handle.contentType());

        List<HttpRange> ranges = parseRanges(rangeHeader);

        if (ranges == null) {
            // Range 头格式非法：按 RFC 7233 回 416，并告知当前总长度
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
            return;
        }

        if (ranges.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLengthLong(length);

            if (!"HEAD".equalsIgnoreCase(request.getMethod())) {
                try (InputStream in = handle.resource().getInputStream()) {
                    copy(in, response.getOutputStream(), length);
                }
            }
            return;
        }

        /*
         * 多段 Range（bytes=0-1,5-6）浏览器播放器不会发，也不值得实现：
         * 只服务第一段，其余忽略。返回单段是合法的（RFC 允许服务器简化）。
         */
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(length);
        long end = range.getRangeEnd(length);

        if (start >= length || start > end) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
            return;
        }

        long count = end - start + 1;

        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
        response.setContentLengthLong(count);

        if ("HEAD".equalsIgnoreCase(request.getMethod())) {
            return;
        }

        try (InputStream in = handle.resource().getInputStream()) {
            skipFully(in, start);
            copy(in, response.getOutputStream(), count);
        }
    }

    /**
     * 解析 Range 头。
     *
     * @return 空列表表示"没有 Range 或 Range 为空"，返回 null 表示格式非法
     */
    private static List<HttpRange> parseRanges(String rangeHeader) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return List.of();
        }
        try {
            return HttpRange.parseRanges(rangeHeader);
        } catch (IllegalArgumentException e) {
            log.debug("非法的 Range 头：{}", rangeHeader);
            return null;
        }
    }

    /**
     * 跳过前 start 字节。
     *
     * <p>{@code InputStream.skip} 允许返回小于请求值（尤其网络流），
     * 这里必须循环，否则会从错误的偏移开始播——听起来像"拖进度条拖不准"。
     */
    private static void skipFully(InputStream in, long start) throws IOException {
        long remaining = start;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                // 兜底：skip 不动时用 read 推进，避免死循环
                if (in.read() < 0) {
                    throw new IOException("媒体流提前结束");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static void copy(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = count;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        out.flush();
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
        response.getWriter().flush();
    }

    /** 点播请求体。刻意只有两个字段：地址不能由客户端提供。 */
    public record ResolveRequest(Long songId, Long playlistId) {
    }

    /** 活跃播放事件上报。event 与 state 二选一（state 是旧客户端的兼容字段）。 */
    public record NowPlayingRequest(Long songId, String state, String event, String instanceId) {
    }
}
