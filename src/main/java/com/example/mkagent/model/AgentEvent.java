package com.example.mkagent.model;

/**
 * 统一 SSE 事件数据 DTO。
 *
 * 所有推送给前端的 SSE data 都是本对象的 JSON 序列化结果，
 * 前端不再解析 "Step 1: xxx" 这类拼接字符串，
 * 而是直接反序列化结构化字段。
 *
 * 安全约束：
 * message 只允许携带用户可理解的脱敏文本，
 * 禁止包含 API Key、Cookie、SQL、系统提示词、异常堆栈、
 * 文件绝对路径或完整的敏感工具原始结果。
 */
public class AgentEvent {

    /**
     * 本事件所属任务的唯一标识（AgentRunContext.runId）。
     * 每条事件都携带，前端无需依赖单独事件获取。
     */
    private String runId;

    /**
     * 事件类型，与 SSE event name 一致，见 AgentEventType。
     */
    private String type;

    /**
     * 用户可理解、经过脱敏的事件描述文本。
     */
    private String message;

    /**
     * 事件发生时所处的 Agent Loop 步数，不适用时可为 null。
     */
    private Integer step;

    /**
     * 事件发生时间（毫秒时间戳）。
     */
    private long timestamp;

    /**
     * Jackson 反序列化所需的无参构造器。
     */
    public AgentEvent() {
    }

    private AgentEvent(
            String runId,
            AgentEventType type,
            String message,
            Integer step
    ) {
        this.runId = runId;
        this.type = type.wireName();
        this.message = message;
        this.step = step;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 构建一条统一事件，自动填充 type 的协议名称和当前时间戳。
     */
    public static AgentEvent of(
            String runId,
            AgentEventType type,
            String message,
            Integer step
    ) {
        return new AgentEvent(runId, type, message, step);
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Integer getStep() {
        return step;
    }

    public void setStep(Integer step) {
        this.step = step;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
