package com.example.mkagent.controller;

import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.shop.ShopAccessGuard;
import com.example.mkagent.shop.ShopProperties;
import com.example.mkagent.shop.aftersale.AfterSaleDecisionWriter;
import com.example.mkagent.shop.aftersale.AfterSaleReturn;
import com.example.mkagent.shop.aftersale.AfterSaleService;
import com.example.mkagent.shop.aftersale.AfterSaleStatus;
import com.example.mkagent.shop.api.AfterSaleViews;
import com.example.mkagent.shop.api.AfterSaleViews.ReturnVO;
import com.example.mkagent.shop.api.ShopViews.PageVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 客服售后接口（B1）：按状态看退货申请、批准、驳回。
 *
 * <h2>权限</h2>
 *
 * 每个方法的第一行都是 {@link ShopAccessGuard#requireAdmin()}：
 * <ol>
 *   <li>缺身份 → 401；</li>
 *   <li>demo 身份关闭且没有可信身份 → 403 {@code SHOP_IDENTITY_NOT_CONFIGURED}
 *       （这一步先于角色判断，**关闭 demo 时后台私有读写一律不放行**）；</li>
 *   <li>不是 ADMIN → 403 {@code NOT_ADMIN}（普通买家因此用不了这些接口）。</li>
 * </ol>
 *
 * <p><b>操作人只来自服务端身份上下文</b>：{@code decided_by} 取
 * {@code requireAdmin()} 的返回值。请求体里没有、也不接受 {@code operatorId} /
 * {@code role} 字段——多一个这样的字段，就等于把"谁批的"交给客户端填。
 *
 * <h2>客服能处理哪些单</h2>
 *
 * 单店 demo：**全部**。这里不做买家归属过滤，因为客服就是这家店的处理人；
 * 把买家所有权规则套到客服身上会得出"客服只能处理自己的申请"这种无意义结论。
 * 多租户隔离属于店铺维度，不在本轮。
 *
 * <h2>重复与冲突</h2>
 *
 * 重复同向（再批一次）→ 409 {@code ALREADY_APPROVED} / {@code ALREADY_REJECTED}；
 * 相反决策 → 409 {@code DECISION_CONFLICT}。两者都**零写入**：
 * 没有新事件、没有额度变化、没有第二条决策记录。客户端据此读详情核对原决策。
 *
 * <h2>不做什么</h2>
 *
 * 不改可售库存、不写退货入库流水、不产生任何退款。
 * 批准只代表"同意退"，实物仍在买家手里（仓库验收是 B2）。
 */
@RestController
@RequestMapping("/shop/admin/aftersale")
public class ShopAfterSaleAdminController {

    /** 列表单页上限：防止一个请求把整张售后表拉出来。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final ShopAccessGuard accessGuard;

    private final AfterSaleService afterSaleService;

    private final ShopProperties properties;

    public ShopAfterSaleAdminController(
            ShopAccessGuard accessGuard,
            AfterSaleService afterSaleService,
            ShopProperties properties
    ) {
        this.accessGuard = accessGuard;
        this.afterSaleService = afterSaleService;
        this.properties = properties;
    }

    /**
     * 驳回请求体。
     *
     * <p>只有一个字段。没有 {@code operatorId}、没有 {@code decision}：
     * 决策结果由调用的路径决定（approve / reject 是两个入口），
     * 操作人由服务端身份决定。
     */
    public record RejectRequest(String reason) {
    }

    /**
     * 按状态分页查看退货申请。
     *
     * @param status SUBMITTED / APPROVED / REJECTED；留空表示全部
     * @param page   从 1 开始（小于 1 按 1 处理，不报错）
     * @param size   页大小（夹紧到 1..100，不报错）
     */
    @GetMapping("/returns")
    public PageVO<ReturnVO> returns(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "0") int size
    ) {
        accessGuard.requireAdmin();

        String normalized = normalizeStatus(status);

        int safePage = Math.max(1, page);
        int defaultSize = properties.getAdminPageSize() > 0 ? properties.getAdminPageSize() : 20;
        int safeSize = size <= 0 ? defaultSize : Math.min(size, MAX_PAGE_SIZE);

        List<ReturnVO> items = afterSaleService
                .adminReturns(normalized, safeSize, (safePage - 1) * safeSize)
                .stream()
                .map(AfterSaleViews::summary)
                .toList();

        return new PageVO<>(items, safePage, safeSize, afterSaleService.adminReturnCount(normalized));
    }

    /** 任意退货申请详情（含事件流与决策记录）。 */
    @GetMapping("/returns/{returnNo}")
    public ReturnVO detail(@PathVariable String returnNo) {
        accessGuard.requireAdmin();

        AfterSaleReturn value = requireReturn(returnNo);

        return AfterSaleViews.detail(
                value,
                afterSaleService.events(value.id()),
                afterSaleService.decision(value.id()).orElse(null));
    }

    /**
     * 批准：保留原额度（那部分数量仍然不能再被申请）。
     *
     * <p>返回决策后的完整详情，让页面不必再发一次 GET 就能刷新。
     */
    @PostMapping("/returns/{returnNo}/approve")
    public ReturnVO approve(@PathVariable String returnNo) {
        String operatorId = accessGuard.requireAdmin();

        return detailOf(afterSaleService.approve(operatorId, returnNo));
    }

    /**
     * 驳回：释放本单申请数量（同一事务内），并记录原因。
     *
     * <p>原因必填——"为什么驳回"是买家唯一能拿到的解释，
     * 留空等于让他面对一个无法申诉的结果。
     */
    @PostMapping("/returns/{returnNo}/reject")
    public ReturnVO reject(
            @PathVariable String returnNo,
            @RequestBody(required = false) RejectRequest request
    ) {
        String operatorId = accessGuard.requireAdmin();

        if (request == null) {
            throw new BusinessException(
                    400, AfterSaleDecisionWriter.CODE_INVALID_REJECT_REASON,
                    "驳回原因必填");
        }

        return detailOf(afterSaleService.reject(operatorId, returnNo, request.reason()));
    }

    private ReturnVO detailOf(AfterSaleReturn value) {
        return AfterSaleViews.detail(
                value,
                afterSaleService.events(value.id()),
                afterSaleService.decision(value.id()).orElse(null));
    }

    private AfterSaleReturn requireReturn(String returnNo) {
        if (returnNo == null || returnNo.isBlank()) {
            throw new BusinessException(400, "INVALID_RETURN_NO", "退货单号不能为空");
        }
        return afterSaleService.adminReturn(returnNo)
                .orElseThrow(() -> new BusinessException(
                        404, "RETURN_NOT_FOUND", "退货申请不存在：" + returnNo));
    }

    /**
     * 状态筛选值校验。
     *
     * <p>不认识的筛选值直接 400，而不是当成"全部"：
     * 静默忽略会让人以为筛选生效了，看到的却是全量。
     */
    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        AfterSaleStatus parsed;
        try {
            parsed = AfterSaleStatus.parse(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    400, "INVALID_STATUS", "未知的售后状态：" + status);
        }
        return parsed.name();
    }
}
