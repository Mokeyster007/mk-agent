package com.example.mkagent.shop.aftersale;

import java.time.LocalDateTime;

/**
 * 仓库收货 / 验货记录（B2）。
 *
 * <h2>一次收货 = 一条记录</h2>
 *
 * 一张已批准的退货单可以分多次收货，每一批一行。这里保留的是**这一批**的事实：
 * 实收几件、其中几件合格、几件损坏、谁在什么时候验的。
 *
 * <p>为什么不把 {@code received_qty} 直接加在退货单上：
 * 那样只能回答"一共收了几件"，回答不了"哪一批、谁验的、坏了几件"。
 * 进度由 {@link AfterSaleReceiptProgress} 从本表累加算出，
 * 退货单的状态仍然只表达客服结论（{@code APPROVED}）。
 *
 * @param receiptNo    对外收货单号（{@code RC + yyyyMMddHHmmss + 4 位十六进制}）
 * @param returnId     所属退货单 id
 * @param orderItemId  原订单行 id（与 A1 / B1 同一把锁的定位键）
 * @param receivedQty  本批实收数量（> 0）
 * @param qualifiedQty 本批合格数量（**只有它**增加可售库存）
 * @param damagedQty   本批损坏 / 不可售数量（只留记录，不入库）
 * @param note         验货备注（可空；与 {@code VARCHAR(200)} 对齐）
 * @param operatorId   操作人（来自服务端身份上下文，不来自请求体）
 * @param createdAt    收货时间（服务端时钟）
 */
public record AfterSaleReceipt(
        long id,
        String receiptNo,
        long returnId,
        long orderItemId,
        int receivedQty,
        int qualifiedQty,
        int damagedQty,
        String note,
        String operatorId,
        LocalDateTime createdAt
) {

    /**
     * 这一批是否产生库存增量。
     *
     * <p>整批损坏（合格 0 件）时**不写流水**：没有实物回到可售库存，
     * 写一条 +0 的流水只会让"库存流水条数"与"实际入库次数"对不上。
     */
    public boolean increasesStock() {
        return qualifiedQty > 0;
    }
}
