package com.example.mkagent.shop.aftersale;

/**
 * 退货申请的来源。
 *
 * <p>存在的意义是**可追溯**，不是权限：普通表单和 Agent 工具走的是
 * 同一个 Java 售后服务、同一套资格校验，差别只在"谁发起"。
 * 排查时能一眼看出"这单是页面填的，还是对话里确认后建的"，
 * 比事后翻日志猜要可靠。
 *
 * <p>两种来源的幂等身份不同（见 {@link AfterSaleKeys}），
 * 因此这个字段也是解释"为什么同一个请求重放不会建两单"的线索。
 */
public enum AfterSaleSource {

    /** 买家在售后页面填写的普通表单。 */
    FORM("普通表单"),

    /** 售后 Agent 在人工确认后调用写工具创建。 */
    AGENT("售后 Agent");

    private final String label;

    AfterSaleSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static AfterSaleSource parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("退货申请来源为空");
        }
        for (AfterSaleSource source : values()) {
            if (source.name().equals(value)) {
                return source;
            }
        }
        throw new IllegalArgumentException("未知的退货申请来源：" + value);
    }
}
