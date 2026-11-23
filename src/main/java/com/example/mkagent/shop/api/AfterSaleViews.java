package com.example.mkagent.shop.api;

import com.example.mkagent.shop.aftersale.AfterSaleDecision;
import com.example.mkagent.shop.aftersale.AfterSaleEligibility;
import com.example.mkagent.shop.aftersale.AfterSaleEvent;
import com.example.mkagent.shop.aftersale.AfterSalePreview;
import com.example.mkagent.shop.aftersale.AfterSaleQuota;
import com.example.mkagent.shop.aftersale.AfterSaleReceipt;
import com.example.mkagent.shop.aftersale.AfterSaleReceiptProgress;
import com.example.mkagent.shop.aftersale.AfterSaleRefund;
import com.example.mkagent.shop.aftersale.AfterSaleReturn;
import com.example.mkagent.shop.aftersale.AfterSaleService;
import com.example.mkagent.shop.aftersale.AfterSaleStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 售后接口的响应对象与映射。
 *
 * <p>与 {@link ShopViews} 同一条纪律：领域对象不直接下发，
 * 金额文案、状态中文、额度三个数都在服务端算一次。
 *
 * <p>时间字段用 {@link LocalDateTime}，与 A0 的序列化约定一致
 * （前端 {@code formatDateTime} 直接吃 {@code 2026-11-22T10:00:00}）。
 */
public final class AfterSaleViews {

    /**
     * 模拟退款的固定中文标签（页面、接口、工具共用同一句口径）。
     *
     * <p>刻意不叫"退款"：本轮不接支付平台，没有任何资金到账，
     * 少一个字就会让买家以为钱已经退回来了。
     */
    public static final String LABEL_SIMULATED_REFUND = "模拟退款";

    private AfterSaleViews() {
    }

    /** 额度三个数：已购 / 已占用 / 剩余。 */
    public record QuotaVO(int purchased, int occupied, int remaining) {
    }

    /**
     * 已签收订单（可申请退货的候选）。
     *
     * @param expired    是否已超过退货窗口（过期订单仍然返回，页面要能解释原因）
     * @param returnable 现在能不能退（未过期且还有额度）
     */
    public record SignedOrderVO(
            String orderNo,
            String sku,
            String productName,
            long unitPriceCents,
            String unitPriceText,
            QuotaVO quota,
            LocalDateTime signedAt,
            LocalDateTime deadline,
            boolean expired,
            boolean returnable
    ) {
    }

    /** 售后事件（详情页的处理记录）。 */
    public record EventVO(
            String type,
            String label,
            String actorId,
            String note,
            LocalDateTime createdAt
    ) {
    }

    /**
     * 客服决策（一单一决）。
     *
     * <p>买家在自己的详情里也看得到：状态变成「已批准 / 已驳回」之后，
     * 他需要知道"谁在什么时候、为什么"。这里只下发决策事实，不下发内部备注。
     */
    public record DecisionVO(
            String decision,
            String label,
            String reason,
            String decidedBy,
            LocalDateTime decidedAt
    ) {
    }

    /**
     * 收货进度（B2）。
     *
     * <p>进度是**算出来的**：批准数量来自退货单，累计实收来自收货记录。
     * 它不是一个独立状态，所以退货单的 {@code status} 仍然只有三种值。
     */
    public record ProgressVO(
            String code,
            String label,
            int approvedQty,
            int receivedQty,
            int remainingQty
    ) {
    }

    /**
     * 一批收货 / 验货记录。
     *
     * @param movement 这一批产生的库存流水；整批损坏（合格 0 件）时为 {@code null}
     */
    public record ReceiptVO(
            String receiptNo,
            int receivedQty,
            int qualifiedQty,
            int damagedQty,
            String note,
            String operatorId,
            LocalDateTime createdAt,
            ShopViews.MovementVO movement
    ) {
    }

    /**
     * 模拟退款（B3）。
     *
     * <p>{@code label} 固定为「模拟退款」：这是**业务上的退款记录**，
     * 不是资金到账。页面与工具都必须按这个口径说，不能对买家说"钱已退回"。
     *
     * @param amountCents 退款金额（分）= 原订单成交单价 × 累计实收数量
     */
    public record RefundVO(
            String refundNo,
            long amountCents,
            String amountText,
            String operatorId,
            LocalDateTime createdAt,
            String label
    ) {
    }

    /**
     * 买家能看懂的"处理到哪一步"（C1）。
     *
     * <p>它是**派生值**：由 {@code status} + 收货进度 + 退款记录算出来，
     * 不落库、不新增状态、不改 {@code shop_aftersale_return.status}。
     * 之所以放在服务端而不是让页面各算各的，是为了让**页面与售后 Agent 说同一句话**
     * ——否则同一个事实会出现"待入库""待退款""已收满"三种叫法。
     *
     * <p>{@code hint} 只描述"接下来等什么"，不预测时间、不承诺到账。
     */
    public record StageVO(
            String code,
            String label,
            String hint
    ) {
    }

    /**
     * 退货申请。
     *
     * @param buyerId  买家身份（客服视角需要它定位是谁的单；对买家自己无隐私问题）
     * @param stage    处理阶段（派生值，见 {@link StageVO}）
     * @param progress 收货进度；非「已批准」状态为 {@code null}
     * @param receipts 收货记录；列表接口为 {@code null}（未加载），详情接口为数组
     * @param refund   模拟退款；没退过款时为 {@code null}
     * @param events   事件流；列表接口为 {@code null}（未加载），详情接口为数组
     * @param decision 决策记录；列表接口与未决策时为 {@code null}
     */
    public record ReturnVO(
            String returnNo,
            String orderNo,
            String buyerId,
            String status,
            String statusLabel,
            String source,
            String sourceLabel,
            String sku,
            String productName,
            long unitPriceCents,
            String unitPriceText,
            int quantity,
            long refundCents,
            String refundText,
            String reason,
            LocalDateTime createdAt,
            StageVO stage,
            ProgressVO progress,
            List<ReceiptVO> receipts,
            RefundVO refund,
            DecisionVO decision,
            List<EventVO> events
    ) {
    }

    /**
     * 预览结果。
     *
     * @param eligible 当前是否可以提交（预览**不是**资格凭证，提交时会重新判定）
     */
    public record PreviewVO(
            String orderNo,
            String sku,
            String productName,
            long unitPriceCents,
            String unitPriceText,
            int requestedQty,
            long refundCents,
            String refundText,
            QuotaVO quota,
            boolean eligible,
            String code,
            String message
    ) {
    }

    public static QuotaVO quota(AfterSaleQuota quota) {
        return new QuotaVO(quota.purchased(), quota.occupied(), quota.remaining());
    }

    public static SignedOrderVO signedOrder(AfterSaleService.SignedOrder order) {
        return new SignedOrderVO(
                order.orderNo(),
                order.sku(),
                order.productName(),
                order.unitPriceCents(),
                ShopViews.money(order.unitPriceCents()),
                quota(order.quota()),
                order.signedAt(),
                order.deadline(),
                order.expired(),
                order.returnable());
    }

    public static EventVO event(AfterSaleEvent event) {
        return new EventVO(
                event.type(),
                AfterSaleEvent.labelOf(event.type()),
                event.actorId(),
                event.note(),
                event.createdAt());
    }

    public static DecisionVO decision(AfterSaleDecision decision) {
        return new DecisionVO(
                decision.decision().name(),
                decision.label(),
                decision.reason(),
                decision.decidedBy(),
                decision.decidedAt());
    }

    /**
     * 列表用：不带事件流与收货记录（未查询，不等于"没有"）。
     *
     * <p>进度会带上：列表需要显示"已批准 / 部分收货 / 全部收货"，
     * 它是**一次批量查询**算出来的（见 {@code AfterSaleService#progressOf}），
     * 不是每行一次查询。退款同理（{@code refundsOf}）：列表要据此决定
     * 显示「模拟退款」按钮还是"已退款"标签。
     */
    public static ReturnVO summary(
            AfterSaleReturn value,
            AfterSaleReceiptProgress progress,
            AfterSaleRefund refund
    ) {
        return render(value, null, null, progress, null, refund);
    }

    /** 详情用：退货单 + 事件 + 决策 + 进度 + 每批收货（含入库流水）+ 模拟退款。 */
    public static ReturnVO detail(AfterSaleService.ReturnDetail detail) {
        List<ReceiptVO> receipts = detail.receipts().stream()
                .map(AfterSaleViews::receipt)
                .toList();
        AfterSaleDecision decision = detail.decision();
        return render(
                detail.value(),
                detail.events().stream().map(AfterSaleViews::event).toList(),
                decision == null ? null : decision(decision),
                detail.progress(),
                receipts,
                detail.refund());
    }

    public static ProgressVO progress(AfterSaleReceiptProgress progress) {
        return new ProgressVO(
                progress.code(),
                progress.label(),
                progress.approvedQty(),
                progress.receivedQty(),
                progress.remainingQty());
    }

    public static ReceiptVO receipt(AfterSaleService.ReceiptWithMovement value) {
        AfterSaleReceipt receipt = value.receipt();
        return new ReceiptVO(
                receipt.receiptNo(),
                receipt.receivedQty(),
                receipt.qualifiedQty(),
                receipt.damagedQty(),
                receipt.note(),
                receipt.operatorId(),
                receipt.createdAt(),
                value.movement() == null ? null : ShopViews.movement(value.movement()));
    }

    /**
     * 模拟退款映射。
     *
     * <p>金额文案在这里算一次：{@code ¥598.00}。前端不再自己除 100，
     * 避免"列表一个数、详情另一个数"。
     */
    public static RefundVO refund(AfterSaleRefund value) {
        return new RefundVO(
                value.refundNo(),
                value.amountCents(),
                ShopViews.money(value.amountCents()),
                value.operatorId(),
                value.createdAt(),
                LABEL_SIMULATED_REFUND);
    }

    private static ReturnVO render(
            AfterSaleReturn value,
            List<EventVO> events,
            DecisionVO decision,
            AfterSaleReceiptProgress progress,
            List<ReceiptVO> receipts,
            AfterSaleRefund refund
    ) {
        return new ReturnVO(
                value.returnNo(),
                value.orderNo(),
                value.buyerId(),
                value.status().name(),
                value.status().label(),
                value.source().name(),
                value.source().label(),
                value.sku(),
                value.productName(),
                value.unitPriceCents(),
                ShopViews.money(value.unitPriceCents()),
                value.quantity(),
                value.refundCents(),
                ShopViews.money(value.refundCents()),
                value.reason(),
                value.createdAt(),
                stage(value, progress, refund),
                progress == null ? null : progress(progress),
                receipts,
                refund == null ? null : refund(refund),
                decision,
                events);
    }

    /**
     * 阶段派生：客服结论 → 仓库进度 → 退款记录，三件事各有各的位置。
     *
     * <p>六个阶段覆盖买家能遇到的全部分支：
     * <ol>
     *   <li>待客服处理（还没结论）；</li>
     *   <li>已驳回（终止分支，不展示"待收货 / 待退款"）；</li>
     *   <li>待收货（已批准、一件没收到）；</li>
     *   <li>部分收货（收到一部分，还剩几件）；</li>
     *   <li>已全部收货、尚未登记模拟退款（**不是**"已退款"）；</li>
     *   <li>已登记模拟退款（有单号才算）。</li>
     * </ol>
     *
     * <p>损坏件计入累计实收：退款退的是"寄回来几件"，合格与否只影响能不能再卖。
     */
    public static StageVO stage(
            AfterSaleReturn value,
            AfterSaleReceiptProgress progress,
            AfterSaleRefund refund
    ) {
        return switch (value.status()) {
            case SUBMITTED -> new StageVO(
                    "WAITING_DECISION",
                    "待客服处理",
                    "客服还没有给出结论；批准或驳回之后这里会显示下一步。");
            case REJECTED -> new StageVO(
                    "REJECTED",
                    "已驳回",
                    "处理已终止：不会再收货，也不会有模拟退款。驳回原因见下方处理结果。");
            case APPROVED -> approvedStage(progress, refund);
        };
    }

    private static StageVO approvedStage(
            AfterSaleReceiptProgress progress,
            AfterSaleRefund refund
    ) {
        // 已批准必然有进度；这里只做防御，不把它当成一种正常分支。
        String code = progress == null ? AfterSaleReceiptProgress.CODE_NONE : progress.code();
        int approved = progress == null ? 0 : progress.approvedQty();
        int received = progress == null ? 0 : progress.receivedQty();
        int remaining = progress == null ? 0 : progress.remainingQty();

        return switch (code) {
            case AfterSaleReceiptProgress.CODE_NONE -> new StageVO(
                    "WAITING_RECEIPT",
                    "待收货",
                    "客服已批准 " + approved + " 件，仓库还没有收到任何一件。");
            case AfterSaleReceiptProgress.CODE_PARTIAL -> new StageVO(
                    "PARTIAL_RECEIPT",
                    "部分收货",
                    "已收 " + received + " 件，还差 " + remaining
                            + " 件；损坏件也计入实收。");
            default -> refund != null
                    ? new StageVO(
                            "REFUNDED",
                            "已登记模拟退款",
                            "已登记一笔模拟退款（" + refund.refundNo()
                                    + "）；这是业务记录，没有真实资金流动。")
                    : new StageVO(
                            "RECEIVED_NOT_REFUNDED",
                            "已全部收货，尚未登记模拟退款",
                            "货已收齐；客服登记模拟退款后，这里会显示退款单号与金额。");
        };
    }

    public static PreviewVO preview(AfterSalePreview preview) {
        /*
         * 金额溢出时不下发数字：refundCents 是 0，但文案必须是「—」而不是「¥0.00」。
         * 「¥0.00」会被理解成"这单不值钱"，而真实情况是"算不出来，请减少数量"。
         */
        String refundText = AfterSaleEligibility.CODE_REFUND_AMOUNT_OUT_OF_RANGE
                .equals(preview.code())
                ? "—"
                : ShopViews.money(preview.refundCents());

        return new PreviewVO(
                preview.orderNo(),
                preview.sku(),
                preview.productName(),
                preview.unitPriceCents(),
                ShopViews.money(preview.unitPriceCents()),
                preview.requestedQty(),
                preview.refundCents(),
                refundText,
                quota(preview.quota()),
                preview.eligible(),
                preview.code(),
                preview.message());
    }
}
