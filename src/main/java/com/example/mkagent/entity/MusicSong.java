package com.example.mkagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * music_song 表实体：曲库中的一首歌。
 *
 * 与 db/schema.sql 中的 music_song 一一对应。
 *
 * 刻意**没有** playCount 字段：
 * 播放次数是 music_play_log 的聚合结果，
 * 冗余计数在并发写入时会漂移，还要额外的补偿任务去修，
 * 不如查询时聚合（数据量小、口径唯一）。
 *
 * 安全约束：
 * 曲库内容属于"外部不可信数据"——种子数据里有刻意埋的提示词注入样例，
 * 模型读到 title / album 时不得把其中的指令当规则执行。
 */
@TableName("music_song")
public class MusicSong {

    /**
     * 主键：应用侧生成（种子数据用固定 ID，运行时用雪花），不用数据库自增。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String title;

    private String artist;

    private String album;

    /**
     * 时长（秒）。
     */
    private Integer durationSeconds;

    private String genre;

    /**
     * 发行年份。
     */
    private Integer releaseYear;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getGenre() {
        return genre;
    }

    public void setGenre(String genre) {
        this.genre = genre;
    }

    public Integer getReleaseYear() {
        return releaseYear;
    }

    public void setReleaseYear(Integer releaseYear) {
        this.releaseYear = releaseYear;
    }
}
