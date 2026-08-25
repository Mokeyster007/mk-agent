package com.example.mkagent.model.vo;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

/**
 * 通用分页结果（项目当前没有统一响应包装类，
 * 分页接口直接返回本对象）。
 *
 * 只暴露前端需要的分页字段，不透出 MyBatis-Plus IPage 内部结构。
 */
public class PageResult<T> {

    private long pageNum;

    private long pageSize;

    private long total;

    private long pages;

    private List<T> records;

    /**
     * 从 MyBatis-Plus 分页结果转换，记录类型由 Entity 映射为 VO。
     */
    public static <E, V> PageResult<V> from(
            IPage<E> page,
            Function<E, V> converter
    ) {
        PageResult<V> result = new PageResult<>();
        result.pageNum = page.getCurrent();
        result.pageSize = page.getSize();
        result.total = page.getTotal();
        result.pages = page.getPages();
        result.records = page.getRecords().stream().map(converter).toList();
        return result;
    }

    public long getPageNum() {
        return pageNum;
    }

    public long getPageSize() {
        return pageSize;
    }

    public long getTotal() {
        return total;
    }

    public long getPages() {
        return pages;
    }

    public List<T> getRecords() {
        return records;
    }
}
