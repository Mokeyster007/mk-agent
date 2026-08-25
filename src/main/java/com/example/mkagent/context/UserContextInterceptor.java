package com.example.mkagent.context;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 用户上下文拦截器：从请求头 X-User-Id 解析用户身份写入 ThreadLocal。
 *
 * 设计要点：
 * 1. 拦截器只"解析与传递"，不做强制校验；
 *    是否必须携带由具体接口层决定（/ai/manus/chat 与 /ai/runs/** 强制），
 *    避免误伤不需要用户身份的接口；
 * 2. afterCompletion 中清理 ThreadLocal，
 *    防止 Tomcat 线程复用导致身份串号。
 */
public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        String userId = request.getHeader(UserContextHolder.USER_ID_HEADER);
        if (userId != null && !userId.isBlank()) {
            UserContextHolder.set(userId.trim());
        }
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex
    ) {
        UserContextHolder.clear();
    }
}
