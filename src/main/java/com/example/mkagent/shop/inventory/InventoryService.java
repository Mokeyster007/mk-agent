package com.example.mkagent.shop.inventory;

import com.example.mkagent.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 库存服务。
 *
 * <p>写方法只有 {@link #applyOutbound}，且**强制要求已有事务**
 * （{@code Propagation.MANDATORY}）：
 * "扣库存"与"更新订单状态"必须同生共死。允许它自己开一个事务，
 * 就等于允许"库存扣了、订单没发货"这种中间态出现在库里。
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
                operatorId,
                "发货出库");
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
