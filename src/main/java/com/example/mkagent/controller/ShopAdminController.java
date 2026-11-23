package com.example.mkagent.controller;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.ShopAccessGuard;
import com.example.mkagent.shop.ShopProperties;
import com.example.mkagent.shop.api.ShopViews;
import com.example.mkagent.shop.api.ShopViews.ActionResultVO;
import com.example.mkagent.shop.api.ShopViews.BalanceVO;
import com.example.mkagent.shop.api.ShopViews.InventoryVO;
import com.example.mkagent.shop.api.ShopViews.MovementVO;
import com.example.mkagent.shop.api.ShopViews.OrderVO;
import com.example.mkagent.shop.api.ShopViews.PageVO;
import com.example.mkagent.shop.api.ShopViews.ProductVO;
import com.example.mkagent.shop.catalog.ShopProductRepository;
import com.example.mkagent.shop.inventory.InventoryService;
import com.example.mkagent.shop.order.OrderActionResult;
import com.example.mkagent.shop.order.ShopOrderService;
import com.example.mkagent.shop.order.ShopOrderStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台演示接口：按状态看订单、发货、标记签收、核对库存与出库流水。
 *
 * <p>每一个方法的第一行都是 {@link ShopAccessGuard#requireAdmin()}：
 * 权限检查不写在业务里，也不靠前端隐藏按钮。
 *
 * <p>本入口是**演示**性质：没有真实仓储系统、没有物流单号、没有承运商。
 * 发货只是"校验库存 → 扣减 → 写出库流水 → 改订单状态"，
 * 它替代的是仓库出库这个动作，不假装对接了 WMS。
 */
@RestController
@RequestMapping("/shop/admin")
public class ShopAdminController {

    /** 后台列表单页上限：防止一个请求把整张订单表拉出来。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 后台首页展示的最近流水条数。 */
    private static final int RECENT_MOVEMENT_LIMIT = 50;

    private final ShopAccessGuard accessGuard;

    private final ShopOrderService orderService;

    private final InventoryService inventoryService;

    private final ShopProductRepository productRepository;

    private final ShopProperties properties;

    public ShopAdminController(
            ShopAccessGuard accessGuard,
            ShopOrderService orderService,
            InventoryService inventoryService,
            ShopProductRepository productRepository,
            ShopProperties properties
    ) {
        this.accessGuard = accessGuard;
        this.orderService = orderService;
        this.inventoryService = inventoryService;
        this.productRepository = productRepository;
        this.properties = properties;
    }

    public record UpdateProductPriceRequest(Long priceCents) {
    }

    public record UpdateProductStatusRequest(String status) {
    }

    /**
     * 按状态分页查看订单。
     *
     * @param status PENDING_SHIPMENT / SHIPPED / SIGNED；留空表示全部
     * @param page   从 1 开始（小于 1 按 1 处理，不报错）
     * @param size   页大小（夹紧到 1..100，不报错）
     */
    @GetMapping("/orders")
    public PageVO<OrderVO> orders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "0") int size
    ) {
        accessGuard.requireAdmin();

        ShopOrderStatus parsed = null;
        if (status != null && !status.isBlank()) {
            parsed = ShopOrderStatus.parse(status);
            if (parsed == null) {
                /*
                 * 不认识的筛选值直接 400，而不是当成"全部"：
                 * 静默忽略会让人以为筛选生效了，看到的却是全量。
                 */
                throw new BusinessException(
                        400, "INVALID_STATUS", "未知的订单状态：" + status);
            }
        }

        int safePage = Math.max(1, page);
        int defaultSize = properties.getAdminPageSize() > 0 ? properties.getAdminPageSize() : 20;
        int safeSize = size <= 0 ? defaultSize : Math.min(size, MAX_PAGE_SIZE);

        List<OrderVO> items = orderService
                .adminOrders(parsed, safeSize, (safePage - 1) * safeSize)
                .stream()
                .map(ShopViews::summary)
                .toList();

        return new PageVO<>(items, safePage, safeSize, orderService.adminOrderCount(parsed));
    }

    /** 任意订单详情（含该订单的出库流水）。 */
    @GetMapping("/orders/{orderNo}")
    public OrderVO order(@PathVariable String orderNo) {
        accessGuard.requireAdmin();

        return orderService.adminOrder(orderNo)
                .map(ShopViews::detail)
                .orElseThrow(() -> new BusinessException(
                        404, "ORDER_NOT_FOUND", "订单不存在：" + orderNo));
    }

    /**
     * 发货（演示出库）。
     *
     * <p>重复发货返回 200 + {@code applied=false}，不重复扣减、不重复写流水。
     * 库存不足返回 409 {@code INSUFFICIENT_STOCK}，订单仍为待发货。
     */
    @PostMapping("/orders/{orderNo}/ship")
    public ActionResultVO ship(@PathVariable String orderNo) {
        String operatorId = accessGuard.requireAdmin();

        return toVO(orderService.ship(operatorId, orderNo));
    }

    /** 标记签收（只允许已发货的订单；不改库存）。 */
    @PostMapping("/orders/{orderNo}/sign")
    public ActionResultVO sign(@PathVariable String orderNo) {
        String operatorId = accessGuard.requireAdmin();

        return toVO(orderService.sign(operatorId, orderNo));
    }

    /**
     * 库存核对：余额 + 最近流水。
     *
     * <p>放在同一个响应里，是为了能一眼对上"扣了多少"和"流水记了什么"。
     */
    @GetMapping("/inventory")
    public InventoryVO inventory() {
        accessGuard.requireAdmin();

        List<BalanceVO> balances = inventoryService.balances().stream()
                .map(ShopViews::balance)
                .toList();

        List<MovementVO> movements = inventoryService.recentMovements(RECENT_MOVEMENT_LIMIT)
                .stream()
                .map(ShopViews::movement)
                .toList();

        return new InventoryVO(balances, movements);
    }

    /** 后台商品列表（含下架商品）。 */
    @GetMapping("/products")
    public List<ProductVO> products() {
        accessGuard.requireAdmin();
        return productRepository.findAll().stream()
                .map(ShopViews::product)
                .toList();
    }

    /** 后台调价（历史订单快照不受影响；用于验证报价变动后需重新确认）。 */
    @PostMapping("/products/{id}/price")
    public ProductVO updateProductPrice(
            @PathVariable long id,
            @RequestBody(required = false) UpdateProductPriceRequest request
    ) {
        accessGuard.requireAdmin();
        if (request == null || request.priceCents() == null || request.priceCents() < 0) {
            throw new BusinessException(400, "INVALID_PRICE", "价格（分）必须为非负整数");
        }
        int rows = productRepository.updatePriceCents(id, request.priceCents());
        if (rows == 0) {
            throw new BusinessException(404, "PRODUCT_NOT_FOUND", "商品不存在：" + id);
        }
        return productRepository.findById(id)
                .map(ShopViews::product)
                .orElseThrow(() -> new BusinessException(404, "PRODUCT_NOT_FOUND", "商品不存在：" + id));
    }

    /** 后台上下架（ACTIVE / INACTIVE）。 */
    @PostMapping("/products/{id}/status")
    public ProductVO updateProductStatus(
            @PathVariable long id,
            @RequestBody(required = false) UpdateProductStatusRequest request
    ) {
        accessGuard.requireAdmin();
        if (request == null || request.status() == null
                || (!"ACTIVE".equals(request.status()) && !"INACTIVE".equals(request.status()))) {
            throw new BusinessException(400, "INVALID_STATUS", "商品状态只允许 ACTIVE 或 INACTIVE");
        }
        int rows = productRepository.updateStatus(id, request.status());
        if (rows == 0) {
            throw new BusinessException(404, "PRODUCT_NOT_FOUND", "商品不存在：" + id);
        }
        return productRepository.findById(id)
                .map(ShopViews::product)
                .orElseThrow(() -> new BusinessException(404, "PRODUCT_NOT_FOUND", "商品不存在：" + id));
    }

    private static ActionResultVO toVO(OrderActionResult result) {
        return new ActionResultVO(
                result.applied(),
                result.message(),
                ShopViews.detail(result.detail()));
    }
}
