package com.example.mkagent.tools;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.aftersale.AfterSaleReturn;
import com.example.mkagent.shop.aftersale.AfterSaleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 售后写工具：{@code submit_return}（{@code AgentType.AFTER_SALES} 里唯一的写操作）。
 *
 * <h2>为什么它必须走人工确认</h2>
 *
 * 这是买家 Agent 唯一会**写业务数据**的动作：一旦执行就产生一张退货单并占用可退额度。
 * 模型不能替买家做这个决定，因此方法上标了 {@link RequiresApproval}：
 * 调用会被 {@code ToolCallAgent} 挂起，确认卡固定订单号 / 订单行 / 商品 / 数量 / 原因，
 * 买家点"批准"之后才真正执行；拒绝或取消**零写入**。
 *
 * <h2>为什么工具自己不做资格判定</h2>
 *
 * 判定只有一份，在 {@code AfterSaleEligibility} 里，由
 * {@code AfterSaleService#createForAgent} 在**事务内**重新跑一遍。
 * 如果这里再判一次，就会出现两套规则——而确认卡上的预览本来就可能是过期的，
 * 真正说了算的必须是执行那一刻的数据库事实。
 *
 * <h2>身份</h2>
 *
 * {@code buyerId} 只来自 {@link AgentRunContextHolder}，工具签名里没有这个参数。
 * 模型即使被用户说服"帮我查一下别人的订单"，也无从传起。
 *
 * <h2>失败怎么返回</h2>
 *
 * 业务失败（额度不足、超窗口、原因非法…）返回**错误信封**而不是抛异常：
 * 模型能看到"这次没做成"和具体原因，并据此向用户解释。
 * 框架的写工具幂等执行器会把这类结果连同占位行一起回滚，
 * 不会把一次失败缓存成"这次调用的答案"。
 */
@Component
public class AfterSaleWriteTool {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleWriteTool.class);

    private final AfterSaleService afterSaleService;

    public AfterSaleWriteTool(AfterSaleService afterSaleService) {
        this.afterSaleService = afterSaleService;
    }

    @Tool(
            name = "submit_return",
            description = """
                    为**当前买家本人**提交一张退货申请（写操作，必须经用户确认）。

                    执行前必须已经明确三项：订单号、退货数量、退货原因。
                    信息不全时先追问，不要猜测数量或原因。

                    提交成功后返回真实的退货单号（returnNo）与估算退款金额。
                    状态为「已提交」，表示等待客服处理——**不是**已批准，也**没有**退款发生。

                    如果返回失败，如实把原因告诉用户（例如超过退货窗口、额度不足），
                    不要改写原因后自动重试。
                    """
    )
    @RequiresApproval(reason = "提交退货申请会创建退货单并占用可退额度")
    public String submitReturn(
            @ToolParam(description = "订单号，必须来自 list_my_signed_orders 或用户明确提供")
            String orderNo,

            @ToolParam(description = "退货数量，至少 1 件，不能超过剩余可退额度")
            Integer quantity,

            @ToolParam(description = "退货原因，1~200 字，必填")
            String reason
    ) {
        // 失败关闭：放在 try 之外，不能被下面的业务 catch 吞掉。
        String userId = AgentRunContextHolder.requireUserId();

        if (orderNo == null || orderNo.isBlank()) {
            return ToolResponseEnvelope.error("请先确认要退哪一笔订单（缺少订单号）");
        }
        if (quantity == null || quantity < 1) {
            return ToolResponseEnvelope.error("请确认退货数量（至少 1 件）");
        }
        if (reason == null || reason.isBlank()) {
            return ToolResponseEnvelope.error("请填写退货原因");
        }

        try {
            AfterSaleReturn created = afterSaleService.createForAgent(
                    userId, orderNo.trim(), quantity, reason);

            log.info("售后 Agent 建单：returnNo={}, orderNo={}, qty={}",
                    created.returnNo(), created.orderNo(), created.quantity());

            return ToolResponseEnvelope.okSingle(new SubmitResult(
                    created.returnNo(),
                    created.orderNo(),
                    created.productName(),
                    created.quantity(),
                    created.unitPriceCents(),
                    created.refundCents(),
                    created.status().name(),
                    created.status().label(),
                    created.source().name()));
        } catch (BusinessException e) {
            /*
             * 业务拒绝：原样返回服务端的稳定原因码与中文说明。
             * 不重试、不换参数重试——那会让"额度不足"变成一次隐形的第二次申请。
             */
            return ToolResponseEnvelope.error(e.getMessage());
        }
    }

    /**
     * 建单结果（模型据此复述，页面据此展示）。
     *
     * @param returnNo 真实退货单号（来自数据库，不是模型生成的）
     * @param status   状态码（A1 固定 SUBMITTED）
     * @param statusLabel 状态中文
     */
    record SubmitResult(
            String returnNo,
            String orderNo,
            String productName,
            int quantity,
            long unitPriceCents,
            long refundCents,
            String status,
            String statusLabel,
            String source
    ) {
    }
}
