package com.example.mkagent.provider;

import java.util.Set;

/**
 * 某个来源**实际授予**的能力。
 *
 * <p>{@code shareWithModel} 恒为 false 并且不是构造参数：
 * 本轮不把任何第三方用户数据发给大模型。把它做成可配置项，
 * 就等于留了一个"改个配置就外发"的开关，不符合本轮的边界。
 */
public record ProviderCapabilities(Set<ProviderCapability> granted) {

    public ProviderCapabilities {
        granted = granted == null ? Set.of() : Set.copyOf(granted);
    }

    public boolean has(ProviderCapability capability) {
        return granted.contains(capability);
    }

    /**
     * 是否允许把该来源的数据发送给模型。
     *
     * <p>恒为 false：跨平台画像与"把歌单喂给模型"是下一轮的设计议题，
     * 需要单独的授权与数据边界，不能用本轮读权限顺带完成。
     */
    public boolean shareWithModel() {
        return false;
    }

    /** 是否允许把该来源的数据持久化到 MkAgent（本轮只在内存中聚合，不落库）。 */
    public boolean persistable() {
        return false;
    }
}
