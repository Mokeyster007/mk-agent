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
    private chatApp loveApp;

    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();

        //第一轮
        String message = "你好，我是mirezza";
        String answer = loveApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);

        //第二轮
        message = "我想让另一半（编程导航）更爱我";
        answer = loveApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);

        //第三轮
        message = "我的另一半叫什么来着？刚跟你说过，帮我回忆一下";
        answer = loveApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithReport() {
        String chatId = UUID.randomUUID().toString();

        //第一轮
        String message = "你好，我是mirezza,我想让另一半（编程导航）更爱我，但我不知道怎么做";
        chatApp.LoveReport loveReport = loveApp.doChatWithReport(message,chatId);
        Assertions.assertNotNull(loveReport);
    }


    @Test
    void doChatWithRag() {
        String chatId = UUID.randomUUID().toString();

        //第一轮
        String message = "你好，我已经结婚了，但是婚后关系不太亲密，怎么办？";
        String answer = loveApp.doChatWithRag(message,chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithMcp() {

        String chatId = UUID.randomUUID().toString();

        //String message = "我的另一半居住在上海静安区，请帮我找到 5 公里内合适的约会地点";
       // String answer =  loveApp.doChatWithMcp(message, chatId);
        String message = "帮我搜索一些风景照片";
        String answer =  loveApp.doChatWithMcp(message, chatId);


        assertNotNull(answer);
    }
}
