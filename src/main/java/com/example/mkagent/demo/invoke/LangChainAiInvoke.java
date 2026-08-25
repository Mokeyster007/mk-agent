package com.example.mkagent.demo.invoke;

import dev.langchain4j.community.model.dashscope.QwenChatModel;

public class LangChainAiInvoke {

    public static  void main(String[] args) {

        QwenChatModel qwenChatModel = QwenChatModel.builder()
                .apiKey(TestApiKey.API_KEY)
                .modelName("qwen-max")
                .build();

        String answer = qwenChatModel.chat("我是mirezza，这是一个ai超级智能体项目");

        System.out.println(answer);
    }
}
