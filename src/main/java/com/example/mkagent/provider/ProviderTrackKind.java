package com.example.mkagent.provider;

/**
 * 聚合读取的类别。
 *
 * <p>每一类对应一个**独立的只读范围**：能读歌单不代表能读"喜欢"，
 * 所以不能用一个 "read" 概括。缺哪个范围就在该来源上明确返回
 * {@code PERMISSION_DENIED}，而不是静默返回空列表——
 * 空列表会被界面解释成"你没有喜欢的歌"，这是错误的结论。
 */
public enum ProviderTrackKind {

    PLAYLISTS("read:playlists"),
    LIKED("read:liked"),
    RECENT("read:recent"),
    SEARCH("read:catalog");

    private final String scope;

    ProviderTrackKind(String scope) {
        this.scope = scope;
    }

    public String scope() {
        return scope;
    }

    public static ProviderTrackKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return LIKED;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LIKED;
        }
    }
}
