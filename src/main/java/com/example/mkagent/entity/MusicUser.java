package com.example.mkagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * music_user 表实体：用户角色。
 *
 * 这张表是**管理员身份的唯一可信来源**：
 * 请求参数、请求头、提示词里出现的"我是管理员"一律不算数，
 * 必须由服务端按 user_id 查这张表。
 *
 * 角色取值只有 USER / ADMIN（列是 VARCHAR(16)，没有枚举约束，
 * 因此读取侧必须按字符串比较并处理未知值，不能假设只有两种）。
 */
@TableName("music_user")
public class MusicUser {

    /**
     * 用户标识本身就是主键，没有额外的自增 id。
     */
    @TableId(type = IdType.INPUT)
    private String userId;

    /**
     * 角色：USER / ADMIN。
     */
    private String role;

    private LocalDateTime createdAt;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
