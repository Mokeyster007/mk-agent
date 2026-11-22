package com.example.mkagent.shop.inventory;

import com.example.mkagent.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 库存服务。
 *
 * <p>写方法只有两个：{@link #applyOutbound}（发货出库）与
 * {@link #applyInbound}（退货入库），且**都强制要求已有事务**
 * （{@code Propagation.MANDATORY}）：
 * "改库存"与"改业务单据"必须同生共死。允许它们自己开一个事务，
 * 就等于允许"库存扣了、订单没发货"或"收货记了、库存没加"这种中间态出现在库里。
 * 用 MANDATORY 把这条约束变成运行时错误，而不是靠调用方自觉。
 */
@Service
public class InventoryService {

    private final InventoryRepository repository;

    public InventoryService(InventoryRepository repository) {
        this.repository = repository;
    }

    /**
     * 发货出库：扣减可售库存 + 写一笔负数流水。
     *
     * <p>调用方必须是 {@code ShopOrderService#ship}，且已在事务里锁住订单行。
     * 库存不足时抛 409，**不扣、不写流水**，由外层事务整体回滚。
     *
     * @param sku         出库 SKU
     * @param quantity    出库数量（正数；内部转成负数流水）
     * @param orderId     来源订单 id
     * @param orderItemId 来源订单行 id（重复发货的唯一性靠它）
     * @param operatorId  执行发货的后台人员
     * @throws BusinessException 409 {@code INSUFFICIENT_STOCK}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyOutbound(
            String sku,
            int quantity,
            long orderId,
            long orderItemId,
            String operatorId
    ) {
        if (quantity <= 0) {
            /*
             * 编程错误而不是业务错误：数量来自订单行，订单行有 CHECK (quantity > 0)。
             * 走到这里说明数据或代码有一处坏了，直接让事务失败比"静默出库 0 件"安全。
             */
            throw new IllegalStateException("出库数量必须为正数：" + quantity);
        }

        int updated = repository.deductIfEnough(sku, quantity);

        if (updated == 0) {
            int available = repository.availableOf(sku);
            throw new BusinessException(
                    409,
                    "INSUFFICIENT_STOCK",
                    "库存不足：" + sku + " 当前可售 " + available
                            + " 件，本单需要 " + quantity
                            + " 件。订单仍为待发货，库存与流水均未变动。");
        }

        repository.insertMovement(
                sku,
                -quantity,
                InventoryMovement.TYPE_OUTBOUND_SHIPMENT,
                orderId,
                orderItemId,
                null,
                InventoryMovement.outboundKey(orderItemId),
                operatorId,
                "发货出库");
    }

    /**
     * 退货入库：增加可售库存 + 写一笔正数流水。
     *
     * <p>调用方必须是收货写入服务，且已在事务里锁住订单行、并已把收货记录插入。
     * 与出库一样用 {@link Propagation#MANDATORY}：
     * "收货记录写了、库存没加"或"库存加了、收货记录没写"都是不可接受的状态。
     *
     * <p>去重键是 {@code IN:{收货记录id}}，不是订单行——
     * 同一订单行可以有多批合法的退货入库，按订单行去重会把第二批挡掉。
     *
     * @param sku         入库 SKU
     * @param quantity    合格数量（> 0；内部写成正数流水）
     * @param orderId     来源订单 id
     * @param orderItemId 来源订单行 id
     * @param receiptId   收货记录 id（流水的唯一来源）
     * @param operatorId  收货操作人
     * @throws BusinessException 409 {@code INVENTORY_ROW_MISSING}（SKU 没有库存行，数据异常）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyInbound(
            String sku,
            int quantity,
            long orderId,
            long orderItemId,
            long receiptId,
            String operatorId
    ) {
        if (quantity <= 0) {
            /*
             * 合格数量为 0 时调用方不该调用本方法（整批损坏不入库）。
             * 真调到了说明判断写错，直接失败比"入 0 件"更容易发现。
             */
            throw new IllegalStateException("退货入库数量必须为正数：" + quantity);
        }

        int updated = repository.addAvailable(sku, quantity);
        if (updated == 0) {
            throw new BusinessException(
                    409,
                    "INVENTORY_ROW_MISSING",
                    "库存行不存在，无法回补：" + sku + "（本次收货已回滚）");
        }

        repository.insertMovement(
                sku,
                quantity,
                InventoryMovement.TYPE_RETURN_INBOUND,
                orderId,
                orderItemId,
                receiptId,
                InventoryMovement.inboundKey(receiptId),
                operatorId,
                "退货入库（验货合格）");
    }

    /** 某张收货记录产生的流水（页面提交后直接展示）。 */
    public List<InventoryMovement> movementsOfReceipt(long receiptId) {
        return repository.findByReceiptId(receiptId);
    }

    /** 全部库存余额（后台核对）。 */
    public List<InventoryBalanceView> balances() {
        return repository.findAllBalances();
    }

    /** 某个订单的流水（从订单追溯到出库记录）。 */
    public List<InventoryMovement> movementsOfOrder(long orderId) {
        return repository.findByOrderId(orderId);
    }

    /** 最近流水（后台核对首页）。 */
    public List<InventoryMovement> recentMovements(int limit) {
        return repository.findRecent(limit);
    }

    /** 某个 SKU 的可售数量。 */
    public int availableOf(String sku) {
        return repository.availableOf(sku);
    }
}
