package com.example.mkagent.shop.aftersale;

/**
 * 退货申请状态（B1 起的完整状态机）。
 *
 * <pre>
 * SUBMITTED ──客服批准──→ APPROVED   保留原额度（那部分数量仍然不能再申请）
 * SUBMITTED ──客服驳回──→ REJECTED   释放本单申请数量（reserved_qty 减回去）
 * </pre>
 *
 * <p>两个终态都**不可回退**，也不允许"再决策一次"：
 * 状态转换用条件更新（{@code WHERE status = 'SUBMITTED'}）实现，
 * 只有 0 行 / 1 行两种结果，没有"改回去"的入口。
 *
 * <p>数据库里的 {@code ck_shop_aftersale_return_status} 与这里的枚举必须一致：
 * 写入一个枚举之外的值会在数据库层失败，而不是变成一个没人认识的字符串。
 *
 * <p>仍然没有 {@code RECEIVED} / {@code REFUNDED}：仓库验收与退款是 B2 之后的事，
 * 提前放进来会让"页面显示已退款、实际一分钱没退"成为可能。
 */
public enum AfterSaleStatus {

    /** 买家已提交，等待客服处理。 */
    SUBMITTED("已提交"),

    /** 客服批准：申请成立，但实物仍在买家手里（等仓库验收，不在 B1 范围）。 */
    APPROVED("已批准"),

    /** 客服驳回：申请不成立，本次申请占用的额度已释放。 */
    REJECTED("已驳回");

    private final String label;

    AfterSaleStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 是否已经做出决策（两个终态）。 */
    public boolean decided() {
        return this != SUBMITTED;
    }

    /**
     * 解析数据库里的字符串。
     *
     * <p>未知值直接抛异常而不是回退成默认状态：
     * 读到不认识的状态说明代码与库不一致，静默降级会让页面显示一个假状态。
     */
    public static AfterSaleStatus parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("退货申请状态为空");
        }
        for (AfterSaleStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的退货申请状态：" + value);
    }
}
