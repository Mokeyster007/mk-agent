package com.example.mkagent.playback;

/**
 * 音源交付方式。
 *
 * <p>四种模式是**表达能力**，不是"都已经接通"的清单：
 * 本轮只真实实现 {@link #DIRECT_AUDIO}（服务端鉴权后签名短期地址 + Range 读取）。
 * 另外三种先占位，接口能如实返回它们，但没有任何一条映射使用它们——
 * 没有真实授权联调之前，不宣称已接入官方 SDK。
 *
 * <ul>
 *   <li>{@link #DIRECT_AUDIO}：自有/受控音频文件，服务端按 Range 交付；</li>
 *   <li>{@link #PROVIDER_SDK}：将来由官方 SDK 换取播放凭证（未实现）；</li>
 *   <li>{@link #EXTERNAL_LINK}：跳转到外部页面播放（未实现，不代理第三方流）；</li>
 *   <li>{@link #UNAVAILABLE}：明确不可播，必须给出原因。</li>
 * </ul>
 */
public enum PlaybackMode {

    DIRECT_AUDIO,

    PROVIDER_SDK,

    EXTERNAL_LINK,

    UNAVAILABLE;

    /**
     * 解析数据库里的字符串；未知值按 {@link #UNAVAILABLE} 处理（失败关闭）。
     *
     * <p>不让未知值抛异常：一条脏映射不应该让整个解析接口 500，
     * 它只应该让"这首歌"不可播。
     */
    public static PlaybackMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNAVAILABLE;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNAVAILABLE;
        }
    }

    /**
     * 这种模式是否可能真的出声（用于候选列表的初筛）。
     */
    public boolean isDeliverable() {
        return this == DIRECT_AUDIO || this == PROVIDER_SDK || this == EXTERNAL_LINK;
    }
}
