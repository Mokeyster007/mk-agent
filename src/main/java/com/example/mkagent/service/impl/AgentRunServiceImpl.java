package com.example.mkagent.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.mkagent.agent.AgentTaskCanceller;
import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.entity.AgentRunEntity;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.mapper.AgentRunMapper;
import com.example.mkagent.model.AgentState;
import com.example.mkagent.model.RunningAgentTask;
import com.example.mkagent.model.dto.AgentRunQueryDTO;
import com.example.mkagent.model.vo.AgentRunVO;
import com.example.mkagent.model.vo.PageResult;
import com.example.mkagent.service.AgentRunService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * AgentRun 任务记录服务实现。
 *
 * 权限模型：
 * 所有查询与取消都强制带 user_id 条件（来自服务端用户上下文，
 * 不由客户端传入），用户只能访问自己的任务。
 * 项目当前没有管理员体系，因此不实现"查询所有任务"的能力。
 */
@Service
public class AgentRunServiceImpl
        extends ServiceImpl<AgentRunMapper, AgentRunEntity>
        implements AgentRunService {

    /**
     * 每页条数上限，防止恶意大分页拖垮数据库。
     */
    private static final long MAX_PAGE_SIZE = 100;

    private final AgentTaskRegistry agentTaskRegistry;

    private final AgentTaskCanceller agentTaskCanceller;

    public AgentRunServiceImpl(
            AgentTaskRegistry agentTaskRegistry,
            AgentTaskCanceller agentTaskCanceller
    ) {
        this.agentTaskRegistry = agentTaskRegistry;
        this.agentTaskCanceller = agentTaskCanceller;
    }

    @Override
    public AgentRunVO getByRunIdForUser(String runId, String userId) {
        AgentRunEntity entity = findByRunIdAndUser(runId, userId);
        return AgentRunVO.fromEntity(entity);
    }

    @Override
    public PageResult<AgentRunVO> pageRuns(AgentRunQueryDTO query) {
        long pageNum = Math.max(query.getPageNum(), 1);
        long pageSize = Math.min(Math.max(query.getPageSize(), 1), MAX_PAGE_SIZE);

        Page<AgentRunEntity> page = lambdaQuery()
                .eq(AgentRunEntity::getUserId, query.getUserId())
                .eq(hasText(query.getState()),
                        AgentRunEntity::getState, query.getState())
                .eq(hasText(query.getAgentType()),
                        AgentRunEntity::getAgentType, query.getAgentType())
                .ge(query.getStartTime() != null,
                        AgentRunEntity::getCreatedAt, query.getStartTime())
                .le(query.getEndTime() != null,
                        AgentRunEntity::getCreatedAt, query.getEndTime())
                .orderByDesc(AgentRunEntity::getCreatedAt)
                .page(new Page<>(pageNum, pageSize));

        return PageResult.from(page, AgentRunVO::fromEntity);
    }

    @Override
    public Map<String, Object> cancelRun(String runId, String userId) {
        /*
         * 1. 归属校验：只能取消自己的任务。
         *    不存在或不属于该用户统一报 404，不泄露他人任务是否存在。
         */
        AgentRunEntity entity = findByRunIdAndUser(runId, userId);

        /*
         * 2. 数据库状态校验：已是终态直接拒绝。
         */
        if (isTerminalState(entity.getState())) {
            throw new BusinessException(
                    409,
                    "任务已结束，无法取消。当前状态：" + entity.getState()
            );
        }

        /*
         * 3. 实时取消依赖内存注册表：
         *    数据库状态可能滞后于真实状态，注册表才是运行中任务的实时来源。
         */
        RunningAgentTask task = agentTaskRegistry.get(runId);
        if (task == null) {
            throw new BusinessException(
                    409,
                    "任务已不在运行中，无法取消：" + runId
            );
        }

        /*
         * 4. 复用既有取消逻辑（状态转换 + 中断线程 + SSE 通知）。
         */
        AgentTaskCanceller.CancelResult result = agentTaskCanceller.cancel(task);

        return Map.of(
                "success", true,
                "runId", runId,
                "state", result.state(),
                "message", result.message()
        );
    }

    /**
     * 按 runId + userId 查询，不存在或不属于该用户时抛 404。
     */
    private AgentRunEntity findByRunIdAndUser(String runId, String userId) {
        AgentRunEntity entity = lambdaQuery()
                .eq(AgentRunEntity::getRunId, runId)
                .eq(AgentRunEntity::getUserId, userId)
                .one();

        if (entity == null) {
            throw new BusinessException(
                    404,
                    "任务不存在或无权访问：" + runId
            );
        }

        return entity;
    }

    /**
     * 状态字符串是否为终态（容忍脏数据：解析失败按终态处理，禁止取消）。
     */
    private boolean isTerminalState(String state) {
        try {
            return AgentState.valueOf(state).isTerminal();
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
