package com.example.mkagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实文档入库测试（live 分组，默认不执行）。
 *
 * 会调用真实 Embedding 模型并写入真实向量库，属于付费链路。
 * 入库幂等与持久化的确定性断言在 RagDocumentIngestionTest /
 * RagIncrementalPersistenceTest 中，那两个测试使用桩模型。
 */
@SpringBootTest
@Tag("live")
class ChatAppDocumentLoaderTest {


    @Resource
    private ChatAppDocumentLoader  chatappdocumentloader;

    @Test
    void loadMarkdowns() {
        chatappdocumentloader.loadMarkdowns();
    }
}