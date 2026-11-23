package com.example.mkagent.controller;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.ShopAccessGuard;
import com.example.mkagent.shop.api.ShopViews;
import com.example.mkagent.shop.api.ShopViews.CartVO;
import com.example.mkagent.shop.api.ShopViews.CatalogSearchVO;
import com.example.mkagent.shop.api.ShopViews.CheckoutConfirmVO;
import com.example.mkagent.shop.api.ShopViews.CheckoutPreviewVO;
import com.example.mkagent.shop.api.ShopViews.ComparisonVO;
import com.example.mkagent.shop.api.ShopViews.OrderVO;
import com.example.mkagent.shop.api.ShopViews.ProductVO;
import com.example.mkagent.shop.api.ShopViews.SessionVO;
import com.example.mkagent.shop.cart.ShopCartService;
import com.example.mkagent.shop.catalog.ShopCatalogService;
import com.example.mkagent.shop.catalog.ShopProductRepository;
import com.example.mkagent.shop.order.ShopCheckoutService;
import com.example.mkagent.shop.order.ShopOrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 买家侧商城接口：商品检索与比较 → 共享购物车 → 结算预览与确认 → 我的订单 / 订单详情。
 *
 * <p>完全不依赖大模型：普通用户不聊 Agent 也能走通全部选品、加购、结算与订单查看。
 */
@RestController
@RequestMapping("/shop")
public class ShopController {

    private final ShopAccessGuard accessGuard;
    private final ShopProductRepository productRepository;
    private final ShopCatalogService catalogService;
    private final ShopCartService cartService;
    private final ShopCheckoutService checkoutService;
    private final ShopOrderService orderService;

    public ShopController(
            ShopAccessGuard accessGuard,
            ShopProductRepository productRepository,
            ShopCatalogService catalogService,
            ShopCartService cartService,
            ShopCheckoutService checkoutService,
            ShopOrderService orderService
    ) {
        this.accessGuard = accessGuard;
        this.productRepository = productRepository;
        this.catalogService = catalogService;
        this.cartService = cartService;
        this.checkoutService = checkoutService;
        this.orderService = orderService;
    }

    public record PlaceOrderRequest(Long productId, Integer quantity) {
    }

    public record CompareRequest(List<String> keys) {
    }

    public record CartAddRequest(Long productId, String sku, Integer quantity) {
    }

    public record CartUpdateRequest(Integer quantity) {
    }

    public record CheckoutPreviewRequest(Long productId, Integer quantity) {
    }

    public record CheckoutConfirmRequest(
            Long productId,
            Integer quantity,
            Long expectedUnitPriceCents,
            Boolean removeFromCart,
            String clientRequestId
    ) {
    }

    @GetMapping("/session")
    public SessionVO session() {
        return ShopViews.session(accessGuard.session());
    }

    /** 全部上架商品（保留 A0 契约，扩展结构化规格字段）。 */
    @GetMapping("/products")
    public List<ProductVO> products() {
        return productRepository.findActive().stream()
                .map(ShopViews::product)
                .toList();
    }

    /** 按硬条件与软偏好检索商品（公开只读，与导购 Agent 共用同一商品服务）。 */
    @GetMapping("/products/search")
    public CatalogSearchVO searchProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Double maxPriceYuan,
            @RequestParam(required = false) Double minPriceYuan,
            @RequestParam(required = false) Boolean wirelessOnly,
            @RequestParam(required = false) Boolean macCompatible,
            @RequestParam(required = false) Boolean preferSilent,
            @RequestParam(required = false) Boolean inStockOnly
    ) {
        Long maxCents = maxPriceYuan == null ? null
                : BigDecimal.valueOf(Math.max(0, maxPriceYuan)).multiply(BigDecimal.valueOf(100)).longValue();
        Long minCents = minPriceYuan == null ? null
                : BigDecimal.valueOf(Math.max(0, minPriceYuan)).multiply(BigDecimal.valueOf(100)).longValue();

        return ShopViews.catalogSearch(catalogService.search(new ShopCatalogService.SearchCriteria(
                keyword, category, maxCents, minCents,
                wirelessOnly, macCompatible, preferSilent, inStockOnly)));
    }

    /** 对比 2~3 款商品规格（公开只读）。 */
    @PostMapping("/products/compare")
    public ComparisonVO compareProducts(@RequestBody(required = false) CompareRequest request) {
        if (request == null || request.keys() == null) {
            throw new BusinessException(400, "INVALID_COMPARE_INPUT", "请提供要比较的商品列表");
        }
        return ShopViews.comparison(catalogService.compare(request.keys()));
    }

    // ==================== 共享购物车 ====================

    @GetMapping("/cart")
    public CartVO myCart() {
        String buyerId = accessGuard.requireBuyerIdentity();
        return ShopViews.cart(cartService.myCart(buyerId));
    }

    @PostMapping("/cart/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CartVO addToCart(@RequestBody(required = false) CartAddRequest request) {
        String buyerId = accessGuard.requireBuyerIdentity();
        if (request == null) {
            throw new BusinessException(400, "INVALID_REQUEST", "请求体不能为空");
        }
        int qty = request.quantity() == null ? 1 : request.quantity();
        if (request.productId() != null) {
            cartService.addItem(buyerId, request.productId(), qty);
        } else if (request.sku() != null && !request.sku().isBlank()) {
            cartService.addBySkuOrId(buyerId, request.sku(), qty);
        } else {
            throw new BusinessException(400, "INVALID_PRODUCT", "请指定要加入购物车的商品");
        }
        return ShopViews.cart(cartService.myCart(buyerId));
    }

    @PatchMapping("/cart/items/{productId}")
    public CartVO updateCartItem(
            @PathVariable long productId,
            @RequestBody(required = false) CartUpdateRequest request
    ) {
        String buyerId = accessGuard.requireBuyerIdentity();
        if (request == null || request.quantity() == null) {
            throw new BusinessException(400, "INVALID_QUANTITY", "请提供修改后的数量");
        }
        cartService.updateQuantity(buyerId, productId, request.quantity());
        return ShopViews.cart(cartService.myCart(buyerId));
    }

    @DeleteMapping("/cart/items/{productId}")
    public CartVO removeCartItem(@PathVariable long productId) {
        String buyerId = accessGuard.requireBuyerIdentity();
        cartService.removeItem(buyerId, productId);
        return ShopViews.cart(cartService.myCart(buyerId));
    }

    // ==================== 结算预览与确认下单 ====================

    @PostMapping("/checkout/preview")
    public CheckoutPreviewVO previewCheckout(@RequestBody(required = false) CheckoutPreviewRequest request) {
        accessGuard.requireBuyerIdentity();
        if (request == null || request.productId() == null) {
            throw new BusinessException(400, "INVALID_PRODUCT", "请先选择要结算的商品");
        }
        int qty = request.quantity() == null ? 1 : request.quantity();
        return ShopViews.checkoutPreview(checkoutService.preview(request.productId(), qty));
    }

    @PostMapping("/checkout/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutConfirmVO confirmCheckout(@RequestBody(required = false) CheckoutConfirmRequest request) {
        String buyerId = accessGuard.requireBuyerIdentity();
        if (request == null || request.productId() == null) {
            throw new BusinessException(400, "INVALID_REQUEST", "请提供确认结算参数");
        }
        int qty = request.quantity() == null ? 1 : request.quantity();
        boolean removeFromCart = request.removeFromCart() == null || request.removeFromCart();
        return ShopViews.checkoutConfirm(checkoutService.confirmFromPage(
                buyerId,
                request.productId(),
                qty,
                request.expectedUnitPriceCents(),
                removeFromCart,
                request.clientRequestId()));
    }

    // ==================== 既有 A0 下单与订单查询 ====================

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

    @GetMapping("/orders")
    public List<OrderVO> myOrders() {
        String buyerId = accessGuard.requireBuyerIdentity();

        return orderService.myOrders(buyerId).stream()
                .map(ShopViews::summary)
                .toList();
    }

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
