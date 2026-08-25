package com.example.mkagent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.mkagent.entity.AgentRunEntity;
import com.example.mkagent.model.dto.AgentRunQueryDTO;
import com.example.mkagent.model.vo.AgentRunVO;
import com.example.mkagent.model.vo.PageResult;

import java.util.Map;

/**
 * AgentRun 任务记录服务。
 *
 * 职责：
 * 1. 按用户维度查询任务详情与历史分页（只能查自己的任务）；
 * 2. 取消任务：先做归属与状态校验，
 *    再复用 AgentTaskRegistry 内存注册表的实时取消能力。
 *
 * 数据库（agent_run）负责历史与状态查询，
 * 内存注册表负责运行中任务的实时取消，两者互补。
 */
public interface AgentRunService extends IService<AgentRunEntity> {

    /**
     * 按 runId 查询当前用户的任务。
     *
     * 任务不存在或不属于该用户时抛 404 业务异常
     * （不区分两种情况，避免泄露他人任务是否存在）。
     */
    AgentRunVO getByRunIdForUser(String runId, String userId);

    /**
     * 分页查询当前用户的任务历史，
     * 支持按 state / agentType / 创建时间范围筛选，
     * 默认按创建时间倒序。
     */
    PageResult<AgentRunVO> pageRuns(AgentRunQueryDTO query);

    /**
     * 取消当前用户的运行中任务。
     *
     * @return 与既有取消接口一致的响应结构：
     *         success / runId / state / message
     */
    Map<String, Object> cancelRun(String runId, String userId);
}
