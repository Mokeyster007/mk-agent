package com.example.mkagent.shop.aftersale;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.ShopProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 退货单的**唯一**写入点。
 *
 * <h2>为什么写操作只能从这里进</h2>
 *
 * 普通表单和 Agent 写工具都必须经过这里：
 * 两条路径的差别只有"谁发起"（{@link AfterSaleSource}）与"幂等身份怎么来"，
 * 资格校验、额度占用、建单、写事件必须完全一致。
 * 一旦某条路径另写一份，就会出现"表单退不了、Agent 能退"这种最糟糕的规则分裂。
 *
 * <h2>事务边界（这是本轮最关键的一段）</h2>
 *
 * <pre>
 * TX:
 *   1. 锁订单行（SELECT ... FOR UPDATE）        ← 同一订单行的并发申请在此排队
 *   2. 拿到锁之后**重算**额度（不信任锁之前的读数）
 *   3. 原子占用额度（reserved_qty + ? <= quantity_total）  ← 0 行 = 额度不足
 *   4. 插入退货单
 *   5. 插入 SUBMITTED 事件
 * COMMIT
 * </pre>
 *
 * 四件事同生共死：提交了就一定"额度已占 + 单已建 + 事件已写"，
 * 回滚了就一定"额度没占 + 没有单 + 没有事件"。
 * 这就是"占用后建单失败必须回滚"的实现方式——不是补偿，是同一个事务。
 *
 * <h2>为什么锁之后还要重算</h2>
 *
 * 锁之前的读数可能已经过期：另一笔申请可能刚好在"读"与"锁"之间提交并占用了额度。
 * 只有在**持有锁**的状态下重新读一次额度，判定才对应"真正要写入的那一刻"。
 * 这也正是"确认卡不是资格凭证"的落点：确认之后执行时，同样走这一遍。
 *
 * <h2>不动库存</h2>
 *
 * 这里没有任何一行碰 {@code shop_inventory_*}：
 * 退货申请只是"买家说想退"，实物还在买家手里。
 * 库存只在 B 段仓库验货合格后才增加（{@code RETURN_INBOUND}）。
 */
@Service
public class AfterSaleWriter {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleWriter.class);

    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AfterSaleRepository repository;

    private final ShopProperties properties;

    public AfterSaleWriter(AfterSaleRepository repository, ShopProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 普通表单的建单入口：先落幂等占位行，再走同一套建单逻辑。
     *
     * <p>占位行必须是**第一条写语句**（PostgreSQL 唯一键冲突会 abort 整个事务）。
     * 建单失败（额度不足、参数非法）时整个事务回滚，
     * 占位行随之消失——失败不会被记成"已经成功过"。
     *
     * @throws org.springframework.dao.DuplicateKeyException 同一幂等键已存在
     */
    @Transactional
    public AfterSaleReturn createWithIdempotency(
            String buyerId,
            String orderNo,
            Integer quantity,
            String reason,
            AfterSaleSource source,
            String actorId,
            String idempotencyKey,
            String requestHash
    ) {
        String returnNo = newReturnNo();
        repository.insertIdempotency(idempotencyKey, requestHash, returnNo);

        return doCreate(buyerId, orderNo, quantity, reason, source, actorId, returnNo);
    }

    /**
     * Agent 写工具的建单入口（由框架的写工具幂等执行器提供事务）。
     *
     * <p>{@link Propagation#MANDATORY} 是刻意的：
     * "在事务外建退货单"会变成运行时错误，而不是一条靠人记住的约定。
     * 没有事务就做不到"额度与单同生共死"。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AfterSaleReturn create(
            String buyerId,
            String orderNo,
            Integer quantity,
            String reason,
            AfterSaleSource source,
            String actorId
    ) {
        return doCreate(buyerId, orderNo, quantity, reason, source, actorId, newReturnNo());
    }

    private AfterSaleReturn doCreate(
            String buyerId,
            String orderNo,
            Integer quantity,
            String reason,
            AfterSaleSource source,
            String actorId,
            String returnNo
    ) {
        String normalizedReason = AfterSaleEligibility.normalizeReason(reason);

        // 1. 先做一次"锁之前"的判定：订单是否存在、是否本人、是否签收、是否超窗口。
        //    这一步不写库，失败时立刻返回，不浪费一次行锁。
        ReturnSubject subject = repository.findSubject(orderNo, buyerId).orElse(null);
        AfterSaleEligibility.Decision precheck = AfterSaleEligibility.evaluate(
                subject,
                quantity,
                normalizedReason,
                LocalDateTime.now(),
                properties.getReturnWindowDays(),
                properties.getMaxReturnReasonLength());
        if (!precheck.eligible()) {
            throw toException(precheck);
        }

        // 2. 锁订单行：同一订单行的并发申请从这里开始排队。
        AfterSaleRepository.LockedItem locked = repository.lockOrderItem(subject.orderItemId())
                .orElseThrow(() -> new BusinessException(
                        404, "ORDER_ITEM_NOT_FOUND", "订单行不存在：" + subject.orderNo()));

        // 3. 锁之后重算：额度可能已经被另一笔申请占用。
        repository.ensureQuota(locked.id(), locked.quantity());
        AfterSaleQuota freshQuota = repository.findQuota(locked.id())
                .orElseThrow(() -> new IllegalStateException(
                        "订单行 " + locked.id() + " 的额度行在事务内不存在"));

        ReturnSubject refreshed = new ReturnSubject(
                subject.orderNo(),
                subject.orderId(),
                subject.buyerId(),
                subject.status(),
                subject.signedAt(),
                locked.id(),
                subject.sku(),
                subject.productName(),
                subject.unitPriceCents(),
                locked.quantity(),
                freshQuota.occupied());

        AfterSaleEligibility.Decision decision = AfterSaleEligibility.evaluate(
                refreshed,
                quantity,
                normalizedReason,
                LocalDateTime.now(),
                properties.getReturnWindowDays(),
                properties.getMaxReturnReasonLength());
        if (!decision.eligible()) {
            throw toException(decision);
        }

        // 4. 原子占用额度：条件更新，0 行即额度不足（理论上已被上一步挡住，这里是兜底）。
        int reserved = repository.reserveQuota(locked.id(), quantity);
        if (reserved == 0) {
            throw new BusinessException(
                    409,
                    AfterSaleEligibility.CODE_RETURN_QUOTA_EXCEEDED,
                    "可退额度不足，请刷新后按最新额度重新申请");
        }

        // 5. 金额：整数分，溢出即拒绝（不接受一个算错的金额）。
        long refundCents;
        try {
            refundCents = Math.multiplyExact(subject.unitPriceCents(), (long) quantity);
        } catch (ArithmeticException e) {
            throw new BusinessException(400, "AMOUNT_OVERFLOW", "退款金额超出可表示范围");
        }

        long returnId = repository.insertReturn(
                returnNo,
                subject.orderId(),
                locked.id(),
                buyerId,
                subject.sku(),
                subject.productName(),
                subject.unitPriceCents(),
                quantity,
                refundCents,
                normalizedReason,
                AfterSaleStatus.SUBMITTED,
                source);

        repository.insertEvent(
                returnId,
                AfterSaleEvent.TYPE_SUBMITTED,
                actorId,
                source == AfterSaleSource.AGENT ? "经对话确认后提交" : "买家在售后页面提交");

        log.info("售后提交：returnNo={}, orderNo={}, qty={}, source={}, actor={}",
                returnNo, orderNo, quantity, source, actorId);

        return repository.findReturnByNo(returnNo)
                .orElseThrow(() -> new IllegalStateException("退货单写入后读不到：" + returnNo));
    }

    /**
     * 判定结果 → HTTP 异常。
     *
     * <p>状态码按语义分：找不到 404，状态/额度/窗口冲突 409，参数问题 400。
     * 前端只认 {@code code}，不解析中文文案。
     */
    private static BusinessException toException(AfterSaleEligibility.Decision decision) {
        int status = switch (decision.code()) {
            case AfterSaleEligibility.CODE_ORDER_NOT_FOUND -> 404;
            case AfterSaleEligibility.CODE_INVALID_QUANTITY,
                 AfterSaleEligibility.CODE_INVALID_REASON -> 400;
            default -> 409;
        };
        return new BusinessException(status, decision.code(), decision.message());
    }

    /**
     * 生成对外退货单号：{@code RT + yyyyMMddHHmmss + 4 位十六进制}。
     *
     * <p>与订单号同构：不暴露自增 id，可读可排序，冲突由唯一索引兜底。
     */
    static String newReturnNo() {
        String stamp = LocalDateTime.now().format(NO_FORMAT);
        return "RT" + stamp + "-" + String.format("%04X", RANDOM.nextInt(0x10000));
    }
}
