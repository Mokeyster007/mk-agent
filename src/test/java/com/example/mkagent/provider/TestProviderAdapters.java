package com.example.mkagent.provider;

import java.util.List;

/**
 * 测试专用适配器。
 *
 * <p>生产代码里只有两个模拟来源；超时、异常、真实来源这三类行为
 * 只用于验证"按来源隔离"和"绑定准入"，不应该出现在产品装配里。
 */
final class TestProviderAdapters {

    private TestProviderAdapters() {
    }

    /** 永远超时：验证一个来源卡住不影响其它来源。 */
    static class SlowAdapter extends StubAdapter {

        SlowAdapter() {
            super("test-slow", "慢来源（测试）", true, true);
        }

        @Override
        public List<ProviderTrack> likedTracks(String providerUserId, PageRequest page) {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    /** 抛异常，且异常信息里带"密钥"：验证错误文案被脱敏。 */
    static class BrokenAdapter extends StubAdapter {

        static final String SECRET = "token=SECRET-SHOULD-NOT-LEAK";

        BrokenAdapter() {
            super("test-broken", "故障来源（测试）", true, true);
        }

        @Override
        public List<ProviderTrack> likedTracks(String providerUserId, PageRequest page) {
            throw new IllegalStateException(SECRET);
        }
    }

    /** 真实来源（非模拟），用于验证绑定准入。 */
    static class RealAdapter extends StubAdapter {

        RealAdapter(String id, boolean configured) {
            super(id, "真实来源（测试）", false, configured);
        }
    }

    /** 空实现基类。 */
    abstract static class StubAdapter implements MusicProviderAdapter {

        private final String id;

        private final String displayName;

        private final boolean simulated;

        private final boolean configured;

        StubAdapter(String id, String displayName, boolean simulated, boolean configured) {
            this.id = id;
            this.displayName = displayName;
            this.simulated = simulated;
            this.configured = configured;
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
            return simulated;
        }

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public ProviderCapabilities capabilities() {
            return new ProviderCapabilities(java.util.Set.of(
                    ProviderCapability.READ_LIKED,
                    ProviderCapability.READ_PLAYLISTS,
                    ProviderCapability.READ_RECENT,
                    ProviderCapability.CATALOG_SEARCH));
        }

        @Override
        public List<ProviderPlaylist> playlists(String providerUserId, PageRequest page) {
            return List.of();
        }

        @Override
        public List<ProviderTrack> likedTracks(String providerUserId, PageRequest page) {
            return List.of();
        }

        @Override
        public List<ProviderTrack> recentTracks(String providerUserId, PageRequest page) {
            return List.of();
        }

        @Override
        public List<ProviderTrack> search(
                String providerUserId, String query, PageRequest page) {
            return List.of();
        }
    }
}
