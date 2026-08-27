package com.example.mkagent.rag;


import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;

/**
 * 创建上下文查询增强器的工厂
 */
public class ChatAppContextualQueryAugmenter {


    /**
     * 制定一个如果向量数据库没有查询到对应的内容或者输入为空的时候的输出模板
     * @return
     */
    public static ContextualQueryAugmenter createInstance() {
        PromptTemplate emptyContextPromptTemplate = new PromptTemplate("""
                你应该输出下面的内容：
                抱歉，我在知识库中没有找到与该问题相关的 AI 学习资料，
                目前我只能基于知识库回答 AI 学习相关的问题，
                可以尝试换个问法，或查阅编程导航客服 https://codefather.cn
                """);

        return ContextualQueryAugmenter.builder()
                .allowEmptyContext(false)
                .emptyContextPromptTemplate(emptyContextPromptTemplate)
                .build();

    }
}