package com.example.mkagent.exception;

/**
 * 业务异常：携带明确的 HTTP 状态码与中文业务消息。
 *
 * 用于可预期的业务错误（例如任务不存在、任务已结束无法取消），
 * 由 GlobalExceptionHandler 统一转换为带状态码的 JSON 响应，
 * 避免直接返回 500。
 *
 * 可选的 {@code code} 是**稳定原因码**（大写常量，供客户端分支判断）：
 * 消息文本会随文案调整，原因码不会。审批接口用它区分
 * "已批准 / 已拒绝 / 已过期 / 正在执行 / 启动超时"等情形，
 * 避免客户端去匹配中文提示。
 */
public class BusinessException extends RuntimeException {

    /**
     * 期望返回给客户端的 HTTP 状态码。
     * 例如：404 任务不存在，409 任务状态冲突。
     */
    private final int httpStatus;

    /**
     * 稳定原因码，可为 null（老接口不传时响应体里不出现该字段）。
     */
    private final String code;

    public BusinessException(int httpStatus, String message) {
        this(httpStatus, null, message);
    }

    public BusinessException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }
}
