package com.example.mkagent.provider;

import java.util.List;

/**
 * 只读音乐平台适配器。
 *
 * <p>接口只描述"读"：目录搜索、歌单、喜欢、最近播放。
 * 没有写方法、没有 token 方法、没有"取播放地址"方法——
 * 后两者会把凭据与第三方流带进本层，而本轮明确不代理第三方流。
 *
 * <p>实现方必须做到：
 * <ol>
 *   <li>{@link #configured()} 如实反映"凭据/合作权限是否到位"。
 *       没有就返回 false，上层据此把连接能力标为"未配置"，不做假登录；</li>
 *   <li>{@link #simulated()} 如实标注这是不是模拟来源。
 *       模拟来源永远不能出现在"已接通真实平台"的文案里；</li>
 *   <li>不在方法内部吞掉超时：让异常抛出来，由聚合层做**按来源隔离**。</li>
 * </ol>
 */
public interface MusicProviderAdapter {

    /** 稳定标识（数据库里的 provider 列，不用展示名）。 */
    String id();

    /** 展示名。 */
    String displayName();

    /** 是否为 demo / mock 适配器（没有真实凭据）。 */
    boolean simulated();

    /** 凭据与合作权限是否到位。false = 只能展示为"未配置"。 */
    boolean configured();

    /** 该来源实际授予的能力。 */
    ProviderCapabilities capabilities();

    /** 读歌单列表。 */
    List<ProviderPlaylist> playlists(String providerUserId, PageRequest page);

    /** 读"我喜欢的音乐"。 */
    List<ProviderTrack> likedTracks(String providerUserId, PageRequest page);

    /** 读最近播放。 */
    List<ProviderTrack> recentTracks(String providerUserId, PageRequest page);

    /** 目录搜索。 */
    List<ProviderTrack> search(String providerUserId, String query, PageRequest page);
}
