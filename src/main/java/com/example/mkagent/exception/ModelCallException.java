package com.example.mkagent.exception;

/**
 * 模型服务调用失败（不可重试或重试已耗尽）。
 *
 * 与普通 RuntimeException 的区别：
 * 1. 携带一条"可以推给前端"的脱敏文案（userMessage）；
 * 2. BaseAgent 识别到本异常时，用 userMessage 作为 ERROR 事件内容，
 *    不再使用通用的"智能体执行失败，请稍后重试。"；
 * 3. 原始异常仍作为 cause 保留，完整堆栈只进服务端 DEBUG 日志，
 *    不进入 SSE、不进入数据库。
 */
public class ModelCallException extends RuntimeException {

    /**
     * 推送给前端的脱敏提示。
     */
    private final String userMessage;

    public ModelCallException(String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.userMessage = userMessage;
    }

    public String getUserMessage() {
        return userMessage;
    }
}
