package com.example.mkagent.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 平台连接配置。
 *
 * <p>三个开关都默认"最保守"：
 * <ul>
 *   <li>{@code demo-enabled=true} 只影响模拟来源（它们没有凭据，风险为 0）；</li>
 *   <li>{@code trusted-identity=false}：当前身份仍是 {@code X-User-Id} 占位，
 *       **不足以**作为真实平台连接归属。真实平台绑定会被拒绝；</li>
 *   <li>{@code timeout-ms} 是单来源读取预算，超时只让该来源失败。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "mkagent.providers")
public class ProviderProperties {

    /** 是否允许连接演示（模拟）来源。 */
    private boolean demoEnabled = true;

    /**
     * 当前是否已有可信的 MkAgent 服务端身份。
     *
     * <p>为 false 时，任何**真实**平台绑定都会被拒绝（409 IDENTITY_NOT_TRUSTED），
     * 而不是把 demo 的 X-User-Id 当成真实归属。这是本轮"不做假登录"的落点。
     */
    private boolean trustedIdentity = false;

    /** 单来源读取超时（毫秒）。 */
    private long timeoutMs = 1500;

    public boolean isDemoEnabled() {
        return demoEnabled;
    }

    public void setDemoEnabled(boolean demoEnabled) {
        this.demoEnabled = demoEnabled;
    }

    public boolean isTrustedIdentity() {
        return trustedIdentity;
    }

    public void setTrustedIdentity(boolean trustedIdentity) {
        this.trustedIdentity = trustedIdentity;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
