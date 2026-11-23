package com.example.mkagent.tools;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.shop.aftersale.AfterSalePreview;
import com.example.mkagent.shop.aftersale.AfterSaleReceiptProgress;
import com.example.mkagent.shop.aftersale.AfterSaleRefund;
import com.example.mkagent.shop.aftersale.AfterSaleReturn;
import com.example.mkagent.shop.aftersale.AfterSaleService;
import com.example.mkagent.shop.aftersale.ReturnSubject;
import com.example.mkagent.shop.api.AfterSaleViews;
import com.example.mkagent.shop.api.ShopViews;
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
        Map<Long, AfterSaleRefund> refunds = afterSaleService.refundsOf(existingReturns);
        for (AfterSaleReturn existing : existingReturns) {
            returns.add(toReturnRow(existing, progress.get(existing.id()), refunds.get(existing.id())));
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
                    null,
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

                    stageCode / stageLabel / stageHint 是**给人看的阶段**（服务端算好的，
                    与买家页面同一套措辞）：WAITING_DECISION 待客服处理、REJECTED 已驳回
                    （终止，没有后续收货或退款）、WAITING_RECEIPT 待收货、
                    PARTIAL_RECEIPT 部分收货、RECEIVED_NOT_REFUNDED 已全部收货但尚未登记模拟退款、
                    REFUNDED 已登记模拟退款。回答"退到哪一步"时优先引用 stageLabel + hint，
                    不要自己另起一套说法。

                    收货口径：receivedQty 是**累计实收**，损坏件也计入；
                    qualifiedQty 只影响"能不能再卖"，不影响"是否收满"。

                    退款字段（只读）：refundNo（模拟退款单号）、refundAmountCents /
                    refundAmountText（金额，分 / ¥ 文案）、refundLabel（固定为"模拟退款"）。
                    它们为 null 表示这单还没有退款记录——此时应回答"尚未登记模拟退款"，
                    不能说"已退款"，也不能说"正在退款中"。

                    口径必须说对：这是**模拟退款**，只代表业务上生成了一条退款记录，
                    没有真实资金流、没有到账、没有支付渠道结果可查。
                    不要说"钱已退回"或"已到账"；也不要因为收了货就回答"已退款"。
                    不要推断物流轨迹或到账时间，那些数据不在本工具里。

                    也不要去解释"真实退款会走哪个支付渠道、要几个工作日到账"——
                    同样没有这些数据，说了就是编。用户追问到账时间时，
                    只回答"这笔是模拟退款，没有真实资金流，查不到到账时间"。
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
        Map<Long, AfterSaleRefund> refunds = afterSaleService.refundsOf(values);
        for (AfterSaleReturn value : values) {
            if (!filter.isEmpty() && !filter.equals(value.orderNo())) {
                continue;
            }
            rows.add(toReturnRow(value, progress.get(value.id()), refunds.get(value.id())));
        }

        if (rows.isEmpty()) {
            return ToolResponseEnvelope.okList(List.of());
        }
        return ToolResponseEnvelope.okList(rows);
    }

    /**
     * 退货申请 → 工具行。
     *
     * <p>收货进度、阶段与退款对模型都是**只读事实**：{@code receiptProgress} 为空表示这单还没批准，
     * 模型应当据此说明"还在等客服处理"，而不是编造"已入库"；
     * {@code refundNo} 为空表示还没退款，不能因为收满了就推断"已退款"。
     *
     * <p>{@code stage} 直接复用 {@link AfterSaleViews#stage}——页面与 Agent
     * 用同一个函数算阶段，措辞不会漂移。
     */
    private static ReturnRow toReturnRow(
            AfterSaleReturn value,
            AfterSaleReceiptProgress progress,
            AfterSaleRefund refund
    ) {
        AfterSaleViews.StageVO stage = AfterSaleViews.stage(value, progress, refund);
        return new ReturnRow(
                value.returnNo(),
                value.orderNo(),
                value.productName(),
                value.quantity(),
                progress == null ? null : progress.approvedQty(),
                value.status().name(),
                value.status().label(),
                value.source().name(),
                value.createdAt() == null ? null : value.createdAt().toString(),
                stage.code(),
                stage.label(),
                stage.hint(),
                progress == null ? null : progress.receivedQty(),
                progress == null ? null : progress.remainingQty(),
                progress == null ? null : progress.code(),
                progress == null ? null : progress.label(),
                refund == null ? null : refund.refundNo(),
                refund == null ? null : refund.amountCents(),
                refund == null ? null : ShopViews.money(refund.amountCents()),
                refund == null ? null : AfterSaleViews.LABEL_SIMULATED_REFUND);
    }

    @Tool(
            name = "preview_return",
            description = """
                    预览一次退货申请：退几件、退多少钱、当前额度是多少、能不能提交。
                    **不写任何数据、不占额度，也不会生成确认卡**。

                    调用它之前必须先确认：订单号、退货数量、退货原因三项都已明确。
                    重要：如果用户已经明确要提交退货且本工具返回 eligible=true，
                    你必须在下一步**立即调用 submit_return** 触发人工确认卡，
                    不得只回复“正在为您提交”或“请确认”就结束本轮！

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
            String normalizedReason = com.example.mkagent.shop.aftersale.AfterSaleEligibility
                    .normalizeReason(reason);
            return ToolResponseEnvelope.okSingle(new PreviewRow(
                    preview.orderNo(),
                    preview.productName(),
                    preview.requestedQty(),
                    normalizedReason,
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
            Integer approvedQty,
            String status,
            String statusLabel,
            String source,
            String createdAt,
            String stageCode,
            String stageLabel,
            String stageHint,
            Integer receivedQty,
            Integer remainingQty,
            String receiptProgress,
            String receiptProgressLabel,
            String refundNo,
            Long refundAmountCents,
            String refundAmountText,
            String refundLabel
    ) {
    }

    /** 资格 / 预览结果。 */
    record PreviewRow(
            String orderNo,
            String productName,
            int requestedQty,
            String reason,
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
