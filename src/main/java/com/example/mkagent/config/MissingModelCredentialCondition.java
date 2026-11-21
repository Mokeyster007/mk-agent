package com.example.mkagent.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * 条件：允许缺凭据启动，且凭据确实为空。
 *
 * <p>两个条件缺一不可：
 * <ul>
 *   <li>{@code mkagent.model.allow-missing-credentials=true} —— 使用者**显式**同意
 *       "这次没有模型也要把应用跑起来"；</li>
 *   <li>api-key 为空 —— 真配了凭据就该用真模型，不能被占位实现顶掉。</li>
 * </ul>
 *
 * <p>为什么不用 {@code @ConditionalOnProperty}：
 * 它只能看一个属性，没法表达"开关打开 **并且** 凭据为空"。
 * 只按开关判断的话，开关打开但凭据齐全时会静默退化成"模型不可用"，
 * 那是最难排查的一类问题。
 *
 * <p>凭据解析顺序与 {@code DashScopeConnectionUtils} 一致
 * （chat.api-key 优先，其次连接级 api-key）：
 * 这里多读一处不影响行为，但少读一处就可能误判"有凭据"，
 * 从而漏掉占位 Bean，让启动重新失败。
 */
class MissingModelCredentialCondition implements Condition {

    static final String SWITCH = "mkagent.model.allow-missing-credentials";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();

        if (!env.getProperty(SWITCH, Boolean.class, false)) {
            return false;
        }

        return !StringUtils.hasText(resolveApiKey(env));
    }

    /**
     * 解析"实际会用于发起请求的 api-key"。
     *
     * <p>只读属性，不读 {@code AI_DASHSCOPE_API_KEY} 环境变量：
     * 后者已经由配置模板的嵌套占位符串进属性里
     * （见 application-local.yml），保持"哪个变量生效"只有一处定义。
     */
    static String resolveApiKey(Environment env) {
        String chatKey = env.getProperty("spring.ai.dashscope.chat.api-key");
        if (StringUtils.hasText(chatKey)) {
            return chatKey;
        }
        return env.getProperty("spring.ai.dashscope.api-key");
    }

    /**
     * 取反条件：注册真实模型 Bean 用。
     *
     * <p>必须单独一个类——{@code @Conditional} 没有内置取反。
     */
    static final class Negated implements Condition {

        private final MissingModelCredentialCondition delegate = new MissingModelCredentialCondition();

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !delegate.matches(context, metadata);
        }
    }
}
