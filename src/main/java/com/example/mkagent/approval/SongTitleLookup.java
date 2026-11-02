package com.example.mkagent.approval;

import java.util.List;

/**
 * 按歌曲 ID 查标题（只读）。
 *
 * 抽成接口是为了让摘要构建可以在没有数据库的单测里跑：
 * 测试传一个 lambda 即可，不需要起 Spring 上下文。
 */
public interface SongTitleLookup {

    /**
     * 按传入顺序返回标题；查不到的 ID 直接跳过（不占位）。
     *
     * @param songIds 歌曲 ID（可为空）
     */
    List<String> titles(List<Long> songIds);
}
