package com.example.mkagent.demo.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.Query;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest
class MultiQueryExpanderDemoTest {

    @Resource
    private MultiQueryExpanderDemo multiQueryExpanderDemo;

    @Test
    void expand() {
        String originalQuery = "谁是程序员鱼皮啊啊啊？";

        List<Query> queries = multiQueryExpanderDemo.expand(originalQuery);

        queries.forEach(query ->
                log.info("扩展后的查询：{}", query.text())
        );

        assertThat(queries)
                .isNotNull()
                .isNotEmpty();

        // 真实模型的非确定性输出：要求生成 3 个扩展查询，
        // 但模型偶尔会返回 2~4 个，因此断言区间而非精确数量，
        // 避免学习演示型测试因模型波动反复失败。
        assertThat(queries)
                .hasSizeBetween(2, 4);

        assertThat(queries)
                .allSatisfy(query ->
                        assertThat(query.text()).isNotBlank()
                );
    }
}