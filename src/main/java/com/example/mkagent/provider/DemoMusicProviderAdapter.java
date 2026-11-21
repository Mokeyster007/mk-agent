package com.example.mkagent.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 演示用只读适配器。
 *
 * <p>它**没有**连接任何真实平台：没有 OAuth、没有 token、没有网络请求。
 * 存在的目的只有一个——在没有平台授权的情况下，把"多来源聚合"的
 * 数据形状、分页、超时隔离、UI 状态做完整并可测。
 *
 * <p>三个必须如实暴露的事实（由 {@link #simulated()} 与文案保证）：
 * <ol>
 *   <li>这是模拟来源，不是"已接网易云 / QQ 音乐"；</li>
 *   <li>曲目不能在本页播放（{@code playable=false}），因为不代理第三方流；</li>
 *   <li>数据不会发给模型，也不会写回平台。</li>
 * </ol>
 *
 * <p>两个实例刻意包含**同名歌曲**（标题与歌手都相同、providerTrackId 不同），
 * 用来验证聚合层不按歌名去重。
 */
public class DemoMusicProviderAdapter implements MusicProviderAdapter {

    private static final String NOT_PLAYABLE_REASON =
            "外部来源仅只读展示：本轮不代理第三方流，也不能把它当作本地曲目播放";

    private final String id;

    private final String displayName;

    private final List<ProviderPlaylist> playlists;

    private final List<ProviderTrack> liked;

    private final List<ProviderTrack> recent;

    private final ProviderCapabilities capabilities;

    public DemoMusicProviderAdapter(
            String id,
            String displayName,
            List<ProviderPlaylist> playlists,
            List<ProviderTrack> liked,
            List<ProviderTrack> recent
    ) {
        this.id = id;
        this.displayName = displayName;
        this.playlists = List.copyOf(playlists);
        this.liked = List.copyOf(liked);
        this.recent = List.copyOf(recent);
        /*
         * 只授予"读"能力。PLAYBACK 不授予：demo 来源本来就不能播，
         * 授予它会让界面出现一个点了没反应的播放按钮。
         */
        this.capabilities = new ProviderCapabilities(Set.of(
                ProviderCapability.CATALOG_SEARCH,
                ProviderCapability.READ_PLAYLISTS,
                ProviderCapability.READ_LIKED,
                ProviderCapability.READ_RECENT
        ));
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public boolean simulated() {
        return true;
    }

    /**
     * 对 demo 适配器而言"可用"不等于"已接通真实平台"：
     * 它没有凭据可配置，所以返回 true 表示"这个模拟来源可以工作"，
     * 前端仍必须按 {@link #simulated()} 显示"模拟来源"。
     */
    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public List<ProviderPlaylist> playlists(String providerUserId, PageRequest page) {
        return slice(playlists, page);
    }

    @Override
    public List<ProviderTrack> likedTracks(String providerUserId, PageRequest page) {
        return slice(liked, page);
    }

    @Override
    public List<ProviderTrack> recentTracks(String providerUserId, PageRequest page) {
        return slice(recent, page);
    }

    @Override
    public List<ProviderTrack> search(String providerUserId, String query, PageRequest page) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String needle = query.trim().toLowerCase();
        List<ProviderTrack> matched = new ArrayList<>();
        for (ProviderTrack track : liked) {
            if (track.title().toLowerCase().contains(needle)
                    || track.artist().toLowerCase().contains(needle)) {
                matched.add(track);
            }
        }
        return slice(matched, page);
    }

    private static <T> List<T> slice(List<T> source, PageRequest page) {
        int from = Math.min(page.offset(), source.size());
        int to = Math.min(from + page.size(), source.size());
        return List.copyOf(source.subList(from, to));
    }

    /* ==================== 两个来源的固定数据 ==================== */

    static DemoMusicProviderAdapter alpha() {
        return new DemoMusicProviderAdapter(
                "demo-alpha",
                "Alpha 音乐（模拟）",
                List.of(
                        new ProviderPlaylist("demo-alpha", "pl-alpha-1", "深夜书房", 12),
                        new ProviderPlaylist("demo-alpha", "pl-alpha-2", "通勤低音量", 8)
                ),
                List.of(
                        track("demo-alpha", "a-1", "夜航星", "林晚", "夜航", null, 241),
                        track("demo-alpha", "a-2", "雾中列车", "林晚", "夜航", null, 198),
                        track("demo-alpha", "a-3", "北纬三十度", "海边的卡夫卡", "地图", null, 233)
                ),
                List.of(
                        track("demo-alpha", "a-2", "雾中列车", "林晚", "夜航", null, 198),
                        track("demo-alpha", "a-1", "夜航星", "林晚", "夜航", null, 241)
                )
        );
    }

    static DemoMusicProviderAdapter beta() {
        return new DemoMusicProviderAdapter(
                "demo-beta",
                "Beta 音乐（模拟）",
                List.of(
                        new ProviderPlaylist("demo-beta", "pl-beta-1", "周末慢跑", 21)
                ),
                List.of(
                        // 与 alpha 同名同歌手：聚合层必须两条都保留
                        track("demo-beta", "b-1", "夜航星", "林晚", "夜航", null, 243),
                        track("demo-beta", "b-2", "纸飞机", "陈默", "夏天", "Live", 205),
                        track("demo-beta", "b-3", "北纬三十度", "海边的卡夫卡", "地图", "Remaster", 236)
                ),
                List.of(
                        track("demo-beta", "b-2", "纸飞机", "陈默", "夏天", "Live", 205)
                )
        );
    }

    private static ProviderTrack track(
            String provider,
            String id,
            String title,
            String artist,
            String album,
            String version,
            int duration
    ) {
        return new ProviderTrack(
                provider, id, title, artist, album, version, duration,
                false, NOT_PLAYABLE_REASON);
    }
}
