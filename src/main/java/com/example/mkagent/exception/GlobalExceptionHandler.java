package com.example.mkagent.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * 全局异常处理器。
 *
 * 项目此前没有统一的异常出口，业务错误（如取消不存在的任务）
 * 只能靠各 Controller 自行拼返回值。这里统一处理：
 * 1. BusinessException：按异常携带的 HTTP 状态码返回 JSON 业务错误；
 * 2. NoResourceFoundException：未暴露的端点 / 不存在的路径返回其自带状态码（404），
 *    不能被下方的通用异常处理器拦截成 500；
 * 3. 其余未捕获异常：返回 500，避免把堆栈细节暴露给客户端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：返回异常自带的状态码与消息。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(
            BusinessException e
    ) {
        log.warn("业务异常：status={}, message={}", e.getHttpStatus(), e.getMessage());

        return ResponseEntity
                .status(e.getHttpStatus())
                .body(Map.of(
                        "success", false,
                        "message", e.getMessage()
                ));
    }

    /**
     * 静态资源 / 未暴露端点不存在：
     * 保留异常自带的状态码（通常 404），不能被通用处理器转成 500。
     * 例如 /actuator/env 未暴露时必须表现为“不可访问”。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(
            NoResourceFoundException e
    ) {
        return ResponseEntity
                .status(e.getStatusCode())
                .body(Map.of(
                        "success", false,
                        "message", "资源不存在"
                ));
    }
    
    /**
     * 通用处理：未预期的系统异常。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("未预期异常", e);

        return ResponseEntity
                .internalServerError()
                .body(Map.of(
                        "success", false,
                        "message", "系统繁忙，请稍后重试。"
                ));
    }
}
