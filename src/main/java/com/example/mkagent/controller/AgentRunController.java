package com.example.mkagent.controller;

import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.model.dto.AgentRunQueryDTO;
import com.example.mkagent.model.vo.AgentRunVO;
import com.example.mkagent.model.vo.PageResult;
import com.example.mkagent.service.AgentRunService;
import jakarta.annotation.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * AgentRun 任务管理接口：查询、历史分页、取消。
 *
 * 权限模型：
 * 1. 所有接口强制要求请求头 X-User-Id（占位用户身份），缺失返回 401；
 * 2. userId 一律取自服务端用户上下文，不由客户端传入，
 *    用户只能访问自己的任务；
 * 3. 项目当前没有管理员体系，因此不提供"查询所有任务"接口。
 *
 * 数据来源分工：
 * - 查询 / 分页：agent_run 数据库（历史 + 状态）；
 * - 取消：先做数据库归属校验，再复用 AgentTaskRegistry 内存注册表
 *   的实时取消能力（AgentRunService.cancelRun）。
 */
@RestController
@RequestMapping("/ai/runs")
public class AgentRunController {

    @Resource
    private AgentRunService agentRunService;

    /**
     * 查询当前用户的单个任务详情。
     *
     * 任务不存在或不属于当前用户时返回 404。
     */
    @GetMapping("/{runId}")
    public AgentRunVO getRun(@PathVariable String runId) {
        String userId = requireUserId();
        return agentRunService.getByRunIdForUser(runId, userId);
    }

    /**
     * 分页查询当前用户的任务历史。
     *
     * 支持筛选：
     * - state：任务状态（RUNNING / SUCCEEDED / FAILED / CANCELLED /
     *   TIMED_OUT / MAX_STEPS_REACHED）
     * - agentType：Agent 类型（CHAT / MANUS / FILE）
     * - startTime / endTime：创建时间范围（ISO-8601，
     *   例如 2026-08-24T00:00:00）
     *
     * 默认按创建时间倒序。
     */
    @GetMapping("/page")
    public PageResult<AgentRunVO> pageRuns(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String agentType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime startTime,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime endTime
    ) {
        String userId = requireUserId();

        AgentRunQueryDTO query = new AgentRunQueryDTO();
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);
        query.setUserId(userId);
        query.setState(state);
        query.setAgentType(agentType);
        query.setStartTime(startTime);
        query.setEndTime(endTime);

        return agentRunService.pageRuns(query);
    }

    /**
     * 取消当前用户的运行中任务。
     *
     * 复用 AgentTaskRegistry + AgentTaskCanceller 的实时取消逻辑：
     * 1. 数据库归属校验（不存在或不属于当前用户 → 404）；
     * 2. 数据库状态已是终态 → 409；
     * 3. 内存注册表中已不存在（任务刚结束）→ 409；
     * 4. 运行中 → 状态转换 + 中断线程 + SSE 通知。
     */
    @PostMapping("/{runId}/cancel")
    public Map<String, Object> cancelRun(@PathVariable String runId) {
        String userId = requireUserId();
        return agentRunService.cancelRun(runId, userId);
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
