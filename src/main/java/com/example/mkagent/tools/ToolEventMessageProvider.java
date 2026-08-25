package com.example.mkagent.tools;

/**
 * 工具调用 SSE 事件（tool_start / tool_result）的消息提供者。
 *
 * 职责：把工具名称和原始结果转换成
 * 用户可理解、经过脱敏的事件文本。
 *
 * 安全约束：
 * 1. 不直接返回完整工具原始结果；
 * 2. 不携带 API Key、Cookie、SQL、异常堆栈、文件绝对路径；
 * 3. 原始结果只用于推导摘要（如结果条数），绝不外发。
 */
public interface ToolEventMessageProvider {

    /**
     * 工具调用开始时的用户可读消息。
     *
     * @param toolName 工具名称，如 web_search
     */
    String startMessage(String toolName);

    /**
     * 工具调用完成后的用户可读摘要消息。
     *
     * @param toolName  工具名称
     * @param rawResult 工具原始结果，仅用于推导摘要，禁止直接外发
     */
    String resultMessage(String toolName, String rawResult);
}
