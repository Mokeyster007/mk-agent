package com.example.mkagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.mkagent.entity.MusicUser;

/**
 * music_user 表 Mapper（单表 CRUD）。
 *
 * 管理员角色校验的读取入口：
 * 服务端按 user_id 查这一行，role == ADMIN 才算管理员。
 */
public interface MusicUserMapper extends BaseMapper<MusicUser> {
}
