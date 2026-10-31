package com.example.mkagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * music_playlist 表实体：用户歌单。
 *
 * 可见性规则（由服务端判定，不交给模型）：
 * 1. is_public = TRUE  → 任何人可读；
 * 2. is_public = FALSE → 只有 user_id 本人可读。
 *
 * 唯一约束 (user_id, name)：同一用户不能建同名歌单，
 * 不同用户之间互不影响。
 */
@TableName("music_playlist")
public class MusicPlaylist {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 歌单归属用户（来自服务端用户上下文，绝不是工具参数）。
     */
    private String userId;

    private String name;

    private String description;

    /**
     * 是否公开。
     */
    private Boolean isPublic;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getIsPublic() {
        return isPublic;
    }

    public void setIsPublic(Boolean isPublic) {
        this.isPublic = isPublic;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
