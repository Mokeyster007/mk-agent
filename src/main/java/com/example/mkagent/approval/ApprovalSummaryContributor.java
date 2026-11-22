package com.example.mkagent.approval;

import java.util.List;

/**
 * 审批摘要的扩展点：让**业务模块**决定自己的确认卡长什么样。
 *
 * <h2>为什么不在 ApprovalSummaryBuilder 里直接判 toolName</h2>
 *
 * 那样会让 {@code approval} 包依赖 {@code shop} 包（要注入售后服务去查订单）。
 * 而审批是通用能力，不该知道电商的存在。
 * 反过来由业务提供实现、审批侧只负责"找到并调用"，
 * 新增业务时审批包一行都不用改。
 *
 * <h2>实现纪律</h2>
 *
 * <ul>
 *   <li>只做**只读**查询：摘要是在"模型刚给出工具调用、尚未执行"时构造的，
 *       任何写操作都不允许发生；</li>
 *   <li>查询失败必须返回可用的降级结果（例如只回显参数），
 *       不能因为"摘要不好看"阻断暂停；</li>
 *   <li>不得把参数原文整段塞进摘要：只挑用户需要核对的字段，
 *       且都要脱敏、折叠控制字符、按码点截断。</li>
 * </ul>
 */
public interface ApprovalSummaryContributor {

    /**
     * 这个工具是否由本实现负责摘要。
     *
     * @param toolName 工具名
     */
    boolean supports(String toolName);

    /**
     * 构造摘要内容。
     *
     * @param argsJson 工具参数 JSON（框架序列化的原文）
     * @param userId   当前任务用户；用于只读校验归属，可为 null
     * @return 摘要内容；返回 {@code null} 表示本实现放弃，交给默认渲染
     */
    Contribution contribute(String argsJson, String userId);

    /**
     * 摘要内容。
     *
     * @param label   动作的中文标签（例如"提交退货申请"）
     * @param details 需要用户核对的键值对（顺序即展示顺序）
     */
    record Contribution(String label, List<ApprovalSummary.Detail> details) {
    }
}
