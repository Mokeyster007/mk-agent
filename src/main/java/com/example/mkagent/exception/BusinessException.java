package com.example.mkagent.exception;

/**
 * 业务异常：携带明确的 HTTP 状态码与中文业务消息。
 *
 * 用于可预期的业务错误（例如任务不存在、任务已结束无法取消），
 * 由 GlobalExceptionHandler 统一转换为带状态码的 JSON 响应，
 * 避免直接返回 500。
 */
public class BusinessException extends RuntimeException {

    /**
     * 期望返回给客户端的 HTTP 状态码。
     * 例如：404 任务不存在，409 任务状态冲突。
     */
    private final int httpStatus;

    public BusinessException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
