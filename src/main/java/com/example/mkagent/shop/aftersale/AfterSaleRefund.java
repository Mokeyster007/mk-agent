package com.example.mkagent.shop.aftersale;

import java.time.LocalDateTime;

/**
 * 模拟退款记录（B3）。
 *
 * <h2>"模拟"两个字的含义</h2>
 *
 * 这张表记录的是**业务上的一次退款决定**：谁、在什么时候、为哪张退货单、
 * 退多少钱。它**不接支付平台**：没有资金流水号、没有回调、没有对账，
 * 也不存在"到账"这件事。页面与工具一律显示"模拟退款"，
 * 不会对买家说"钱已退回"。
 *
 * <h2>一张退货单最多一笔</h2>
 *
 * 由 {@code uk(return_id)} 保证，而不是靠调用方自觉：
 * 重复点击、并发点击都只可能落一条，第二次由数据库直接拒绝。
 *
 * <h2>金额怎么算</h2>
 *
 * {@code 原订单成交单价 × 累计实收数量}（整数分）。
 * 用累计**实收**而不是合格件数：退款退的是"买家寄回来的件数"，
 * 损坏件同样要退钱；合格与否只影响"能不能再卖"（那是 B2 的库存回补）。
 *
 * @param id          自增主键
 * @param refundNo    对外模拟退款单号（{@code RF + yyyyMMddHHmmss + 4 位十六进制}）
 * @param returnId    所属退货单 id
 * @param amountCents 退款金额（分）；正数，服务端算，不接受客户端传入
 * @param operatorId  操作人（来自服务端身份上下文）
 * @param createdAt   退款时间（服务端时钟）
 */
public record AfterSaleRefund(
        long id,
        String refundNo,
        long returnId,
        long amountCents,
        String operatorId,
        LocalDateTime createdAt
) {
}
