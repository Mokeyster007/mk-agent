package com.example.mkagent.provider;

/**
 * 外部曲目引用。
 *
 * <p>身份是 {@code provider + providerTrackId}，**不是歌名**：
 * 两个平台可以有同名歌曲，同一平台也可以有同名不同版本（live / remaster）。
 * 按歌名去重会把它们合成一条，用户点进去听到的却是另一首。
 *
 * <p>刻意**没有** {@code localSongId} 字段：
 * 外部曲目不自动导入本地曲库，也不允许把外部 ID 填进现有 songId。
 * 想播放必须先有本地音源映射（本轮不代理第三方流）。
 */
public record ProviderTrack(
        String provider,
        String providerTrackId,
        String title,
        String artist,
        String album,
        /** 版本信息（live / remaster / acoustic），没有则为 null */
        String version,
        Integer durationSeconds,
        /** 本页能否直接播放（本轮 demo 来源恒为 false） */
        boolean playable,
        /** 不能播时的原因；能播时为 null */
        String playableReason
) {

    /** 稳定身份键：用于前端 key 与"同名不合并"的断言。 */
    public String trackKey() {
        return provider + ":" + providerTrackId;
    }
}
