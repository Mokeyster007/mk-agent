package com.example.mkagent.shop.api;

import com.example.mkagent.shop.aftersale.AfterSaleEvent;
import com.example.mkagent.shop.aftersale.AfterSalePreview;
import com.example.mkagent.shop.aftersale.AfterSaleQuota;
import com.example.mkagent.shop.aftersale.AfterSaleReturn;
import com.example.mkagent.shop.aftersale.AfterSaleService;

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
     * 退货申请。
     *
     * @param events 事件流；列表接口为 {@code null}（未加载），详情接口为数组
     */
    public record ReturnVO(
            String returnNo,
            String orderNo,
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

    /** 列表用：不带事件流（未查询，不等于"没有事件"）。 */
    public static ReturnVO summary(AfterSaleReturn value) {
        return render(value, null);
    }

    public static ReturnVO detail(AfterSaleReturn value, List<AfterSaleEvent> events) {
        return render(value, events.stream().map(AfterSaleViews::event).toList());
    }

    private static ReturnVO render(AfterSaleReturn value, List<EventVO> events) {
        return new ReturnVO(
                value.returnNo(),
                value.orderNo(),
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
                events);
    }

    public static PreviewVO preview(AfterSalePreview preview) {
        return new PreviewVO(
                preview.orderNo(),
                preview.sku(),
                preview.productName(),
                preview.unitPriceCents(),
                ShopViews.money(preview.unitPriceCents()),
                preview.requestedQty(),
                preview.refundCents(),
                ShopViews.money(preview.refundCents()),
                quota(preview.quota()),
                preview.eligible(),
                preview.code(),
                preview.message());
    }
}
