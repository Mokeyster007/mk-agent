package com.example.mkagent.shop.aftersale;

/**
 * 收货进度：**算出来的**，不是存出来的。
 *
 * <pre>
 * approvedQty = 退货单的申请数量（status = APPROVED 时才谈进度）
 * receivedQty = SUM(shop_aftersale_receipt.received_qty)
 * remaining   = max(0, approvedQty - receivedQty)
 * </pre>
 *
 * <h2>为什么不给退货单加第四个状态</h2>
 *
 * {@code shop_aftersale_return.status} 表达的是**客服结论**：
 * 待审批 / 已批准 / 已驳回。收货是批准之后仓库做的事，
 * 把它塞进同一个字段会得到"已批准但部分收货"这种既不是状态也不是事实的值，
 * 而且会让"已批准"这个查询条件突然漏掉一批单。
 *
 * <p>进度是纯粹的派生值：只要收货记录在，进度就永远和事实一致；
 * 存一份独立的进度列，就多了一个会与事实不一致的地方。
 *
 * @param approvedQty 批准数量（= 退货单申请数量）
 * @param receivedQty 累计实收数量
 * @param code        {@code NONE} / {@code PARTIAL} / {@code COMPLETE}
 * @param label       中文（未收货 / 部分收货 / 全部收货）
 */
public record AfterSaleReceiptProgress(
        int approvedQty,
        int receivedQty,
        String code,
        String label
) {

    /** 一件都没收到。 */
    public static final String CODE_NONE = "NONE";

    /** 收到了一部分。 */
    public static final String CODE_PARTIAL = "PARTIAL";

    /** 全部收齐。 */
    public static final String CODE_COMPLETE = "COMPLETE";

    /**
     * 按"批准数量 + 累计实收"算进度。
     *
     * <p>累计实收大于批准数量时按"全部收货"处理并把剩余夹到 0：
     * 正常路径不可能发生（写入时有超收校验 + 行锁），
     * 但展示层不应该因为一条脏数据就显示"已收 3 / 批准 2，还剩 -1"。
     */
    public static AfterSaleReceiptProgress of(int approvedQty, int receivedQty) {
        int approved = Math.max(0, approvedQty);
        int received = Math.max(0, receivedQty);

        String code;
        if (received <= 0) {
            code = CODE_NONE;
        } else if (received >= approved) {
            code = CODE_COMPLETE;
        } else {
            code = CODE_PARTIAL;
        }

        String label = switch (code) {
            case CODE_COMPLETE -> "全部收货";
            case CODE_PARTIAL -> "部分收货";
            default -> "未收货";
        };

        return new AfterSaleReceiptProgress(approved, received, code, label);
    }

    /** 还能再收几件（夹到 0，不会出现负数）。 */
    public int remainingQty() {
        return Math.max(0, approvedQty - receivedQty);
    }
}
