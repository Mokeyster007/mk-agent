package com.example.mkagent.provider;

/**
 * 第三方平台能力。
 *
 * <p><b>刻意没有"写歌单"这一项。</b>本轮只申请读权限；
 * 把写能力放进枚举，会让人以为"以后打开就行"，
 * 而实际的产品约束是"不替用户改远端数据"。
 *
 * <p>每一项都必须**独立**判断：能读歌单不等于能读"喜欢"，
 * 能读也不等于允许保存、分析或发给模型。后者不在本枚举里，
 * 由 {@link ProviderCapabilities#shareWithModel()} 单独表达且恒为 false。
 */
public enum ProviderCapability {

    /** 目录搜索：按关键词查曲目。 */
    CATALOG_SEARCH,

    /** 读歌单列表与歌单内曲目。 */
    READ_PLAYLISTS,

    /** 读"我喜欢的音乐"。 */
    READ_LIKED,

    /** 读最近播放。 */
    READ_RECENT,

    /**
     * 站内播放：平台允许在自己的播放器里放。
     *
     * <p>注意：即使平台支持，本轮也不代理第三方流——
     * 本能力只用于告诉用户"这个来源在网页里放不了"。
     */
    PLAYBACK
}
