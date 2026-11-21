package com.example.mkagent.controller;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.ShopAccessGuard;
import com.example.mkagent.shop.api.ShopViews;
import com.example.mkagent.shop.api.ShopViews.OrderVO;
import com.example.mkagent.shop.api.ShopViews.ProductVO;
import com.example.mkagent.shop.api.ShopViews.SessionVO;
import com.example.mkagent.shop.catalog.ShopProductRepository;
import com.example.mkagent.shop.order.ShopOrderService;
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
 * 买家侧商城接口：商品列表 → 立即购买 → 我的订单 / 订单详情。
 *
 * <p>三条约束：
 * <ol>
 *   <li><b>商品列表不需要身份</b>：浏览商品是公开行为。
 *       但"我的订单"必须要求身份——没有身份就没有"我的"；</li>
 *   <li><b>下单只接受 productId + quantity</b>：单价与买家都由服务端决定，
 *       请求体里多传 {@code price} 或 {@code buyerId} 不会生效（也没有对应字段可绑）；</li>
 *   <li><b>只返回自己的订单</b>：归属条件写在 SQL 里，
 *       别人的订单号返回 404 而不是 403，避免用它探测订单是否存在。</li>
 * </ol>
 *
 * <p>本类不调用任何模型：商城闭环与 Agent 完全解耦，
 * 模型不可用时这些接口照常工作。
 */
@RestController
@RequestMapping("/shop")
public class ShopController {

    private final ShopAccessGuard accessGuard;

    private final ShopProductRepository productRepository;

    private final ShopOrderService orderService;

    public ShopController(
            ShopAccessGuard accessGuard,
            ShopProductRepository productRepository,
            ShopOrderService orderService
    ) {
        this.accessGuard = accessGuard;
        this.productRepository = productRepository;
        this.orderService = orderService;
    }

    /**
     * 下单请求体。
     *
     * <p>只有两个字段，这是刻意的：字段不存在，客户端就传不进来。
     * 用"接收了再忽略"的写法，会让人误以为传了有用。
     */
    public record PlaceOrderRequest(Long productId, Integer quantity) {
    }

    /**
     * 当前会话：身份、商城角色、能做什么。
     *
     * <p>不抛异常：页面需要在点击之前就知道"能不能下单"，
     * 而不是等用户填完数量再吃一个 403。
     */
    @GetMapping("/session")
    public SessionVO session() {
        return ShopViews.session(accessGuard.session());
    }

    /**
     * 商品列表（含已售罄的，{@code purchasable=false}）。
     *
     * <p>不需要身份：售罄商品也返回，让页面能显示"已售罄"而不是"消失"。
     */
    @GetMapping("/products")
    public List<ProductVO> products() {
        return productRepository.findActive().stream()
                .map(ShopViews::product)
                .toList();
    }

    /**
     * 下单（演示付款）。
     *
     * <p>返回 201：语义上是"创建了一个订单资源"。
     * 响应里带完整订单（含订单号），页面据此展示"下单成功"。
     */
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderVO placeOrder(@RequestBody(required = false) PlaceOrderRequest request) {
        String buyerId = accessGuard.requireBuyerIdentity();

        if (request == null) {
            throw new BusinessException(400, "INVALID_REQUEST", "请求体不能为空");
        }

        return ShopViews.detail(
                orderService.placeOrder(buyerId, request.productId(), request.quantity()));
    }

    /** 我的订单（新的在前）。 */
    @GetMapping("/orders")
    public List<OrderVO> myOrders() {
        String buyerId = accessGuard.requireBuyerIdentity();

        return orderService.myOrders(buyerId).stream()
                .map(ShopViews::summary)
                .toList();
    }

    /** 订单详情（含出库流水）。 */
    @GetMapping("/orders/{orderNo}")
    public OrderVO order(@PathVariable String orderNo) {
        String buyerId = accessGuard.requireBuyerIdentity();

        if (orderNo == null || orderNo.isBlank()) {
            throw new BusinessException(400, "INVALID_ORDER_NO", "订单号不能为空");
        }

        return orderService.myOrder(buyerId, orderNo)
                .map(ShopViews::detail)
                .orElseThrow(() -> new BusinessException(
                        404, "ORDER_NOT_FOUND", "订单不存在：" + orderNo));
    }
}
