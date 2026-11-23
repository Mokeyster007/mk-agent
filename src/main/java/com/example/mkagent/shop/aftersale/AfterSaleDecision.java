package com.example.mkagent.shop.aftersale;

import java.time.LocalDateTime;

/**
 * 客服决策记录（一单一决，只增不改）。
 *
 * <p>为什么不把 {@code decidedBy} / {@code decidedAt} 直接放在退货单上：
 * 放在同一行意味着任何一次 {@code UPDATE} 都能顺手改掉"当时是谁批的、
 * 为什么驳回"，审计价值当场归零。单独一张表 + 唯一索引，
 * 让"这一单被谁在什么时候怎么处理"成为不可覆盖的事实。
 *
 * @param id        内部 id
 * @param returnId  所属退货单 id
 * @param decision  决策结果（与 {@link AfterSaleStatus} 的两个终态同名）
 * @param reason    驳回原因（批准时为 {@code null}，由数据库 CHECK 保证）
 * @param decidedBy 决策人（服务端身份，不来自请求体）
 * @param decidedAt 决策时间
 */
public record AfterSaleDecision(
        long id,
        long returnId,
        AfterSaleStatus decision,
        String reason,
        String decidedBy,
        LocalDateTime decidedAt
) {

    /** 决策结果的中文标签（页面直接展示）。 */
    public String label() {
        return decision.label();
    }
}
