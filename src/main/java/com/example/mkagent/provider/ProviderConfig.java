package com.example.mkagent.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 平台适配器装配。
 *
 * <p>当前只注册**两个模拟来源**。没有真实平台，因为：
 * <ul>
 *   <li>没有可信的 MkAgent 服务端身份（绑定归属不可信）；</li>
 *   <li>没有平台的客户端凭据与合作权限。</li>
 * </ul>
 * 这两条不满足时接入真实平台，做出来的只会是"看起来通了"的假集成。
 * 接入真实平台时，在这里新增一个实现 {@link MusicProviderAdapter} 的 Bean，
 * 并由 {@link ProviderBindingGuard} 决定是否允许绑定。
 */
@Configuration
public class ProviderConfig {

    @Bean
    public MusicProviderAdapter demoAlphaProvider() {
        return DemoMusicProviderAdapter.alpha();
    }

    @Bean
    public MusicProviderAdapter demoBetaProvider() {
        return DemoMusicProviderAdapter.beta();
    }
}
