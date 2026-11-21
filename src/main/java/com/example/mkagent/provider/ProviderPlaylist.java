package com.example.mkagent.provider;

/**
 * 外部歌单（只读）。
 *
 * <p>只有元数据：本轮不拉取歌单内曲目（那是另一条分页链路，
 * 需要单独的权限与超时预算）。{@code trackCount} 允许为 null——
 * 平台没给就不要猜 0。
 */
public record ProviderPlaylist(
        String provider,
        String providerPlaylistId,
        String name,
        Integer trackCount
) {
}
