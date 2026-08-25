package com.example.mkagent.model;

/**
 * Agent 类型。
 *
 * 不同类型对应不同的工具白名单（最小权限原则），
 * 工具集合的划分与装配见 AgentToolProvider。
 */
public enum AgentType {

    /**
     * 轻量问答型 Agent：仅开放公开网页搜索能力。
     */
    CHAT,

    /**
     * 通用任务型 Agent（当前实现：MkManus）：
     * 网页搜索、网页抓取、任务终止。
     */
    MANUS,

    /**
     * 文件任务型 Agent：文件读写、资源下载、PDF 生成。
     *
     * 当前只保留类型与工具映射，尚未创建完整的 FILE Agent 实例。
     */
    FILE
}
