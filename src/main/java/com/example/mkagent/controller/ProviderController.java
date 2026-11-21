package com.example.mkagent.controller;

import com.example.mkagent.music.MusicAccessGuard;
import com.example.mkagent.provider.PageRequest;
import com.example.mkagent.provider.ProviderAggregationService;
import com.example.mkagent.provider.ProviderConnection;
import com.example.mkagent.provider.ProviderConnectionService;
import com.example.mkagent.provider.ProviderTrackKind;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 音乐账号（平台连接）的只读接口。
 *
 * <p>全部走 {@code X-User-Id} 身份，且每个查询都带 userId：
 * 没有"按 provider 全局查"的入口，因此不存在跨用户读取连接的可能。
 *
 * <p>本轮**没有** OAuth 回调接口：真实平台绑定需要可信服务端身份与凭据，
 * 两者都未就绪。这里返回的模拟来源会被前端明确标注"模拟"，
 * 不会被描述成"已接网易云 / QQ 音乐"。
 */
@RestController
@RequestMapping("/ai/music/providers")
public class ProviderController {

    private final MusicAccessGuard accessGuard;

    private final ProviderConnectionService connectionService;

    private final ProviderAggregationService aggregationService;

    public ProviderController(
            MusicAccessGuard accessGuard,
            ProviderConnectionService connectionService,
            ProviderAggregationService aggregationService
    ) {
        this.accessGuard = accessGuard;
        this.connectionService = connectionService;
        this.aggregationService = aggregationService;
    }

    /**
     * 来源目录 + 当前用户的连接状态。
     *
     * <p>未连接的来源也会返回：界面要能展示"有哪些来源、各自能做什么"。
     */
    @GetMapping
    public List<ProviderConnectionService.ProviderView> catalog() {
        String userId = accessGuard.requireUser();
        return connectionService.catalog(userId);
    }

    /** 当前用户的连接列表（不含任何凭据引用）。 */
    @GetMapping("/connections")
    public List<ConnectionView> connections() {
        String userId = accessGuard.requireUser();
        return connectionService.connections(userId).stream()
                .map(ConnectionView::of)
                .toList();
    }

    /**
     * 连接一个来源。
     *
     * <p>模拟来源直接建立本地连接；真实来源会被 {@code ProviderBindingGuard}
     * 拒绝（没有可信身份 / 没有凭据）。
     */
    @PostMapping("/{provider}/connect")
    public ProviderConnectionService.ProviderView connect(@PathVariable String provider) {
        String userId = accessGuard.requireUser();
        return connectionService.connect(userId, provider);
    }

    /** 解绑。幂等：不存在也返回 200。 */
    @DeleteMapping("/{provider}/connection")
    public void disconnect(@PathVariable String provider) {
        String userId = accessGuard.requireUser();
        connectionService.disconnect(userId, provider);
    }

    /**
     * 只读聚合。
     *
     * <p>返回条目 + 每个来源的状态。一个来源失败只影响它自己那一块。
     *
     * @param kind     PLAYLISTS / LIKED / RECENT / SEARCH
     * @param provider 可选：只看某个来源
     * @param q        SEARCH 时的关键词
     */
    @GetMapping("/tracks")
    public ProviderAggregationService.Aggregate tracks(
            @RequestParam(defaultValue = "LIKED") String kind,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        String userId = accessGuard.requireUser();
        return aggregationService.read(
                userId,
                ProviderTrackKind.parse(kind),
                provider,
                q,
                PageRequest.of(page, size)
        );
    }

    /**
     * 连接的对外视图。
     *
     * <p>刻意不包含 {@code credentialRef}：它是内部定位符，
     * 前端既不需要也不该知道凭据存在哪里。
     */
    public record ConnectionView(
            String provider,
            String displayName,
            String status,
            String scopes,
            Long connectedAtEpochSecond
    ) {
        static ConnectionView of(ProviderConnection connection) {
            return new ConnectionView(
                    connection.provider(),
                    connection.displayName(),
                    connection.status().name(),
                    connection.scopes(),
                    connection.connectedAt() == null
                            ? null
                            : connection.connectedAt().getEpochSecond()
            );
        }
    }
}
