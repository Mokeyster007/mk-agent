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
     * 音乐业务：普通用户。
     *
     * 只读音乐工具（检索歌曲 / 歌曲详情 / 我的歌单）。
     * 写工具（建歌单、加歌等）在 M3 之前不得进入本类型的白名单。
     *
     * 追加在枚举末尾：agent_run.agent_type 存的是枚举名（VARCHAR(32)），
     * 不依赖 ordinal，但保持既有值的相对顺序可以让日志与脚本更好读。
     */
    MUSIC_USER,

    /**
     * 音乐业务：管理员。
     *
     * 与 MUSIC_USER 的差别只有"能看到统计类只读数据"，
     * 且管理员身份必须由服务端查 music_user 表确认，
     * 绝不接受请求参数或提示词里的自我声明。
     */
    MUSIC_ADMIN
}
