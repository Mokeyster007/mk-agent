package com.example.mkagent.approval;

/**
 * 工具契约版本（参与配置兼容性指纹）。
 *
 * 什么时候必须 +1：
 * 1. 工具语义变化但签名不变（例如 `add_songs_to_playlist` 从"重复即报错"
 *    改成"重复跳过"）——参数 Schema 看不出区别，只能靠人显式声明；
 * 2. 新增/删除写工具，且旧检查点在新语义下不再安全；
 * 3. 审批粒度变化（单工具审批 → 整轮审批）。
 *
 * 为什么不用时间戳自动生成：自动值每次发布都变，
 * 会让所有已暂停任务在发版后立刻失效——这个决定必须是人做的。
 */
public final class ToolContractVersion {

    /**
     * 当前契约版本。
     */
    public static final String CURRENT = "music-tools-v1";

    private ToolContractVersion() {
    }
}
