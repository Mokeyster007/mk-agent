package com.example.mkagent.shop.aftersale;

/**
 * 退货申请状态。
 *
 * <p>A1 只有 {@link #SUBMITTED} 一个值，这是刻意的：
 * 本轮做到"买家已提交、可查询"就停，**不假装客服已批准**。
 * 数据库里的 {@code ck_shop_aftersale_return_status} 同样只允许这一个值，
 * 因此"多出一个没人认识的状态"在写入时就会失败。
 *
 * <p>B 段新增 {@code APPROVED} / {@code REJECTED} / {@code RECEIVED} 时，
 * 必须同时改这里的枚举和那条 CHECK——让状态机的扩张成为一次看得见的变更。
 */
public enum AfterSaleStatus {

    /** 买家已提交，等待客服处理（A1 的唯一状态）。 */
    SUBMITTED("已提交");

    private final String label;

    AfterSaleStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
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
