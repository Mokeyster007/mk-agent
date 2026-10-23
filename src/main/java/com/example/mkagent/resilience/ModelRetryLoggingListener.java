package com.example.mkagent.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;

import java.util.regex.Pattern;

/**
 * 模型调用重试日志监听器。
 *
 * 解决的问题：
 * Spring AI 自带的监听器（RetryUtils$2）用 {@code log.warn(msg, throwable)} 打印，
 * 一次 400 错误会把整段堆栈刷进 WARN 日志，几百行噪音里根本看不到"到底是哪类错误"。
 *
 * 这里统一成：
 * 1. WARN 只输出一行：异常类型 + 单行化并截断的 message；
 * 2. 完整堆栈降到 DEBUG，排查时单独打开即可。
 *
 * 安全约束：
 * message 里可能带请求参数片段，落日志前做一次密钥打码，
 * 保证 sk-xxx、api-key=xxx 之类的值不会进日志。
 */
public class ModelRetryLoggingListener implements RetryListener {

    private static final Logger log =
            LoggerFactory.getLogger(ModelRetryLoggingListener.class);

    /**
     * 单行日志里 message 的最大长度，超出截断。
     */
    private static final int MAX_MESSAGE_CHARS = 300;

    /**
     * 常见密钥形态：sk- 开头的百炼/OpenAI Key。
     */
    private static final Pattern SK_KEY = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");

    /**
     * 形如 api-key=xxx、secret: xxx、token=xxx 的赋值。
     */
    private static final Pattern ASSIGNMENT_SECRET = Pattern.compile(
            "(?i)(api[-_]?key|secret|token|password)\\s*[=:]\\s*[^\\s,;&]+"
    );

    @Override
    public <T, E extends Throwable> void onError(
            RetryContext context,
            RetryCallback<T, E> callback,
            Throwable throwable
    ) {
        int attempt = context.getRetryCount();

        /*
         * 只输出一行：类型 + 摘要。
         * 不要把 throwable 作为最后一个参数传进来，否则 slf4j 会打印完整堆栈。
         */
        log.warn(
                "模型调用失败：attempt={}, error={}",
                attempt,
                summarize(throwable)
        );

        // 完整堆栈只放在 DEBUG，默认日志级别下不可见。
        log.debug("模型调用失败完整堆栈：attempt={}", attempt, throwable);
    }

    /**
     * 把异常压缩成单行摘要：类型 + 单行 message（已打码、已截断）。
     */
    static String summarize(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }

        String type = throwable.getClass().getSimpleName();
        String message = throwable.getMessage();

        if (message == null || message.isBlank()) {
            return type;
        }

        // 折叠换行/制表符，保证日志只有一行。
        String oneLine = message.replaceAll("\\s+", " ").trim();
        oneLine = maskSecrets(oneLine);

        if (oneLine.length() > MAX_MESSAGE_CHARS) {
            oneLine = oneLine.substring(0, MAX_MESSAGE_CHARS) + "...";
        }

        return type + " - " + oneLine;
    }

    /**
     * 密钥打码：日志里只保留形态，不保留真实值。
     */
    static String maskSecrets(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String masked = SK_KEY.matcher(text).replaceAll("sk-***");
        return ASSIGNMENT_SECRET.matcher(masked).replaceAll("$1=***");
    }
}
