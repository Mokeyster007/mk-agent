package com.example.mkagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ChatAppDocumentLoaderTest {


    @Resource
    private ChatAppDocumentLoader  chatappdocumentloader;

    @Test
    void loadMarkdowns() {
        chatappdocumentloader.loadMarkdowns();
    }
}