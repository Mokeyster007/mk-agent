package com.example.mkagent.shop.aftersale;

import com.example.mkagent.approval.ApprovalSummary;
import com.example.mkagent.approval.ApprovalSummaryBuilder;
import com.example.mkagent.approval.ApprovalSummaryContributor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 售后确认卡的摘要（{@code submit_return}）。
 *
 * <h2>确认卡必须固定什么</h2>
 *
 * 订单号、订单行（商品）、退货数量、退款金额、退货原因。
 * 这五项就是"用户点批准时同意的东西"，任何一项没显示，
 * 用户都可能批准了一件自己没看清的事。
 *
 * <h2>数据从哪来</h2>
 *
 * 数量与原因来自**参数快照**（模型这一轮真正要提交的值），
 * 商品名与金额来自一次**只读预览**（与提交时同一套判定）。
 * 预览失败（订单不属于本人 / 已不可退）时降级为只显示参数，
 * 不阻断暂停——真正的拦截在执行时。
 *
 * <h2>为什么不在前端拼</h2>
 *
 * 前端如果自己拿订单号去查一次，看到的是"现在"的数据，
 * 而确认卡对应的是"快照那一刻"的参数。两者不一致时，
 * 用户批准的和系统执行的就会是两件事。
 */
@Component
public class AfterSaleApprovalSummaryContributor implements ApprovalSummaryContributor {

    /** 确认卡里单个值的最大长度（码点）。 */
    private static final int MAX_VALUE_LENGTH = 120;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AfterSaleService afterSaleService;

    public AfterSaleApprovalSummaryContributor(AfterSaleService afterSaleService) {
        this.afterSaleService = afterSaleService;
    }

    @Override
    public boolean supports(String toolName) {
        return "submit_return".equals(toolName);
    }

    @Override
    public Contribution contribute(String argsJson, String userId) {
        JsonNode args = parse(argsJson);
        String orderNo = text(args, "orderNo");
        Integer quantity = integer(args, "quantity");
        String reason = text(args, "reason");

        List<ApprovalSummary.Detail> details = new ArrayList<>();
        details.add(detail("订单号", orderNo));

        /*
         * 只读预览：补上商品名、退款金额与当前额度。
         * 失败就只显示参数——摘要不能因为查询失败而阻断暂停。
         */
        String productName = null;
        Long refundCents = null;
        Integer remaining = null;

        if (userId != null && !userId.isBlank() && orderNo != null && quantity != null) {
            try {
                AfterSalePreview preview = afterSaleService.preview(
                        userId, orderNo, quantity, reason);
                productName = preview.productName();
                refundCents = preview.refundCents();
                remaining = preview.quota().remaining();
            } catch (Exception e) {
                // 归属不符 / 订单不存在 / 不可退：摘要退化为参数回显。
            }
        }

        if (productName != null) {
            details.add(detail("商品", productName));
        }
        details.add(detail("退货数量", quantity == null ? "未指定" : quantity + " 件"));
        if (refundCents != null) {
            details.add(detail("预计退款", money(refundCents)));
        }
        details.add(detail("退货原因", reason));
        if (remaining != null) {
            details.add(detail("当前剩余可退", remaining + " 件"));
        }

        return new Contribution("提交退货申请", details);
    }

    private static ApprovalSummary.Detail detail(String label, String value) {
        return new ApprovalSummary.Detail(
                label,
                ApprovalSummaryBuilder.sanitize(value == null ? "未提供" : value, MAX_VALUE_LENGTH));
    }

    private static String money(long cents) {
        return "¥" + BigDecimal.valueOf(cents, 2).toPlainString();
    }

    private static JsonNode parse(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(argsJson);
        } catch (Exception e) {
            // 参数不是合法 JSON 属于异常情况；摘要退化成"未提供"。
            return null;
        }
    }

    private static String text(JsonNode args, String field) {
        if (args == null) {
            return null;
        }
        JsonNode node = args.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Integer integer(JsonNode args, String field) {
        if (args == null) {
            return null;
        }
        JsonNode node = args.get(field);
        return node == null || !node.canConvertToInt() ? null : node.asInt();
    }
}
