package com.example.mkagent.shop.order;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.ShopProperties;
import com.example.mkagent.shop.catalog.ShopProduct;
import com.example.mkagent.shop.catalog.ShopProductRepository;
import com.example.mkagent.shop.inventory.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 订单服务：下单、发货、签收。
 *
 * <h2>三条业务规则（与验收用例一一对应）</h2>
 * <ol>
 *   <li><b>下单不预占、不扣库存</b>：下单只写订单头 + 明细。
 *       "下单成功"不等于"商品被锁住"，所以下单后库存必须原样不动。</li>
 *   <li><b>发货才校验并扣减</b>：校验 → 扣减 → 写流水 → 改状态，
 *       四件事在**同一个事务**里。任何一步失败整体回滚，
 *       不允许出现"扣了库存但订单没发货"或反过来。</li>
 *   <li><b>签收只记时间</b>：不再动库存。签收时间与订单行是 A1 售后资格校验的输入。</li>
 * </ol>
 *
 * <h2>为什么价格和买家都不接受客户端传值</h2>
 * 下单请求体只有 {@code productId} 和 {@code quantity}。
 * 单价从商品表读，买家从服务端身份取。
 * 一旦允许客户端传价格或 buyerId，就等于把"花多少钱"和"谁买的"交给调用方决定。
 *
 * <h2>幂等边界</h2>
 * 发货/签收的重复请求返回 {@code applied=false} 的 200，不重复写库。
 * 这里**不**引入审批链路那套 idempotency_key：
 * 订单状态本身就是天然幂等键（待发货只能发一次），
 * 再加一层键只会多一份需要维护的一致性。
 */
@Service
public class ShopOrderService {

    private static final Logger log = LoggerFactory.getLogger(ShopOrderService.class);

    private static final DateTimeFormatter NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 买家订单列表最多返回多少条（A0 不做分页，够用且不会一次拉全库）。 */
    private static final int BUYER_ORDER_LIMIT = 50;

    private final ShopOrderRepository orderRepository;

    private final ShopProductRepository productRepository;

    private final InventoryService inventoryService;

    private final ShopProperties properties;

    public ShopOrderService(
            ShopOrderRepository orderRepository,
            ShopProductRepository productRepository,
            InventoryService inventoryService,
            ShopProperties properties
    ) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.inventoryService = inventoryService;
        this.properties = properties;
    }

    /**
     * 下单（演示付款）。
     *
     * <p><b>不动库存</b>。这一点在代码里没有任何"顺手扣一下"的分支，
     * 也不在库存表上做预占——本轮明确不做预占。
     *
     * @param buyerId   服务端解析出的买家身份
     * @param productId 商品 id
     * @param quantity  数量
     * @return 新订单详情（流水为空，因为还没发货）
     * @throws BusinessException 400 参数非法 / 404 商品不存在或已下架
     */
    @Transactional
    public ShopOrderDetail placeOrder(String buyerId, Long productId, Integer quantity) {
        if (productId == null || productId <= 0) {
            throw new BusinessException(400, "INVALID_PRODUCT", "商品 ID 必须是正整数");
        }
        if (quantity == null || quantity < 1) {
            throw new BusinessException(400, "INVALID_QUANTITY", "购买数量至少为 1 件");
        }
        if (quantity > properties.getMaxQuantityPerOrder()) {
            throw new BusinessException(
                    400,
                    "INVALID_QUANTITY",
                    "单笔订单最多 " + properties.getMaxQuantityPerOrder() + " 件");
        }

        ShopProduct product = productRepository.findActiveById(productId)
                .orElseThrow(() -> new BusinessException(
                        404, "PRODUCT_NOT_FOUND", "商品不存在或已下架：" + productId));

        long lineCents;
        try {
            lineCents = Math.multiplyExact(product.priceCents(), (long) quantity);
        } catch (ArithmeticException e) {
            throw new BusinessException(400, "AMOUNT_OVERFLOW", "订单金额超出可表示范围");
        }

        String orderNo = newOrderNo();

        long orderId = orderRepository.insertOrder(
                orderNo,
                buyerId,
                ShopOrderStatus.PENDING_SHIPMENT,
                lineCents,
                ShopOrder.PAYMENT_MODE_DEMO);

        orderRepository.insertItem(
                orderId,
                product.id(),
                product.sku(),
                product.name(),
                product.priceCents(),
                quantity,
                lineCents);

        log.info("商城下单：orderNo={}, productId={}, quantity={}, totalCents={}",
                orderNo, productId, quantity, lineCents);

        return detailOf(orderNo);
    }

    /**
     * 发货（后台演示人员）。
     *
     * <p>事务内的顺序是刻意安排的：
     * <ol>
     *   <li>锁订单行 → 状态不是待发货就直接返回（幂等，不碰库存）；</li>
     *   <li>原子条件扣减库存 → 0 行即库存不足，抛 409，整体回滚；</li>
     *   <li>写一笔负数出库流水；</li>
     *   <li>条件更新订单为已发货 → 0 行说明状态被改过，抛 409，整体回滚。</li>
     * </ol>
     *
     * @param operatorId 后台操作人（来自服务端身份）
     * @param orderNo    订单号
     * @throws BusinessException 404 订单不存在 / 409 库存不足或状态冲突
     */
    @Transactional
    public OrderActionResult ship(String operatorId, String orderNo) {
        ShopOrderRepository.LockedOrder locked = orderRepository.lockByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(
                        404, "ORDER_NOT_FOUND", "订单不存在：" + orderNo));

        if (locked.status() == ShopOrderStatus.SIGNED) {
            return OrderActionResult.notApplied(
                    "该订单已签收，本次未重复发货，库存与流水均未变动",
                    detailOf(orderNo));
        }

        if (locked.status() == ShopOrderStatus.SHIPPED) {
            return OrderActionResult.notApplied(
                    "该订单已发货，本次未重复扣减库存，也未新增出库流水",
                    detailOf(orderNo));
        }

        ShopOrderItem item = orderRepository.findItem(locked.id())
                .orElseThrow(() -> new IllegalStateException(
                        "订单 " + orderNo + " 缺少明细，拒绝发货"));

        inventoryService.applyOutbound(
                item.sku(), item.quantity(), locked.id(), item.id(), operatorId);

        int updated = orderRepository.markShipped(locked.id(), operatorId);
        if (updated == 0) {
            /*
             * 理论上不可达（本事务持有订单行锁），但保留这条检查：
             * 一旦真的发生，必须回滚已经扣掉的库存，而不是让两者不一致。
             */
            throw new BusinessException(
                    409, "ORDER_STATUS_CONFLICT", "订单状态已变化，请刷新后重试");
        }

        log.info("商城发货：orderNo={}, sku={}, quantity={}, operator={}",
                orderNo, item.sku(), item.quantity(), operatorId);

        return OrderActionResult.applied(
                "已发货：扣减 " + item.sku() + " " + item.quantity() + " 件，并写入出库流水",
                detailOf(orderNo));
    }

    /**
     * 签收（后台演示人员标记）。
     *
     * <p>只在已发货后允许；签收不改库存。
     * 为什么签收也放在后台而不是买家自己点：
     * 本轮要保证"签收时间"是一个可核对的事实，
     * 让买家自报签收会把 A1 的售后起算点交给买家控制。
     *
     * @param operatorId 后台操作人
     * @param orderNo    订单号
     * @throws BusinessException 404 订单不存在 / 409 尚未发货
     */
    @Transactional
    public OrderActionResult sign(String operatorId, String orderNo) {
        ShopOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(
                        404, "ORDER_NOT_FOUND", "订单不存在：" + orderNo));

        if (order.status() == ShopOrderStatus.SIGNED) {
            return OrderActionResult.notApplied(
                    "该订单已签收，本次未重复记录签收时间", detailOf(orderNo));
        }

        if (!order.status().canSign()) {
            throw new BusinessException(
                    409,
                    "ORDER_NOT_SHIPPED",
                    "订单当前为「" + order.status().label() + "」，只有已发货的订单才能签收");
        }

        int updated = orderRepository.markSigned(orderNo, operatorId);
        if (updated == 0) {
            throw new BusinessException(
                    409, "ORDER_STATUS_CONFLICT", "订单状态已变化，请刷新后重试");
        }

        log.info("商城签收：orderNo={}, operator={}", orderNo, operatorId);

        return OrderActionResult.applied("已签收：记录签收时间，库存不变", detailOf(orderNo));
    }

    /** 买家本人的订单列表。 */
    public List<ShopOrderSummary> myOrders(String buyerId) {
        return orderRepository.findByBuyer(buyerId, BUYER_ORDER_LIMIT).stream()
                .map(this::summaryOf)
                .toList();
    }

    /**
     * 买家本人的某个订单。
     *
     * <p>不是自己的订单返回空，由控制器转成 404：
     * 不区分"不存在"和"不是你的"，避免用订单号探测别人的订单。
     */
    public Optional<ShopOrderDetail> myOrder(String buyerId, String orderNo) {
        return orderRepository.findByOrderNoAndBuyer(orderNo, buyerId)
                .map(order -> detailOf(order.orderNo()));
    }

    /** 后台：按状态分页。 */
    public List<ShopOrderSummary> adminOrders(ShopOrderStatus status, int limit, int offset) {
        return orderRepository.findForAdmin(status, limit, offset).stream()
                .map(this::summaryOf)
                .toList();
    }

    /** 后台：按状态总数。 */
    public long adminOrderCount(ShopOrderStatus status) {
        return orderRepository.countForAdmin(status);
    }

    /** 后台：任意订单详情（含流水）。 */
    public Optional<ShopOrderDetail> adminOrder(String orderNo) {
        return orderRepository.findByOrderNo(orderNo).map(order -> detailOf(order.orderNo()));
    }

    private ShopOrderSummary summaryOf(ShopOrder order) {
        ShopOrderItem item = orderRepository.findItem(order.id())
                .orElseThrow(() -> new IllegalStateException(
                        "订单 " + order.orderNo() + " 缺少明细"));
        return new ShopOrderSummary(order, item);
    }

    private ShopOrderDetail detailOf(String orderNo) {
        ShopOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(
                        404, "ORDER_NOT_FOUND", "订单不存在：" + orderNo));
        ShopOrderItem item = orderRepository.findItem(order.id())
                .orElseThrow(() -> new IllegalStateException(
                        "订单 " + orderNo + " 缺少明细"));
        return new ShopOrderDetail(order, item, inventoryService.movementsOfOrder(order.id()));
    }

    /**
     * 生成对外订单号：{@code SO + yyyyMMddHHmmss + 4 位十六进制}。
     *
     * <p>不用自增 id 对外：连续数字会泄露"这个店一共多少单"，
     * 也让别人可以顺序试探。时间戳 + 随机后缀可读、可排序，
     * 冲突由 {@code uk_shop_order_no} 兜底（同一秒 65536 种后缀，实际不会撞）。
     */
    static String newOrderNo() {
        String stamp = LocalDateTime.now().format(NO_FORMAT);
        return "SO" + stamp + "-" + String.format("%04X", RANDOM.nextInt(0x10000));
    }
}
