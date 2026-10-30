package com.example.mkagent.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * "是否启用 OpenAI 兼容客户端"的两个互斥条件。
 *
 * 为什么不用 `@ConditionalOnProperty`：
 * 它对"属性存在但值为空字符串"是算匹配的，而这里要的语义是
 * "base-url 有实际内容才算启用"。用 `StringUtils.hasText` 判断，
 * 既能识别空串，也能识别只写了空格的配置错误。
 *
 * 两个条件必须互斥且穷尽，否则会出现"两个 ChatModel 同时被创建"或
 * "一个都没有"的情况——前者导致按类型注入歧义，后者导致启动失败。
 */
public final class OpenAiCompatibleConditions {

    /**
     * 开关属性：配了它就切到 OpenAI 兼容客户端。
     */
    public static final String BASE_URL_PROPERTY = "mkagent.model.base-url";

    private OpenAiCompatibleConditions() {
    }

    /**
     * 配了 base-url → 启用 OpenAI 兼容客户端。
     */
    public static class Enabled implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return isConfigured(context);
        }
    }

    /**
     * 没配 base-url → 继续用 DashScope 原生客户端。
     */
    public static class Disabled implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !isConfigured(context);
        }
    }

    private static boolean isConfigured(ConditionContext context) {
        return StringUtils.hasText(
                context.getEnvironment().getProperty(BASE_URL_PROPERTY));
    }
}
