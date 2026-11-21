package com.example.mkagent.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力表达的边界。
 *
 * <p>这一组用例存在的理由：能力一旦被"顺手"扩大，
 * 就会变成"能读歌单 → 也能存 → 也能发给模型"的滑坡。
 * 这里把三条底线钉死。
 */
class ProviderCapabilitiesTest {

    @Test
    @DisplayName("读能力逐项独立：有读歌单不代表有读喜欢")
    void capabilitiesAreIndependent() {
        ProviderCapabilities onlyPlaylists = new ProviderCapabilities(
                java.util.Set.of(ProviderCapability.READ_PLAYLISTS));

        assertTrue(onlyPlaylists.has(ProviderCapability.READ_PLAYLISTS));
        assertFalse(onlyPlaylists.has(ProviderCapability.READ_LIKED));
        assertFalse(onlyPlaylists.has(ProviderCapability.READ_RECENT));
        assertFalse(onlyPlaylists.has(ProviderCapability.PLAYBACK));
    }

    @Test
    @DisplayName("能读取不等于允许发给模型，也不等于允许持久化")
    void readingDoesNotImplyEgressOrStorage() {
        ProviderCapabilities all = new ProviderCapabilities(java.util.Set.of(
                ProviderCapability.CATALOG_SEARCH,
                ProviderCapability.READ_PLAYLISTS,
                ProviderCapability.READ_LIKED,
                ProviderCapability.READ_RECENT,
                ProviderCapability.PLAYBACK));

        assertFalse(all.shareWithModel(), "本轮不把任何第三方用户数据发给模型");
        assertFalse(all.persistable(), "本轮只做内存聚合，不落本地曲库");
    }

    @Test
    @DisplayName("模拟来源不授予 PLAYBACK：界面上不该出现点了没反应的播放按钮")
    void demoAdaptersDoNotClaimPlayback() {
        MusicProviderAdapter alpha = DemoMusicProviderAdapter.alpha();
        MusicProviderAdapter beta = DemoMusicProviderAdapter.beta();

        assertTrue(alpha.simulated());
        assertTrue(beta.simulated());
        assertFalse(alpha.capabilities().has(ProviderCapability.PLAYBACK));
        assertFalse(beta.capabilities().has(ProviderCapability.PLAYBACK));
    }

    @Test
    @DisplayName("同名歌曲在数据层就是两条：身份是 provider + providerTrackId")
    void sameTitleKeepsDistinctIdentity() {
        ProviderTrack alpha = DemoMusicProviderAdapter.alpha()
                .likedTracks("sim", new PageRequest(1, 50)).stream()
                .filter(track -> "夜航星".equals(track.title()))
                .findFirst()
                .orElseThrow();

        ProviderTrack beta = DemoMusicProviderAdapter.beta()
                .likedTracks("sim", new PageRequest(1, 50)).stream()
                .filter(track -> "夜航星".equals(track.title()))
                .findFirst()
                .orElseThrow();

        assertNotEquals(alpha.trackKey(), beta.trackKey());
        assertFalse(alpha.playable(), "外部曲目本轮不可播放");
        assertTrue(alpha.playableReason().contains("不代理第三方流"));
    }
}
