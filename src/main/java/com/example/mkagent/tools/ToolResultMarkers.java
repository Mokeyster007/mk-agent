package com.example.mkagent.tools;

/**
 * 工具结果里出现的关键标记文案。
 *
 * 这些字符串同时被生产代码（重试装饰器、前端文案推导）和测试引用，
 * 集中在一处可以避免"改了一边漏了另一边"——
 * 之前 startsWith("没有找到") 在带引号的字符串上静默失效，
 * 就是文案散落各处的后果之一。
 *
 * 放在 tools 包（而不是 resilience）是为了保持依赖方向单一：
 * resilience 依赖 tools，反之不行。
 */
public final class ToolResultMarkers {

    /**
     * 重试耗尽文案的固定标记。
     *
     * 完整文案形如：
     * "工具 web_scrape 多次尝试仍失败：Read timed out。请换用其他方式，
     * 或在无法完成时调用 terminate_task"。
     */
    public static final String EXHAUSTED = "多次尝试仍失败";

    /**
     * "同一工具 + 同一目标主机"连续重试耗尽、后续调用被直接拦下的标记。
     *
     * 完整文案形如：
     * "该站点连续失败，请换用其他来源或在无法完成时调用 terminate_task
     * （工具 web_scrape，目标 github.com，已连续重试耗尽 2 次）"。
     *
     * 与 {@link #EXHAUSTED} 分开：这条文案表示"根本没再执行"，
     * 前端文案应显示"已跳过"，而不是"抓取失败"。
     */
    public static final String TARGET_BLOCKED = "该站点连续失败";

    private ToolResultMarkers() {
    }
}
