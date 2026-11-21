package com.example.mkagent.provider;

import com.example.mkagent.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 平台绑定的准入判断。
 *
 * <p>为什么需要单独一层：绑定是"把外部账号挂到某个 MkAgent 用户身上"，
 * 归属错了比读不到数据严重得多。当前身份是 {@code X-User-Id} 占位，
 * 可以被任意伪造——用它作为真实平台的连接归属，等于把别人的音乐账号
 * 挂到伪造的身份下。
 *
 * <p>所以：
 * <ul>
 *   <li>模拟来源：允许（没有凭据、没有外部账号，最坏情况是看到假数据，且界面标注"模拟"）；</li>
 *   <li>真实来源：必须同时满足"可信身份"与"适配器已配置凭据"，
 *       否则明确拒绝，不做假登录。</li>
 * </ul>
 */
@Component
public class ProviderBindingGuard {

    private final ProviderProperties properties;

    public ProviderBindingGuard(ProviderProperties properties) {
        this.properties = properties;
    }

    public void requireBindable(MusicProviderAdapter adapter) {
        if (adapter.simulated()) {
            if (!properties.isDemoEnabled()) {
                throw new BusinessException(
                        409, "PROVIDER_DEMO_DISABLED", "演示来源已关闭，无法连接");
            }
            return;
        }

        if (!properties.isTrustedIdentity()) {
            /*
             * 这条分支本轮一定命中：没有真实登录，就没有可信归属。
             * 明确报错而不是"先绑上再说"——后者会把 demo 身份写进连接表，
             * 等接入真实认证时这些脏数据无法区分。
             */
            throw new BusinessException(
                    409,
                    "IDENTITY_NOT_TRUSTED",
                    "当前身份不是可信的服务端身份，无法绑定真实音乐平台");
        }

        if (!adapter.configured()) {
            throw new BusinessException(
                    409,
                    "PROVIDER_NOT_CONFIGURED",
                    "该平台的客户端凭据与合作权限尚未配置");
        }

        /*
         * 走到这里说明"身份可信 + 凭据到位"，但真实平台的绑定必须由
         * OAuth 回调完成（校验 state / PKCE、把回调与发起用户绑定、
         * 再写入连接）。本轮没有这条链路，所以直接拒绝。
         *
         * 为什么不用这个接口"先绑上"：没有令牌的连接是一个假连接，
         * 它会让界面显示"已连接"，而所有读取都会失败。
         */
        throw new BusinessException(
                501,
                "PROVIDER_OAUTH_NOT_IMPLEMENTED",
                "真实音乐平台需要通过官方 OAuth 授权回调完成绑定，本轮未实现");
    }
}
