package com.example.mkagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.mkagent.entity.MusicSong;

/**
 * music_song 表 Mapper（单表 CRUD）。
 *
 * 联表 / 聚合查询（歌单内歌曲、按歌手统计等）走 JdbcTemplate，
 * 不在这里堆 XML：本项目没有 Mapper XML，动态 SQL 用 JdbcTemplate
 * 反而更直观，也更容易在 H2 上验证。
 *
 * Mapper 扫描入口见 MkAgentApplication 上的 @MapperScan。
 */
public interface MusicSongMapper extends BaseMapper<MusicSong> {
}
