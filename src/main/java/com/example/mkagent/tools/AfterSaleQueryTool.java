package com.example.mkagent.tools;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.shop.aftersale.AfterSalePreview;
import com.example.mkagent.shop.aftersale.AfterSaleReceiptProgress;
import com.example.mkagent.shop.aftersale.AfterSaleReturn;
import com.example.mkagent.shop.aftersale.AfterSaleService;
import com.example.mkagent.shop.aftersale.ReturnSubject;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 售后只读工具（{@code AgentType.AFTER_SALES}）。
 *
 * <h2>身份纪律</h2>
 *
 * 每个方法的第一行都是 {@link AgentRunContextHolder#requireUserId()}：
 * 买家身份由入口冻结进运行上下文，**工具签名里没有 buyerId**。
 * 模型无法通过参数查别人的订单——它连这个字段都传不进来。
 *
 * <h2>为什么只读工具这么多</h2>
 *
 * 售后对话的真实难点不是"建单"，而是"问清楚是哪一单、退几件、为什么不能退"。
 * 这五件事各自需要一个工具：
 * <ul>
 *   <li>{@code list_my_signed_orders}：用户说"我上周买的键盘"时先找到订单；</li>
 *   <li>{@code get_my_order_detail}：确认订单状态、签收时间、已有申请；</li>
 *   <li>{@code check_return_eligibility}：只问"还能不能退"，不需要理由；</li>
 *   <li>{@code list_my_return_requests}：回答"我上次那单怎么样了"，也用于防重复提交；</li>
 *   <li>{@code preview_return}：给出退款金额与完整预览，供确认卡之前核对。</li>
 * </ul>
 *
 * <p>所有返回值都走 {@link ToolResponseEnvelope}：模型只要看 {@code ok}
 * 就知道成功与否，不用猜哪句话是错误信息。
 */
@Component
public class AfterSaleQueryTool {

    /** 单次列表最多返回多少条（与页面一致，避免一次拉全库）。 */
    private static final int MAX_LIST = 20;

    private final AfterSaleService afterSaleService;

    public AfterSaleQueryTool(AfterSaleService afterSaleService) {
        this.afterSaleService = afterSaleService;
    }

    @Tool(
            name = "list_my_signed_orders",
            description = """
                    列出**当前买家本人**的已签收订单，并给出每单的可退额度。

                    用途：用户说"我要退上周买的键盘"但没给订单号时，先调用它找到订单。
                    返回字段：orderNo（订单号）、productName、sku、purchasedQty（已购）、
                    occupiedQty（已占用）、remainingQty（剩余可退）、signedAt（签收时间）、
                    deadline（退货截止）、expired（是否已超窗口）、returnable（现在能不能退）。

                    注意：只返回已签收订单。未签收的订单不能申请退货，
                    不要用本工具的结果去推测未签收订单的情况。
                    """
    )
    public String listMySignedOrders(
            @ToolParam(description = "最多返回几条，1~20，默认 10", required = false)
            Integer limit
    ) {
        String userId = AgentRunContextHolder.requireUserId();

        int size = limit == null || limit < 1 ? 10 : Math.min(limit, MAX_LIST);

        List<Object> rows = new ArrayList<>();
        List<AfterSaleService.SignedOrder> orders = afterSaleService.signedOrders(userId);
        for (AfterSaleService.SignedOrder order : orders) {
            if (rows.size() >= size) {
                break;
            }
            rows.add(new SignedOrderRow(
                    order.orderNo(),
                    order.productName(),
                    order.sku(),
                    order.quota().purchased(),
                    order.quota().occupied(),
                    order.quota().remaining(),
                    order.signedAt() == null ? null : order.signedAt().toString(),
                    order.deadline() == null ? null : order.deadline().toString(),
                    order.expired(),
                    order.returnable()));
        }

        if (rows.isEmpty()) {
            return ToolResponseEnvelope.error(
                    "当前买家没有任何已签收订单，因此暂时无法申请退货");
        }
        return ToolResponseEnvelope.okList(rows);
    }

    @Tool(
            name = "get_my_order_detail",
            description = """
                    查看**当前买家本人**某一笔订单的详情与退货资格相关事实。

                    返回：订单状态、签收时间、商品快照、成交单价、
                    已购 / 已占用 / 剩余可退数量、退货窗口天数与截止时间，
                    以及该订单已有的退货申请列表（含已批准单的收货进度）。

                    订单号必须来自 list_my_signed_orders 或用户明确提供的订单号；
                    不要编造订单号。
                    """
    )
    public String getMyOrderDetail(
            @ToolParam(description = "订单号，例如 SO20261122101500-1A2B")
            String orderNo
    ) {
        String userId = AgentRunContextHolder.requireUserId();

        Optional<ReturnSubject> subject = afterSaleService.subject(userId, orderNo);
        if (subject.isEmpty()) {
            return ToolResponseEnvelope.error("订单不存在或不属于当前买家：" + orderNo);
        }

        ReturnSubject value = subject.get();
        int windowDays = afterSaleService.returnWindowDays();

        List<Object> returns = new ArrayList<>();
        List<AfterSaleReturn> existingReturns = afterSaleService.returnsOfOrderItem(value.orderItemId());
        Map<Long, AfterSaleReceiptProgress> progress = afterSaleService.progressOf(existingReturns);
        for (AfterSaleReturn existing : existingReturns) {
            returns.add(toReturnRow(existing, progress.get(existing.id())));
        }

        return ToolResponseEnvelope.okSingle(new OrderDetailRow(
                value.orderNo(),
                value.status().name(),
                value.status().label(),
                value.signedAt() == null ? null : value.signedAt().toString(),
                value.sku(),
                value.productName(),
                value.unitPriceCents(),
                value.purchasedQty(),
                value.occupiedQty(),
                value.quota().remaining(),
                windowDays,
                value.signedAt() == null ? null : value.signedAt().plusDays(windowDays).toString(),
                returns));
    }

    @Tool(
            name = "check_return_eligibility",
            description = """
                    检查某个订单行在**指定数量**下是否还能申请退货，不写任何数据。

                    返回 eligible（是否可以提交）、code/message（不能提交时的原因）、
                    quota（已购 / 已占用 / 剩余）、以及按成交单价估算的退款金额。

                    这只回答"现在能不能退"，**不代表已经占住额度**：
                    真正提交时会在事务内重新校验一次。
                    """
    )
    public String checkReturnEligibility(
            @ToolParam(description = "订单号") String orderNo,
            @ToolParam(description = "想退几件，至少 1 件") Integer quantity
    ) {
        String userId = AgentRunContextHolder.requireUserId();

        try {
            AfterSalePreview preview = afterSaleService.preview(userId, orderNo, quantity, "资格检查");
            return ToolResponseEnvelope.okSingle(new PreviewRow(
                    preview.orderNo(),
                    preview.productName(),
                    preview.requestedQty(),
                    preview.unitPriceCents(),
                    preview.refundCents(),
                    preview.quota().purchased(),
                    preview.quota().occupied(),
                    preview.quota().remaining(),
                    preview.eligible(),
                    preview.code(),
                    preview.message()));
        } catch (com.example.mkagent.exception.BusinessException e) {
            return ToolResponseEnvelope.error(e.getMessage());
        }
    }

    @Tool(
            name = "list_my_return_requests",
            description = """
                    列出**当前买家本人**已提交的退货申请（可按订单号过滤）。

                    用途：回答"我上次那单退得怎么样了"，以及**在提交前确认是否已经退过**，
                    避免同一订单行重复申请。

                    状态含义：SUBMITTED 等客服处理；APPROVED 客服已同意退（等仓库收货）；
                    REJECTED 已驳回。已批准的单会带收货进度字段：
                    receivedQty（仓库累计实收）、remainingQty（还没收到的数量）、
                    receiptProgress / receiptProgressLabel（NONE/PARTIAL/COMPLETE，
                    未收货 / 部分收货 / 全部收货）；未批准时这些字段为 null。

                    本轮没有退款：不要回答"已退款"。
                    """
    )
    public String listMyReturnRequests(
            @ToolParam(description = "只看某个订单的申请；不传表示全部", required = false)
            String orderNo
    ) {
        String userId = AgentRunContextHolder.requireUserId();

        String filter = orderNo == null ? "" : orderNo.trim();

        List<Object> rows = new ArrayList<>();
        List<AfterSaleReturn> values = afterSaleService.myReturns(userId);
        Map<Long, AfterSaleReceiptProgress> progress = afterSaleService.progressOf(values);
        for (AfterSaleReturn value : values) {
            if (!filter.isEmpty() && !filter.equals(value.orderNo())) {
                continue;
            }
            rows.add(toReturnRow(value, progress.get(value.id())));
        }

        if (rows.isEmpty()) {
            return ToolResponseEnvelope.okList(List.of());
        }
        return ToolResponseEnvelope.okList(rows);
    }

    /**
     * 退货申请 → 工具行。
     *
     * <p>收货进度对模型是**只读事实**：{@code receiptProgress} 为空表示这单还没批准，
     * 模型应当据此说明"还在等客服处理"，而不是编造"已入库"。
     */
    private static ReturnRow toReturnRow(AfterSaleReturn value, AfterSaleReceiptProgress progress) {
        return new ReturnRow(
                value.returnNo(),
                value.orderNo(),
                value.productName(),
                value.quantity(),
                value.status().name(),
                value.status().label(),
                value.source().name(),
                value.createdAt() == null ? null : value.createdAt().toString(),
                progress == null ? null : progress.receivedQty(),
                progress == null ? null : progress.remainingQty(),
                progress == null ? null : progress.code(),
                progress == null ? null : progress.label());
    }

    @Tool(
            name = "preview_return",
            description = """
                    预览一次退货申请：退几件、退多少钱、当前额度是多少、能不能提交。
                    **不写任何数据、不占额度**。

                    调用它之前必须先确认：订单号、退货数量、退货原因三项都已明确。
                    返回结果用于向用户复述"将要提交什么"，随后调用 submit_return 走人工确认。

                    预览不是资格凭证：从预览到用户点确认之间，额度可能被另一笔申请用掉，
                    因此 submit_return 执行时会在事务内重新校验。
                    """
    )
    public String previewReturn(
            @ToolParam(description = "订单号") String orderNo,
            @ToolParam(description = "退货数量，至少 1 件") Integer quantity,
            @ToolParam(description = "退货原因，1~200 字") String reason
    ) {
        String userId = AgentRunContextHolder.requireUserId();

        try {
            AfterSalePreview preview = afterSaleService.preview(userId, orderNo, quantity, reason);
            return ToolResponseEnvelope.okSingle(new PreviewRow(
                    preview.orderNo(),
                    preview.productName(),
                    preview.requestedQty(),
                    preview.unitPriceCents(),
                    preview.refundCents(),
                    preview.quota().purchased(),
                    preview.quota().occupied(),
                    preview.quota().remaining(),
                    preview.eligible(),
                    preview.code(),
                    preview.message()));
        } catch (com.example.mkagent.exception.BusinessException e) {
            return ToolResponseEnvelope.error(e.getMessage());
        }
    }

    /** 已签收订单行。 */
    record SignedOrderRow(
            String orderNo,
            String productName,
            String sku,
            int purchasedQty,
            int occupiedQty,
            int remainingQty,
            String signedAt,
            String deadline,
            boolean expired,
            boolean returnable
    ) {
    }

    /** 订单详情（含已有申请）。 */
    record OrderDetailRow(
            String orderNo,
            String status,
            String statusLabel,
            String signedAt,
            String sku,
            String productName,
            long unitPriceCents,
            int purchasedQty,
            int occupiedQty,
            int remainingQty,
            int returnWindowDays,
            String returnDeadline,
            List<Object> existingReturns
    ) {
    }

    /** 已有退货申请。 */
    record ReturnRow(
            String returnNo,
            String orderNo,
            String productName,
            int quantity,
            String status,
            String statusLabel,
            String source,
            String createdAt,
            Integer receivedQty,
            Integer remainingQty,
            String receiptProgress,
            String receiptProgressLabel
    ) {
    }

    /** 资格 / 预览结果。 */
    record PreviewRow(
            String orderNo,
            String productName,
            int requestedQty,
            long unitPriceCents,
            long refundCents,
            int purchasedQty,
            int occupiedQty,
            int remainingQty,
            boolean eligible,
            String code,
            String message
    ) {
    }
}
