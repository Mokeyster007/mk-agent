package com.example.mkagent.provider;

/**
 * 平台连接状态。
 *
 * <p>三态就够表达本轮需要区分的界面：
 * <ul>
 *   <li>{@link #CONNECTED} 可用；</li>
 *   <li>{@link #EXPIRED} 授权过期（有连接记录，但令牌不再有效）——
 *       与"从未连接"必须区分，否则用户会反复点"连接"；</li>
 *   <li>{@link #REVOKED} 用户在平台侧撤销了授权。</li>
 * </ul>
 */
public enum ProviderConnectionStatus {

    CONNECTED,
    EXPIRED,
    REVOKED;

    public static ProviderConnectionStatus parse(String raw) {
        if (raw == null) {
            return REVOKED;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // 未知状态一律按"不可用"处理，不猜
            return REVOKED;
        }
    }
}
