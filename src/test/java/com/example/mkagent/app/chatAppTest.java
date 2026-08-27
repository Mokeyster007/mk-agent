package com.example.mkagent.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class chatAppTest {

    @Resource
    private chatApp aiApp;

    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();

        //第一轮
        String message = "你好，我是mirezza，我是一名Java开发，想系统学习AI";
        String answer = aiApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);

        //第二轮
        message = "我想先从Spring AI入手学习AI应用开发";
        answer = aiApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);

        //第三轮
        message = "我的技术背景是什么来着？刚跟你说过，帮我回忆一下";
        answer = aiApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithReport() {
        String chatId = UUID.randomUUID().toString();

        //第一轮
        String message = "你好，我是mirezza，我想系统学习AI但不知道从何入手，帮我制定一份学习建议";
        chatApp.StudyReport studyReport = aiApp.doChatWithReport(message,chatId);
        Assertions.assertNotNull(studyReport);
    }


    @Test
    void doChatWithRag() {
        String chatId = UUID.randomUUID().toString();

        //第一轮
        String message = "你好，我是零基础，想了解一下学习AI需要准备哪些数学基础？";
        String answer = aiApp.doChatWithRag(message,chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithMcp() {

        String chatId = UUID.randomUUID().toString();

        //String message = "我的另一半居住在上海静安区，请帮我找到 5 公里内合适的约会地点";
       // String answer =  aiApp.doChatWithMcp(message, chatId);
        String message = "帮我搜索一些风景照片";
        String answer =  aiApp.doChatWithMcp(message, chatId);


        assertNotNull(answer);
    }
}
