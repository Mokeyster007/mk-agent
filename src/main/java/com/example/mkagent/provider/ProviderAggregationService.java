package com.example.mkagent.provider;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 只读聚合：把多个来源的数据并排放出来，**不做合并去重**。
 *
 * <p>三条设计约束：
 * <ol>
 *   <li><b>按来源隔离失败</b>：一个来源超时 / 报错 / 权限不足，
 *       只影响它自己那一块，其它来源照常返回。整页失败是最糟的体验——
 *       用户只是想看歌单，不该因为某个平台挂了而什么都看不到。</li>
 *   <li><b>不按歌名去重</b>：两个平台可以有同名歌曲，
 *       合并会让用户点进去听到另一首。身份是 provider + providerTrackId。</li>
 *   <li><b>不落库、不外发</b>：本轮只在内存里聚合，不写本地曲库，
 *       也不把任何第三方数据交给模型。</li>
 * </ol>
 *
 * <p>超时用 {@link Future#get(long, TimeUnit)} 实现，而不是在适配器内部 sleep：
 * 适配器可能卡在网络 IO 上，只有从外部打断才可靠。
 */
@Service
public class ProviderAggregationService {

    private static final Logger log = LoggerFactory.getLogger(ProviderAggregationService.class);

    private final ProviderConnectionService connectionService;

    private final ProviderProperties properties;

    private final ExecutorService executor;

    public ProviderAggregationService(
            ProviderConnectionService connectionService,
            ProviderProperties properties
    ) {
        this.connectionService = connectionService;
        this.properties = properties;
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "provider-read");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 读取一个类别。
     *
     * @param providerFilter 可选：只看某个来源；为空表示全部已连接来源
     */
    public Aggregate read(
            String userId,
            ProviderTrackKind kind,
            String providerFilter,
            String query,
            PageRequest page
    ) {
        Map<String, MusicProviderAdapter> adapters = connectionService.adapterMap();

        List<ProviderTrack> tracks = new ArrayList<>();
        List<ProviderPlaylist> playlists = new ArrayList<>();
        List<SourceStatus> sources = new ArrayList<>();

        if (kind == ProviderTrackKind.SEARCH && (query == null || query.isBlank())) {
            // 没有关键词就不发请求：避免把所有来源的目录都拉一遍
            return new Aggregate(tracks, playlists, sources);
        }

        for (ProviderConnection connection : connectionService.connections(userId)) {
            if (providerFilter != null
                    && !providerFilter.isBlank()
                    && !providerFilter.equals(connection.provider())) {
                continue;
            }

            MusicProviderAdapter adapter = adapters.get(connection.provider());

            if (adapter == null) {
                sources.add(SourceStatus.failure(
                        connection.provider(), connection.displayName(), false,
                        "UNKNOWN_PROVIDER", "PROVIDER_UNKNOWN",
                        "该来源已下线，无法读取", 0));
                continue;
            }

            if (!connection.usable()) {
                sources.add(SourceStatus.failure(
                        adapter.id(), adapter.displayName(), adapter.simulated(),
                        connection.status().name(),
                        connection.status() == ProviderConnectionStatus.EXPIRED
                                ? "CONNECTION_EXPIRED"
                                : "CONNECTION_REVOKED",
                        connection.status() == ProviderConnectionStatus.EXPIRED
                                ? "授权已过期，请重新连接"
                                : "授权已被撤销，请重新连接",
                        0));
                continue;
            }

            if (!connection.hasScope(kind.scope())) {
                /*
                 * 明确返回"权限不足"，不返回空列表：
                 * 空列表会被界面说成"你没有喜欢的歌"，那是错误结论。
                 */
                sources.add(SourceStatus.failure(
                        adapter.id(), adapter.displayName(), adapter.simulated(),
                        "PERMISSION_DENIED", "SCOPE_MISSING",
                        "该来源未授予「" + kind.scope() + "」权限", 0));
                continue;
            }

            long startedAt = System.nanoTime();
            Future<SourceData> future = executor.submit(
                    () -> fetch(adapter, connection, kind, query, page));

            try {
                // 下限 100ms：配成 0 或负数会让每次读取都立刻超时，属于配置错误而非业务失败
                long budget = Math.max(100, properties.getTimeoutMs());
                SourceData data = future.get(budget, TimeUnit.MILLISECONDS);
                long elapsed = elapsedMs(startedAt);

                tracks.addAll(data.tracks());
                playlists.addAll(data.playlists());

                sources.add(SourceStatus.ok(
                        adapter.id(), adapter.displayName(), adapter.simulated(), elapsed));
            } catch (TimeoutException e) {
                future.cancel(true);
                sources.add(SourceStatus.failure(
                        adapter.id(), adapter.displayName(), adapter.simulated(),
                        "TIMEOUT", "PROVIDER_TIMEOUT",
                        "该来源响应超时，其它来源不受影响", elapsedMs(startedAt)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                sources.add(SourceStatus.failure(
                        adapter.id(), adapter.displayName(), adapter.simulated(),
                        "ERROR", "PROVIDER_INTERRUPTED",
                        "读取被中断", elapsedMs(startedAt)));
            } catch (ExecutionException e) {
                /*
                 * 不把异常 message 直接给前端：可能含外部 URL / 内部字段。
                 * 日志里只记类型，便于排查又不泄露凭据。
                 */
                Throwable cause = e.getCause();
                log.warn("平台读取失败：provider={}, kind={}, reason={}",
                        adapter.id(), kind, cause == null ? "unknown" : cause.getClass().getSimpleName());

                sources.add(SourceStatus.failure(
                        adapter.id(), adapter.displayName(), adapter.simulated(),
                        "ERROR", "PROVIDER_ERROR",
                        "该来源读取失败，其它来源不受影响", elapsedMs(startedAt)));
            }
        }

        return new Aggregate(tracks, playlists, sources);
    }

    private SourceData fetch(
            MusicProviderAdapter adapter,
            ProviderConnection connection,
            ProviderTrackKind kind,
            String query,
            PageRequest page
    ) {
        return switch (kind) {
            case PLAYLISTS -> new SourceData(
                    List.of(),
                    adapter.playlists(connection.providerUserId(), page));
            case LIKED -> new SourceData(
                    adapter.likedTracks(connection.providerUserId(), page), List.of());
            case RECENT -> new SourceData(
                    adapter.recentTracks(connection.providerUserId(), page), List.of());
            case SEARCH -> new SourceData(
                    adapter.search(connection.providerUserId(), query, page), List.of());
        };
    }

    private static long elapsedMs(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    private record SourceData(List<ProviderTrack> tracks, List<ProviderPlaylist> playlists) {
    }

    /** 单个来源的读取结果（成功或失败）。 */
    public record SourceStatus(
            String provider,
            String displayName,
            boolean simulated,
            /** OK / TIMEOUT / ERROR / PERMISSION_DENIED / EXPIRED / REVOKED / UNKNOWN_PROVIDER */
            String status,
            String errorCode,
            String message,
            long elapsedMs
    ) {
        static SourceStatus ok(String provider, String name, boolean simulated, long elapsed) {
            return new SourceStatus(provider, name, simulated, "OK", null, null, elapsed);
        }

        static SourceStatus failure(
                String provider, String name, boolean simulated,
                String status, String code, String message, long elapsed) {
            return new SourceStatus(provider, name, simulated, status, code, message, elapsed);
        }
    }

    /** 聚合结果：条目 + 每个来源的状态。 */
    public record Aggregate(
            List<ProviderTrack> tracks,
            List<ProviderPlaylist> playlists,
            List<SourceStatus> sources
    ) {
    }
}
