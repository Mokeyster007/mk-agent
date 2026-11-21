package com.example.mkagent.playback;

/**
 * 一条"曲目 → 音源"映射（{@code music_track_source} 的一行）。
 *
 * <p>{@code objectKey} 是**服务端内部标识**：它是媒体目录（或 classpath）下的相对路径，
 * 只用于本地读取。它绝不进入任何接口响应——对外只给签名后的短期地址，
 * 否则前端拿到路径就能绕过鉴权直接猜文件。
 *
 * @param songId       曲目 ID（沿用 music_song 的主键，不新建曲库）
 * @param provider     音源提供方标识（例如 {@code local-test-fixture}）
 * @param mode         交付方式
 * @param format       已知音源格式（例如 {@code WAV}），只用于如实展示
 * @param objectKey    服务端内部定位
 * @param contentType  实际响应的 Content-Type
 * @param ownerUserId  非空表示仅该用户可播；为空表示所有已开通用户可播
 * @param processable  是否允许客户端做音频处理（EQ）
 * @param testAudio    是否为技术夹具测试音
 */
public record TrackSource(
        long songId,
        String provider,
        PlaybackMode mode,
        String format,
        String objectKey,
        String contentType,
        String ownerUserId,
        boolean processable,
        boolean testAudio
) {

    /**
     * 当前用户能否播这条映射。
     *
     * <p>空 owner 表示公共可播；非空必须**完全相等**——
     * 不做前缀匹配、不忽略大小写，避免 {@code music-user-1} 与
     * {@code music-user-10} 互相串号。
     */
    public boolean accessibleBy(String userId) {
        return ownerUserId == null || ownerUserId.isBlank() || ownerUserId.equals(userId);
    }
}
