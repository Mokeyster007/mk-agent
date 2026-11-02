package com.example.mkagent.approval;

import java.util.List;

/**
 * 审批摘要：给用户看的那一份。
 *
 * 与"执行用的参数"严格分开：
 * - 摘要只含动作名、歌单名、歌曲数量、有限曲目、有效期；
 * - 参数原文只存在检查点里，用于执行，不进摘要、不下发前端。
 *
 * @param tool           主工具名（一轮多工具时取第一个写工具）
 * @param toolLabel      动作的中文标签，例如"创建歌单"
 * @param playlistName   歌单名（非歌单类工具为 null）
 * @param songCount      涉及歌曲数（非歌单类工具为 null）
 * @param songs          前若干首标题（已脱敏、已截断）
 * @param roundToolCount 本轮待执行的工具总数（整轮暂停）
 * @param text           渲染后的单行摘要（≤500 字符）
 */
public record ApprovalSummary(
        String tool,
        String toolLabel,
        String playlistName,
        Integer songCount,
        List<String> songs,
        int roundToolCount,
        String text
) {

    /**
     * 摘要文本的硬上限（与 agent_approval.summary_text 列宽一致）。
     */
    public static final int MAX_TEXT_LENGTH = 500;
}
