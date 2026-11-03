package com.example.mkagent.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BusinessException} 与全局异常处理的兼容性约束。
 *
 * 批次 2 给 BusinessException 加了可选原因码 {@code code}，
 * 这一组断言保证：
 * 1. 旧构造方式（只有状态码 + 消息）行为完全不变；
 * 2. {@code code} 为 null 时响应体里不出现该字段（老客户端不会看到新字段）；
 * 3. HTTP 状态码仍然来自异常本身；
 * 4. 未预期异常仍然只返回脱敏文案，不泄露异常消息与堆栈。
 */
class BusinessExceptionContractTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("旧构造方式：code 为 null，响应体结构与状态码不变")
    void legacyConstructorKeepsResponseShape() {
        BusinessException e = new BusinessException(409, "任务已结束，无法取消。");

        assertNull(e.getCode(), "旧构造方式不应产生原因码");
        assertEquals(409, e.getHttpStatus());
        assertEquals("任务已结束，无法取消。", e.getMessage());

        ResponseEntity<Map<String, Object>> response =
                handler.handleBusinessException(e);

        assertEquals(409, response.getStatusCode().value());

        Map<String, Object> body = response.getBody();
        assertTrue(body != null);
        assertEquals(2, body.size(), "老接口的响应体只能有 success / message，实际：" + body);
        assertEquals(Boolean.FALSE, body.get("success"));
        assertEquals("任务已结束，无法取消。", body.get("message"));
        assertFalse(body.containsKey("code"), "code 为 null 时不得出现在响应体里");
    }

    @Test
    @DisplayName("带原因码：响应体多一个稳定 code，其余字段不变")
    void codeIsExposedOnlyWhenPresent() {
        BusinessException e = new BusinessException(429, "RESUME_CONCURRENCY_LIMIT",
                "系统繁忙，请稍后重试");

        ResponseEntity<Map<String, Object>> response =
                handler.handleBusinessException(e);

        assertEquals(429, response.getStatusCode().value());

        Map<String, Object> body = response.getBody();
        assertTrue(body != null);
        assertEquals(3, body.size(), "实际：" + body);
        assertEquals(Boolean.FALSE, body.get("success"));
        assertEquals("RESUME_CONCURRENCY_LIMIT", body.get("code"));
        assertEquals("系统繁忙，请稍后重试", body.get("message"));
    }

    @Test
    @DisplayName("未预期异常：500 且只返回脱敏文案")
    void unexpectedExceptionIsDesensitized() {
        RuntimeException leaky = new RuntimeException("jdbc://secret-host db password=xxx");

        ResponseEntity<Map<String, Object>> response =
                handler.handleUnexpected(leaky);

        assertEquals(500, response.getStatusCode().value());

        Map<String, Object> body = response.getBody();
        assertTrue(body != null);
        assertEquals(Boolean.FALSE, body.get("success"));
        assertEquals("系统繁忙，请稍后重试。", body.get("message"));
        assertFalse(String.valueOf(body).contains("secret-host"),
                "响应体不得泄露异常原文，实际：" + body);
    }
}
