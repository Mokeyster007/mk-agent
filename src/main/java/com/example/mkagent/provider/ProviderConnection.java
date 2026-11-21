package com.example.mkagent.provider;

import java.time.Instant;
import java.util.Arrays;

/**
 * 一条平台连接（只读绑定的元数据）。
 *
 * <p>刻意**不含** access/refresh token：
 * 第三方凭据只能放在外部密钥存储里，{@code credentialRef} 只是引用。
 * 本轮没有真实凭据，所以它恒为 null——这也是"未配置"的一种表达。
 */
public record ProviderConnection(
        String userId,
        String provider,
        String providerUserId,
        String displayName,
        ProviderConnectionStatus status,
        String scopes,
        String credentialRef,
        Instant connectedAt,
        Instant updatedAt
) {

    /** 是否拥有某个只读范围（空格分隔）。 */
    public boolean hasScope(String scope) {
        if (scopes == null || scopes.isBlank() || scope == null) {
            return false;
        }
        return Arrays.stream(scopes.trim().split("\\s+"))
                .anyMatch(scope::equals);
    }

    /** 是否能用于读请求（状态 + 凭据引用都到位）。 */
    public boolean usable() {
        return status == ProviderConnectionStatus.CONNECTED;
    }
}
