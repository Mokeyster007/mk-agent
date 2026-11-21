package com.example.mkagent.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
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
     *
     * 带原因码时额外返回 {@code code}（稳定常量，客户端按它分支），
     * 不带时响应体与老版本完全一致——老接口的返回结构不能被这次改动改变。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(
            BusinessException e
    ) {
        log.warn("业务异常：status={}, code={}, message={}",
                e.getHttpStatus(), e.getCode(), e.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        if (e.getCode() != null) {
            body.put("code", e.getCode());
        }
        body.put("message", e.getMessage());

        return ResponseEntity
                .status(e.getHttpStatus())
                .body(body);
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
     * SSE / 异步请求超时（AsyncRequestTimeoutException）与
     * 异步响应已不可写（AsyncRequestNotUsableException）。
     *
     * 这两类异常的共同点：HTTP 连接已经由 Servlet 容器接管或已经断开，
     * 此时"再写一个 JSON 错误体"必然失败，会二次抛出
     * HttpMessageNotWritableException，把一条正常的超时记录刷成 ERROR 堆栈。
     *
     * 因此这里刻意：
     * 1. 返回 void：Spring MVC 视为"已处理、无需响应体"，不会调用消息转换器；
     * 2. 只记一行 WARN（不带堆栈），保留可观测性又不污染日志。
     *
     * 注意：必须是比 @ExceptionHandler(Exception.class) 更精确的匹配，
     * 否则会先落到通用处理器上，重新变成"写响应体 → 转换失败"。
     */
    @ExceptionHandler({
            AsyncRequestTimeoutException.class,
            AsyncRequestNotUsableException.class
    })
    public void handleAsyncRequestTimeout(Exception e) {
        log.warn(
                "异步请求已结束，跳过响应体写入：type={}",
                e.getClass().getSimpleName()
        );
    }

    /**
     * 数据库约束冲突（唯一索引 / CHECK / 外键）。
     *
     * <p>为什么单独处理成 409 而不是让它落到 500：
     * 商城的库存流水唯一索引、库存非负 CHECK 都是**业务语义**的约束
     * （"同一订单行只能出库一次"、"库存不能为负"），
     * 撞上它们说明请求与当前状态冲突，重试同样的请求也不会成功。
     * 返回 500 会让人以为是服务挂了，去重启或重试，反而放大问题。
     *
     * <p>日志只记异常类名与最内层原因类名，不记异常消息：
     * 约束冲突的原始消息里带 SQL 片段和参数值（可能含用户数据）。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(
            DataIntegrityViolationException e
    ) {
        Throwable root = e.getMostSpecificCause();
        log.warn("数据库约束冲突：type={}, root={}",
                e.getClass().getSimpleName(),
                root == null ? "unknown" : root.getClass().getSimpleName());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "DATA_CONFLICT");
        body.put("message", "操作与当前数据状态冲突，请刷新后重试。");

        return ResponseEntity.status(409).body(body);
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
