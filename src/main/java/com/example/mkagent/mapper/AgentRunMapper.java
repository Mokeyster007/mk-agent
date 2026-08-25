package com.example.mkagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.mkagent.entity.AgentRunEntity;

/**
 * agent_run 表 Mapper。
 *
 * 继承 MyBatis-Plus BaseMapper 获得通用 CRUD 能力，
 * 当前无自定义 SQL。
 *
 * Mapper 扫描入口见 MkAgentApplication 上的 @MapperScan。
 */
public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {
}
