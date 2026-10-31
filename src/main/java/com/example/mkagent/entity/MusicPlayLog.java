package com.example.mkagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * music_play_log 表实体：播放日志。
 *
 * 只记录"谁在什么时候听了哪首歌、从哪来"，
 * 不记录设备指纹、IP、User-Agent 等个人信息。
 *
 * 本批（M2b）不提供 get_play_history 工具：
 * 播放历史属于强隐私数据，先把表和种子数据准备好，
 * 等权限模型（M3）落地再开放读取。
 */
@TableName("music_play_log")
public class MusicPlayLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String userId;

    private Long songId;

    /**
     * 播放来源，例如 playlist / search / album。
     */
    private String source;

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

    public Long getSongId() {
        return songId;
    }

    public void setSongId(Long songId) {
        this.songId = songId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
