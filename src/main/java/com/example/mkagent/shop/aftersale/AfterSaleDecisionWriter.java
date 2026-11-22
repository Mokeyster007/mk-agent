package com.example.mkagent.shop.aftersale;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.ShopProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客服决策的**唯一**写入点：批准 / 驳回。
 *
 * <h2>为什么批准和驳回必须在同一个方法里</h2>
 *
 * 两者竞争的是同一个东西——"这一单还能不能被决策"。
 * 拆成两个独立实现时，最典型的 bug 是两边各自判断一次状态，
 * 于是"批准与驳回并发"会同时通过检查，最后一条 UPDATE 覆盖前一条，
 * 库里留下"已批准但额度已释放"这种自相矛盾的状态。
 * 这里只有一条路径：锁 → 重读 → 条件转换 → 分叉（只有驳回释放额度）。
 *
 * <h2>锁顺序（全工程统一，不允许反着来）</h2>
 *
 * <pre>
 * 1. shop_order_item 一行   SELECT ... FOR UPDATE      ← 与 A1 新申请同一把锁
 * 2. shop_aftersale_return 一行  条件 UPDATE ... WHERE status = 'SUBMITTED'
 * 3. shop_aftersale_quota  一行  条件 UPDATE ... WHERE reserved_qty >= ?
 * 4. shop_aftersale_decision 插入（uk(return_id) 兜底）
 * 5. shop_aftersale_event    插入（uk(return_id, event_type) 兜底）
 * </pre>
 *
 * 第 1 步与 {@link AfterSaleWriter} 完全一致：**先订单行，再退货单**。
 * 批准本身不改额度，仍然拿这把锁，是为了让"批准 / 驳回 / 新申请"三者
 * 只有一个共同排队点——否则就要分别证明三组两两之间不会死锁。
 *
 * <h2>事务边界</h2>
 *
 * 状态转换、额度释放、决策记录、事件写在**同一个事务**里：
 * 任何一步失败（包括额度不足以释放）都整笔回滚，
 * 不会留下"状态已改但额度没退"或"额度退了但没有决策记录"的中间态。
 *
 * <h2>重试口径</h2>
 *
 * 重复同向 / 相反决策一律 409，并且**在写任何东西之前**就返回：
 * 没有新事件、没有额度变化、没有第二条决策记录。
 * 客户端据此去读详情（详情里有决策人、时间、结果与原因）。
 *
 * <h2>不做什么</h2>
 *
 * 不碰 {@code shop_inventory_*}、不写退货入库流水、不产生任何退款。
 * 批准只代表"同意退"，实物还在买家手里。
 */
@Service
public class AfterSaleDecisionWriter {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleDecisionWriter.class);

    /** 重复批准。 */
    public static final String CODE_ALREADY_APPROVED = "ALREADY_APPROVED";

    /** 重复驳回。 */
    public static final String CODE_ALREADY_REJECTED = "ALREADY_REJECTED";

    /** 相反决策（已批准又要驳回，或反之）。 */
    public static final String CODE_DECISION_CONFLICT = "DECISION_CONFLICT";

    /** 驳回原因非法（为空或过长）。 */
    public static final String CODE_INVALID_REJECT_REASON = "INVALID_REJECT_REASON";

    /**
     * 额度与退货单对不上（本单要释放的数量超过当前已占用）。
     *
     * <p>正常路径走不到这里：状态是 SUBMITTED 就说明这笔额度还没被释放过。
     * 出现即说明有人绕过服务直接改了库，必须整笔失败而不是"能减多少减多少"。
     */
    public static final String CODE_QUOTA_INCONSISTENT = "RETURN_QUOTA_INCONSISTENT";

    private final AfterSaleRepository repository;

    private final ShopProperties properties;

    public AfterSaleDecisionWriter(AfterSaleRepository repository, ShopProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 批准：保留原额度。
     *
     * @param operatorId 客服身份（来自服务端身份上下文，**不来自请求体**）
     */
    @Transactional
    public AfterSaleReturn approve(String operatorId, String returnNo) {
        return decide(operatorId, returnNo, AfterSaleStatus.APPROVED, null);
    }

    /**
     * 驳回：释放本单申请数量。
     *
     * @param operatorId 客服身份（来自服务端身份上下文，**不来自请求体**）
     * @param reason     驳回原因（必填；与 {@code shop_aftersale_decision.reason} 同长度上限）
     */
    @Transactional
    public AfterSaleReturn reject(String operatorId, String returnNo, String reason) {
        String normalized = AfterSaleEligibility.normalizeReason(reason);
        if (normalized.isEmpty()) {
            throw new BusinessException(
                    400, CODE_INVALID_REJECT_REASON, "驳回原因必填");
        }
        int max = properties.getMaxRejectReasonLength();
        if (normalized.codePointCount(0, normalized.length()) > max) {
            throw new BusinessException(
                    400, CODE_INVALID_REJECT_REASON, "驳回原因最多 " + max + " 个字");
        }
        return decide(operatorId, returnNo, AfterSaleStatus.REJECTED, normalized);
    }

    private AfterSaleReturn decide(
            String operatorId,
            String returnNo,
            AfterSaleStatus target,
            String reason
    ) {
        // 1. 预检（不加锁）：先确认这单存在、且还没被决策，避免白拿一把行锁。
        AfterSaleReturn current = repository.findReturnByNo(returnNo)
                .orElseThrow(() -> new BusinessException(
                        404, "RETURN_NOT_FOUND", "退货申请不存在：" + returnNo));
        if (current.status().decided()) {
            throw conflict(current.status(), target);
        }

        // 2. 锁订单行：与 A1 新申请、另一个决策请求排队。
        repository.lockOrderItem(current.orderItemId())
                .orElseThrow(() -> new BusinessException(
                        409, "ORDER_ITEM_NOT_FOUND",
                        "原订单行已不存在，无法决策：" + returnNo));

        // 3. 拿到锁之后重读：状态与额度都可能已经变了。
        AfterSaleReturn fresh = repository.findReturnByNo(returnNo)
                .orElseThrow(() -> new BusinessException(
                        404, "RETURN_NOT_FOUND", "退货申请不存在：" + returnNo));
        if (fresh.status().decided()) {
            throw conflict(fresh.status(), target);
        }

        /*
         * 4. 驳回前先确认额度够释放。
         *
         * 这一步不替代后面的条件更新，而是把"数据不一致"变成一条
         * 说得清的错误：releaseQuota 返回 0 行时我们只能猜原因，
         * 在这里读一次就能明确报"本单要释放 2 件，但当前只占用 1 件"。
         */
        if (target == AfterSaleStatus.REJECTED) {
            AfterSaleQuota quota = repository.findQuota(fresh.orderItemId())
                    .orElseThrow(() -> new BusinessException(
                            409, CODE_QUOTA_INCONSISTENT,
                            "订单行没有额度记录，无法释放额度：" + returnNo));
            if (quota.occupied() < fresh.quantity()) {
                throw new BusinessException(
                        409, CODE_QUOTA_INCONSISTENT,
                        "额度与退货单不一致：本单需释放 " + fresh.quantity()
                                + " 件，当前仅占用 " + quota.occupied() + " 件");
            }
        }

        // 5. 条件状态转换：**唯一**的"赢得决策权"判据。
        int won = repository.transitionStatus(
                fresh.id(), AfterSaleStatus.SUBMITTED, target);
        if (won == 0) {
            AfterSaleReturn latest = repository.findReturnByNo(returnNo)
                    .orElseThrow(() -> new BusinessException(
                            404, "RETURN_NOT_FOUND", "退货申请不存在：" + returnNo));
            throw conflict(latest.status(), target);
        }

        // 6. 只有驳回释放额度，数量**从原退货单读**（不来自请求）。
        int releasedQty = 0;
        if (target == AfterSaleStatus.REJECTED) {
            releasedQty = fresh.quantity();
            int released = repository.releaseQuota(fresh.orderItemId(), releasedQty);
            if (released == 0) {
                throw new BusinessException(
                        409, CODE_QUOTA_INCONSISTENT,
                        "额度释放失败（剩余额度不足以减 " + releasedQty + " 件），本次决策已回滚");
            }
        }

        // 7. 决策记录（一单一决）。
        repository.insertDecision(fresh.id(), target, reason, operatorId);

        // 8. 事件：一个动作一个事件。
        String note = target == AfterSaleStatus.APPROVED
                ? "批准，保留已占用额度 " + fresh.quantity() + " 件"
                : "驳回，已释放可退额度 " + releasedQty + " 件";
        repository.insertEvent(fresh.id(), target.name(), operatorId, note);

        log.info("售后决策：returnNo={}, decision={}, operator={}, released={}",
                returnNo, target, operatorId, releasedQty);

        return repository.findReturnByNo(returnNo)
                .orElseThrow(() -> new IllegalStateException(
                        "决策写入后读不到退货单：" + returnNo));
    }

    /**
     * 已经决策过的单：区分"同向重复"与"相反决策"。
     *
     * <p>两者都是 409 且零写入，但原因码不同：同向重复是正常的用户误操作
     * （再点了一次），相反决策说明有人拿着过期的界面在操作，
     * 客户端可以用不同文案引导。
     */
    private static BusinessException conflict(AfterSaleStatus actual, AfterSaleStatus target) {
        String code = actual == target
                ? (target == AfterSaleStatus.APPROVED ? CODE_ALREADY_APPROVED : CODE_ALREADY_REJECTED)
                : CODE_DECISION_CONFLICT;
        String message = actual == target
                ? "这一单已经是「" + actual.label() + "」，重复操作不会产生新的决策或额度变化。"
                + "请以详情里的决策记录为准。"
                : "这一单已经是「" + actual.label() + "」，不能改成「" + target.label() + "」。"
                + "请以详情里的决策记录为准。";
        return new BusinessException(409, code, message);
    }
}
