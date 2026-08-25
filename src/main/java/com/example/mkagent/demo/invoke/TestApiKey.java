package com.example.mkagent.demo.invoke;

/**
 * API Key 统一从环境变量读取，不再硬编码到源码。
 *
 * 本地运行方式：
 * IDEA Run Configuration -> Environment variables 中配置：
 * DASHSCOPE_API_KEY=你的真实密钥。
 */
public interface TestApiKey {
    String API_KEY = System.getenv("DASHSCOPE_API_KEY");
}
