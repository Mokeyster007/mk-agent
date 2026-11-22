package com.example.mkagent.shop.aftersale;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.ShopProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 售后服务：普通表单与售后 Agent **共用的唯一业务入口**。
 *
 * <h2>读与写的分工</h2>
 *
 * <ul>
 *   <li>本类负责"读 + 预览 + 表单幂等编排"；</li>
 *   <li>真正写库只在 {@link AfterSaleWriter}，且必须在一个事务里。</li>
 * </ul>
 *
 * 拆开的原因是幂等重放：同键并发时，输的那一方必须**整体回滚**，
 * 再换一个新事务去读赢家的结果。如果读写混在一个事务方法里，
 * 回滚之后就没有干净的事务可用了。
 *
 * <h2>表单幂等（与 Agent 分开）</h2>
 *
 * <pre>
 * 1. 先按键查记录：命中 → 校验 request_hash → 原样返回首次结果（不再判额度）
 * 2. 未命中 → 在事务里"插占位行 + 建单"，占位行是第一条写语句
 * 3. 并发同键 → 输家整体回滚，重新查记录并回放
 * </pre>
 *
 * 第 1 步"不再判额度"是关键：先前已成功的提交，
 * 不能因为此刻额度已被别的申请用光而被误判成失败。
 * 幂等回放回答的是"这次提交有没有成功过"，不是"现在还能不能退"。
 */
@Service
public class AfterSaleService {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleService.class);

    /** 同一个键被复用于不同参数。 */
    public static final String CODE_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

    private final AfterSaleRepository repository;

    private final AfterSaleWriter writer;

    private final ShopProperties properties;

    public AfterSaleService(
            AfterSaleRepository repository,
            AfterSaleWriter writer,
            ShopProperties properties
    ) {
        this.repository = repository;
        this.writer = writer;
        this.properties = properties;
    }

    // ---------------------------------------------------------------
    // 读
    // ---------------------------------------------------------------

    /**
     * 买家的已签收订单 + 每单当前额度。
     *
     * <p>过期订单**仍然返回**（{@code expired=true}），
     * 让页面能解释"为什么这单不能退了"，而不是让它凭空消失。
     */
    public List<SignedOrder> signedOrders(String buyerId) {
        LocalDateTime now = LocalDateTime.now();
        int windowDays = properties.getReturnWindowDays();

        List<SignedOrder> result = new ArrayList<>();
        for (ReturnSubject subject : repository.findSignedOrders(
                buyerId, properties.getAftersaleListLimit())) {
            AfterSaleQuota quota = subject.quota();
            LocalDateTime deadline = subject.signedAt() == null
                    ? null
                    : subject.signedAt().plusDays(windowDays);
            boolean expired = deadline == null || now.isAfter(deadline);

            result.add(new SignedOrder(
                    subject.orderNo(),
                    subject.sku(),
                    subject.productName(),
                    subject.unitPriceCents(),
                    quota,
                    subject.signedAt(),
                    deadline,
                    expired,
                    !expired && quota.hasAny()));
        }
        return result;
    }

    /** 我的退货申请（新的在前）。 */
    public List<AfterSaleReturn> myReturns(String buyerId) {
        return repository.findReturnsByBuyer(buyerId, properties.getAftersaleListLimit());
    }

    /**
     * 我的某个退货申请。
     *
     * <p>不是自己的返回空，由控制器转成 404——不区分"不存在"与"不是你的"。
     */
    public Optional<AfterSaleReturn> myReturn(String buyerId, String returnNo) {
        return repository.findReturnByNoAndBuyer(returnNo, buyerId);
    }

    /** 退货单的事件流（详情页的"处理记录"）。 */
    public List<AfterSaleEvent> events(long returnId) {
        return repository.findEvents(returnId);
    }

    /**
     * 某订单的资格判定事实（买家本人）。
     *
     * <p>给"订单详情"工具用：它需要回答"这单是什么状态、签收多久了、
     * 还能退几件"，而不只是"能不能退"。返回空表示订单不存在或不是本人。
     */
    public Optional<ReturnSubject> subject(String buyerId, String orderNo) {
        return repository.findSubject(orderNo, buyerId);
    }

    /** demo 退货窗口天数（工具与页面展示用，避免各处写死 7）。 */
    public int returnWindowDays() {
        return properties.getReturnWindowDays();
    }

    /** 某订单行上的全部申请（详情里解释额度占用）。 */
    public List<AfterSaleReturn> returnsOfOrderItem(long orderItemId) {
        return repository.findReturnsByOrderItem(orderItemId);
    }

    /**
     * 预览：不写库地跑一遍同样的判定。
     *
     * <p>订单不存在或不属于当前买家 → 404（与 A0 口径一致）。
     * 其余不可退原因（未签收 / 超窗口 / 额度不足 / 原因非法）
     * 返回 {@code eligible=false} 的结构化结果，而不是抛异常：
     * 页面要展示原因，模型也要据此向用户解释。
     */
    public AfterSalePreview preview(String buyerId, String orderNo, Integer quantity, String reason) {
        ReturnSubject subject = repository.findSubject(orderNo, buyerId)
                .orElseThrow(() -> new BusinessException(
                        404, AfterSaleEligibility.CODE_ORDER_NOT_FOUND,
                        "订单不存在或不属于当前买家：" + orderNo));

        String normalizedReason = AfterSaleEligibility.normalizeReason(reason);
        AfterSaleEligibility.Decision decision = AfterSaleEligibility.evaluate(
                subject,
                quantity,
                normalizedReason,
                LocalDateTime.now(),
                properties.getReturnWindowDays(),
                properties.getMaxReturnReasonLength());

        int qty = quantity == null || quantity < 0 ? 0 : quantity;
        long refundCents = decision.eligible()
                ? subject.unitPriceCents() * (long) qty
                : 0L;

        return new AfterSalePreview(
                subject.orderNo(),
                subject.orderItemId(),
                subject.sku(),
                subject.productName(),
                subject.unitPriceCents(),
                qty,
                refundCents,
                subject.quota(),
                decision.eligible(),
                decision.code(),
                decision.message());
    }

    // ---------------------------------------------------------------
    // 写：普通表单（带表单幂等）
    // ---------------------------------------------------------------

    /**
     * 普通表单提交。
     *
     * @param buyerId         服务端身份
     * @param orderNo         订单号
     * @param quantity        数量
     * @param reason          原因
     * @param clientRequestId 前端生成的提交标识（同一次提交期间不变）
     * @throws BusinessException 404 订单不存在 / 409 额度或窗口 / 400 参数 /
     *                           409 {@code IDEMPOTENCY_KEY_REUSED} 同键不同参数
     */
    public AfterSaleReturn submitForm(
            String buyerId,
            String orderNo,
            Integer quantity,
            String reason,
            String clientRequestId
    ) {
        String key;
        try {
            key = AfterSaleKeys.formKey(buyerId, clientRequestId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "INVALID_REQUEST_ID", e.getMessage());
        }

        String normalizedReason = AfterSaleEligibility.normalizeReason(reason);
        String requestHash = AfterSaleKeys.requestHash(
                orderNo, quantity == null ? -1 : quantity, normalizedReason);

        // 1. 先查记录：命中就直接回放，不再判额度（见类注释）。
        Optional<AfterSaleRepository.StoredIdempotency> existing = repository.findIdempotency(key);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, buyerId);
        }

        // 2. 未命中：在一个事务里"占位 + 建单"。
        try {
            return writer.createWithIdempotency(
                    buyerId, orderNo, quantity, normalizedReason,
                    AfterSaleSource.FORM, buyerId, key, requestHash);
        } catch (DuplicateKeyException e) {
            /*
             * 并发同键：另一个请求先插入了占位行并提交。
             * 我们的事务已整体回滚（占位行、额度、退货单都没留下），
             * 现在换一个新事务读赢家结果原样返回。
             */
            log.info("表单提交命中并发幂等键，回放首次结果：user={}", buyerId);
            AfterSaleRepository.StoredIdempotency winner = repository.findIdempotency(key)
                    .orElseThrow(() -> new BusinessException(
                            409,
                            "SUBMIT_CONFLICT",
                            "同一提交正在处理中，请稍后重试"));
            return replay(winner, requestHash, buyerId);
        }
    }

    /**
     * 回放首次结果。
     *
     * <p>哈希不一致说明客户端复用了同一个 {@code clientRequestId} 却改了参数，
     * 这是 bug，必须明确拒绝——静默返回上一单会让用户以为改动生效了。
     */
    private AfterSaleReturn replay(
            AfterSaleRepository.StoredIdempotency stored,
            String requestHash,
            String buyerId
    ) {
        if (!stored.requestHash().equals(requestHash)) {
            throw new BusinessException(
                    409,
                    CODE_KEY_REUSED,
                    "同一个提交标识被用于不同的退货参数，已拒绝执行");
        }

        return repository.findReturnByNoAndBuyer(stored.returnNo(), buyerId)
                .orElseThrow(() -> new BusinessException(
                        409,
                        "SUBMIT_CONFLICT",
                        "此前记录的退货单已不可访问，请重新提交"));
    }

    // ---------------------------------------------------------------
    // 写：Agent 写工具（事务由框架的幂等执行器提供）
    // ---------------------------------------------------------------

    /**
     * Agent 确认后建单。
     *
     * <p><b>不接收 buyerId 之外的任何"资格输入"</b>：
     * 数量与原因来自确认卡快照，归属、签收时间、已购数量、已占额度
     * 全部在事务内重新从数据库读——预览不是资格凭证。
     *
     * @param buyerId 服务端身份（由 AgentRunContext 冻结后传入，不来自模型参数）
     * @param orderNo 订单号（来自确认卡快照）
     * @param quantity 数量（来自确认卡快照）
     * @param reason 原因（来自确认卡快照）
     */
    public AfterSaleReturn createForAgent(
            String buyerId,
            String orderNo,
            Integer quantity,
            String reason
    ) {
        return writer.create(
                buyerId,
                orderNo,
                quantity,
                AfterSaleEligibility.normalizeReason(reason),
                AfterSaleSource.AGENT,
                buyerId);
    }

    /**
     * 已签收订单 + 额度（页面与工具共用的读模型）。
     *
     * @param orderNo        订单号
     * @param sku            SKU
     * @param productName    商品名
     * @param unitPriceCents 成交单价（分）
     * @param quota          额度
     * @param signedAt       签收时间
     * @param deadline       退货窗口截止时间
     * @param expired        是否已超窗口
     * @param returnable     现在能不能退（未过期且还有额度）
     */
    public record SignedOrder(
            String orderNo,
            String sku,
            String productName,
            long unitPriceCents,
            AfterSaleQuota quota,
            LocalDateTime signedAt,
            LocalDateTime deadline,
            boolean expired,
            boolean returnable
    ) {
    }
}
