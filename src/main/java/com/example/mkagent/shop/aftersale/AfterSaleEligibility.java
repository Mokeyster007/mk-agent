package com.example.mkagent.shop.aftersale;

import com.example.mkagent.shop.order.ShopOrderStatus;

import java.time.LocalDateTime;

/**
 * 退货资格判定：**纯函数**，不查库、不碰事务、不依赖 Agent。
 *
 * <h2>为什么必须只有一个实现</h2>
 *
 * 同一套规则要同时服务四个入口：普通表单提交、Agent 的资格查询工具、
 * Agent 的预览工具、以及确认后真正建单的那一次重算。
 * 任何一处另写一份判定，都会出现"预览说能退、提交却退不了"
 * 或者更糟的"预览说不能退、提交却退了"。
 * 因此判定只有这一个静态方法，四个入口都调它。
 *
 * <h2>判定顺序是刻意的</h2>
 *
 * 先判"这是不是你的、签收没有、过没过窗口"，最后才判数量与原因。
 * 顺序决定了用户看到哪条提示：先告诉他"订单未签收"，
 * 比先告诉他"数量不合法"更有用——后者会让他去改一个根本改不对的数字。
 *
 * <h2>窗口是 demo 规则</h2>
 *
 * {@code returnWindowDays} 是演示用的明确窗口（默认 7 天），
 * 不是真实平台的售后政策。它存在的意义是让"超期不能退"成为
 * 一条**可测试、可解释**的规则，而不是一句"看情况"。
 */
public final class AfterSaleEligibility {

    /** 订单不存在或不属于当前买家。 */
    public static final String CODE_ORDER_NOT_FOUND = "ORDER_NOT_FOUND";

    /** 订单还没签收（含已发货未签收）。 */
    public static final String CODE_ORDER_NOT_SIGNED = "ORDER_NOT_SIGNED";

    /** 超过 demo 退货窗口。 */
    public static final String CODE_RETURN_WINDOW_CLOSED = "RETURN_WINDOW_CLOSED";

    /** 数量非法（≤0 或不是整数）。 */
    public static final String CODE_INVALID_QUANTITY = "INVALID_QUANTITY";

    /** 超过剩余可退额度。 */
    public static final String CODE_RETURN_QUOTA_EXCEEDED = "RETURN_QUOTA_EXCEEDED";

    /** 原因为空或过长。 */
    public static final String CODE_INVALID_REASON = "INVALID_REASON";

    /**
     * 退款金额超出可表示范围。
     *
     * <p>金额是整数分，{@code 单价 × 数量} 溢出时**不能**回绕成一个看似正常的数字。
     * 预览据此拒绝展示金额，提交据此拒绝建单——两处共用这一个码，
     * 前端也只需要认一个。
     */
    public static final String CODE_REFUND_AMOUNT_OUT_OF_RANGE = "REFUND_AMOUNT_OUT_OF_RANGE";

    private AfterSaleEligibility() {
    }

    /**
     * 判定结果。
     *
     * @param eligible 是否可以提交
     * @param code     稳定原因码（可提交时为 {@code null}）
     * @param message  给用户看的中文说明
     */
    public record Decision(boolean eligible, String code, String message) {

        static Decision ok() {
            return new Decision(true, null, null);
        }

        static Decision no(String code, String message) {
            return new Decision(false, code, message);
        }
    }

    /**
     * 完整判定。
     *
     * @param subject         事实快照；{@code null} 表示订单不存在或不属于当前买家
     * @param requestedQty    想退几件
     * @param reason          退货原因（原始文本）
     * @param now             当前时间
     * @param windowDays      demo 退货窗口天数
     * @param maxReasonLength 原因最大长度（按码点计）
     */
    public static Decision evaluate(
            ReturnSubject subject,
            Integer requestedQty,
            String reason,
            LocalDateTime now,
            int windowDays,
            int maxReasonLength
    ) {
        if (subject == null) {
            /*
             * 不存在与不属于当前买家合并成同一个结果：
             * 分开会给出"这个订单号确实存在，只是不是你的"的探测信号，
             * 与 A0 的 findByOrderNoAndBuyer 返回 404 是同一口径。
             */
            return Decision.no(CODE_ORDER_NOT_FOUND, "订单不存在或不属于当前买家");
        }

        if (subject.status() != ShopOrderStatus.SIGNED || subject.signedAt() == null) {
            return Decision.no(
                    CODE_ORDER_NOT_SIGNED,
                    "订单当前为「" + subject.status().label() + "」，只有已签收的订单才能申请退货");
        }

        LocalDateTime deadline = subject.signedAt().plusDays(windowDays);
        if (now.isAfter(deadline)) {
            return Decision.no(
                    CODE_RETURN_WINDOW_CLOSED,
                    "已超过 " + windowDays + " 天退货窗口（签收时间 "
                            + subject.signedAt() + "，截止 " + deadline + "）");
        }

        if (requestedQty == null || requestedQty < 1) {
            return Decision.no(CODE_INVALID_QUANTITY, "退货数量至少为 1 件");
        }

        AfterSaleQuota quota = subject.quota();
        if (requestedQty > quota.remaining()) {
            return Decision.no(
                    CODE_RETURN_QUOTA_EXCEEDED,
                    "可退额度不足：已购 " + quota.purchased()
                            + " 件，已占用 " + quota.occupied()
                            + " 件，剩余 " + quota.remaining() + " 件");
        }

        String invalidReason = validateReason(reason, maxReasonLength);
        if (invalidReason != null) {
            return Decision.no(CODE_INVALID_REASON, invalidReason);
        }

        return Decision.ok();
    }

    /**
     * 原因校验（与数量分开，因为"预览"和"提交"都要单独解释它）。
     *
     * @return 不合法时返回中文说明；合法返回 {@code null}
     */
    public static String validateReason(String reason, int maxReasonLength) {
        String normalized = normalizeReason(reason);
        if (normalized.isEmpty()) {
            return "请填写退货原因";
        }
        if (normalized.codePointCount(0, normalized.length()) > maxReasonLength) {
            return "退货原因最多 " + maxReasonLength + " 个字";
        }
        return null;
    }

    /**
     * 原因归一化：去首尾空白 + 把连续空白折叠成一个空格 + 去掉控制字符。
     *
     * <p>为什么不直接拒绝含换行的原因：用户从别处粘贴一段说明是正常行为，
     * 而换行会进入审批摘要（摘要必须单行，否则 SSE 帧会被拆坏）。
     * 折叠比拒绝更友好，且不改变语义。
     */
    public static String normalizeReason(String reason) {
        if (reason == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(reason.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < reason.length(); ) {
            int codePoint = reason.codePointAt(i);
            i += Character.charCount(codePoint);

            if (Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029) {
                if (cleaned.length() > 0 && !lastWasSpace) {
                    cleaned.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            if (codePoint == ' ' || codePoint == '\u3000') {
                if (cleaned.length() > 0 && !lastWasSpace) {
                    cleaned.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            cleaned.appendCodePoint(codePoint);
            lastWasSpace = false;
        }
        return cleaned.toString().trim();
    }
}
