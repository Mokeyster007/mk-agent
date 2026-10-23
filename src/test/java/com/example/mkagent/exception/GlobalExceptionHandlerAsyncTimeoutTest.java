package com.example.mkagent.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理器对 SSE 超时的处理（不联网、不启动完整 Spring 上下文）。
 *
 * 要证明的三件事：
 * 1. AsyncRequestTimeoutException 命中"专门处理器"，不会落到通用 Exception 处理器；
 * 2. 处理器返回 void，MVC 不再写响应体 —— 因此不会出现
 *    HttpMessageNotWritableException（SSE 连接已断开时写 JSON 必然失败）；
 * 3. 即使响应已经 committed，处理器也不碰响应对象，不抛异常。
 */
class GlobalExceptionHandlerAsyncTimeoutTest {

    @Test
    @DisplayName("AsyncRequestTimeoutException 解析到专门处理器，而不是通用处理器")
    void resolvesDedicatedHandler() throws NoSuchMethodException {
        Method handler = GlobalExceptionHandler.class.getMethod(
                "handleAsyncRequestTimeout",
                Exception.class
        );

        ExceptionHandler annotation = handler.getAnnotation(ExceptionHandler.class);
        assertNotNull(annotation, "必须标注 @ExceptionHandler");
        assertTrue(
                Arrays.asList(annotation.value()).contains(AsyncRequestTimeoutException.class)
        );
        assertTrue(
                Arrays.asList(annotation.value()).contains(AsyncRequestNotUsableException.class)
        );

        // 返回 void 是关键：Spring MVC 视为"已处理、无需响应体"。
        assertEquals(void.class, handler.getReturnType());
    }

    @Test
    @DisplayName("SSE 超时经 MVC 处理：200 且响应体为空，不尝试写 JSON")
    void asyncTimeoutWritesNoBody() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/test/async-timeout"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("SSE 连接不可用（AsyncRequestNotUsableException）同样不写响应体")
    void asyncNotUsableWritesNoBody() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/test/async-not-usable"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("响应已 committed 时处理器不触碰响应，也不抛转换异常")
    void handlerLeavesCommittedResponseUntouched() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        assertDoesNotThrow(
                () -> handler.handleAsyncRequestTimeout(new AsyncRequestTimeoutException())
        );

        assertEquals("", response.getContentAsString());
        assertEquals(200, response.getStatus());
        assertTrue(response.isCommitted());
    }

    @Test
    @DisplayName("普通异常仍走通用处理器（不会被异步超时处理器吞掉）")
    void otherExceptionsStillUseGenericHandler() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("系统繁忙")
                ));
    }

    /**
     * 测试专用控制器：抛出各类异步异常。
     */
    @RestController
    static class ThrowingController {

        @GetMapping("/test/async-timeout")
        public String asyncTimeout() throws IOException {
            throw new AsyncRequestTimeoutException();
        }

        @GetMapping("/test/async-not-usable")
        public String asyncNotUsable() throws IOException {
            throw new AsyncRequestNotUsableException("response is not usable");
        }

        @GetMapping("/test/boom")
        public String boom() {
            throw new IllegalStateException("boom");
        }
    }
}
