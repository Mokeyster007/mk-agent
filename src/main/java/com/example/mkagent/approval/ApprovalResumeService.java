package com.example.mkagent.approval;

import com.example.mkagent.agent.BaseAgent;
import com.example.mkagent.agent.ToolCallAgent;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.model.AgentRunContext;
import com.example.mkagent.resilience.AgentConcurrencyGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审批恢复编排（批次 2）：把"已决定"变成"真的在跑"。
 *
 * 顺序是固定的，任何一步失败都要留下**明确、可重试或明确失败**的状态：
 *
 * <pre>
 * 归属校验 → 状态校验 → 启动期限 → 找 Agent → 检查点校验
 *        → 领取（CAS，epoch+1） → 拿并发许可 → 构建 ctx → 提交执行
 * </pre>
 *
 * 三条不可动摇的规则：
 * 1. **先校验后领取**：校验不过就不该产生 CLAIMED，否则要靠补偿来收拾；
 * 2. **拿不到许可不算失败**：这是"系统忙"，补偿回 PENDING_RESUME 让用户重试，
 *    不消耗"实际执行失败次数"，但受启动期限约束，不会无限等待；
 * 3. **不在事务里执行模型/工具**：领取是独立短事务，提交执行发生在事务之外。
 *
 * 本类不实现崩溃重放：进程在"领取成功、提交执行之前"退出时，
 * 记录会停在 CLAIMED，由批次 4 的扫描器按启动期限接手。
 */
@Service
public class ApprovalResumeService {

    private static final Logger log =
            LoggerFactory.getLogger(ApprovalResumeService.class);

    private final ApprovalClaimService claimService;

    private final ApprovalProperties properties;

    /**
     * 按 agentType 路由到实际执行者。
     *
     * 用 List 注入而不是按 Bean 名硬编码：
     * 审批记录里存的是 AgentType（MUSIC_USER / MUSIC_ADMIN / MANUS），
     * Bean 名是实现细节，改名不该让历史审批全部失效。
     */
    private final List<BaseAgent> agents;

    /**
     * 启动收尾门禁（批次 4B）。为 null 时不做入口限制（纯单测直接 new 的场景）。
     */
    private final RecoveryGate recoveryGate;

    public ApprovalResumeService(
            ApprovalClaimService claimService,
            ApprovalProperties properties,
            List<BaseAgent> agents
    ) {
        this(claimService, properties, agents, null);
    }

    @Autowired
    public ApprovalResumeService(
            ApprovalClaimService claimService,
            ApprovalProperties properties,
            List<BaseAgent> agents,
            RecoveryGate recoveryGate
    ) {
        this.claimService = claimService;
        this.properties = properties;
        this.agents = agents == null ? List.of() : agents;
        this.recoveryGate = recoveryGate;
    }

    /**
     * 查询一条审批（请求级归属校验：不是自己的直接 404）。
     */
    public ApprovalView view(String approvalId, String userId) {
        ApprovalRecord record = requireOwned(approvalId, userId);
        return ApprovalView.of(record, claimService.findSummaryJson(approvalId));
    }

    /**
     * 恢复执行：返回本次续流的 SSE。
     *
     * 校验失败与并发不足都抛 {@link BusinessException}（带稳定原因码），
     * 此时**不会**有任何工具被执行，也不会产生半截领取。
     */
    public SseEmitter resume(String approvalId, String userId) {
        /*
         * 门禁最先判断：启动收尾期间不能领取、不能建 ctx、不能提交执行。
         * 放在归属校验之前是有意的——收尾期间连"这条审批能不能恢复"都不该被判定，
         * 否则会基于一个正在被收尾修改的快照给用户一个会过期的结论。
         */
        if (recoveryGate != null) {
            recoveryGate.requireOpen();
        }

        ApprovalRecord record = requireOwned(approvalId, userId);

        requireResumable(record);

        String runId = record.runId();

        /*
         * 启动期限：决定之后必须在期限内真正跑起来。
         * 抢不到许可 / 执行器持续拒绝都属于"还没跑起来"，
         * 过了期限就明确失败，避免任务永远挂在 APPROVED。
         */
        if (startDeadlineExceeded(record)) {
            claimService.fail(record.approvalId(), runId,
                    ApprovalRecord.STATUS_APPROVED.equals(record.status())
                            ? ApprovalRecord.STATUS_APPROVED
                            : ApprovalRecord.STATUS_REJECTED,
                    "APPROVAL_RESUME_TIMEOUT",
                    "审批已决定但未在期限内启动，任务未执行");
            throw new BusinessException(410, "APPROVAL_RESUME_TIMEOUT",
                    "审批已超过启动期限，任务已终止");
        }

        BaseAgent agent = resolveAgent(record.agentType());
        if (agent == null) {
            /*
             * 找不到执行者属于部署问题，不是数据损坏：
             * 不改审批状态，修好部署后可以重试。
             */
            throw new BusinessException(503, "AGENT_UNAVAILABLE",
                    "审批对应的 Agent 当前不可用：" + record.agentType());
        }

        if (!(agent instanceof ToolCallAgent toolCallAgent)) {
            claimService.fail(record.approvalId(), runId,
                    ApprovalRecord.STATUS_INVALIDATED,
                    "CHECKPOINT_UNSUPPORTED_AGENT",
                    "审批记录无法恢复到该 Agent 类型");
            throw new BusinessException(409, "CHECKPOINT_UNSUPPORTED_AGENT",
                    "审批记录无法恢复到该 Agent 类型");
        }

        /*
         * 检查点校验：结构、归属、schema、配置指纹、白名单、整轮参数哈希。
         * 任何一项不通过都失败关闭——宁可让用户重做一次请求，
         * 也不能"批准了 A 却执行了 B"。
         */
        String payload = claimService.findCheckpoint(runId);
        CheckpointValidator.Result validation = CheckpointValidator.validate(
                record,
                payload,
                toolCallAgent.currentConfigVersion(),
                toolCallAgent.currentWhitelistHash()
        );

        if (!validation.ok()) {
            claimService.fail(record.approvalId(), runId,
                    ApprovalRecord.STATUS_INVALIDATED,
                    validation.code(),
                    "审批检查点校验未通过，任务未执行");
            throw new BusinessException(409, validation.code(),
                    "审批检查点校验未通过，任务已终止：" + validation.code());
        }

        ApprovalCheckpoint checkpoint;
        try {
            checkpoint = CheckpointCodec.decode(payload);
        } catch (RuntimeException e) {
            // validate 已经解过一次，这里失败只可能是并发改动，同样失败关闭
            claimService.fail(record.approvalId(), runId,
                    ApprovalRecord.STATUS_INVALIDATED,
                    "CHECKPOINT_CORRUPT",
                    "审批检查点无法解析，任务未执行");
            throw new BusinessException(409, "CHECKPOINT_CORRUPT",
                    "审批检查点无法解析，任务已终止");
        }

        /*
         * 领取：approval PENDING_RESUME → CLAIMED、run WAITING_APPROVAL → RUNNING，
         * 代次 +1。两条 CAS 在同一事务，输的一方拿到 0 行并抛异常。
         */
        int epoch;
        try {
            epoch = claimService.claim(record);
        } catch (IllegalStateException e) {
            /*
             * 并发恢复：另一个请求先领取成功。
             * 这不是错误，如实返回"正在执行"即可，数据库无需回滚（本来就没改）。
             */
            throw new BusinessException(409, "RESUME_IN_PROGRESS",
                    "该审批正在恢复执行，请勿重复提交");
        }

        /*
         * 并发许可：拿不到就补偿回可重试状态。
         * 不计入失败次数、不改审批决定——用户已经批准了，
         * 不能因为系统忙就把这次批准作废。
         */
        AgentConcurrencyGuard guard = agent.getConcurrencyGuard();
        if (guard != null && !guard.tryAcquire()) {
            compensateQuietly(record.approvalId(), runId, epoch, "无并发许可");
            throw new BusinessException(429, "RESUME_CONCURRENCY_LIMIT",
                    "系统繁忙，请稍后重试");
        }

        AgentRunContext ctx;
        try {
            ctx = CheckpointRestorer.restore(record, checkpoint, epoch);
        } catch (RuntimeException e) {
            /*
             * ctx 构建失败（runId 非法、历史反序列化异常等）：
             * 许可必须还回去，数据库补偿回可重试状态。
             */
            releasePermit(guard);
            compensateQuietly(record.approvalId(), runId, epoch, "上下文构建失败");
            throw new BusinessException(409, "RESUME_CONTEXT_FAILED",
                    "恢复上下文构建失败，任务未执行");
        }

        SseEmitter emitter = agent.createResumeEmitter(ctx);

        try {
            /*
             * BaseAgent.resume 只做两件事：登记句柄 → 提交执行器。
             * 启动标记（CLAIMED→RUNNING）由任务体第一条语句完成，
             * 因此这里返回成功只代表"进了队列"，不代表"开始执行"。
             * 登记失败/线程池拒绝会先释放许可再抛出，异步体一行都没跑，
             * 下面的补偿因此是安全的。
             */
            return agent.resume(ctx, emitter);
        } catch (RuntimeException e) {
            /*
             * 执行器拒绝 / 登记失败：异步体一行都没跑，
             * 补偿回 PENDING_RESUME + WAITING_APPROVAL，用户可以直接重试。
             *
             * 补偿本身失败时不能吞掉原始错误：记日志后仍然返回
             * RESUME_SUBMIT_FAILED——此时记录停在 CLAIMED，
             * 由后续恢复机制按启动期限收尾，不在这里伪造结果。
             */
            compensateQuietly(record.approvalId(), runId, epoch, "提交执行失败");
            log.warn("审批恢复提交失败，已补偿：approvalId={}, runId={}, epoch={}",
                    record.approvalId(), runId, epoch, e);
            throw new BusinessException(409, "RESUME_SUBMIT_FAILED",
                    "恢复提交失败，请重试");
        }
    }

    // ==================== 内部 ====================

    /**
     * 归属校验 + 存在性：一律 404，不区分"不存在"与"不是你的"。
     *
     * 注意这里**只读**：绝不会因为别人发来跨用户请求就改动审批状态。
     */
    private ApprovalRecord requireOwned(String approvalId, String userId) {
        ApprovalRecord record = claimService.find(approvalId);
        if (record == null || userId == null || !record.userId().equals(userId)) {
            throw new BusinessException(404, "APPROVAL_NOT_FOUND",
                    "审批不存在或无权访问：" + approvalId);
        }
        return record;
    }

    /**
     * 只有"已决定且尚未跑完"的审批可以恢复。
     */
    private void requireResumable(ApprovalRecord record) {
        if (!record.isDecided()) {
            throw new BusinessException(409, "APPROVAL_NOT_DECIDED",
                    "审批尚未做出决定，当前状态：" + record.status());
        }

        String resumeState = record.resumeState();

        if (ApprovalRecord.RESUME_FINISHED.equals(resumeState)) {
            throw new BusinessException(409, "APPROVAL_ALREADY_FINISHED",
                    "该审批的执行阶段已结束");
        }

        if (ApprovalRecord.RESUME_FAILED.equals(resumeState)) {
            throw new BusinessException(409, "APPROVAL_FAILED",
                    "该审批已失败，无法恢复");
        }

        if (ApprovalRecord.RESUME_CLAIMED.equals(resumeState)
                || ApprovalRecord.RESUME_RUNNING.equals(resumeState)) {
            /*
             * 已有领取者：
             * - 句柄还在 → 明确拒绝重复启动，绝不并行跑第二份；
             * - 句柄不在（进程在领取后退出）→ 只有过了启动期限才收尾成失败，
             *   期限内的重领留给批次 4 的扫描器，这里不猜。
             */
            if (hasLiveHandle(record)) {
                throw new BusinessException(409, "RESUME_IN_PROGRESS",
                        "该审批正在执行，请勿重复提交");
            }
            throw new BusinessException(409, "RESUME_IN_PROGRESS",
                    "该审批已被领取，等待启动完成或超时");
        }
    }

    private boolean hasLiveHandle(ApprovalRecord record) {
        for (BaseAgent agent : agents) {
            if (agent.getTaskRegistry() == null) {
                continue;
            }
            if (agent.getTaskRegistry().get(record.runId()) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean startDeadlineExceeded(ApprovalRecord record) {
        if (record.decidedAt() == null) {
            return false;
        }
        LocalDateTime deadline =
                record.decidedAt().plus(properties.getResumeStartTimeout());
        return deadline.isBefore(LocalDateTime.now());
    }

    private BaseAgent resolveAgent(String agentType) {
        if (agentType == null) {
            return null;
        }
        for (BaseAgent agent : agents) {
            if (agent.agentType() != null
                    && agentType.equals(agent.agentType().name())) {
                return agent;
            }
        }
        return null;
    }

    /**
     * 释放已获取的并发许可（ctx 尚未建立时的路径）。
     *
     * 这里不能走 BaseAgent.releaseConcurrencyPermit：那条路径依赖 ctx 上的
     * release-once 标记，而此刻 ctx 还不存在。
     */
    private void releasePermit(AgentConcurrencyGuard guard) {
        if (guard != null) {
            guard.release();
        }
    }

    /**
     * 补偿：回退到"可重试"，失败也不掩盖原始错误。
     *
     * 补偿本身可能因为"任务状态已变化"而整体回滚（见 ApprovalClaimService），
     * 此时记录停在 CLAIMED、run 停在 RUNNING——这是**已知的、留给后续恢复机制**
     * 的状态，不能在这里再补一次写，也不能把用户看到的错误改成补偿异常。
     */
    private void compensateQuietly(
            String approvalId, String runId, int epoch, String reason) {
        try {
            claimService.compensate(approvalId, runId, epoch);
        } catch (RuntimeException e) {
            log.error("审批补偿失败，记录保留给后续恢复机制：approvalId={}, runId={}, epoch={}, reason={}",
                    approvalId, runId, epoch, reason, e);
        }
    }
}
