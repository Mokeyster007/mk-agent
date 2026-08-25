package com.example.mkagent.model.dto;

import java.time.LocalDateTime;

/**
 * agent_run 分页查询条件（请求入参）。
 *
 * 约束：
 * 1. userId 不由客户端传入，由服务端从用户上下文强制填充，
 *    防止越权查询他人任务；
 * 2. state / agentType / 时间范围均为可选筛选条件。
 */
public class AgentRunQueryDTO {

    /**
     * 页码，从 1 开始。
     */
    private long pageNum = 1;

    /**
     * 每页条数。
     */
    private long pageSize = 10;

    /**
     * 服务端填充：只允许查询自己的任务。
     */
    private String userId;

    /**
     * 可选：按任务状态筛选（AgentState 枚举名）。
     */
    private String state;

    /**
     * 可选：按 Agent 类型筛选（CHAT / MANUS / FILE）。
     */
    private String agentType;

    /**
     * 可选：创建时间范围起点（含）。
     */
    private LocalDateTime startTime;

    /**
     * 可选：创建时间范围终点（含）。
     */
    private LocalDateTime endTime;

    public long getPageNum() {
        return pageNum;
    }

    public void setPageNum(long pageNum) {
        this.pageNum = pageNum;
    }

    public long getPageSize() {
        return pageSize;
    }

    public void setPageSize(long pageSize) {
        this.pageSize = pageSize;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getAgentType() {
        return agentType;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }
}
