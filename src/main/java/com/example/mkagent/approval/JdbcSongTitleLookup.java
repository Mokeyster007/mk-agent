package com.example.mkagent.approval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从曲库查歌曲标题（审批摘要用，只读）。
 *
 * 只查标题，不查歌手/专辑：摘要只需要让用户认出"这几首歌"，
 * 多查一列就多一份把无关数据带进审批记录的机会。
 */
@Component
public class JdbcSongTitleLookup implements SongTitleLookup {

    /**
     * 单次最多查多少首：摘要只展示前几首，查多了纯属浪费。
     */
    private static final int MAX_LOOKUP = 20;

    private final JdbcTemplate jdbcTemplate;

    public JdbcSongTitleLookup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<String> titles(List<Long> songIds) {
        if (songIds == null || songIds.isEmpty()) {
            return List.of();
        }

        List<Long> limited = songIds.size() > MAX_LOOKUP
                ? songIds.subList(0, MAX_LOOKUP)
                : songIds;

        String placeholders = String.join(",",
                Collections.nCopies(limited.size(), "?"));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, title FROM music_song WHERE id IN (" + placeholders + ")",
                limited.toArray()
        );

        Map<Long, String> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            Object title = row.get("title");
            if (id instanceof Number number && title != null) {
                byId.put(number.longValue(), title.toString());
            }
        }

        List<String> result = new ArrayList<>();
        for (Long songId : limited) {
            String title = byId.get(songId);
            if (title != null) {
                result.add(title);
            }
        }
        return result;
    }
}
