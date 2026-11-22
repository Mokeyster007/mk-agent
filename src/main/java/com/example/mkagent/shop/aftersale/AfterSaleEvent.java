package com.example.mkagent.shop.aftersale;

import java.time.LocalDateTime;

/**
 * 售后事件（只增不改）。
 *
 * <p>A1 只会产生 {@link #TYPE_SUBMITTED} 一种事件：买家提交退货申请。
 * B 段的审批、收货、验货会往同一个序列里追加事件，
 * 因此这里从第一天就按"事件流"建模，而不是把状态塞回退货单一行。
 *
 * @param id        事件 id
 * @param returnId  所属退货单
 * @param type      事件类型
 * @param actorId   操作人（买家或后台人员，来自服务端身份）
 * @param note      备注（可为空）
 * @param createdAt 发生时间
 */
public record AfterSaleEvent(
        long id,
        long returnId,
        String type,
        String actorId,
        String note,
        LocalDateTime createdAt
) {

    /** 买家提交退货申请。 */
    public static final String TYPE_SUBMITTED = "SUBMITTED";

    /** 事件类型的中文标签（页面直接展示）。 */
    public static String labelOf(String type) {
        return switch (type) {
            case TYPE_SUBMITTED -> "买家提交退货申请";
            default -> type;
        };
    }
}
