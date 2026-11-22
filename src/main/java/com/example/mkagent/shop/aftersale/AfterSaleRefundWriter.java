package com.example.mkagent.shop.aftersale;

import com.example.mkagent.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 模拟退款的**唯一**写入点（B3）。
 *
 * <h2>一次操作只做一件事</h2>
 *
 * <pre>
 * TX:
 *   1. 锁订单行（SELECT ... FOR UPDATE）   ← 与 A1 新申请、B1 决策、B2 收货同一把锁
 *   2. 拿锁后重读退货单（必须 APPROVED）
 *   3. 拿锁后重读"这张单退过款没有"（uk(return_id) 兜底）
 *   4. 重读累计实收，必须等于批准数量（全部收货）
 *   5. 金额 = 订单行成交单价 × 累计实收（整数分，溢出即拒绝）
 *   6. 插入退款记录
 * COMMIT
 * </pre>
 *
 * <h2>为什么它不动库存、不写流水、不释放额度</h2>
 *
 * 三件事各自属于别的动作：
 * <ul>
 *   <li>库存已经在 B2 收货时按合格件数回补过了，退款再改一次就是重复计算；</li>
 *   <li>入库流水按收货记录去重（{@code IN:{receipt_id}}），退款不是入库；</li>
 *   <li>额度在批准时就已经保留（收货也不释放）——退款是资金动作，
 *       放回"可退"等于允许同一件商品被退第二次。</li>
 * </ul>
 *
 * <h2>为什么金额用"累计实收"而不是"合格件数"</h2>
 *
 * 退款退的是"买家寄回来几件"，损坏件同样要退钱；
 * 合格与否只决定"能不能再卖"（那是 B2 的事）。
 * 所以本类只读收货记录的 {@code received_qty} 之和，
 * 完全不看 {@code qualified_qty}。
 *
 * <h2>不做什么</h2>
 *
 * 不接支付平台、不产生真实资金流、不发通知、不做部分退款 / 多次退款。
 */
@Service
public class AfterSaleRefundWriter {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleRefundWriter.class);

    /** 这一单不是"已批准"，不能退款（待审批 / 已驳回）。 */
    public static final String CODE_NOT_APPROVED = "REFUND_NOT_ALLOWED";

    /** 还没收满，不能退款。 */
    public static final String CODE_NOT_RECEIVED = "REFUND_NOT_RECEIVED";

    /** 这张退货单已经退过款（一张单最多一笔）。 */
    public static final String CODE_ALREADY_REFUNDED = "REFUND_ALREADY_EXISTS";

    /** 退货单上的单价快照与原订单行不一致（数据异常，不能按错误金额退款）。 */
    public static final String CODE_AMOUNT_MISMATCH = "REFUND_AMOUNT_MISMATCH";

    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AfterSaleRepository repository;

    public AfterSaleRefundWriter(AfterSaleRepository repository) {
        this.repository = repository;
    }

    /**
     * 为一张"已批准 + 全部收货"的退货单生成一笔模拟退款。
     *
     * <p>调用方（{@link AfterSaleService#refund}）已做过 404 / 已退款的预检；
     * 这里在事务内**重新**判断一遍，因为预检和拿锁之间可能插进另一个退款请求。
     *
     * @param operatorId 操作人（服务端身份上下文）
     * @param returnNo   退货单号
     * @throws BusinessException 409 未收满 / 已退过款 / 金额与订单不符；
     *                           400 金额溢出
     */
    @Transactional
    public AfterSaleRefund refund(String operatorId, String returnNo) {
        // 1. 预检（不加锁）：先确认这单存在且已批准，避免白拿一把行锁。
        AfterSaleReturn current = repository.findReturnByNo(returnNo)
                .orElseThrow(() -> notFound(returnNo));
        requireApproved(current);

        // 2. 锁订单行：与收货、决策、新申请排队，保证"读到的实收"不会被另一批改掉。
        AfterSaleRepository.LockedItem locked = repository.lockOrderItem(current.orderItemId())
                .orElseThrow(() -> new BusinessException(
                        409, "ORDER_ITEM_NOT_FOUND",
                        "原订单行已不存在，无法退款：" + returnNo));

        // 3. 拿锁后重读：状态、已退款、累计实收都可能刚被别的请求改过。
        AfterSaleReturn fresh = repository.findReturnByNo(returnNo)
                .orElseThrow(() -> notFound(returnNo));
        requireApproved(fresh);

        repository.findRefundByReturnId(fresh.id()).ifPresent(existing -> {
            throw alreadyRefunded(existing);
        });

        int received = repository.sumReceivedQty(fresh.id());
        if (received < fresh.quantity()) {
            throw new BusinessException(
                    409,
                    CODE_NOT_RECEIVED,
                    "还没收满，不能退款：批准 " + fresh.quantity() + " 件，已收 " + received
                            + " 件，还差 " + (fresh.quantity() - received) + " 件");
        }

        /*
         * 4. 金额 = **原订单成交单价** × 累计实收（整数分）。
         *
         * 单价取自订单行（锁内读到的值），不是退货单上的估算值：
         * 万一两者不一致，说明有数据被绕过业务路径改过，
         * 这时候宁可拒绝，也不能按一个说不清来源的单价退钱。
         */
        if (fresh.unitPriceCents() != locked.unitPriceCents()) {
            throw new BusinessException(
                    409,
                    CODE_AMOUNT_MISMATCH,
                    "退货单上的单价快照与原订单行不一致（退货单 " + fresh.unitPriceCents()
                            + " 分 / 订单行 " + locked.unitPriceCents()
                            + " 分），已拒绝退款，请联系管理员核对数据");
        }

        long amountCents;
        try {
            amountCents = AfterSaleMoney.refundCents(locked.unitPriceCents(), received);
        } catch (ArithmeticException e) {
            throw new BusinessException(
                    400,
                    AfterSaleEligibility.CODE_REFUND_AMOUNT_OUT_OF_RANGE,
                    "退款金额超出可表示范围，已拒绝本次模拟退款");
        }

        // 5. 落库（uk(return_id) 兜底并发重复退款）。
        String refundNo = newRefundNo();
        long refundId = repository.insertRefund(refundNo, fresh.id(), amountCents, operatorId);

        log.info("模拟退款：refundNo={}, returnNo={}, amountCents={}, received={}, operator={}",
                refundNo, returnNo, amountCents, received, operatorId);

        return repository.findRefundById(refundId)
                .orElseThrow(() -> new IllegalStateException(
                        "模拟退款记录写入后读不到：" + refundNo));
    }

    /** 生成对外模拟退款单号：{@code RF + yyyyMMddHHmmss + 4 位十六进制}。 */
    static String newRefundNo() {
        String stamp = LocalDateTime.now().format(NO_FORMAT);
        return "RF" + stamp + "-" + String.format("%04X", RANDOM.nextInt(0x10000));
    }

    /**
     * 只有"已批准"的退货单能退款。
     *
     * <p>待审批（还没同意退）与已驳回（实物应退回买家）都要说清原因，
     * 而不是一句"状态不对"。
     */
    private static void requireApproved(AfterSaleReturn value) {
        if (value.status() == AfterSaleStatus.APPROVED) {
            return;
        }
        String hint = switch (value.status()) {
            case SUBMITTED -> "这一单还在等客服处理，批准之后才能退款";
            case REJECTED -> "这一单已被驳回，没有可退的款";
            default -> "当前状态为「" + value.status().label() + "」，不能退款";
        };
        throw new BusinessException(409, CODE_NOT_APPROVED, hint + "：" + value.returnNo());
    }

    private static BusinessException alreadyRefunded(AfterSaleRefund existing) {
        return new BusinessException(
                409,
                CODE_ALREADY_REFUNDED,
                "这张退货单已经生成过模拟退款（" + existing.refundNo()
                        + "，金额 " + existing.amountCents() + " 分），不会重复退款");
    }

    private static BusinessException notFound(String returnNo) {
        return new BusinessException(404, "RETURN_NOT_FOUND", "退货申请不存在：" + returnNo);
    }
}
