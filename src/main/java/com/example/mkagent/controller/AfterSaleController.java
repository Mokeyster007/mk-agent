package com.example.mkagent.controller;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.ShopAccessGuard;
import com.example.mkagent.shop.aftersale.AfterSaleReturn;
import com.example.mkagent.shop.aftersale.AfterSaleService;
import com.example.mkagent.shop.api.AfterSaleViews;
import com.example.mkagent.shop.api.AfterSaleViews.PreviewVO;
import com.example.mkagent.shop.api.AfterSaleViews.ReturnVO;
import com.example.mkagent.shop.api.AfterSaleViews.SignedOrderVO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 买家侧售后接口：普通退货表单 + 我的售后。
 *
 * <h2>三条约束</h2>
 * <ol>
 *   <li><b>每个方法都先过 {@link ShopAccessGuard#requireBuyerIdentity()}</b>：
 *       关闭 demo 身份且没有可信身份时，这里一律 403
 *       {@code SHOP_IDENTITY_NOT_CONFIGURED}，不依赖 {@code X-User-Id} 放行；</li>
 *   <li><b>请求体里没有 buyerId / 价格 / 签收时间 / 已购数量</b>：
 *       字段不存在就传不进来。数量与原因由用户填，其余事实全部服务端读；</li>
 *   <li><b>别人的退货单返回 404 而不是 403</b>：不给出"这个单号确实存在"的探测信号。</li>
 * </ol>
 *
 * <p>本类不调用任何模型：普通售后闭环与 Agent 完全解耦，
 * 模型不可用时这些接口照常工作。
 */
@RestController
@RequestMapping("/shop/aftersale")
public class AfterSaleController {

    private final ShopAccessGuard accessGuard;

    private final AfterSaleService afterSaleService;

    public AfterSaleController(ShopAccessGuard accessGuard, AfterSaleService afterSaleService) {
        this.accessGuard = accessGuard;
        this.afterSaleService = afterSaleService;
    }

    /**
     * 表单提交请求体。
     *
     * <p>只有四个字段，这是刻意的：
     * 没有 {@code buyerId}（身份来自服务端）、没有 {@code unitPriceCents}
     * （价格来自订单行快照）、没有 {@code signedAt}（来自订单）、
     * 没有 {@code purchasedQty}（来自订单行）。
     *
     * @param clientRequestId 前端生成的提交标识；同一次提交重试时必须保持不变
     */
    public record SubmitReturnRequest(
            String orderNo,
            Integer quantity,
            String reason,
            String clientRequestId
    ) {
    }

    /** 预览请求体（只读，不写库）。 */
    public record PreviewRequest(String orderNo, Integer quantity, String reason) {
    }

    /** 我的已签收订单 + 每单当前额度。 */
    @GetMapping("/orders")
    public List<SignedOrderVO> signedOrders() {
        String buyerId = accessGuard.requireBuyerIdentity();

        return afterSaleService.signedOrders(buyerId).stream()
                .map(AfterSaleViews::signedOrder)
                .toList();
    }

    /**
     * 预览：退几件、退多少钱、为什么不能退。
     *
     * <p>用 POST 只是因为原因文本可能较长、放 query 不合适；
     * 它**不写任何数据**，也不占额度。
     */
    @PostMapping("/preview")
    public PreviewVO preview(@RequestBody(required = false) PreviewRequest request) {
        String buyerId = accessGuard.requireBuyerIdentity();

        if (request == null) {
            throw new BusinessException(400, "INVALID_REQUEST", "请求体不能为空");
        }

        return AfterSaleViews.preview(afterSaleService.preview(
                buyerId, request.orderNo(), request.quantity(), request.reason()));
    }

    /**
     * 提交退货申请（普通表单）。
     *
     * <p>返回 201：创建了一个退货申请资源。响应里的退货单号是**数据库里的真实单号**，
     * 页面直接展示它，不自己拼。
     */
    @PostMapping("/returns")
    @ResponseStatus(HttpStatus.CREATED)
    public ReturnVO submit(@RequestBody(required = false) SubmitReturnRequest request) {
        String buyerId = accessGuard.requireBuyerIdentity();

        if (request == null) {
            throw new BusinessException(400, "INVALID_REQUEST", "请求体不能为空");
        }

        AfterSaleReturn created = afterSaleService.submitForm(
                buyerId,
                request.orderNo(),
                request.quantity(),
                request.reason(),
                request.clientRequestId());

        return AfterSaleViews.detail(created, afterSaleService.events(created.id()));
    }

    /** 我的售后列表（新的在前，不含事件流）。 */
    @GetMapping("/returns")
    public List<ReturnVO> myReturns() {
        String buyerId = accessGuard.requireBuyerIdentity();

        return afterSaleService.myReturns(buyerId).stream()
                .map(AfterSaleViews::summary)
                .toList();
    }

    /** 售后详情（含事件流）。 */
    @GetMapping("/returns/{returnNo}")
    public ReturnVO myReturn(@PathVariable String returnNo) {
        String buyerId = accessGuard.requireBuyerIdentity();

        if (returnNo == null || returnNo.isBlank()) {
            throw new BusinessException(400, "INVALID_RETURN_NO", "退货单号不能为空");
        }

        return afterSaleService.myReturn(buyerId, returnNo)
                .map(value -> AfterSaleViews.detail(value, afterSaleService.events(value.id())))
                .orElseThrow(() -> new BusinessException(
                        404, "RETURN_NOT_FOUND", "退货申请不存在：" + returnNo));
    }
}
