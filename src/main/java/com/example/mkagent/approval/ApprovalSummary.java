package com.example.mkagent.approval;

import java.util.List;

/**
 * 审批摘要：给用户看的那一份。
 *
 * <p>与"执行用的参数"严格分开：
 * 摘要只含动作名、目标对象、数量、有限条目、有效期；
 * 参数原文只存在检查点里，用于执行，不进摘要、不下发前端。
 *
 * <h2>为什么有 {@code details}</h2>
 *
 * 音乐写工具的确认卡是"歌单名 + 歌曲列表"，而售后写工具的确认卡是
 * "订单号 / 商品 / 数量 / 原因 / 退款金额"——两者的形状不同。
 * 与其为每种业务各写一个摘要类型（前端要跟着认两种结构），
 * 不如统一成"若干条 标签→值"，由各业务自己提供：
 * 音乐工具继续填 playlistName / songCount / songs（保持既有卡片不变），
 * 售后工具填 {@code details}。前端看到 details 就按通用键值对渲染。
 *
 * @param tool           主工具名（一轮多工具时取第一个写工具）
 * @param toolLabel      动作的中文标签，例如"创建歌单"
 * @param playlistName   歌单名（非歌单类工具为 null）
 * @param songCount      涉及歌曲数（非歌单类工具为 null）
 * @param songs          前若干首标题（已脱敏、已截断）
 * @param roundToolCount 本轮待执行的工具总数（整轮暂停）
 * @param text           渲染后的单行摘要（≤500 字符）
 * @param details        通用键值对（无则为空列表）；与上面三个音乐字段互斥使用
 */
public record ApprovalSummary(
        String tool,
        String toolLabel,
        String playlistName,
        Integer songCount,
        List<String> songs,
        int roundToolCount,
        String text,
        List<Detail> details
) {

    /**
     * 摘要文本的硬上限（与 agent_approval.summary_text 列宽一致）。
     */
    public static final int MAX_TEXT_LENGTH = 500;

    /**
     * 通用键值对。
     *
     * <p>label 与 value 都已脱敏、已折叠控制字符：
     * 摘要会进 SSE 事件，换行会把一条事件拆成两行。
     */
    public record Detail(String label, String value) {
    }

    /**
     * 兼容既有调用方（音乐摘要没有 details）。
     */
    public ApprovalSummary(
            String tool,
            String toolLabel,
            String playlistName,
            Integer songCount,
            List<String> songs,
            int roundToolCount,
            String text
    ) {
        this(tool, toolLabel, playlistName, songCount, songs, roundToolCount, text, List.of());
    }

    /** 序列化时永远给数组，避免前端拿到 null 还要判空。 */
    public ApprovalSummary {
        details = details == null ? List.of() : List.copyOf(details);
    }
}
