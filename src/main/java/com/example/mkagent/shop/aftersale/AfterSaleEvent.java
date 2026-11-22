package com.example.mkagent.shop.aftersale;

import java.time.LocalDateTime;

/**
 * 售后事件（只增不改）。
 *
 * <p>事件与状态转换一一对应：{@link #TYPE_SUBMITTED}（买家提交）、
 * {@link #TYPE_APPROVED}（客服批准）、{@link #TYPE_REJECTED}（客服驳回）。
 *
 * <p>驳回时释放额度是**同一个动作的一部分**，不额外写一条 {@code RELEASED}：
 * 一个动作两条事件会让"事件条数"与"状态转换次数"对不上，
 * 排查时得先猜哪两条属于同一次操作。
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

    /** 客服批准。 */
    public static final String TYPE_APPROVED = "APPROVED";

    /** 客服驳回（同一事务内已释放额度）。 */
    public static final String TYPE_REJECTED = "REJECTED";

    /** 事件类型的中文标签（页面直接展示）。 */
    public static String labelOf(String type) {
        return switch (type) {
            case TYPE_SUBMITTED -> "买家提交退货申请";
            case TYPE_APPROVED -> "客服批准退货申请";
            case TYPE_REJECTED -> "客服驳回退货申请";
            default -> type;
        };
    }
}
