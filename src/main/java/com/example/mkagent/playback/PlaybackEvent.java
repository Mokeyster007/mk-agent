package com.example.mkagent.playback;

import com.example.mkagent.exception.BusinessException;

/**
 * 活跃播放的生命周期事件。
 *
 * <p>为什么把"开始"和"心跳"分成两个事件，而不是只用一个 {@code PLAYING}：
 * 一次 {@code PLAYING} 上报既可能是"刚开始播这首"，也可能是"同一首还在播"。
 * 两者语义完全不同——
 * <ul>
 *   <li>{@link #START} 允许换曲目、允许从暂停恢复、会写一条播放日志；</li>
 *   <li>{@link #HEARTBEAT} 只能刷新**同一个播放实例**的最后活跃时间，
 *       不能换曲目、不能复活已停止的实例、不写播放日志。</li>
 * </ul>
 * 如果只有 {@code PLAYING}，旧标签页迟到的请求就会被当成"开始播放"，
 * 把用户已经切走的那首重新写成当前曲目。
 *
 * <p>{@link #PAUSE} / {@link #STOP} 同样要求实例匹配：
 * 旧标签页不能停掉新实例。
 */
public enum PlaybackEvent {

    /** 新的一次播放开始（新曲目，或同一实例从暂停恢复）。 */
    START,

    /** 心跳：只刷新最后活跃时间。 */
    HEARTBEAT,

    /** 暂停：仍是当前曲目，但**不算正在播放**。 */
    PAUSE,

    /** 停止：当前曲目清空。 */
    STOP;

    /**
     * 解析客户端事件。
     *
     * <p>{@code state} 是旧字段名的别名（PLAYING / PAUSED / STOPPED），
     * 语义映射到对应事件；{@code event} 优先。
     *
     * <p>注意：别名只解决"字段叫什么"，**不**解决"没有实例标识"——
     * {@code instanceId} 仍然是必填的，否则心跳与暂停无法做实例隔离。
     * 旧客户端升级时会收到 400，这是有意的：宁可让它在开发期暴露，
     * 也不要让一个无法区分实例的播放事实进入审批链路。
     */
    public static PlaybackEvent parse(String event, String legacyState) {
        String raw = event != null && !event.isBlank() ? event : legacyState;

        if (raw == null || raw.isBlank()) {
            throw new BusinessException(
                    400, "INVALID_PLAYBACK_EVENT", "缺少播放事件（event 或 state）");
        }

        return switch (raw.trim().toUpperCase()) {
            case "START", "PLAYING" -> START;
            case "HEARTBEAT" -> HEARTBEAT;
            case "PAUSE", "PAUSED" -> PAUSE;
            case "STOP", "STOPPED" -> STOP;
            default -> throw new BusinessException(
                    400, "INVALID_PLAYBACK_EVENT", "未知的播放事件：" + raw);
        };
    }

    /** 事件对应的曲目状态（STOP 之后不再有"当前曲目"）。 */
    public String resultingState() {
        return switch (this) {
            case START, HEARTBEAT -> PlaybackService.STATE_PLAYING;
            case PAUSE -> PlaybackService.STATE_PAUSED;
            case STOP -> PlaybackService.STATE_STOPPED;
        };
    }
}
