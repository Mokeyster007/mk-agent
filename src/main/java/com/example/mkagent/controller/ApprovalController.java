package com.example.mkagent.controller;

import com.example.mkagent.approval.ApprovalDecisionService;
import com.example.mkagent.approval.ApprovalDecisionService.DecisionResult;
import com.example.mkagent.approval.ApprovalResumeService;
import com.example.mkagent.approval.ApprovalView;
import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.exception.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


/**
 * 审批接口（M3 批次 2）：approve / reject / resume / 查询。
 *
 * 权限模型与任务接口一致：userId 只取自服务端用户上下文，
 * 不接受请求体、查询参数或路径里的用户标识。
 * 不是自己的审批一律 404——不区分"不存在"与"不是你的"，
 * 避免用审批 ID 探测别人的任务。
 *
 * 为什么决定接口是 POST 且**只接受 approvalId**：
 * 审批绑定的是"暂停那一刻的整轮工具与参数"（args_hash），
 * 一旦允许客户端在决定时再传工具名或参数，就等于绕过了哈希校验，
 * 可以"批准 A、执行 B"。因此这里不读请求体。
 */
@RestController
@RequestMapping("/ai/approvals")
public class ApprovalController {

    private final ApprovalDecisionService decisionService;

    private final ApprovalResumeService resumeService;

    public ApprovalController(
            ApprovalDecisionService decisionService,
            ApprovalResumeService resumeService
    ) {
        this.decisionService = decisionService;
        this.resumeService = resumeService;
    }

    /**
     * 批准：落决定 → 尝试恢复 → 直接返回这次执行的续流。
     *
     * 一次 HTTP 调用完成"决定 + 执行"，这是用户点"批准"时应有的语义。
     * 但内部仍是两个职责：决定是持久化事实，恢复是领取执行权；
     * 因此"决定已落库、恢复没起来"是一个可重试的稳定状态，
     * 由 {@code POST /{approvalId}/resume} 接续。
     *
     * 决定保存之后才失败（无并发许可 / 执行器拒绝 / 启动期限已过）时：
     * 决定**不撤销**，返回带稳定 code 的错误，客户端可重试 /resume。
     */
    @PostMapping("/{approvalId}/approve")
    public SseEmitter approve(@PathVariable String approvalId) {
        return decideAndResume(approvalId, true);
    }

    /**
     * 拒绝：落决定 → 回填拒绝回执并继续模型轮 → 返回续流。
     *
     * 拒绝同样不执行任何业务工具，只把"用户拒绝"作为工具结果回填，
     * 让模型带着这个结果继续给出最终回答。
     */
    @PostMapping("/{approvalId}/reject")
    public SseEmitter reject(@PathVariable String approvalId) {
        return decideAndResume(approvalId, false);
    }

    /**
     * 决定 + 恢复：决定失败按原状态码抛出；决定成功但启动失败时
     * 保留决定并透传恢复失败的原因码。
     */
    private SseEmitter decideAndResume(String approvalId, boolean approve) {
        String userId = requireUserId();

        DecisionResult result = decisionService.decide(approvalId, userId, approve);
        if (!result.ok()) {
            throw new BusinessException(result.httpStatus(), result.code(),
                    messageOf(result.code()));
        }

        try {
            return resumeService.resume(approvalId, userId);
        } catch (BusinessException e) {
            /*
             * 绝不因为启动失败回滚决定：回滚会让"用户已经批准"这个事实消失，
             * 而业务工具一次都没执行。保留决定 + 明确错误码，让 /resume 可以重试。
             */
            throw new BusinessException(e.getHttpStatus(), e.getCode(),
                    e.getMessage() + "（决定已保存，可重试恢复）");
        }
    }

    /**
     * 恢复执行，返回续流 SSE。
     *
     * 断流不会取消任务：后台继续跑，结果以 agent_run 查询为准。
     */
    @PostMapping("/{approvalId}/resume")
    public SseEmitter resume(@PathVariable String approvalId) {
        return resumeService.resume(approvalId, requireUserId());
    }

    /**
     * 查询审批状态与脱敏摘要（不含检查点原文）。
     */
    @GetMapping("/{approvalId}")
    public ApprovalView get(@PathVariable String approvalId) {
        return resumeService.view(approvalId, requireUserId());
    }

    /**
     * 原因码 → 中文提示。文案集中在这里，避免各处拼不同说法。
     */
    private String messageOf(String code) {
        return switch (code) {
            case "APPROVAL_NOT_FOUND" -> "审批不存在或无权访问";
            case "APPROVAL_EXPIRED" -> "审批已过期";
            case "ALREADY_APPROVED" -> "该审批已经批准过";
            case "ALREADY_REJECTED" -> "该审批已经拒绝过";
            case "APPROVAL_NOT_PENDING" -> "审批已不在待确认状态";
            default -> "审批操作失败：" + code;
        };
    }

    /**
     * 要求当前请求携带用户身份，否则 401。
     */
    private String requireUserId() {
        String userId = UserContextHolder.get();
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(
                    401,
                    "缺少用户身份，请在请求头中携带 "
                            + UserContextHolder.USER_ID_HEADER
            );
        }
        return userId;
    }
}
