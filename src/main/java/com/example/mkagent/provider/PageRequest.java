package com.example.mkagent.provider;

/**
 * 分页参数。
 *
 * <p>上限 50 是刻意的：外部接口通常按"页"计费/限流，
 * 一次拿太多既慢又容易触发风控。越界一律夹到合法区间，不报错——
 * 分页参数不是业务语义，没必要让用户因为 size=1000 看到一个 400。
 */
public record PageRequest(int page, int size) {

    public static final int DEFAULT_SIZE = 20;

    public static final int MAX_SIZE = 50;

    public PageRequest {
        page = Math.max(1, page);
        size = Math.min(MAX_SIZE, Math.max(1, size));
    }

    public static PageRequest of(Integer page, Integer size) {
        return new PageRequest(
                page == null ? 1 : page,
                size == null ? DEFAULT_SIZE : size);
    }

    public int offset() {
        return (page - 1) * size;
    }
}
