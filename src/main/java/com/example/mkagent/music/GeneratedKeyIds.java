package com.example.mkagent.music;

import org.springframework.jdbc.support.KeyHolder;

import java.util.Map;

/**
 * 从 {@link KeyHolder} 里取回单列生成主键（identity）。
 *
 * 为什么不用 {@code keyHolder.getKey()}：
 * 1. PostgreSQL 的 {@code RETURN_GENERATED_KEYS} 会返回**整行**，
 *    此时 {@code getKey()} 在键多于一个时直接抛
 *    {@code InvalidDataAccessApiUsageException}；
 * 2. 所以插入时必须显式声明只取 id 列
 *    （{@code prepareStatement(sql, new String[]{"id"})}）。
 *
 * 为什么不直接用列名查 {@code getKeys().get("id")}：
 * 列名大小写由驱动/数据库决定——PostgreSQL 未加引号的标识符返回小写 {@code id}，
 * H2 在 {@code MODE=PostgreSQL} 下默认返回大写 {@code ID}。
 * 这里按**不区分大小写**匹配，避免为了取一个 id 去绑定某个具体驱动行为。
 */
final class GeneratedKeyIds {

    private static final String ID_COLUMN = "id";

    private GeneratedKeyIds() {
    }

    /**
     * @return 生成的 id
     * @throws IllegalStateException 没有返回任何键，或返回的不是数字
     */
    static long extract(KeyHolder keyHolder) {
        Map<String, Object> keys = keyHolder.getKeys();

        if (keys != null) {
            for (Map.Entry<String, Object> entry : keys.entrySet()) {
                if (entry.getKey() != null
                        && ID_COLUMN.equalsIgnoreCase(entry.getKey())
                        && entry.getValue() instanceof Number number) {
                    return number.longValue();
                }
            }

            // 只取了一列但驱动改了个名字：仍然可以安全地拿唯一的值。
            if (keys.size() == 1) {
                Object only = keys.values().iterator().next();
                if (only instanceof Number number) {
                    return number.longValue();
                }
            }
        }

        throw new IllegalStateException(
                "插入后未取回生成的主键 id（keyHolder=" + keys + "）");
    }
}
