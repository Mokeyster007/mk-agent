package com.example.mkagent.shop.aftersale;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.inventory.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 收货 / 验货的**唯一**写入点（B2）。
 *
 * <h2>一次操作做完四件事</h2>
 *
 * <pre>
 * TX:
 *   1. 锁订单行（SELECT ... FOR UPDATE）        ← 与 A1 新申请、B1 决策同一把锁
 *   2. 拿锁后重读退货单状态（必须是 APPROVED）
 *   3. 拿锁后重读累计实收，判断"本次实收 + 已收 ≤ 批准数量"
 *   4. 插入收货记录
 *   5. 合格数量 &gt; 0 时：增加可售库存 + 写 RETURN_INBOUND 流水（去重键 IN:{收货记录id}）
 * COMMIT
 * </pre>
 *
 * 五步同一个事务：提交了就一定"收货记录 + 库存 + 流水"三者齐全，
 * 回滚了就一件都没发生。不会出现"库存加了但没有收货记录"，
 * 也不会出现"收货记了、库存没加"。
 *
 * <h2>为什么必须拿订单行锁</h2>
 *
 * "不超收"是一条跨行的约束：累计实收 = 本表多行之和。
 * 没有串行化点时，两个请求会同时读到"已收 1 / 批准 2"，各自认为还能收 1 件，
 * 最后收到 3 件。锁住订单行之后，第二批在第一步就排队，
 * 拿到锁时读到的是第一批提交后的累计值。
 *
 * <h2>为什么不改退货单状态、不动额度</h2>
 *
 * 状态仍然只表达客服结论（{@code APPROVED}）；进度是算出来的
 * （{@link AfterSaleReceiptProgress}）。
 * 额度也不释放：收进来的件数已经把这份退货资格用掉了，
 * 再放回"可退"等于允许同一件商品被退第二次。额度只在驳回时释放。
 *
 * <h2>不做什么</h2>
 *
 * 不建残次库存（损坏数量只留在收货记录里）、不产生退款、不做多仓。
 */
@Service
public class AfterSaleReceiptWriter {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleReceiptWriter.class);

    /** 这一单不是"已批准"，不能收货。 */
    public static final String CODE_NOT_APPROVED = "RETURN_NOT_APPROVED";

    /** 本次实收会让累计实收超过批准数量。 */
    public static final String CODE_OVER_APPROVED = "RECEIPT_OVER_APPROVED";

    /** 数量不合法（实收 &lt;= 0，或 合格 + 损坏 != 实收）。 */
    public static final String CODE_INVALID_QUANTITY = "RECEIPT_QUANTITY_INVALID";

    /** 同一个收货标识被用于不同的参数。 */
    public static final String CODE_REQUEST_CONFLICT = "RECEIPT_REQUEST_CONFLICT";

    /** 非幂等的写入冲突（单号重号等数据异常）。 */
    public static final String CODE_WRITE_CONFLICT = "RECEIPT_WRITE_CONFLICT";

    /** 备注超长。 */
    public static final String CODE_INVALID_NOTE = "INVALID_RECEIPT_NOTE";

    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AfterSaleRepository repository;

    private final InventoryService inventoryService;

    public AfterSaleReceiptWriter(
            AfterSaleRepository repository,
            InventoryService inventoryService
    ) {
        this.repository = repository;
        this.inventoryService = inventoryService;
    }

    /**
     * 登记一批收货 + 验货结果。
     *
     * <p>调用方（{@link AfterSaleService#receive}）已经做过参数校验与幂等预检；
     * 这里只负责"在事务里把事实写对"。幂等键冲突会以
     * {@code DuplicateKeyException} 冒出去，由服务层翻译成"回放 / 冲突"。
     *
     * @param operatorId  收货人（来自服务端身份上下文）
     * @param returnNo    退货单号
     * @param receivedQty 本批实收
     * @param qualifiedQty 本批合格（增加可售库存）
     * @param damagedQty  本批损坏（只留记录）
     * @param note        备注（可空）
     * @param requestKey  幂等键
     * @param requestHash 请求哈希（同键不同参的判据）
     */
    @Transactional
    public AfterSaleReceipt receive(
            String operatorId,
            String returnNo,
            int receivedQty,
            int qualifiedQty,
            int damagedQty,
            String note,
            String requestKey,
            String requestHash
    ) {
        // 1. 预检（不加锁）：先确认这单存在且已批准，避免白拿一把行锁。
        AfterSaleReturn current = repository.findReturnByNo(returnNo)
                .orElseThrow(() -> notFound(returnNo));
        requireApproved(current);

        // 2. 锁订单行：与 A1 新申请、B1 决策、另一批收货排队。
        repository.lockOrderItem(current.orderItemId())
                .orElseThrow(() -> new BusinessException(
                        409, "ORDER_ITEM_NOT_FOUND",
                        "原订单行已不存在，无法收货：" + returnNo));

        // 3. 拿锁后重读：状态可能刚被驳回，累计实收也可能刚被上一批改掉。
        AfterSaleReturn fresh = repository.findReturnByNo(returnNo)
                .orElseThrow(() -> notFound(returnNo));
        requireApproved(fresh);

        int alreadyReceived = repository.sumReceivedQty(fresh.id());
        if (alreadyReceived + receivedQty > fresh.quantity()) {
            throw new BusinessException(
                    409,
                    CODE_OVER_APPROVED,
                    "本次实收 " + receivedQty + " 件会超过批准数量：已收 " + alreadyReceived
                            + " 件，批准 " + fresh.quantity() + " 件，本次最多还能收 "
                            + Math.max(0, fresh.quantity() - alreadyReceived) + " 件");
        }

        // 4. 收货记录（uk(request_key) 是并发同键的兜底）。
        String receiptNo = newReceiptNo();
        long receiptId = repository.insertReceipt(
                receiptNo,
                fresh.id(),
                fresh.orderItemId(),
                receivedQty,
                qualifiedQty,
                damagedQty,
                note,
                requestKey,
                requestHash,
                operatorId);

        // 5. 只有合格数量入可售库存；整批损坏不写流水。
        if (qualifiedQty > 0) {
            inventoryService.applyInbound(
                    fresh.sku(),
                    qualifiedQty,
                    fresh.orderId(),
                    fresh.orderItemId(),
                    receiptId,
                    operatorId);
        }

        log.info("售后退货收货：receiptNo={}, returnNo={}, received={}, qualified={}, damaged={}, operator={}",
                receiptNo, returnNo, receivedQty, qualifiedQty, damagedQty, operatorId);

        return repository.findReceiptById(receiptId)
                .orElseThrow(() -> new IllegalStateException(
                        "收货记录写入后读不到：" + receiptNo));
    }

    /**
     * 只有"已批准"的退货单能收货。
     *
     * <p>待审批（{@code SUBMITTED}）与已驳回（{@code REJECTED}）都要给出
     * 说得出原因的错误，而不是一句"状态不对"：操作员需要知道下一步该做什么。
     */
    private static void requireApproved(AfterSaleReturn value) {
        if (value.status() == AfterSaleStatus.APPROVED) {
            return;
        }
        String hint = switch (value.status()) {
            case SUBMITTED -> "这一单还在等客服处理，批准之后才能收货";
            case REJECTED -> "这一单已被驳回，实物应退回买家，不能入库";
            default -> "当前状态为「" + value.status().label() + "」，不能收货";
        };
        throw new BusinessException(409, CODE_NOT_APPROVED, hint + "：" + value.returnNo());
    }

    private static BusinessException notFound(String returnNo) {
        return new BusinessException(404, "RETURN_NOT_FOUND", "退货申请不存在：" + returnNo);
    }

    /**
     * 生成对外收货单号：{@code RC + yyyyMMddHHmmss + 4 位十六进制}。
     *
     * <p>与退货单号同构：不暴露自增 id，可读可排序，冲突由唯一索引兜底。
     */
    static String newReceiptNo() {
        String stamp = LocalDateTime.now().format(NO_FORMAT);
        return "RC" + stamp + "-" + String.format("%04X", RANDOM.nextInt(0x10000));
    }
}
