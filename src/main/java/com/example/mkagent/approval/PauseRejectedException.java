package com.example.mkagent.approval;

/**
 * 暂停被拒绝：数据库里的任务状态已不是"运行中且代次匹配"。
 *
 * 典型原因：暂停前用户刚好取消，或另一条路径已经把它暂停了。
 * 这不是异常情况，调用方应放弃暂停并让任务按既有状态收尾，
 * 不能硬写一条没人能处理的审批记录。
 */
public class PauseRejectedException extends RuntimeException {

    public PauseRejectedException(String message) {
        super(message);
    }
}
