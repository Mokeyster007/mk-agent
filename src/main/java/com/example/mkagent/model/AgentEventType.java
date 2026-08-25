package com.example.mkagent.model;

/**
 * 统一 SSE 事件类型。
 *
 * wireName 同时充当：
 * 1. SSE 协议中的 event name，前端通过
 *    eventSource.addEventListener("tool_start", ...) 精确监听；
 * 2. AgentEvent.type 字段值，前端即使只用 onmessage
 *    也能从 JSON data 中解析出事件类型。
 *
 * 命名统一使用小写下划线格式（snake_case）。
 */
public enum AgentEventType {

    /** 本次任务的 runId，前端用它调用取消接口。 */
    RUN_ID("run_id"),

    /** 任务生命周期状态通知：开始、超时、达到预算等。 */
    STATUS("status"),

    /** 一轮 Agent Loop（Think/Act）执行完成后的摘要。 */
    STEP("step"),

    /** 工具调用开始，消息为脱敏后的用户可读文本。 */
    TOOL_START("tool_start"),

    /** 工具调用完成，消息为脱敏摘要，不包含完整原始结果。 */
    TOOL_RESULT("tool_result"),

    /** 模型给出的最终回答。 */
    FINAL_ANSWER("final_answer"),

    /** 任务失败，消息为脱敏提示，不包含异常堆栈。 */
    ERROR("error"),

    /** SSE 流正常结束标记，之后连接关闭。 */
    DONE("done"),

    /** 任务被主动取消（取消接口或客户端提前断开）。 */
    CANCELLED("cancelled");

    private final String wireName;

    AgentEventType(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 事件在 SSE 协议中的 event name，与 AgentEvent.type 保持一致。
     */
    public String wireName() {
        return wireName;
    }
}
