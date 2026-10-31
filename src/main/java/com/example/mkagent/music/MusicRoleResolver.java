package com.example.mkagent.music;

import com.example.mkagent.context.AgentRunContextHolder;
import com.example.mkagent.entity.MusicUser;
import com.example.mkagent.mapper.MusicUserMapper;
import com.example.mkagent.model.AgentRunContext;
import org.springframework.stereotype.Component;

/**
 * 音乐业务角色解析：userId → 角色。
 *
 * 可信来源只有一个：`music_user` 表。
 * 请求参数、请求头里的角色声明、提示词里的"我是管理员"一律不算数。
 *
 * 三种结果：
 * <ul>
 *   <li>{@code USER}：表里存在且角色不是 ADMIN；</li>
 *   <li>{@code ADMIN}：表里存在且角色是 ADMIN；</li>
 *   <li>{@code UNKNOWN}：表里没有这个人（或 userId 为空）——**视为无权限**。</li>
 * </ul>
 *
 * 为什么 UNKNOWN 不能回落到 USER：
 * 回落到 USER 就等于"任何自造一个 X-User-Id 的人都能用只读工具"。
 * 现在还没有真实认证（见 docs/music-permissions.md），
 * 这条回落会让越权从"需要伪造身份"变成"随便填一个"。
 *
 * 角色列是 VARCHAR(16) 且没有约束，因此：
 * 1. 读取侧必须处理 null / 空串 / 大小写 / 前后空格；
 * 2. 除 ADMIN 以外的任何值都按 USER 处理（最小权限，不会误升权）。
 */
@Component
public class MusicRoleResolver {

    /**
     * 角色枚举。
     */
    public enum MusicRole {

        /**
         * 普通用户：只能读自己的歌单 + 公开歌单。
         */
        USER,

        /**
         * 管理员：只能额外读**聚合统计**，不能读任何人的歌单或播放历史。
         */
        ADMIN,

        /**
         * 未知用户：表里查不到，视为无权限。
         */
        UNKNOWN
    }

    /**
     * 管理员角色字面量（与 MusicSeedInitializer 写入的值一致）。
     */
    public static final String ROLE_ADMIN = "ADMIN";

    private final MusicUserMapper userMapper;

    public MusicRoleResolver(MusicUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 按 userId 解析角色；查不到返回 {@link MusicRole#UNKNOWN}。
     *
     * @param userId 服务端固化的用户标识，允许为 null
     */
    public MusicRole resolve(String userId) {
        if (userId == null || userId.isBlank()) {
            return MusicRole.UNKNOWN;
        }

        MusicUser user = userMapper.selectById(userId.trim());
        if (user == null) {
            return MusicRole.UNKNOWN;
        }

        return isAdminRole(user.getRole())
                ? MusicRole.ADMIN
                : MusicRole.USER;
    }

    /**
     * 解析当前任务上下文的用户角色。
     *
     * 没有绑定的上下文（不是 Agent 任务里调用）→ UNKNOWN，
     * 而不是抛异常：角色解析是"判定"，是否拒绝由调用方决定。
     * 需要 userId 的地方仍然用
     * {@link AgentRunContextHolder#requireUserId()}（失败关闭）。
     */
    public MusicRole resolveCurrent() {
        AgentRunContext ctx = AgentRunContextHolder.current();
        return resolve(ctx == null ? null : ctx.getUserId());
    }

    /**
     * 当前任务上下文是否为管理员。
     */
    public boolean isCurrentUserAdmin() {
        return resolveCurrent() == MusicRole.ADMIN;
    }

    /**
     * 角色字符串是否等价于 ADMIN（容忍大小写与空格）。
     */
    static boolean isAdminRole(String role) {
        return role != null && ROLE_ADMIN.equalsIgnoreCase(role.trim());
    }
}
