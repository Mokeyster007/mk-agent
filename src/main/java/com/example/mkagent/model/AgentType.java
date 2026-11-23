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
    FILE,

    /**
     * 离线评测专用类型。
     *
     * 仅用于 AgentEvaluator 内部创建的评测 Agent：
     * 1. 复用 MANUS 的工具白名单与系统提示词；
     * 2. 使用独立的评测并发闸门；
     * 3. 不注入 AgentRunRecorder，因此不会写入 agent_run 表。
     */
    EVAL,

    /**
     * 历史兼容保留符号：已退场的音乐普通用户类型。
     * 仅用于旧库 agent_run / agent_conversation 历史记录反序列化与领域回填，
     * 默认不再注册任何音乐 Agent、音乐路由或音乐工具。
     */
    @Deprecated
    MUSIC_USER,

    /**
     * 历史兼容保留符号：已退场的音乐管理员类型。
     * 仅用于旧库历史记录反序列化，默认不注册任何能力。
     */
    @Deprecated
    MUSIC_ADMIN,

    /**
     * 电商售后（A1）：查本人订单、校验可退资格、查已有申请、
     * 预览并在人工确认后提交退货申请。
     */
    AFTER_SALES,

    /**
     * 电商导购与下单助手：商品硬条件检索与比较、共享购物车操作、
     * 结算预览并在人工确认后创建演示订单。
     */
    SHOP_ASSISTANT
}
