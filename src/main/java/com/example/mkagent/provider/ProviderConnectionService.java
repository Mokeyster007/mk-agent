package com.example.mkagent.provider;

import com.example.mkagent.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 平台连接管理。
 *
 * <p>三个动作：列目录、连接、解绑。没有"刷新令牌"——
 * 本轮没有真实令牌，写一个空实现只会让人误以为刷新已经做好了。
 *
 * <p>所有方法都以 {@code userId} 为第一个参数，并且**只**按它查询：
 * 跨用户读取不是靠调用方自觉，而是仓储层没有提供别的入口。
 */
@Service
public class ProviderConnectionService {

    /** 模拟来源授予的只读范围。没有写范围，也没有任何离线授权。 */
    static final String DEMO_SCOPES = "read:catalog read:playlists read:liked read:recent";

    private final List<MusicProviderAdapter> adapters;

    private final ProviderConnectionRepository repository;

    private final ProviderBindingGuard bindingGuard;

    public ProviderConnectionService(
            List<MusicProviderAdapter> adapters,
            ProviderConnectionRepository repository,
            ProviderBindingGuard bindingGuard
    ) {
        this.adapters = adapters == null ? List.of() : List.copyOf(adapters);
        this.repository = repository;
        this.bindingGuard = bindingGuard;
    }

    /** providerId → 适配器。重复 id 直接暴露为启动期错误，不静默取第一个。 */
    public Map<String, MusicProviderAdapter> adapterMap() {
        Map<String, MusicProviderAdapter> map = new LinkedHashMap<>();
        for (MusicProviderAdapter adapter : adapters) {
            MusicProviderAdapter previous = map.put(adapter.id(), adapter);
            if (previous != null) {
                throw new IllegalStateException("重复的平台适配器 id：" + adapter.id());
            }
        }
        return map;
    }

    /**
     * 来源目录 + 当前用户的连接状态。
     *
     * <p>未连接的来源也要返回：界面需要展示"有哪些来源可用、各自能做什么"，
     * 否则用户不知道能连什么。
     */
    public List<ProviderView> catalog(String userId) {
        Map<String, ProviderConnection> connections = new LinkedHashMap<>();
        for (ProviderConnection connection : repository.findAll(userId)) {
            connections.put(connection.provider(), connection);
        }

        List<ProviderView> views = new ArrayList<>();
        for (MusicProviderAdapter adapter : adapterMap().values()) {
            views.add(ProviderView.of(adapter, connections.get(adapter.id())));
        }
        return views;
    }

    /** 连接一个来源（模拟来源直接建立本地连接；真实来源必须通过准入判断）。 */
    public ProviderView connect(String userId, String providerId) {
        MusicProviderAdapter adapter = requireAdapter(providerId);
        bindingGuard.requireBindable(adapter);

        Instant now = Instant.now();

        ProviderConnection connection = new ProviderConnection(
                userId,
                adapter.id(),
                adapter.simulated() ? "sim-" + userId : null,
                adapter.displayName(),
                ProviderConnectionStatus.CONNECTED,
                adapter.simulated() ? DEMO_SCOPES : "",
                /*
                 * 真实来源这里应当写"外部密钥存储的引用"，绝不写明文令牌。
                 * 本轮没有真实凭据，保持 null。
                 */
                null,
                now,
                now
        );

        repository.upsert(connection, now);

        return ProviderView.of(adapter, connection);
    }

    /** 解绑。不存在也返回成功（幂等），避免界面因为重复点击报错。 */
    public void disconnect(String userId, String providerId) {
        requireAdapter(providerId);
        repository.delete(userId, providerId);
    }

    public List<ProviderConnection> connections(String userId) {
        return repository.findAll(userId);
    }

    public Optional<ProviderConnection> connection(String userId, String providerId) {
        return repository.find(userId, providerId);
    }

    private MusicProviderAdapter requireAdapter(String providerId) {
        MusicProviderAdapter adapter = adapterMap().get(providerId);
        if (adapter == null) {
            throw new BusinessException(
                    404, "PROVIDER_NOT_FOUND", "未知的音乐来源：" + providerId);
        }
        return adapter;
    }

    /**
     * 给前端看的来源视图。
     *
     * <p>刻意把 {@code simulated} 与 {@code configured} 分开：
     * 一个模拟来源是"可用但模拟"，一个真实来源可能是"存在但未配置"。
     * 合成一个布尔值会让界面无法准确表达这两种状态。
     */
    public record ProviderView(
            String provider,
            String displayName,
            boolean simulated,
            boolean configured,
            List<String> capabilities,
            boolean connected,
            String status,
            String scopes,
            String providerUserId,
            Long connectedAtEpochSecond
    ) {

        static ProviderView of(MusicProviderAdapter adapter, ProviderConnection connection) {
            List<String> caps = new ArrayList<>(
                    new TreeSet<>(adapter.capabilities().granted().stream()
                            .map(Enum::name)
                            .toList()));

            return new ProviderView(
                    adapter.id(),
                    adapter.displayName(),
                    adapter.simulated(),
                    adapter.configured(),
                    caps,
                    connection != null && connection.usable(),
                    connection == null ? null : connection.status().name(),
                    connection == null ? null : connection.scopes(),
                    connection == null ? null : connection.providerUserId(),
                    connection == null || connection.connectedAt() == null
                            ? null
                            : connection.connectedAt().getEpochSecond()
            );
        }
    }
}
